package vn.motngum.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Màn hình chính: danh sách đơn, in / in lại tem, tạo đơn tại quầy. */
class OrdersActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val tabButtons = mutableMapOf<String, Button>()
    private var tab = "new"
    private val refresh: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() { render(); list.postDelayed(this, 30_000) }
    }

    private val green = Color.rgb(0, 166, 90)
    private val ink = Color.rgb(29, 27, 24)
    private val muted = Color.rgb(109, 103, 95)
    private val warn = Color.rgb(217, 119, 6)
    private val red = Color.rgb(194, 65, 12)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        OrderStore.get(this).deleteOlderThan(30)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245, 243, 239)) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(ink); setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        bar.addView(TextView(this).apply { text = "MotNgum POS"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { setTextColor(Color.rgb(207, 201, 192)); textSize = 13f }
        bar.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(barButton("+ Tạo đơn") { newOrderDialog() })
        actions.addView(barButton("🖨 In thử") { testPrint() })
        actions.addView(barButton("↕ Căn khe tem") { calibrate() })
        actions.addView(barButton("⚙ Cài đặt") { startActivity(Intent(this, MainActivity::class.java)) })
        bar.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) })
        root.addView(bar)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), 0) }
        for ((key, label) in listOf("new" to "Mới / chờ in", "printed" to "Đã in – đang pha", "done" to "Xong")) {
            val b = Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { tab = key; render() }
            }
            tabButtons[key] = b
            tabs.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        OrderManager.addListener(refresh)
        list.post(ticker)
    }

    override fun onPause() {
        OrderManager.removeListener(refresh)
        list.removeCallbacks(ticker)
        super.onPause()
    }

    // ------------------------------------------------------------ render ---
    private fun render() {
        val prefs = Prefs(this)
        val a11y = OrderReaderService.instance != null
        status.text = buildString {
            append(if (a11y) "✅ Đang theo dõi đơn" else "❌ Chưa bật Trợ năng – vào Cài đặt")
            append("  ·  Máy in: ").append(prefs.printerHost.ifBlank { "chưa cài" })
            append("  ·  Tự in: ").append(if (prefs.autoPrint) "bật" else "tắt")
            if (!prefs.standalone) append("  ·  (đang gửi về máy tính)")
        }
        val all = OrderStore.get(this).list()
        val counts = all.groupingBy { it.status }.eachCount()
        for ((key, b) in tabButtons) {
            val base = b.text.toString().substringBefore(" (")
            b.text = "$base (${counts[key] ?: 0})"
            b.setTextColor(if (key == tab) green else ink)
            b.typeface = if (key == tab) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        list.removeAllViews()
        val shown = all.filter { it.status == tab }.let { if (tab == "done") it.take(30) else it }
        if (shown.isEmpty()) {
            list.addView(TextView(this).apply { text = "Không có đơn"; setTextColor(muted); setPadding(dp(8), dp(16), 0, 0) })
        }
        shown.forEach { list.addView(card(it)) }
    }

    private fun card(o: StoredOrder): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.rgb(227, 222, 214))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val src = Labels.sourceLabel(o.source).ifEmpty { "TẠI QUẦY" }
        head.addView(TextView(this).apply { text = src; setTextColor(green); textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, dp(6), 0) })
        head.addView(TextView(this).apply { text = o.code; setTextColor(ink); textSize = 20f; typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val t = SimpleDateFormat("HH:mm", Locale.US).format(Date(o.createdAt))
        head.addView(TextView(this).apply { text = "$t · ${o.cupCount} ly"; setTextColor(muted) })
        card.addView(head)
        if (o.customer.isNotBlank()) card.addView(small(o.customer, muted))
        if (o.changedAfterPrint) card.addView(small("⚠ Đơn thay đổi sau khi đã in — kiểm tra & in lại", warn, bold = true))
        if (o.printCount < 0) card.addView(small("⚠ Chưa in được — kiểm tra máy in rồi bấm “In tem”", warn, bold = true))

        for (it in o.items) {
            card.addView(TextView(this).apply {
                text = "${it.qty}×  ${it.name}"; setTextColor(ink); textSize = 16f; setPadding(0, dp(6), 0, 0)
            })
            if (it.options.isNotEmpty()) card.addView(small("     " + it.options.joinToString(", "), muted))
            if (it.note.isNotBlank()) card.addView(small("     * ${it.note}", red))
        }
        if (o.note.isNotBlank()) card.addView(small("Ghi chú đơn: ${o.note}", red))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        btns.addView(actionButton(if (o.printCount > 0) "🖨 In lại" else "🖨 In tem") { doPrint(o.id, null) })
        if (o.status != "done") btns.addView(actionButton("✔ Xong") { setStatus(o, "done") })
        if (o.status == "new" || o.status == "printed") btns.addView(actionButton("✕ Huỷ") { confirmCancel(o) })
        if (o.status == "done" || o.status == "cancelled") btns.addView(actionButton("↺ Mở lại") { setStatus(o, "printed") })
        btns.addView(actionButton("👁 Xem tem") { previewDialog(o) })
        if (o.screenshot.isNotBlank() && File(o.screenshot).exists()) btns.addView(actionButton("📷 Ảnh") { screenshotDialog(o) })
        if (o.rawText.isNotBlank()) btns.addView(actionButton("📄 Chữ gốc") { rawDialog(o) })
        card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(btns) })
        return card
    }

    // ----------------------------------------------------------- actions ---
    private fun doPrint(id: Long, only: List<Int>?) {
        toast("Đang in…")
        OrderManager.print(this, id, only) { err -> toast(err ?: "Đã in") }
    }

    private fun setStatus(o: StoredOrder, s: String) {
        OrderStore.get(this).setStatus(o.id, s)
        render()
    }

    private fun confirmCancel(o: StoredOrder) {
        AlertDialog.Builder(this).setMessage("Huỷ đơn ${o.code}?")
            .setPositiveButton("Huỷ đơn") { _, _ -> setStatus(o, "cancelled") }
            .setNegativeButton("Không", null).show()
    }

    private fun testPrint() {
        val demo = StoredOrder(
            0, "manual", "TEST", "", "", listOf(Item("Trà sữa trân châu đường đen", 1, mutableListOf("Size L", "50% đường", "Ít đá"), "Tem in thử")),
            "new", false, 0, "", "", System.currentTimeMillis(), 0,
        )
        val p = Prefs(this)
        val data = Tspl.job(Labels.renderOrder(demo, p.labelSize, p.shopName).map(Labels::toMono), p.labelSize, p.printDensity)
        OrderManager.sendRaw(this, data) { err -> toast(err ?: "Đã gửi tem thử") }
    }

    private fun calibrate() {
        OrderManager.sendRaw(this, Tspl.calibrate(Prefs(this).labelSize)) { err -> toast(err ?: "Máy in đang dò khe tem…") }
    }

    private fun newOrderDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(small("Mỗi dòng 1 món:\n2x Trà sữa | Size L, 50% đường | ghi chú: ít ngọt", muted))
        val code = EditText(this).apply { hint = "Mã đơn / số thẻ (tuỳ chọn)"; setSingleLine() }
        val text = EditText(this).apply {
            hint = "1x Cà phê sữa đá | Size M, Ít đá"; minLines = 4; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        box.addView(code); box.addView(text)
        fun create(printNow: Boolean) {
            val parsed = OrderParser.parseManual(text.text.toString())
            if (code.text.isNotBlank()) parsed.code = code.text.toString().trim().uppercase()
            if (parsed.items.isEmpty()) { toast("Đơn chưa có món nào"); return }
            // đơn tay không để vòng tự in đụng vào: in ngay hoặc đánh dấu -1
            val o = OrderStore.get(this).insert("manual", parsed, "", printCount = if (printNow) 0 else -1)
            tab = "new"
            render()
            if (printNow) doPrint(o.id, null)
        }
        AlertDialog.Builder(this).setTitle("Tạo đơn tại quầy").setView(box)
            .setPositiveButton("Tạo & in tem") { _, _ -> create(true) }
            .setNeutralButton("Chỉ tạo") { _, _ -> create(false) }
            .setNegativeButton("Đóng", null).show()
    }

    private fun previewDialog(o: StoredOrder) {
        val p = Prefs(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        col.addView(small("Bấm vào 1 tem để in riêng tem đó", muted))
        var dialog: AlertDialog? = null
        Labels.renderOrder(o, p.labelSize, p.shopName).forEachIndexed { i, bmp ->
            col.addView(ImageView(this).apply {
                setImageBitmap(bmp); adjustViewBounds = true; setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { dialog?.dismiss(); doPrint(o.id, listOf(i + 1)) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(this).setTitle("Tem đơn ${o.code}")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("In tất cả") { _, _ -> doPrint(o.id, null) }
            .setNegativeButton("Đóng", null).show()
    }

    private fun screenshotDialog(o: StoredOrder) {
        val bmp = BitmapFactory.decodeFile(o.screenshot) ?: return toast("Không mở được ảnh")
        AlertDialog.Builder(this).setView(ScrollView(this).apply {
            addView(ImageView(this@OrdersActivity).apply { setImageBitmap(bmp); adjustViewBounds = true })
        }).setNegativeButton("Đóng", null).show()
    }

    /** Chữ đọc được từ màn hình Grab — copy gửi cho người chỉnh parser nếu tách món sai. */
    private fun rawDialog(o: StoredOrder) {
        val tv = TextView(this).apply {
            text = o.rawText; setTextIsSelectable(true); typeface = Typeface.MONOSPACE; textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Chữ đọc từ màn hình").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("order", o.rawText))
                toast("Đã copy")
            }
            .setNegativeButton("Đóng", null).show()
    }

    // ----------------------------------------------------------- helpers ---
    private fun small(s: String, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); textSize = 13f; if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun barButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; setOnClickListener { onClick() }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0); setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
