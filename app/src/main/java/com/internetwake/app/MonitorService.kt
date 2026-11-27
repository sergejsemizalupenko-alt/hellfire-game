package com.internetwake.app
import android.app.*
import android.content.*
import android.os.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class MonitorService : Service() {
 companion object {
  const val ACTION_START = "wake.START"
  const val ACTION_STOP = "wake.STOP"
  const val ACTION_OVERLAY_UPDATE = "wake.OVERLAY_UPDATE"
  private const val CHANNEL = "internet_wake"
  private const val PREF = "wake_log"
  private const val KEY = "entries"

  fun snapshot(context: Context): JSONObject {
   val prefs = context.getSharedPreferences(PREF, 0)
   return JSONObject()
    .put("running", prefs.getBoolean("running", false))
    .put("startedAt", prefs.getLong("started_at", 0L))
    .put("entries", JSONArray(prefs.getString(KEY, "[]")))
  }
 }

 private val active = AtomicBoolean(false)
 private val discoveryRunning = AtomicBoolean(false)
 private val discoveryResults = ConcurrentLinkedQueue<Pair<Server, Long>>()
 @Volatile private var discoveryWorker: Thread? = null
 private var worker: Thread? = null
 private var interval = 5
 private val mainHandler = Handler(Looper.getMainLooper())
 @Volatile private var overlayView: TextView? = null
 @Volatile private var lastActiveCount = 0
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
   ACTION_OVERLAY_UPDATE -> { mainHandler.post { updateOverlayDot() }; return START_NOT_STICKY }
   ACTION_START -> {
    interval = intent.getIntExtra("seconds", 5).coerceIn(5, 3600)
    getSharedPreferences(PREF, 0).edit().putLong("started_at", System.currentTimeMillis()).apply()
    startForeground(11, notification("Мониторинг запущен"))
    if (active.compareAndSet(false, true)) {
     lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InternetWake:Monitor").also { it.acquire() }
     log("Мониторинг запущен. Интервал: $interval сек.")
     worker = thread(name = "InternetWakeWorker") { loop() }
     mainHandler.post { updateOverlayDot() }
    }
   }
  }
  return START_STICKY
 }
 private fun loop() {
  // The selected servers stay under continuous cycle-by-cycle monitoring.
  // Recovery discovery runs on a separate worker and never blocks this loop.
  var selected: List<Server> = emptyList()
  var candidatePool: List<Server> = emptyList()
  var lastPoolRefresh = 0L
  var lastSearchStart = 0L
  var cycle = 0
  while (active.get()) {
   val cycleBegan = SystemClock.elapsedRealtime()
   val began = System.currentTimeMillis()
   try {
    if (candidatePool.isEmpty() || began - lastPoolRefresh > 5 * 60 * 1000L) {
     log("Обновляем список серверов Европы.")
     val candidates = loadServers()
     candidatePool = (candidates + fallback).distinct().take(60)
     log("Найдено кандидатов: ${candidatePool.size}.")
     lastPoolRefresh = began
    }
    cycle++
    // Always probe every currently selected server first, even during recovery.
    val currentResults = probeAll(selected).sortedBy { it.second }
    val additions = mutableListOf<Pair<Server, Long>>()
    while (true) {
     val item = discoveryResults.poll() ?: break
     if (item.first !in selected && additions.none { it.first == item.first }) additions.add(item)
    }
    val healthy = (currentResults + additions)
     .distinctBy { it.first }
     .sortedBy { it.second }
     .take(5)
    // Keep failed entries in the five-server monitoring set until a verified
    // replacement is found. This lets a recovered old server be detected too.
    val currentHealthy = currentResults.map { it.first }.toSet()
    val failedSelected = selected.filterNot { it in currentHealthy }
    val newServers = healthy.map { it.first }.filterNot { it in currentHealthy }
    val replacements = minOf(newServers.size, failedSelected.size)
    selected = (currentResults.map { it.first } + newServers +
     failedSelected.drop(replacements)).distinct().take(5)

    // Start recovery as soon as even one of the five selected servers fails.
    // The monitor loop keeps probing the selected servers while discovery runs.
    val needsRecovery = healthy.size < 5
    lastActiveCount = healthy.size
    mainHandler.post { updateOverlayDot() }

    log("Цикл №$cycle: активных серверов ${healthy.size}/5.")
    if (healthy.isEmpty()) {
     log("Рабочих серверов пока нет. Мониторинг и фоновый поиск продолжаются.")
    } else {
     healthy.forEach { log("${it.first.name}: ${it.second} мс") }
    }

    // Any loss from the target of five starts a single background replacement
    // search immediately; this worker never blocks the continuous monitor loop.
    if (needsRecovery &&
        !discoveryRunning.get() && SystemClock.elapsedRealtime() - lastSearchStart >= 5000L) {
     startBackgroundDiscovery(candidatePool, selected, healthy.size)
     lastSearchStart = SystemClock.elapsedRealtime()
    }
    updateNotification("Цикл $cycle • серверов ${healthy.size}/5")
   } catch (e: Exception) { log("Ошибка цикла: ${e.message ?: "сеть"}") }
   val remaining = interval * 1000L - (SystemClock.elapsedRealtime() - cycleBegan)
   if (remaining > 0) try { Thread.sleep(remaining) } catch (_: InterruptedException) { }
  }
 }
 private fun startBackgroundDiscovery(pool: List<Server>, current: List<Server>, activeCount: Int) {
  if (!discoveryRunning.compareAndSet(false, true)) return
  val excluded = current.toSet()
  discoveryWorker = thread(name = "InternetWakeDiscovery") {
   try {
    val alternatives = pool.filterNot { it in excluded }
    if (alternatives.isEmpty()) return@thread
    log("Осталось ${activeCount}/5 серверов. Ищем замену в фоне; мониторинг продолжается.")
    val found = probeAll(alternatives).sortedBy { it.second }
    val slots = (5 - activeCount).coerceAtLeast(0)
    val additions = found.take(slots)
    additions.forEach { discoveryResults.offer(it) }
    log("Фоновый поиск завершён: найдено новых серверов ${additions.size}.")
   } catch (e: Exception) {
    log("Ошибка фонового поиска: ${e.message ?: "сеть"}")
   } finally {
    discoveryRunning.set(false)
   }
  }
 }
 private fun probeAll(servers: List<Server>): List<Pair<Server, Long>> {
  if (servers.isEmpty()) return emptyList()
  val executor = Executors.newFixedThreadPool(minOf(servers.size, 20))
  return try {
   val tasks = servers.map { server -> executor.submit(Callable { probe(server)?.let { server to it } }) }
   val results = mutableListOf<Pair<Server, Long>>()
   for (task in tasks) {
    if (Thread.currentThread().isInterrupted) break
    try {
     task.get()?.let { results.add(it) }
    } catch (_: InterruptedException) {
     Thread.currentThread().interrupt()
     break
    } catch (_: Exception) { }
   }
   results
  } finally {
   executor.shutdownNow()
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
   val code = conn.responseCode
   // A server is available only when its ping endpoint returns a successful
   // HTTP response; transport-level HTTP errors must not count as healthy.
   if (code !in 200..399) return null
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
  active.set(false)
  worker?.interrupt(); worker = null
  discoveryResults.clear()
  mainHandler.post { removeOverlayDot() }
  discoveryWorker?.interrupt()
  discoveryWorker = null
  try { lock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
  lock = null
  getSharedPreferences(PREF, 0).edit().putBoolean("running", false).putLong("started_at", 0L).apply()
  log("Мониторинг остановлен.")
  stopForeground(STOP_FOREGROUND_REMOVE)
 }
 private fun updateOverlayDot() {
  val prefs = getSharedPreferences(PREF, 0)
  if (!active.get() || !prefs.getBoolean("overlay_enabled", false) || !Settings.canDrawOverlays(this)) {
   removeOverlayDot()
   return
  }
  if (overlayView != null) {
   overlayView?.setTextColor(dotColor())
   return
  }
  val dot = TextView(this).apply {
   text = "●"
   textSize = 24f
   gravity = Gravity.CENTER
   setTextColor(dotColor())
   background = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(Color.argb(210, 5, 22, 38))
    setStroke(dp(1), Color.argb(180, 120, 190, 225))
   }
   setOnClickListener {
    val launch = packageManager.getLaunchIntentForPackage(packageName)
    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (launch != null) startActivity(launch)
   }
  }
  val params = WindowManager.LayoutParams(dp(48), dp(48),
   WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
   PixelFormat.TRANSLUCENT).apply {
    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    y = dp(18)
   }
  try {
   (getSystemService(WINDOW_SERVICE) as WindowManager).addView(dot, params)
   overlayView = dot
  } catch (_: Exception) { overlayView = null }
 }
 private fun dotColor(): Int = when (lastActiveCount) {
  5 -> Color.rgb(70, 230, 155)
  in 1..4 -> Color.rgb(255, 195, 65)
  else -> Color.rgb(255, 75, 95)
 }
 private fun removeOverlayDot() {
  val dot = overlayView ?: return
  try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(dot) } catch (_: Exception) { }
  overlayView = null
 }
 private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
 private fun createChannel() {
  if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "INTERNET WAKE", NotificationManager.IMPORTANCE_LOW))
 }
 private fun notification(text: String): Notification {
  val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("INTERNET WAKE").setContentText(text).setOngoing(true).setContentIntent(pi).build()
 }
 private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(11, notification(text)) }
 override fun onDestroy() {
  if (active.get()) stopMonitor()
  super.onDestroy()
 }
}