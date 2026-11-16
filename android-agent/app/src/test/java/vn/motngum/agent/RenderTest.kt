package vn.motngum.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Vẽ tem + màn hình đơn hàng thật (Robolectric, đồ hoạ native) và lưu PNG vào app/build/render để xem. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
class RenderTest {
    private val out = File("build/render").apply { mkdirs() }
    private val screen = """
        Chi tiết đơn hàng
        GF-512
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
    """.trimIndent().lines()

    private fun save(b: Bitmap, name: String) = File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test
    fun labelsAndOrdersScreen() {
        val ctx = RuntimeEnvironment.getApplication()
        Prefs(ctx).printerHost = "192.168.1.100"
        val id = OrderManager.ingestScreen(ctx, "com.grab.merchant", screen)!!
        // Lần đọc thứ 2 (giả lập cuộn) không tạo đơn trùng
        assertEquals(id, OrderManager.ingestScreen(ctx, "com.grab.merchant", screen))
        val order = OrderStore.get(ctx).get(id)!!
        assertEquals(3, order.cupCount)

        val size = LabelSize(50, 30, 2)
        val labels = Labels.renderOrder(order, size, "Một Ngụm")
        assertEquals(3, labels.size)
        labels.forEachIndexed { i, b ->
            assertEquals(size.widthPx, b.width); assertEquals(size.heightPx, b.height)
            save(b, "label-${i + 1}.png")
        }
        val mono = Labels.toMono(labels[0])
        assertTrue("tem phải có điểm đen", mono.data.any { it != 0xFF.toByte() })

        OrderStore.get(ctx).insert("manual", OrderParser.parseManual("1x Cà phê muối | Size L, Ít đá | ghi chú: không đường"), "")
        val act = Robolectric.buildActivity(OrdersActivity::class.java).setup().get()
        val root: View = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2560, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1600, 2560)
        val shot = Bitmap.createBitmap(1600, 2560, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(shot))
        save(shot, "orders.png")
    }

    /** Máy in giả: mở cổng TCP trên máy, đơn mới phải được TỰ in tới đó sau thời gian chờ. */
    @Test
    fun autoPrintsOverTcp() {
        val ctx = RuntimeEnvironment.getApplication()
        val fake = java.net.ServerSocket(0)
        val received = java.util.concurrent.CompletableFuture<ByteArray>()
        Thread { fake.accept().use { received.complete(it.getInputStream().readBytes()) } }.start()
        Prefs(ctx).apply { printerHost = "127.0.0.1"; printerPort = fake.localPort; printDelayMs = 1000; autoPrint = true }

        // mã đơn khác test trên (Robolectric giữ lại singleton giữa các test)
        val id = OrderManager.ingestScreen(ctx, "com.grab.merchant", screen.map { it.replace("GF-512", "GF-777") })!!
        Thread.sleep(1300)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))

        val data = String(received.get(10, java.util.concurrent.TimeUnit.SECONDS), Charsets.ISO_8859_1)
        assertTrue(data.startsWith("SIZE 50 mm,30 mm"))
        assertEquals(3, Regex("BITMAP 0,0,50,240,0,").findAll(data).count())
        for (i in 0 until 50) { // chờ executor cập nhật trạng thái
            if (OrderStore.get(ctx).get(id)!!.status == "printed") break
            Thread.sleep(50)
        }
        assertEquals("printed", OrderStore.get(ctx).get(id)!!.status)
        fake.close()
    }
}
