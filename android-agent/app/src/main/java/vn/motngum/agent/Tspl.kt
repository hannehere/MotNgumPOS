package vn.motngum.agent

import java.io.ByteArrayOutputStream

/** Ảnh 1-bit theo định dạng TSPL: mỗi byte 8 điểm, bit 1 = trắng, bit 0 = in đen. */
class MonoImage(val widthBytes: Int, val height: Int, val data: ByteArray) {
    init {
        require(data.size == widthBytes * height) { "Sai kích thước ảnh" }
    }

    companion object {
        /** black(x, y) = true nếu điểm đó cần in đen. */
        fun from(width: Int, height: Int, black: (Int, Int) -> Boolean): MonoImage {
            val wb = (width + 7) / 8
            val out = ByteArray(wb * height) { 0xFF.toByte() }
            for (y in 0 until height) for (x in 0 until width) {
                if (black(x, y)) {
                    val i = y * wb + x / 8
                    out[i] = (out[i].toInt() and (0x80 ushr (x % 8)).inv()).toByte()
                }
            }
            return MonoImage(wb, height, out)
        }
    }
}

data class LabelSize(val widthMm: Int, val heightMm: Int, val gapMm: Int, val dpi: Int = 203) {
    val widthPx: Int get() = Math.round(widthMm / 25.4 * dpi).toInt().let { it - it % 8 }
    val heightPx: Int get() = Math.round(heightMm / 25.4 * dpi).toInt()
}

/** Lệnh cho máy in tem TSPL (Xprinter XP-365B, TSC, Gprinter…). */
object Tspl {
    private fun header(size: LabelSize, density: Int = 8, speed: Int = 4) =
        ("SIZE ${size.widthMm} mm,${size.heightMm} mm\r\nGAP ${size.gapMm} mm,0 mm\r\n" +
            "DENSITY $density\r\nSPEED $speed\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nSET TEAR ON\r\n").toByteArray()

    fun job(images: List<MonoImage>, size: LabelSize, density: Int = 8, speed: Int = 4): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(size, density, speed))
        for (img in images) {
            out.write("CLS\r\n".toByteArray())
            out.write("BITMAP 0,0,${img.widthBytes},${img.height},0,".toByteArray())
            out.write(img.data)
            out.write("\r\nPRINT 1,1\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    /** Cho máy tự dò khe giữa các tem (khi mới lắp cuộn tem / đổi khổ tem). */
    fun calibrate(size: LabelSize): ByteArray =
        "SIZE ${size.widthMm} mm,${size.heightMm} mm\r\nGAP ${size.gapMm} mm,0 mm\r\nGAPDETECT\r\n".toByteArray()
}
