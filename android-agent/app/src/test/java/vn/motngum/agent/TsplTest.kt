package vn.motngum.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsplTest {
    @Test
    fun monoBitsBlackIsZero() {
        // 10x1: điểm 0 và 9 đen
        val img = MonoImage.from(10, 1) { x, _ -> x == 0 || x == 9 }
        assertEquals(2, img.widthBytes)
        assertArrayEquals(byteArrayOf(0x7F, 0xBF.toByte()), img.data)
    }

    @Test
    fun jobLayout() {
        val size = LabelSize(50, 30, 2)
        assertEquals(400, size.widthPx)
        assertEquals(240, size.heightPx)
        val img = MonoImage.from(size.widthPx, size.heightPx) { _, _ -> false }
        val s = String(Tspl.job(listOf(img, img), size), Charsets.ISO_8859_1)
        assertTrue(s.startsWith("SIZE 50 mm,30 mm\r\nGAP 2 mm,0 mm\r\n"))
        assertEquals(2, Regex("BITMAP 0,0,50,240,0,").findAll(s).count())
        assertTrue(s.endsWith("PRINT 1,1\r\n"))
        assertTrue(String(Tspl.calibrate(size)).contains("GAPDETECT"))
    }
}
