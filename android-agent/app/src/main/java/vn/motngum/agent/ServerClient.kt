package vn.motngum.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Gửi dữ liệu về MotNgum POS server (HTTP trong mạng LAN), chạy nền + thử lại. */
class ServerClient(context: Context) {
    private val prefs = Prefs(context)

    fun post(path: String, body: JSONObject, onResult: ((Int, String) -> Unit)? = null) {
        executor.execute {
            var attempt = 0
            while (true) {
                attempt++
                try {
                    val (code, text) = request("POST", path, body.toString())
                    AgentLog.add("POST $path → $code ${text.take(160)}")
                    onResult?.invoke(code, text)
                    return@execute
                } catch (e: Exception) {
                    Log.w(TAG, "POST $path lỗi (lần $attempt): $e")
                    if (attempt >= 4) {
                        AgentLog.add("POST $path thất bại: ${e.message}")
                        onResult?.invoke(-1, e.message ?: "error")
                        return@execute
                    }
                    Thread.sleep(1000L * attempt * attempt)
                }
            }
        }
    }

    fun get(path: String, onResult: (Int, String) -> Unit) {
        executor.execute {
            try {
                val (code, text) = request("GET", path, null)
                onResult(code, text)
            } catch (e: Exception) {
                onResult(-1, e.message ?: e.toString())
            }
        }
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        val conn = URL(prefs.serverUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4000
            conn.readTimeout = 15000
            conn.setRequestProperty("X-Api-Key", prefs.apiKey)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "MotNgumServer"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

/** Nhật ký ngắn hiển thị trên màn hình app để dễ kiểm tra. */
object AgentLog {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null

    @Synchronized
    fun add(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$t  $msg")
        while (lines.size > 60) lines.removeLast()
        Log.i("MotNgum", msg)
        listener?.invoke()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
