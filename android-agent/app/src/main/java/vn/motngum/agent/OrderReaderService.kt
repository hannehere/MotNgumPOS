package vn.motngum.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.app.PendingIntent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Dịch vụ Trợ năng: đọc chữ trên màn hình của app bán hàng.
 *
 * Đọc qua cây Accessibility chính xác hơn nhiều so với chụp ảnh rồi OCR (không sai dấu tiếng Việt),
 * nên đây là cách chính. Ảnh chụp màn hình vẫn được gửi kèm để lưu bằng chứng, và nếu không đọc
 * được chữ (app vẽ bằng canvas/webview) thì server sẽ OCR ảnh.
 */
class OrderReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var client: ServerClient

    // Trạng thái phiên đọc đơn hiện tại
    private var currentCode: String? = null
    private var merged: List<String> = emptyList()
    private var lastSentHash = 0
    private var scrolls = 0
    private val screenshotted = LinkedHashSet<String>()
    private var expectOrderUntil = 0L
    private var lastScreenshotAt = 0L

    private val captureRunnable = Runnable { capture() }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        client = ServerClient(this)
        applyPackages()
        OrderManager.scheduleAutoPrint(this) // in các đơn còn sót (nếu app vừa khởi động lại)
        AgentLog.add("Đã bật Trợ năng – theo dõi: ${prefs.packages.joinToString()}")
    }

    /** Cập nhật danh sách app theo dõi khi người dùng đổi cài đặt. */
    fun applyPackages() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.packageNames = prefs.packages.toTypedArray()
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in prefs.packages) return
        // gom nhiều sự kiện liên tiếp lại, đọc 1 lần khi màn hình đã ổn định
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, 700)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ mở đơn ---
    /** Gọi từ NotificationListener khi có thông báo đơn mới. */
    fun openOrder(pkg: String, contentIntent: PendingIntent?) {
        handler.post {
            wakeScreen()
            expectOrderUntil = System.currentTimeMillis() + 60_000
            var opened = false
            if (contentIntent != null) {
                opened = runCatching {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val opts = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            ).toBundle()
                        contentIntent.send(this, 0, null, null, null, null, opts)
                    } else {
                        contentIntent.send()
                    }
                    true
                }.getOrElse {
                    AgentLog.add("Không mở được thông báo: ${it.message}")
                    false
                }
            }
            if (!opened) {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { startActivity(it) }
                    opened = true
                }
            }
            AgentLog.add(if (opened) "Đã mở app $pkg để đọc đơn" else "Không mở được app $pkg")
            handler.postDelayed(captureRunnable, 2500)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "motngum:wake",
        )
        wl.acquire(10_000)
    }

    // ----------------------------------------------------------- đọc chữ ---
    private fun capture() {
        val root = findRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        val lines = ArrayList<String>()
        collect(root, lines, 0)
        val clean = dedupeAdjacent(lines)

        val codeRegex = runCatching { Regex(prefs.codeRegex) }.getOrNull()
        val code = codeRegex?.find(clean.joinToString("\n"))?.value

        if (code == null) {
            if (prefs.debugSendAll && clean.isNotEmpty()) sendScreen(pkg, clean)
            // Không đọc được chữ nhưng đang chờ đơn -> chụp màn hình cho server OCR
            if (clean.size < 3 && System.currentTimeMillis() < expectOrderUntil) {
                takeScreenshotAndUpload(pkg, clean, "nocode-${System.currentTimeMillis() / 10_000}")
            }
            return
        }

        if (code != currentCode) {
            currentCode = code
            merged = emptyList()
            lastSentHash = 0
            scrolls = 0
        }
        merged = ScreenMerger.merge(merged, clean)

        val h = merged.hashCode()
        if (h != lastSentHash) {
            lastSentHash = h
            sendScreen(pkg, merged)
        }
        if (prefs.uploadScreenshots && code !in screenshotted) {
            screenshotted.add(code)
            while (screenshotted.size > 50) screenshotted.remove(screenshotted.first())
            takeScreenshotAndUpload(pkg, merged, code)
        }

        // Cuộn xuống để đọc hết món (danh sách dài)
        if (prefs.autoScroll && scrolls < MAX_SCROLLS) {
            val scrollable = findScrollable(root)
            if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                scrolls++
                // sự kiện TYPE_VIEW_SCROLLED sẽ gọi lại capture()
            } else {
                scrolls = MAX_SCROLLS
            }
        }
    }

    private fun findRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() in prefs.packages) return it }
        return windows.asSequence()
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() in prefs.packages }
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 60) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach(out::add)
        else if (desc.isNotEmpty() && node.childCount == 0) out.add(desc)
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    private fun dedupeAdjacent(lines: List<String>): List<String> {
        val out = ArrayList<String>(lines.size)
        for (l in lines) if (out.lastOrNull() != l) out.add(l)
        return out
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        // Ưu tiên vùng cuộn lớn nhất (danh sách món), bỏ qua các thanh cuộn ngang nhỏ
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        val r = android.graphics.Rect()
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.isScrollable) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea && r.height() >= r.width() / 3) {
                    best = n; bestArea = area
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return best
    }

    private fun sendScreen(pkg: String, lines: List<String>) {
        if (prefs.standalone) {
            val snapshot = lines.toList()
            bg.execute {
                val id = OrderManager.ingestScreen(this, pkg, snapshot)
                if (id == null && prefs.debugSendAll) AgentLog.add("Màn hình (${snapshot.size} dòng): ${snapshot.take(12).joinToString(" | ")}")
            }
            return
        }
        client.post(
            "/api/agent/screen",
            JSONObject()
                .put("package", pkg)
                .put("lines", JSONArray(lines))
                .put("captured_at", System.currentTimeMillis() / 1000.0),
        )
    }

    // ----------------------------------------------------- chụp màn hình ---
    private fun takeScreenshotAndUpload(pkg: String, lines: List<String>, tag: String) {
        if (Build.VERSION.SDK_INT < 30) return // takeScreenshot cần Android 11+
        val now = System.currentTimeMillis()
        if (now - lastScreenshotAt < 1500) return // giới hạn tần suất của hệ thống
        lastScreenshotAt = now
        takeScreenshot(Display.DEFAULT_DISPLAY, bg, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                result.hardwareBuffer.close()
                if (hw == null) return
                val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val scale = minOf(1f, 900f / bmp.width)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                ) else bmp
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 75, bos)
                if (prefs.standalone) {
                    saveScreenshotLocally(pkg, tag, bos.toByteArray())
                    return
                }
                AgentLog.add("Đã chụp màn hình ($tag) – gửi server")
                client.post(
                    "/api/agent/screenshot",
                    JSONObject()
                        .put("package", pkg)
                        .put("image_base64", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                        .put("lines", JSONArray(lines)),
                )
            }

            override fun onFailure(errorCode: Int) {
                AgentLog.add("Chụp màn hình lỗi: $errorCode (app có thể chặn chụp màn hình)")
            }
        })
    }

    /** Lưu ảnh đơn vào bộ nhớ app, gắn vào đơn có mã [code]; xoá ảnh cũ hơn 3 ngày. */
    private fun saveScreenshotLocally(pkg: String, code: String, jpeg: ByteArray) {
        val dir = java.io.File(filesDir, "screenshots").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val f = java.io.File(dir, "${System.currentTimeMillis()}-${code.replace(Regex("[^A-Za-z0-9-]"), "_")}.jpg")
        f.writeBytes(jpeg)
        // đợi đơn được lưu xong (ingest chạy trên cùng hàng đợi bg) rồi gắn ảnh
        bg.execute {
            OrderStore.get(this).findRecent(OrderManager.sourceFor(pkg), code)?.let {
                OrderStore.get(this).setScreenshot(it.id, f.absolutePath)
            }
        }
        AgentLog.add("Đã lưu ảnh chụp đơn $code")
    }

    companion object {
        @Volatile
        var instance: OrderReaderService? = null
        private const val MAX_SCROLLS = 6
    }
}
