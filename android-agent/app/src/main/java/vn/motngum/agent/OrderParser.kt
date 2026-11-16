package vn.motngum.agent

import java.text.Normalizer

/** Một món trong đơn. */
data class Item(
    var name: String,
    var qty: Int = 1,
    val options: MutableList<String> = mutableListOf(),
    var note: String = "",
    var price: String = "",
)

data class ParsedOrder(
    var code: String = "",
    var customer: String = "",
    var note: String = "",
    val items: MutableList<Item> = mutableListOf(),
) {
    val cupCount: Int get() = items.sumOf { it.qty }
}

/**
 * Bóc tách đơn hàng từ các dòng chữ đọc được trên màn hình app bán hàng.
 * (Bản Kotlin của server/motngum/parser.py — giữ 2 bản giống nhau.)
 *
 * Quy tắc:
 *  - mã đơn: khớp regex (mặc định GF-xxx)
 *  - "1x" + dòng sau, hoặc "2x Tên món", hoặc "Tên món x2" => món mới
 *  - các dòng nằm dưới một món => tuỳ chọn của món đó (Size L, 50% đường, Ít đá…)
 *  - "Ghi chú: …" dưới một món => ghi chú của món; trước món đầu / sau phần tổng => ghi chú đơn
 *  - gặp "Tạm tính", "Tổng", "Phí"… => hết danh sách món
 */
object OrderParser {
    private const val CUR = "(?:₫|đ|d|vnd|vnđ|k)"
    private val I = setOf(RegexOption.IGNORE_CASE)

    private val RE_PRICE_ONLY = Regex("^[-+]?\\s*\\d{1,3}(?:[.,\\s]\\d{3})*(?:[.,]\\d+)?\\s*$CUR?$", I)
    private val RE_TRAILING_PRICE = Regex("\\s+[-+]?\\d{1,3}(?:[.,]\\d{3})+\\s*$CUR?$|\\s+\\d+\\s*$CUR$", I)
    private val RE_QTY_FIRST = Regex("^(\\d{1,2})\\s*[xX×*]\\s*(\\S.*)$")
    private val RE_QTY_ONLY = Regex("^(?:[xX×]\\s*)?(\\d{1,2})\\s*[xX×]?$")
    private val RE_NAME_QTY = Regex("^(\\S.*?)\\s+[xX×]\\s*(\\d{1,2})$")
    private val RE_NOTE = Regex(
        "^(?:ghi\\s*chú|lưu\\s*ý|note|notes|special instructions?)\\s*(?:của\\s+\\S+(?:\\s+\\S+)?)?\\s*[:：]?\\s*(.*)$", I
    )
    private val RE_CUSTOMER = Regex("^(?:khách\\s*hàng|khách|tên khách|customer|người nhận)\\s*[:：]?\\s*(.*)$", I)
    private val RE_BULLET = Regex("^[\\s•·\\-–+*▪◦>]+")
    private val RE_SPACES = Regex("[ \\t]+")

    /** Gặp các dòng này nghĩa là đã hết danh sách món. */
    val STOP_WORDS = listOf(
        "tổng", "tạm tính", "thành tiền", "total", "subtotal", "thanh toán", "payment",
        "phí", "fee", "khuyến mãi", "promo", "chiết khấu", "discount", "giảm giá",
        "tiền mặt", "cash", "thu khách", "doanh thu", "net",
    )

    /** Các dòng giao diện (nút bấm, tiêu đề) cần bỏ qua. */
    val IGNORE_EXACT = setOf(
        "chấp nhận", "accept", "từ chối", "reject", "decline", "in", "print", "in hóa đơn",
        "in hoá đơn", "chi tiết đơn hàng", "order details", "chi tiết", "món", "items", "item",
        "xem thêm", "see more", "đóng", "close", "quay lại", "back", "đơn hàng mới", "new order",
        "đơn mới", "sẵn sàng", "ready", "đánh dấu sẵn sàng", "mark as ready", "giao hàng",
        "delivery", "tự đến lấy", "self pick-up", "pickup", "liên hệ", "chat", "gọi", "call",
        "món ăn", "danh sách món", "số lượng", "qty", "grabfood", "grabmart", "đã nhận", "x",
    )

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC)
            .replace(' ', ' ').replace("​", "")
            .replace(RE_SPACES, " ").trim()

    private fun low(s: String) = norm(s).lowercase()

    private fun isStop(line: String): Boolean {
        val l = low(line)
        return STOP_WORDS.any { w -> l == w || l.startsWith("$w ") || l.startsWith("$w:") || l.startsWith("$w (") }
    }

    private fun isIgnored(line: String): Boolean {
        val l = low(line).trimEnd(':')
        return l in IGNORE_EXACT || RE_PRICE_ONLY.matches(l)
    }

    private fun stripPrice(text: String): Pair<String, String> {
        val m = RE_TRAILING_PRICE.find(text) ?: return text.trim() to ""
        return text.substring(0, m.range.first).trim() to m.value.trim()
    }

    private fun joinNote(a: String, b: String) = "$a; $b".trim { it == ';' || it == ' ' }

    fun splitLines(raw: List<String>): List<String> =
        raw.flatMap { it.lines() }.map(::norm).filter { it.isNotEmpty() }

    fun findCode(lines: List<String>, patterns: List<Regex>): String {
        val text = lines.joinToString("\n")
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val v = if (m.groupValues.size > 1) m.groupValues[1] else m.value
            return v.trim().uppercase()
        }
        return ""
    }

    fun parse(raw: List<String>, codePatterns: List<Regex>): ParsedOrder {
        val lines = splitLines(raw)
        val order = ParsedOrder(code = findCode(lines, codePatterns))
        var cur: Item? = null
        var pendingQty: Int? = null
        var expect: String? = null
        var inItems = false
        var done = false

        for (line in lines) {
            if (order.code.isNotEmpty() && low(line) == order.code.lowercase()) continue

            if (expect != null) {
                val target = expect
                expect = null
                if (!isIgnored(line) && !isStop(line)) {
                    if (target == "note") {
                        val c = cur
                        if (c != null && !done) c.note = joinNote(c.note, line)
                        else order.note = joinNote(order.note, line)
                    } else if (order.customer.isEmpty()) {
                        order.customer = line
                    }
                    continue
                }
            }

            val mNote = RE_NOTE.matchEntire(line)
            if (mNote != null) {
                val v = mNote.groupValues[1].trim()
                val c = cur
                when {
                    v.isEmpty() -> expect = "note"
                    c != null && !done -> c.note = joinNote(c.note, v)
                    else -> order.note = joinNote(order.note, v)
                }
                continue
            }

            val mc = RE_CUSTOMER.matchEntire(line)
            if (mc != null && !inItems) {
                val v = mc.groupValues[1].trim()
                if (v.isNotEmpty()) { if (order.customer.isEmpty()) order.customer = v } else expect = "customer"
                continue
            }

            if (done) continue
            if (inItems && isStop(line)) { done = true; cur = null; continue }

            val mq = RE_QTY_ONLY.matchEntire(line)
            if (mq != null && (line.lowercase().contains('x') || line.contains('×'))) {
                val q = mq.groupValues[1].toInt()
                if (q in 1..99) { pendingQty = q; continue }
            }

            if (isIgnored(line)) continue

            if (pendingQty != null) {
                val (name, price) = stripPrice(line.replace(RE_BULLET, ""))
                cur = Item(name = name, qty = pendingQty, price = price).also { order.items.add(it) }
                pendingQty = null
                inItems = true
                continue
            }

            val mf = RE_QTY_FIRST.matchEntire(line)
            if (mf != null) {
                val (name, price) = stripPrice(mf.groupValues[2])
                if (name.isNotEmpty() && !RE_PRICE_ONLY.matches(name)) {
                    cur = Item(name = name, qty = mf.groupValues[1].toInt(), price = price).also { order.items.add(it) }
                    inItems = true
                    continue
                }
            }

            val mn = RE_NAME_QTY.matchEntire(line)
            if (mn != null && RE_BULLET.find(line)?.range?.first != 0) {
                cur = Item(name = mn.groupValues[1].trim(), qty = mn.groupValues[2].toInt()).also { order.items.add(it) }
                inItems = true
                continue
            }

            val c = cur
            if (c != null) {
                val (opt, _) = stripPrice(line.replace(RE_BULLET, ""))
                if (opt.isNotEmpty() && !isIgnored(opt)) c.options.add(opt)
            }
        }
        order.items.removeAll { it.name.isEmpty() }
        return order
    }

    /**
     * Nhập tay: mỗi dòng 1 món, ví dụ `2x Trà sữa | Size L, 50% đường | ghi chú: ít ngọt`.
     * Dòng bắt đầu bằng '#' là mã đơn, dòng 'ghi chú: …' là ghi chú cả đơn.
     */
    fun parseManual(text: String): ParsedOrder {
        val order = ParsedOrder()
        for (raw in splitLines(listOf(text))) {
            if (raw.startsWith("#")) { order.code = raw.trimStart('#').trim().uppercase(); continue }
            val mNote = RE_NOTE.matchEntire(raw)
            if (mNote != null && '|' !in raw) { order.note = mNote.groupValues[1].trim(); continue }
            val parts = raw.split('|').map { it.trim() }
            var head = parts[0]
            var qty = 1
            val mq = RE_QTY_FIRST.matchEntire(head)
            if (mq != null) {
                qty = mq.groupValues[1].toInt(); head = mq.groupValues[2].trim()
            } else {
                RE_NAME_QTY.matchEntire(head)?.let { head = it.groupValues[1].trim(); qty = it.groupValues[2].toInt() }
            }
            val item = Item(name = head, qty = qty)
            for (p in parts.drop(1)) {
                val mn = RE_NOTE.matchEntire(p)
                if (mn != null) item.note = mn.groupValues[1].trim()
                else item.options.addAll(p.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            order.items.add(item)
        }
        return order
    }
}
