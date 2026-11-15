package com.aura.app.move

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.aura.app.MainActivity
import com.aura.app.BuildConfig
import com.aura.app.R
import com.aura.app.data.Merged
import com.aura.app.data.ActivityStore

/** Foreground service that records an activity from the phone's GPS and saves it when you finish. */
class RecordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager
    private lateinit var wl: PowerManager.WakeLock
    private var ticker: Runnable? = null
    private var tickCount = 0
    private var simulating = false

    private val listener = LocationListener { loc: Location -> Recorder.onLocation(loc) }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aura:record")
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("rec", "Activity recording", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            ACTION_START -> start(i.getStringExtra("sport") ?: "run", i.getBooleanExtra("sim", false) && BuildConfig.DEBUG)
            ACTION_PAUSE -> { Recorder.pause(); notifyNow() }
            ACTION_RESUME -> { Recorder.resume(); notifyNow() }
            ACTION_STOP -> stop(i.getBooleanExtra("save", true))
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(sport: String, sim: Boolean) {
        if (Recorder.state.value.status != Recorder.Status.Idle) return
        wl.acquire(6 * 3600_000L)
        Recorder.begin(sport)
        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        simulating = sim
        if (sim) simulate(sport)
        else try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
        catch (e: Exception) { Recorder.reset(); stop(false); return }
        ticker = object : Runnable { override fun run() { Recorder.tick(); if (++tickCount % 5 == 0) notifyNow(); main.postDelayed(this, 1000) } }.also { main.postDelayed(it, 1000) }
    }

    /** Debug-only test mode: a synthetic walk/run/ride, one fix per second, along the planned route if there is one. */
    private fun simulate(sport: String) {
        val mps = when (sport) { "walk" -> 3.0; "run" -> 7.0; else -> 12.0 }
        val route = Planned.route?.pts
        val cum = route?.let { p -> DoubleArray(p.size).also { for (i in 1 until p.size) it[i] = it[i - 1] + p[i - 1].distanceToAsDouble(p[i]) } }
        var n = 0
        val r = object : Runnable {
            override fun run() {
                if (!simulating || Recorder.state.value.status == Recorder.Status.Idle) return
                val d = n * mps; n++
                val lat: Double; val lon: Double
                if (route != null && cum != null && route.size > 1) {
                    val dd = d.coerceAtMost(cum.last())
                    var i = 1; while (i < cum.size - 1 && cum[i] < dd) i++
                    val seg = (cum[i] - cum[i - 1]).coerceAtLeast(0.01); val t = ((dd - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
                    lat = route[i - 1].latitude + (route[i].latitude - route[i - 1].latitude) * t
                    lon = route[i - 1].longitude + (route[i].longitude - route[i - 1].longitude) * t
                } else {
                    val a = d / 250.0   // a circle of 250 m radius
                    lat = 12.9716 + 250.0 / 111_320.0 * Math.sin(a); lon = 77.5946 + 250.0 / 108_500.0 * (1 - Math.cos(a))
                }
                Recorder.onLocation(Location("sim").apply {
                    latitude = lat; longitude = lon; accuracy = 5f; speed = mps.toFloat(); time = System.currentTimeMillis(); altitude = 900.0 + 8 * Math.sin(d / 60.0)
                })
                main.postDelayed(this, 1000)
            }
        }
        main.post(r)
    }

    /** Debug-only stand-in for what a watch would have written to Health Connect. */
    private fun fakeWatch(start: Long, end: Long, distM: Double): Merged {
        val span = (end - start).coerceAtLeast(1)
        val hr = (start..end step 5000).map { t -> val p = (t - start).toDouble() / span; t to (125 + 30 * Math.sin(p * 6.0) + 25 * p).toInt() }
        return Merged(hrAvg = hr.map { it.second }.average().toInt(), hrMax = hr.maxOf { it.second }, steps = (distM / 0.75).toInt(),
            kcal = distM * 0.06, hcDistM = distM * 1.01, hr = hr, readAt = System.currentTimeMillis())
    }

    private fun stop(save: Boolean) {
        val wasSim = simulating; simulating = false
        ticker?.let { main.removeCallbacks(it) }; ticker = null
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        if (save && Recorder.state.value.status != Recorder.Status.Idle) {
            val rec = Recorder.finish()?.let { if (wasSim) it.copy(merged = fakeWatch(it.startMs, it.endMs, it.distM)) else it }
            if (rec != null) Recorder.setSaved(ActivityStore(this).save(rec)) else Recorder.reset()
        } else Recorder.reset()
        if (wl.isHeld) wl.release()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun notification(): Notification {
        val s = Recorder.state.value
        val txt = String.format("%d:%02d · %.2f km", s.elapsedS / 60, s.elapsedS % 60, s.distM / 1000)
        fun act(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RecordService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = s.status == Recorder.Status.Paused
        return Notification.Builder(this, "rec").setSmallIcon(R.drawable.ic_stat).setContentTitle(if (paused) "Activity paused" else "Recording activity")
            .setContentText(txt).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_WORKOUT)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", act(if (paused) ACTION_RESUME else ACTION_PAUSE, 21)).build()).build()
    }

    private fun notifyNow() { getSystemService(NotificationManager::class.java).notify(2, notification()) }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { try { lm.removeUpdates(listener) } catch (_: Exception) {}; if (wl.isHeld) wl.release(); super.onDestroy() }

    companion object {
        const val ACTION_START = "rec.START"; const val ACTION_PAUSE = "rec.PAUSE"; const val ACTION_RESUME = "rec.RESUME"; const val ACTION_STOP = "rec.STOP"
        fun start(c: Context, sport: String, sim: Boolean = false) = c.startForegroundService(Intent(c, RecordService::class.java).setAction(ACTION_START).putExtra("sport", sport).putExtra("sim", sim))
        fun send(c: Context, action: String, save: Boolean = true) = c.startService(Intent(c, RecordService::class.java).setAction(action).putExtra("save", save))
    }
}
