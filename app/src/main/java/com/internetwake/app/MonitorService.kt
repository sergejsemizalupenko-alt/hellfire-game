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
  private const val CHANNEL = "internet_wake"
  private const val PREF = "wake_log"
  private const val KEY = "entries"
  fun snapshot(c: Context): JSONObject {
   val p = c.getSharedPreferences(PREF, 0)
   return JSONObject().put("running", p.getBoolean("running", false)).put("entries", JSONArray(p.getString(KEY, "[]")))
  }
 }
 private val active = AtomicBoolean(false)
 private var worker: Thread? = null
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

 override fun onBind(intent: Intent?) = null
 override fun onCreate() { super.onCreate(); createChannel() }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  when (intent?.action) {
   ACTION_STOP -> { stopMonitor(); stopSelf(); return START_NOT_STICKY }
   ACTION_START -> {
    interval = intent.getIntExtra("seconds", 10).coerceIn(5, 3600)
    startForeground(11, notification("Мониторинг запущен"))
    if (active.compareAndSet(false, true)) {
     lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InternetWake:Monitor").also { it.acquire() }
     log("Мониторинг запущен. Интервал: $interval сек.")
     worker = thread(name = "InternetWakeWorker") { loop() }
    }
   }
  }
  return START_STICKY
 }
 private fun loop() {
  // Keep the same five chosen servers in the monitoring set. A failed server
  // must NOT be removed: it is probed again on every cycle and can recover.
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
     val ranked = candidates.mapNotNull { s -> probe(s)?.let { s to it } }
      .sortedBy { it.second }
     // Keep exactly five candidates, including temporarily unavailable ones.
     // Failed candidates remain in the set and are retried on every cycle.
     selected = (ranked.map { it.first } + candidates.filterNot { candidate ->
      ranked.any { it.first == candidate }
     }).distinct().take(5)
     lastDiscovery = began
     selected.forEachIndexed { i, s -> log("${i + 1}. ${s.name}") }
    }
    cycle++
    // Probe ALL five selected servers every cycle, including those that failed
    // previously. This allows the availability counter to move in both directions.
    val results = selected.mapNotNull { s -> probe(s)?.let { s to it } }
     .sortedBy { it.second }
    log("Цикл №$cycle: активных серверов ${results.size}/5.")
    if (results.isEmpty()) {
     val ok = probe(Server("Google connectivity", "https://connectivitycheck.gstatic.com", "generate_204")) != null ||
      probe(Server("Google", "https://www.google.com", "generate_204")) != null
     log(if (ok) "Сеть доступна (резерв Google)." else "Нет ответа от серверов.")
    } else {
     results.forEach { log("${it.first.name}: ${it.second} мс") }
    }
    updateNotification("Цикл $cycle • серверов ${results.size}/5")
   } catch (e: Exception) { log("Ошибка цикла: ${e.message ?: "сеть"}") }
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
 private fun log(message: String) {
  val p = getSharedPreferences(PREF, 0); val arr = JSONArray(p.getString(KEY, "[]"))
  arr.put(JSONObject().put("time", System.currentTimeMillis()).put("message", message))
  while (arr.length() > 500) arr.remove(0)
  p.edit().putString(KEY, arr.toString()).putBoolean("running", active.get()).apply()
  val payload = JSONObject().put("type", "log").put("time", System.currentTimeMillis()).put("message", message)
  sendBroadcast(Intent("com.internetwake.EVENT").setPackage(packageName).putExtra("payload", payload.toString()))
 }
 private fun stopMonitor() {
  active.set(false); worker?.interrupt(); worker = null
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