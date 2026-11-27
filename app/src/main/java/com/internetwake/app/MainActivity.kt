package com.internetwake.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.webkit.*
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val payload = intent?.getStringExtra("payload") ?: return
            web.evaluateJavascript("window.onNativeEvent && window.onNativeEvent($payload)", null)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidWake")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
        val filter = IntentFilter("com.internetwake.EVENT")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    }
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.post { web.evaluateJavascript("window.onAppResume && window.onAppResume()", null) }
        val prefs = getSharedPreferences("wake_log", 0)
        if (prefs.getBoolean("running", false) && prefs.getBoolean("overlay_enabled", false) && Settings.canDrawOverlays(this)) {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_OVERLAY_UPDATE))
        }
    }
    override fun onDestroy() { try { unregisterReceiver(receiver) } catch (_: Exception) {}; super.onDestroy() }
    inner class Bridge {
        @JavascriptInterface fun start(seconds: Int) {
            val i = Intent(this@MainActivity, MonitorService::class.java).setAction(MonitorService.ACTION_START).putExtra("seconds", seconds.coerceIn(5,3600))
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        @JavascriptInterface fun stop() { startService(Intent(this@MainActivity, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)) }
        @JavascriptInterface fun state(): String = MonitorService.snapshot(this@MainActivity).toString()
        @JavascriptInterface fun overlayEnabled(): Boolean = getSharedPreferences("wake_log", 0).getBoolean("overlay_enabled", false)
        @JavascriptInterface fun overlayPermissionGranted(): Boolean = Settings.canDrawOverlays(this@MainActivity)
        @JavascriptInterface fun setOverlayEnabled(enabled: Boolean) {
            getSharedPreferences("wake_log", 0).edit().putBoolean("overlay_enabled", enabled).apply()
            if (enabled && !Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            } else startService(Intent(this@MainActivity, MonitorService::class.java).setAction(MonitorService.ACTION_OVERLAY_UPDATE))
        }
    }
}
