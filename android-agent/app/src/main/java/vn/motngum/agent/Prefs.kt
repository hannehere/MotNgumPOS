package vn.motngum.agent

import android.content.Context
import android.content.SharedPreferences

/** Cài đặt của agent, lưu trong SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("motngum", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "http://192.168.1.10:8686")!!.trimEnd('/')
        set(v) = sp.edit().putString("serverUrl", v.trim()).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "doi-ma-nay-di")!!
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    /** Package của các app bán hàng cần theo dõi, cách nhau bởi dấu phẩy. */
    var packagesCsv: String
        get() = sp.getString("packages", DEFAULT_PACKAGES)!!
        set(v) = sp.edit().putString("packages", v).apply()

    val packages: Set<String>
        get() = packagesCsv.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Thông báo nào được coi là "có đơn mới" (regex, không phân biệt hoa thường). */
    var notifRegex: String
        get() = sp.getString("notifRegex", "đơn|order|món")!!
        set(v) = sp.edit().putString("notifRegex", v).apply()

    /** Regex nhận diện mã đơn trên màn hình — chỉ khi thấy mã đơn mới gửi về server. */
    var codeRegex: String
        get() = sp.getString("codeRegex", DEFAULT_CODE_REGEX)!!
        set(v) = sp.edit().putString("codeRegex", v).apply()

    var autoOpen: Boolean
        get() = sp.getBoolean("autoOpen", true)
        set(v) = sp.edit().putBoolean("autoOpen", v).apply()

    var autoScroll: Boolean
        get() = sp.getBoolean("autoScroll", true)
        set(v) = sp.edit().putBoolean("autoScroll", v).apply()

    var uploadScreenshots: Boolean
        get() = sp.getBoolean("uploadScreenshots", true)
        set(v) = sp.edit().putBoolean("uploadScreenshots", v).apply()

    /** Gửi mọi màn hình đọc được (kể cả không có mã đơn) để xem trong "Nhật ký agent" — dùng khi chỉnh parser. */
    var debugSendAll: Boolean
        get() = sp.getBoolean("debugSendAll", false)
        set(v) = sp.edit().putBoolean("debugSendAll", v).apply()

    companion object {
        const val DEFAULT_PACKAGES = "com.grab.merchant"
        const val DEFAULT_CODE_REGEX = "\\bG[FM]-?\\d{2,6}\\b"
    }
}
