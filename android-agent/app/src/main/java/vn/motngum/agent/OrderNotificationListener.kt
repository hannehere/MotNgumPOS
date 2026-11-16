package vn.motngum.agent

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Nghe thông báo của app bán hàng (GrabMerchant...). Khi thấy thông báo có đơn:
 *  1. gửi nội dung thông báo về server (để ghi nhật ký / bóc đơn nếu thông báo đủ chi tiết)
 *  2. nhờ OrderReaderService mở thông báo -> vào màn hình chi tiết đơn -> đọc chữ + chụp màn hình.
 */
class OrderNotificationListener : NotificationListenerService() {

    private val recent = LinkedHashMap<String, Long>()

    override fun onListenerConnected() {
        AgentLog.add("Đã bật nghe thông báo")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        val pkg = sbn.packageName ?: return
        if (pkg !in prefs.packages) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = buildString {
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { append(it).append('\n') }
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { append(it).append('\n') }
        }.trim()
        val all = "$title\n$text\n$big"

        val isOrder = runCatching { Regex(prefs.notifRegex, RegexOption.IGNORE_CASE).containsMatchIn(all) }
            .getOrDefault(true)
        if (!isOrder) return

        // Một đơn có thể bắn nhiều thông báo (chuông lặp lại) -> bỏ trùng trong 60 giây
        val key = "$pkg|${sbn.id}|$title|$text"
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > 60_000 }
        if (recent.containsKey(key)) return
        recent[key] = now

        AgentLog.add("Thông báo đơn từ $pkg: $title – $text")
        if (!prefs.standalone) ServerClient(this).post(
            "/api/agent/notification",
            JSONObject()
                .put("package", pkg)
                .put("title", title)
                .put("text", text)
                .put("big_text", big)
                .put("posted_at", sbn.postTime / 1000.0),
        )

        if (prefs.autoOpen) {
            val reader = OrderReaderService.instance
            if (reader == null) {
                AgentLog.add("Chưa bật Trợ năng (Accessibility) nên không tự mở đơn được")
            } else {
                reader.openOrder(pkg, n.contentIntent)
            }
        }
    }
}
