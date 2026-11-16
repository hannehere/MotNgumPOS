package vn.motngum.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cùng các mẫu với server/tests/test_parser.py để 2 bản parser luôn giống nhau. */
class OrderParserTest {
    private val grab = listOf(Regex(Prefs.DEFAULT_CODE_REGEX))

    private val screenA = """
        Chi tiết đơn hàng
        GF-512
        Nguyễn V. A
        Giao hàng
        3 món
        1x
        Trà sữa trân châu đường đen
        45.000₫
        Size L
        50% đường
        Ít đá
        Ghi chú: không lấy ống hút
        2x
        Trà đào cam sả
        70.000₫
        Size M
        Tạm tính
        115.000₫
        Chấp nhận
    """.trimIndent().lines()

    private val screenB = """
        Đơn hàng mới
        GF-88
        2x Cà phê sữa đá 58.000đ
        - Ít ngọt
        1x Bạc xỉu 29.000đ
        Ghi chú
        Giao nhanh giúp em
        Tổng cộng 87.000đ
    """.trimIndent().lines()

    @Test
    fun grabSplitQtyLines() {
        val o = OrderParser.parse(screenA, grab)
        assertEquals("GF-512", o.code)
        assertEquals(listOf(1 to "Trà sữa trân châu đường đen", 2 to "Trà đào cam sả"), o.items.map { it.qty to it.name })
        assertEquals(listOf("Size L", "50% đường", "Ít đá"), o.items[0].options)
        assertEquals("không lấy ống hút", o.items[0].note)
        assertEquals(listOf("Size M"), o.items[1].options)
        assertEquals(3, o.cupCount)
    }

    @Test
    fun inlineQtyPriceAndNoteAfterLabel() {
        val o = OrderParser.parse(screenB, grab)
        assertEquals("GF-88", o.code)
        assertEquals(
            listOf(Triple(2, "Cà phê sữa đá", "58.000đ"), Triple(1, "Bạc xỉu", "29.000đ")),
            o.items.map { Triple(it.qty, it.name, it.price) },
        )
        assertEquals(listOf("Ít ngọt"), o.items[0].options)
        assertEquals("Giao nhanh giúp em", o.items[1].note)
    }

    @Test
    fun noCodeNoItems() {
        val o = OrderParser.parse(listOf("Bạn có đơn hàng mới", "Mở app để xem"), grab)
        assertEquals("", o.code)
        assertEquals(0, o.items.size)
    }

    @Test
    fun manualSyntax() {
        val o = OrderParser.parseManual("#a12\n2x Trà sữa | Size L, 50% đường | ghi chú: ít ngọt\nCà phê đen x3\nghi chú: mang đi")
        assertEquals("A12", o.code)
        assertEquals(2, o.items[0].qty)
        assertEquals(listOf("Size L", "50% đường"), o.items[0].options)
        assertEquals("ít ngọt", o.items[0].note)
        assertEquals("Cà phê đen" to 3, o.items[1].name to o.items[1].qty)
        assertEquals("mang đi", o.note)
    }

    @Test
    fun expandCupsNumbersAcrossOrder() {
        val o = OrderParser.parse(screenA, grab)
        val cups = Labels.expandCups(o.items)
        assertEquals(3, cups.size)
        assertEquals(listOf("Trà sữa trân châu đường đen", "Trà đào cam sả", "Trà đào cam sả"), cups.map { it.name })
    }
}
