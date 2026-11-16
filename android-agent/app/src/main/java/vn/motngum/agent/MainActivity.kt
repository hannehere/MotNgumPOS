package vn.motngum.agent

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        val serverUrl = findViewById<EditText>(R.id.serverUrl).apply { setText(prefs.serverUrl) }
        val apiKey = findViewById<EditText>(R.id.apiKey).apply { setText(prefs.apiKey) }
        val packages = findViewById<EditText>(R.id.packages).apply { setText(prefs.packagesCsv) }
        val notifRegex = findViewById<EditText>(R.id.notifRegex).apply { setText(prefs.notifRegex) }
        val codeRegex = findViewById<EditText>(R.id.codeRegex).apply { setText(prefs.codeRegex) }
        val autoOpen = findViewById<Switch>(R.id.autoOpen).apply { isChecked = prefs.autoOpen }
        val autoScroll = findViewById<Switch>(R.id.autoScroll).apply { isChecked = prefs.autoScroll }
        val uploadShots = findViewById<Switch>(R.id.uploadShots).apply { isChecked = prefs.uploadScreenshots }
        val debugAll = findViewById<Switch>(R.id.debugAll).apply { isChecked = prefs.debugSendAll }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val badRegex = listOf(notifRegex, codeRegex).firstOrNull {
                runCatching { Regex(it.text.toString()) }.isFailure
            }
            if (badRegex != null) {
                badRegex.error = "Regex không hợp lệ"
                return@setOnClickListener
            }
            prefs.serverUrl = serverUrl.text.toString()
            prefs.apiKey = apiKey.text.toString()
            prefs.packagesCsv = packages.text.toString()
            prefs.notifRegex = notifRegex.text.toString()
            prefs.codeRegex = codeRegex.text.toString()
            prefs.autoOpen = autoOpen.isChecked
            prefs.autoScroll = autoScroll.isChecked
            prefs.uploadScreenshots = uploadShots.isChecked
            prefs.debugSendAll = debugAll.isChecked
            OrderReaderService.instance?.applyPackages()
            Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            prefs.serverUrl = serverUrl.text.toString()
            prefs.apiKey = apiKey.text.toString()
            ServerClient(this).get("/api/health") { code, body ->
                runOnUiThread {
                    val msg = if (code == 200) "Kết nối OK: $body" else "Không kết nối được ($code): $body"
                    AgentLog.add(msg)
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Đã tắt tối ưu pin rồi", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        AgentLog.listener = { runOnUiThread { log.text = AgentLog.text() } }
        log.text = AgentLog.text()
    }

    override fun onPause() {
        AgentLog.listener = null
        super.onPause()
    }

    private fun refreshStatus() {
        val notifOk = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, OrderNotificationListener::class.java).flattenToString()) == true
        val a11yOk = OrderReaderService.instance != null
        status.text = buildString {
            append(if (notifOk) "✅" else "❌").append(" Quyền đọc thông báo\n")
            append(if (a11yOk) "✅" else "❌").append(" Trợ năng đọc đơn hàng\n")
            append("Máy chủ: ").append(prefs.serverUrl)
        }
    }
}
