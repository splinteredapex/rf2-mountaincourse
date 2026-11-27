package com.splinteredapex.logger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/** Foreground service that owns a recording run, so it keeps going with the screen off. */
class LoggerService : Service() {

    private var session: Session? = null
    private var gnss: PhoneGnss? = null
    private var sensors: MotionSensors? = null
    private var external: ExternalReceiver? = null
    private var ntrip: NtripClient? = null
    private var events: Session.TextFile? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startUtcMs = 0L

    private val flusher = object : Runnable {
        override fun run() {
            session?.flushAll()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRun()
            ACTION_STOP -> stopRun()
            ACTION_EVENT -> logEvent(intent.getStringExtra(EXTRA_KIND) ?: "MARK", intent.getStringExtra(EXTRA_LABEL) ?: "")
        }
        return START_NOT_STICKY
    }

    private fun startRun() {
        if (session != null) return
        startInForeground()
        val prefs = Prefs(this)
        Live.resetForSession()
        val s = Session.create(this)
        session = s
        Live.sessionName = s.name
        startUtcMs = System.currentTimeMillis()
        Live.startElapsedNs = SystemClock.elapsedRealtimeNanos()

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apexlogger:run").apply { acquire(6 * 60 * 60 * 1000L) }

        events = s.text("events.csv", "t_ns,utc_ms,gnss_utc_ms,kind,number,label")
        writeMeta(s, prefs, end = false)

        try {
            gnss = PhoneGnss(this, s).also { it.start() }
        } catch (e: Exception) {
            Live.lastError = "Phone GNSS: ${e.message}"
        }
        sensors = MotionSensors(this, s).also { it.start() }
        if (prefs.source != ExternalSource.NONE) {
            val ext = ExternalReceiver(this, s, prefs).also { it.start() }
            external = ext
            if (prefs.ntripEnabled) ntrip = NtripClient(prefs, s) { ext.write(it) }.also { it.start() }
        }
        Live.recording = true
        logEvent("START", "")
        handler.postDelayed(flusher, 2000)
    }

    private fun stopRun() {
        val s = session ?: run { stopSelf(); return }
        logEvent("STOP", "")
        Live.recording = false
        handler.removeCallbacks(flusher)
        runCatching { ntrip?.stop() }
        runCatching { external?.stop() }
        runCatching { sensors?.stop() }
        runCatching { gnss?.stop() }
        writeMeta(s, Prefs(this), end = true)
        s.closeAll()
        ntrip = null; external = null; sensors = null; gnss = null; events = null; session = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun logEvent(kind: String, label: String) {
        val e = events ?: return
        val number = when (kind) {
            "MARK" -> ++Live.markCount
            else -> 0
        }
        val t = SystemClock.elapsedRealtimeNanos()
        val g = Live.gnssUtcNowMs()?.toString() ?: ""
        e.line("$t,${System.currentTimeMillis()},$g,$kind,$number,${label.replace(',', ' ')}")
        e.flush()
    }

    private fun writeMeta(s: Session, prefs: Prefs, end: Boolean) {
        val j = JSONObject()
        j.put("format", "splinteredapex-course-log/1")
        j.put("app_version", runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "")
        j.put("run", s.name)
        j.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        j.put("android_sdk", Build.VERSION.SDK_INT)
        j.put("start_utc", Session.isoUtc(startUtcMs))
        if (end) {
            j.put("end_utc", Session.isoUtc(System.currentTimeMillis()))
            j.put("phone_fixes", Live.fixCount)
            j.put("raw_epochs", Live.rawEpochs)
            j.put("external_bytes", Live.extBytes)
            j.put("ntrip_bytes", Live.ntripBytes)
        }
        j.put("time_base", "t_ns columns are SystemClock.elapsedRealtimeNanos, shared by every file")
        j.put("external_source", prefs.source.name)
        if (prefs.source == ExternalSource.BLUETOOTH) j.put("bluetooth_device", prefs.btName)
        j.put("ntrip", if (prefs.ntripEnabled) "${prefs.ntripHost}:${prefs.ntripPort}/${prefs.ntripMount}" else "off")
        j.put("notes", prefs.notes)
        java.io.File(s.dir, "run.json").writeText(j.toString(2))
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Recording course data")
            .setContentText("Tap to open the logger")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        if (session != null) stopRun()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_EVENT = "event"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LABEL = "label"
        private const val CHANNEL = "rec"

        fun send(context: Context, action: String, kind: String? = null, label: String? = null) {
            val i = Intent(context, LoggerService::class.java).setAction(action)
            kind?.let { i.putExtra(EXTRA_KIND, it) }
            label?.let { i.putExtra(EXTRA_LABEL, it) }
            if (action == ACTION_START) context.startForegroundService(i) else context.startService(i)
        }
    }
}
