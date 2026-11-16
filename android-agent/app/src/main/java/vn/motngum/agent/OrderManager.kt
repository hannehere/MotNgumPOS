package vn.motngum.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Bộ não của chế độ tự chạy trên tablet:
 * chữ màn hình -> tách món -> lưu đơn -> chờ đủ món -> vẽ tem -> gửi lệnh TSPL tới máy in qua LAN.
 */
object OrderManager {
    private val main = Handler(Looper.getMainLooper())
    private val printer = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    private fun notifyChanged() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun sourceFor(pkg: String) = when (pkg) {
        "com.grab.merchant" -> "grab"
        else -> pkg.substringAfterLast('.').lowercase()
    }

    /** Nhận chữ đọc được từ màn hình app bán hàng. Trả về id đơn (nếu có). */
    fun ingestScreen(context: Context, pkg: String, lines: List<String>): Long? {
        val prefs = Prefs(context)
        val code = runCatching { Regex(prefs.codeRegex) }.getOrElse { Regex(Prefs.DEFAULT_CODE_REGEX) }
        val parsed = OrderParser.parse(lines, listOf(code))
        if (parsed.code.isEmpty() || parsed.items.isEmpty()) return null
        val (order, action) = OrderStore.get(context).upsertParsed(sourceFor(pkg), parsed, lines.joinToString("\n"))
        AgentLog.add("Đơn ${order.code}: $action – ${order.cupCount} ly")
        if (action != "unchanged") {
            notifyChanged()
            scheduleAutoPrint(context)
        }
        return order.id
    }

    // ------------------------------------------------------------------ in ---
    private var appContext: Context? = null

    /** Chờ đơn ổn định (không đổi trong N giây, để kịp cuộn đọc hết món) rồi in. */
    fun scheduleAutoPrint(context: Context) {
        appContext = context.applicationContext
        val prefs = Prefs(context)
        if (!prefs.autoPrint) return
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({ runDuePrints() }, TOKEN, android.os.SystemClock.uptimeMillis() + prefs.printDelayMs + 200)
    }

    fun runDuePrints() {
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        if (!prefs.autoPrint) return
        for (o in OrderStore.get(ctx).dueForPrint(prefs.printDelayMs)) print(ctx, o.id, null, auto = true)
    }

    fun print(context: Context, orderId: Long, only: List<Int>? = null, auto: Boolean = false, done: ((String?) -> Unit)? = null) {
        val ctx = context.applicationContext
        appContext = ctx
        printer.execute {
            val store = OrderStore.get(ctx)
            val order = store.get(orderId)
            val err = if (order == null) "Không tìm thấy đơn" else runCatching {
                val prefs = Prefs(ctx)
                val images = Labels.renderOrder(order, prefs.labelSize, prefs.shopName, only).map(Labels::toMono)
                send(prefs, Tspl.job(images, prefs.labelSize, prefs.printDensity))
                store.markPrinted(orderId)
                AgentLog.add("Đã in ${images.size} tem đơn ${order.code}")
                null
            }.getOrElse {
                if (auto) store.markPrintFailed(orderId)
                AgentLog.add("In lỗi đơn ${order.code}: ${it.message}")
                "Không in được: ${it.message}"
            }
            notifyChanged()
            done?.let { cb -> main.post { cb(err) } }
        }
    }

    fun sendRaw(context: Context, data: ByteArray, done: (String?) -> Unit) {
        val prefs = Prefs(context)
        printer.execute {
            val err = runCatching { send(prefs, data); null }.getOrElse { "Không in được: ${it.message}" }
            main.post { done(err) }
        }
    }

    private fun send(prefs: Prefs, data: ByteArray) {
        if (prefs.printerHost.isBlank()) error("Chưa nhập IP máy in trong Cài đặt")
        Socket().use { s ->
            s.connect(InetSocketAddress(prefs.printerHost, prefs.printerPort), 4000)
            s.soTimeout = 8000
            s.getOutputStream().apply { write(data); flush() }
        }
    }

    private val TOKEN = Any()
}
