package vn.motngum.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMergerTest {
    private val header = listOf("Chi tiết đơn hàng", "GF-512")
    private val footer = listOf("Chấp nhận")

    @Test
    fun appendsScrolledContentKeepingHeaderAndFooter() {
        val a = header + listOf("1x", "Trà sữa", "Size M", "2x", "Trà đào") + footer
        val b = header + listOf("2x", "Trà đào", "Size M", "Tạm tính") + footer
        assertEquals(
            header + listOf("1x", "Trà sữa", "Size M", "2x", "Trà đào", "Size M", "Tạm tính") + footer,
            ScreenMerger.merge(a, b),
        )
    }

    @Test
    fun ignoresSnapshotAlreadyContained() {
        val a = header + listOf("1x", "Trà sữa", "Size L") + footer
        assertEquals(a, ScreenMerger.merge(a, header + listOf("1x", "Trà sữa")))
        assertEquals(a, ScreenMerger.merge(a, a))
    }

    @Test
    fun startsFromEmpty() {
        val b = listOf("GF-1", "1x", "Cà phê")
        assertEquals(b, ScreenMerger.merge(emptyList(), b))
    }
}
