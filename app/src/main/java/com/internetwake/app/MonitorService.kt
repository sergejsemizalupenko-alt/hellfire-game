package com.internetwake.app

import android.app.*
import android.content.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MonitorService : Service() {
    companion object {
        const val ACTION_START = "wake.START"
        const val ACTION_STOP = "wake.STOP"
        const val ACTION_SPEED = "wake.SPEED"
        private const val CHANNEL = "internet_wake"
        private const val PREF = "wake_log"
        private const val KEY = "entries"
        private const val KEY_HISTORY = "speed_history"
        fun snapshot(c: Context): JSONObject {
            val p = c.getSharedPreferences(PREF, 0)
            return JSONObject().put("running", p.getBoolean("running", false)).put("entries", JSONArray(p.getString(KEY, "[]"))).put("history", JSONArray(p.getString(KEY_HISTORY, "[]")) )
        }
    }
    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private var speedWorker: Thread? = null
    private var interval = 10
    private var lock: PowerManager.WakeLock? = null
    private val fallback = listOf(
        "Poznan, Poland|https://speedtest.kamilszczepanski.com|empty.php",
        "Prague, Czech Republic|https://speedtest.cesnet.cz|backend/empty.php",
        "Prague, Turris|https://librespeed.turris.cz|backend/empty.php",
        "Nuremberg, Germany|https://de1.backend.librespeed.org|empty.php",
        "Nuremberg, Germany 2|https://de4.backend.librespeed.org|empty.php",
        "Nuremberg, Germany 3|https://de3.backend.librespeed.org|empty.php",
        "Nuremberg, Germany 4|https://de5.backend.librespeed.org|empty.php",
        "Vilnius, Lithuania|https://lt1.backend.librespeed.org|empty.php",
        "Helsinki, Finland|https://finew.openspeed.org|backend437/empty.php",
        "Helsinki, Finland 2|https://fast.kabi.tk|empty.php",
        "Serbia|https://speedtest2.sox.rs|libre/backend/empty.php",
        "Frankfurt, Germany|https://fra.speedtest.clouvider.net/backend|empty.php"
    ).map { val a = it.split("|"); Server(a[0], a[1], a[2]) }

    data class Server(val name: String, val base: String, val path: String)
    data class SpeedServer(val name: String, val base: String, val dl: String, val ul: String, val ping: String)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMonitor(); stopSelf(); return START_NOT_STICKY }
            ACTION_SPEED -> {
                if (speedWorker?.isAlive == true) return START_STICKY
                startForeground(11, notification("Измерение скорости интернета"))
                speedWorker = thread(name = "InternetWakeSpeedTest") {
                    try { runSpeedTest() } catch (e: Exception) { sendSpeed("error", e.message ?: "Ошибка теста"); log("Ошибка теста скорости: ${e.message ?: "сеть"}") }
                    finally { sendSpeed("done", "ok"); if (!active.get()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }; speedWorker = null }
                }
            }
            ACTION_START -> {
                interval = intent.getIntExtra("seconds", 10).coerceIn(5, 3600)
                startForeground(11, notification("Мониторинг запущен"))
                if (active.compareAndSet(false, true)) {
                    lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InternetWake:Monitor").also { it.acquire() }
                    log("Мониторинг запущен. Интервал: $interval сек.")
                    worker = thread(name = "InternetWakeWorker") { loop() }
                    startAutomaticSpeedTests()
                }
            }
        }
        return START_STICKY
    }
    private fun startAutomaticSpeedTests() {
        if (speedWorker?.isAlive == true) return
        speedWorker = thread(name = "InternetWakeAutoSpeedTest") {
            while (active.get()) {
                try {
                    runSpeedTest()
                } catch (e: Exception) {
                    sendSpeed("error", e.message ?: "Ошибка теста")
                    log("Ошибка автоматического теста скорости: ${e.message ?: "сеть"}")
                } finally {
                    sendSpeed("done", "ok")
                }
                if (active.get()) {
                    try { Thread.sleep(interval * 1000L) } catch (_: InterruptedException) { }
                }
            }
            speedWorker = null
        }
    }

    private fun loop() {
        var selected: List<Server> = emptyList()
        var lastDiscovery = 0L
        var cycle = 0
        while (active.get()) {
            val began = System.currentTimeMillis()
            try {
                if (selected.isEmpty() || began - lastDiscovery > 30 * 60 * 1000L) {
                    log("Загружаем список европейских серверов.")
                    val candidates = loadServers()
                    log("Серверов для проверки: ${candidates.size}")
                    val ranked = candidates.mapNotNull { s -> probe(s)?.let { s to it } }.sortedBy { it.second }
                    // Keep three candidates even when some fail during discovery.
                    // Every candidate is probed again on every monitoring cycle.
                    selected = (ranked.map { it.first } + candidates.filterNot { candidate ->
                        ranked.any { it.first == candidate }
                    }).distinct().take(3)
                    lastDiscovery = began
                    selected.forEachIndexed { i, s ->
                        val latency = ranked.firstOrNull { it.first == s }?.second
                        log(if (latency != null) "${i + 1}. ${s.name} — ${latency} мс" else "${i + 1}. ${s.name} — ожидает проверки")
                    }
                }
                cycle++
                val checked = selected.mapNotNull { s -> probe(s)?.let { s to it } }.sortedBy { it.second }
                // Do not remove failed servers from selected: they may recover next cycle.
                log("Цикл №$cycle: активных серверов ${checked.size}/3.")
                if (checked.isEmpty()) {
                    val ok = probe(Server("Google connectivity", "https://connectivitycheck.gstatic.com", "generate_204")) != null ||
                        probe(Server("Google", "https://www.google.com", "generate_204")) != null
                    log(if (ok) "Сеть доступна (резерв Google)." else "Нет ответа от серверов.")
                } else {
                    checked.forEach { log("${it.first.name}: ${it.second} мс") }
                }
                updateNotification("Цикл $cycle • серверов ${checked.size}/3")
            } catch (e: Exception) { log("Ошибка цикла: ${e.message ?: "сеть"}") }
            // Interval is a pause after each completed cycle; long discovery must not trigger an immediate next cycle.
            try { Thread.sleep(interval * 1000L) } catch (_: InterruptedException) { }
        }
    }
    private fun loadServers(): List<Server> {
        return try {
            val c = URL("https://raw.githubusercontent.com/librespeed/speedtest/master/server-list.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            val out = mutableListOf<Server>()
            val country = Regex("Netherlands|Germany|Finland|England|United Kingdom|Poland|Czech|Serbia|Lithuania|Italy|France|Sweden|Norway|Austria|Romania|Slovakia|Hungary|Bulgaria|Ukraine|Latvia|Estonia|Denmark|Switzerland|Spain|Portugal|Belgium|Ireland|Greece|Croatia|Slovenia|Bosnia|Albania|Montenegro|Moldova|Luxembourg", RegexOption.IGNORE_CASE)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name"); val base = o.optString("server"); val path = o.optString("pingURL")
                if (name.isNotBlank() && base.isNotBlank() && path.isNotBlank() && country.containsMatchIn(name)) out.add(Server(name, base, path))
            }
            if (out.isEmpty()) fallback else out
        } catch (_: Exception) { log("Используем резервный список серверов."); fallback }
    }
    private fun probe(s: Server): Long? {
        var conn: HttpURLConnection? = null
        return try {
            val base = if (s.base.endsWith("/")) s.base else s.base + "/"
            val url = URL(URL(base), s.path).toString() + "?wake=${System.nanoTime()}"
            val start = System.nanoTime()
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"; conn.connectTimeout = 4500; conn.readTimeout = 4500; conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.responseCode
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        } catch (_: Exception) { null } finally { conn?.disconnect() }
    }

    private fun sendSpeed(stage: String, value: String, name: String = "") {
        val payload = JSONObject().put("type", "speed").put("stage", stage).put("value", value).put("name", name)
        sendBroadcast(Intent("com.internetwake.EVENT").setPackage(packageName).putExtra("payload", payload.toString()))
    }
    private fun runSpeedTest() {
        log("Начинаем тест скорости LibreSpeed.")
        val raw = URL("https://raw.githubusercontent.com/librespeed/speedtest/master/server-list.json")
        val conn = raw.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 10000
        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() }); conn.disconnect()
        val region = Regex("Netherlands|Germany|Finland|England|United Kingdom|Poland|Czech|Serbia|Lithuania|Italy|France|Sweden|Norway|Austria|Romania|Slovakia|Hungary|Bulgaria|Ukraine|Latvia|Estonia|Denmark|Switzerland|Spain|Portugal|Belgium|Ireland|Greece|Croatia|Slovenia|Bosnia|Albania|Montenegro|Moldova|Luxembourg", RegexOption.IGNORE_CASE)
        val candidates = mutableListOf<SpeedServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name"); var base = o.optString("server")
            if (name.isBlank() || !region.containsMatchIn(name) || base.isBlank()) continue
            if (base.startsWith("//")) base = "https:$base"
            if (!base.startsWith("http")) continue
            if (!base.endsWith("/")) base += "/"
            val dl = o.optString("dlURL"); val ul = o.optString("ulURL"); val ping = o.optString("pingURL")
            if (dl.isNotBlank() && ul.isNotBlank() && ping.isNotBlank()) candidates.add(SpeedServer(name, base, dl, ul, ping))
        }
        if (candidates.isEmpty()) throw Exception("Список серверов пуст")
        log("Проверяем ${candidates.size} европейских серверов.")
        val measured = candidates.take(30).mapNotNull { server ->
            try {
                val start = System.nanoTime()
                val u = URL(URL(server.base), server.ping + "?wake=" + System.nanoTime())
                val c = u.openConnection() as HttpURLConnection
                c.connectTimeout = 3500; c.readTimeout = 3500; c.useCaches = false
                val code = c.responseCode; c.disconnect()
                if (code in 200..299) server to ((System.nanoTime() - start) / 1_000_000.0) else null
            } catch (_: Exception) { null }
        }.sortedBy { it.second }
        val bestPair = measured.firstOrNull() ?: throw Exception("Нет доступных тестовых серверов")
        val best = bestPair.first
        log("Выбран сервер: ${best.name} (${String.format("%.1f", bestPair.second)} мс)")
        sendSpeed("server", "ok", best.name)
        val pings = mutableListOf<Double>()
        repeat(8) {
            val start = System.nanoTime()
            val u = URL(URL(best.base), best.ping + "?wake=" + System.nanoTime())
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            val code = c.responseCode; c.disconnect()
            if (code in 200..299) pings.add((System.nanoTime() - start) / 1_000_000.0)
        }
        if (pings.isEmpty()) throw Exception("Не удалось измерить ping")
        val ping = pings.average()
        val jitter = if (pings.size > 1) pings.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average() else 0.0
        sendSpeed("ping", String.format(java.util.Locale.US, "%.1f", ping))
        sendSpeed("jitter", String.format(java.util.Locale.US, "%.1f", jitter))
        log("Ping: ${String.format("%.1f", ping)} мс • Jitter: ${String.format("%.1f", jitter)} мс")
        val dlUrl = URL(URL(best.base), best.dl + "?ckSize=4&wake=" + System.nanoTime())
        val dlConn = dlUrl.openConnection() as HttpURLConnection
        dlConn.connectTimeout = 10000; dlConn.readTimeout = 20000; dlConn.useCaches = false
        val dlStart = System.nanoTime(); var downloaded = 0L
        dlConn.inputStream.use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; downloaded += n } }
        dlConn.disconnect()
        val dlSeconds = (System.nanoTime() - dlStart) / 1_000_000_000.0
        if (downloaded == 0L || dlSeconds <= 0) throw Exception("Загрузка не состоялась")
        val dlMbps = downloaded * 8.0 / dlSeconds / 1_000_000.0
        sendSpeed("download", String.format(java.util.Locale.US, "%.2f", dlMbps))
        log("Download: ${String.format("%.2f", dlMbps)} Мбит/с")
        val upload = ByteArray(1024 * 1024); java.security.SecureRandom().nextBytes(upload)
        val ulUrl = URL(URL(best.base), best.ul + "?wake=" + System.nanoTime())
        val ulConn = ulUrl.openConnection() as HttpURLConnection
        ulConn.requestMethod = "POST"; ulConn.doOutput = true; ulConn.connectTimeout = 10000; ulConn.readTimeout = 20000
        ulConn.setRequestProperty("Content-Type", "application/octet-stream")
        ulConn.setFixedLengthStreamingMode(upload.size)
        val ulStart = System.nanoTime()
        ulConn.outputStream.use { it.write(upload) }
        val code = ulConn.responseCode
        ulConn.disconnect()
        val ulSeconds = (System.nanoTime() - ulStart) / 1_000_000_000.0
        if (code !in 200..299 || ulSeconds <= 0) throw Exception("Сервер отклонил отправку ($code)")
        val ulMbps = upload.size * 8.0 / ulSeconds / 1_000_000.0
        saveSpeedResult(ping, jitter, dlMbps, ulMbps, best.name)
        sendSpeed("upload", String.format(java.util.Locale.US, "%.2f", ulMbps))
        log("Upload: ${String.format("%.2f", ulMbps)} Мбит/с")
        log("Тест скорости завершён.")
    }

    private fun saveSpeedResult(ping: Double, jitter: Double, download: Double, upload: Double, server: String) {
        val prefs = getSharedPreferences(PREF, 0)
        val history = JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        history.put(JSONObject()
            .put("time", System.currentTimeMillis())
            .put("ping", ping)
            .put("jitter", jitter)
            .put("download", download)
            .put("upload", upload)
            .put("server", server))
        while (history.length() > 50) history.remove(0)
        prefs.edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    private fun log(message: String) {
        val p = getSharedPreferences(PREF, 0); val arr = JSONArray(p.getString(KEY, "[]"))
        arr.put(JSONObject().put("time", System.currentTimeMillis()).put("message", message))
        while (arr.length() > 500) arr.remove(0)
        p.edit().putString(KEY, arr.toString()).putBoolean("running", active.get()).apply()
        val payload = JSONObject().put("type", "log").put("time", System.currentTimeMillis()).put("message", message)
        sendBroadcast(Intent("com.internetwake.EVENT").setPackage(packageName).putExtra("payload", payload.toString()))
    }
    private fun stopMonitor() {
        active.set(false); worker?.interrupt(); worker = null; speedWorker?.interrupt()
        try { lock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        lock = null
        getSharedPreferences(PREF, 0).edit().putBoolean("running", false).apply()
        log("Мониторинг остановлен.")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "INTERNET WAKE", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("INTERNET WAKE").setContentText(text).setOngoing(true).setContentIntent(pi).build()
    }
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(11, notification(text)) }
    override fun onDestroy() { if (active.get()) stopMonitor(); super.onDestroy() }
}
