package vn.motngum.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vẽ tem dán ly (mỗi ly 1 tem). Dùng font hệ thống nên in được tiếng Việt có dấu. */
object Labels {

    /** Món số lượng 3 => 3 tem. */
    fun expandCups(items: List<Item>): List<Item> = items.flatMap { item -> List(maxOf(1, item.qty)) { item } }

    fun render(
        order: StoredOrder,
        item: Item,
        index: Int,
        total: Int,
        size: LabelSize,
        shopName: String,
    ): Bitmap {
        val w = size.widthPx
        val h = size.heightPx
        val s = size.dpi / 203f
        val margin = (1.5f / 25.4f * size.dpi)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val x0 = margin
        val x1 = w - margin
        val width = x1 - x0
        var y = margin

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; color = Color.BLACK }

        // --- Dòng đầu: nền đen, "GRAB GF-512" ... "1/3"
        val headH = 30 * s
        c.drawRect(x0, y, x1, y + headH, bold)
        val head = Paint(bold).apply { color = Color.WHITE; textSize = 24 * s }
        val left = "${sourceLabel(order.source)} ${order.code}".trim()
        val right = "$index/$total"
        val base = y + (headH + head.textSize * 0.72f) / 2
        c.drawText(left, x0 + 4 * s, base, head)
        c.drawText(right, x1 - 4 * s - head.measureText(right), base, head)
        y += headH + 4 * s

        // --- Footer
        val foot = Paint(normal).apply { textSize = 16 * s }
        val footY = h - margin - 2 * s
        val time = SimpleDateFormat("HH:mm dd/MM", Locale.US).format(Date())
        val footer = listOf(time, shopName).filter { it.isNotBlank() }.joinToString(" · ")

        // --- Tên món: tối đa 2 dòng, tự thu nhỏ
        var nameSize = 30 * s
        var nameLines: List<String>
        while (true) {
            bold.textSize = nameSize
            nameLines = wrap(item.name, bold, width)
            if (nameLines.size <= 2 || nameSize <= 18 * s) break
            nameSize -= 1
        }
        nameLines = ellipsize(nameLines, 2)
        for (ln in nameLines) {
            y += bold.textSize
            c.drawText(ln, x0, y, bold)
            y += 3 * s
        }

        // --- Tuỳ chọn + ghi chú (ghi chú món + ghi chú đơn)
        val parts = mutableListOf<String>()
        if (item.options.isNotEmpty()) parts.add(item.options.joinToString(", "))
        val notes = listOf(item.note, order.note).filter { it.isNotBlank() }
        if (notes.isNotEmpty()) parts.add("* " + notes.joinToString(" / "))
        val avail = footY - foot.textSize - 4 * s - y
        if (parts.isNotEmpty() && avail > 10) {
            var ds = 20 * s
            var lines: List<String>
            var lh: Float
            while (true) {
                normal.textSize = ds
                lh = ds + 2 * s
                lines = parts.flatMap { wrap(it, normal, width) }
                if (lines.size * lh <= avail || ds <= 13 * s) break
                ds -= 1
            }
            lines = ellipsize(lines, maxOf(1, (avail / lh).toInt()))
            for (ln in lines) {
                y += normal.textSize
                c.drawText(ln, x0, y, normal)
                y += 2 * s
            }
        }

        // --- vẽ footer
        val lineY = footY - foot.textSize - 2 * s
        c.drawLine(x0, lineY, x1, lineY, foot)
        c.drawText(footer, x0, footY - 2 * s, foot)
        return bmp
    }

    fun toMono(bmp: Bitmap): MonoImage {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return MonoImage.from(bmp.width, bmp.height) { x, y ->
            val p = px[y * bmp.width + x]
            val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            lum < 140
        }
    }

    fun renderOrder(order: StoredOrder, size: LabelSize, shopName: String, only: List<Int>? = null): List<Bitmap> {
        val cups = expandCups(order.items)
        return cups.mapIndexedNotNull { i, item ->
            if (only != null && (i + 1) !in only) null else render(order, item, i + 1, cups.size, size, shopName)
        }
    }

    fun sourceLabel(source: String) = when (source) {
        "manual" -> ""
        "grab" -> "GRAB"
        else -> source.uppercase().take(6)
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word0 in text.split(' ').filter { it.isNotEmpty() }) {
            var word = word0
            val trial = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(trial) <= width) { cur = trial; continue }
            if (cur.isNotEmpty()) lines.add(cur)
            while (paint.measureText(word) > width && word.length > 1) {
                var cut = word.length
                while (cut > 1 && paint.measureText(word, 0, cut) > width) cut--
                lines.add(word.substring(0, cut))
                word = word.substring(cut)
            }
            cur = word
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    private fun ellipsize(lines: List<String>, max: Int): List<String> {
        if (lines.size <= max) return lines
        val out = lines.take(max).toMutableList()
        out[max - 1] = out[max - 1].dropLast(1) + "…"
        return out
    }
}
