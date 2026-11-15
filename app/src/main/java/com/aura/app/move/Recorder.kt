package com.aura.app.move

import android.location.Location
import com.aura.app.data.Rec
import com.aura.app.data.TrackPt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max

/** Live state and maths of the activity being recorded. All mutation happens on the main thread (location callbacks and a 1 s ticker). */
object Recorder {
    enum class Status { Idle, Recording, Paused }

    data class State(
        val status: Status = Status.Idle, val sport: String = "run",
        val elapsedS: Int = 0, val movingS: Int = 0, val distM: Double = 0.0, val speed: Double = 0.0,
        val gain: Double = 0.0, val acc: Float = -1f, val lat: Double = 0.0, val lon: Double = 0.0,
        val points: Int = 0, val version: Int = 0, val savedId: Long = -1, val message: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val pts = ArrayList<TrackPt>()
    private var last: Location? = null
    private var startMs = 0L; private var pausedMs = 0L; private var pauseAt = 0L
    private var movingS = 0; private var dist = 0.0; private var speedSm = 0.0
    private var altSm = Double.NaN; private var altRef = Double.NaN; private var gain = 0.0

    fun points(): List<TrackPt> = synchronized(pts) { ArrayList(pts) }

    /** per-sport sanity limit for a believable speed, metres per second */
    private fun maxSpeed(sport: String) = when (sport) { "walk" -> 4.5; "run" -> 9.0; else -> 22.0 }

    fun begin(sport: String) {
        synchronized(pts) { pts.clear() }
        last = null; startMs = System.currentTimeMillis(); pausedMs = 0; pauseAt = 0; movingS = 0; dist = 0.0; speedSm = 0.0
        altSm = Double.NaN; altRef = Double.NaN; gain = 0.0
        _state.value = State(status = Status.Recording, sport = sport, message = "Waiting for GPS…")
    }

    fun pause() { if (_state.value.status == Status.Recording) { pauseAt = System.currentTimeMillis(); publish(Status.Paused) } }
    fun resume() { if (_state.value.status == Status.Paused) { pausedMs += System.currentTimeMillis() - pauseAt; last = null; publish(Status.Recording) } }

    fun tick() {
        if (_state.value.status != Status.Recording) return
        if (speedSm >= 0.5) movingS++
        publish()
    }

    fun onLocation(loc: Location) {
        val s = _state.value
        _state.update { it.copy(acc = loc.accuracy, lat = loc.latitude, lon = loc.longitude) }
        if (s.status != Status.Recording) return
        if (loc.accuracy > 35f) { _state.update { it.copy(message = "GPS signal is weak") }; return }
        val prev = last
        if (prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt < 0.5) return
            val d = prev.distanceTo(loc).toDouble()
            val v = d / dt
            if (v > maxSpeed(s.sport)) return                       // GPS jump
            val sp = if (loc.hasSpeed()) loc.speed.toDouble() else v
            speedSm = if (speedSm == 0.0) sp else 0.7 * speedSm + 0.3 * sp
            if (d >= max(2.0, loc.accuracy * 0.35) || sp > 1.0) dist += d     // ignore jitter when standing still
            else speedSm *= 0.6
        }
        last = loc
        if (loc.hasAltitude()) {
            altSm = if (altSm.isNaN()) loc.altitude else 0.8 * altSm + 0.2 * loc.altitude
            if (altRef.isNaN()) altRef = altSm
            if (altSm - altRef >= 3.0) { gain += altSm - altRef; altRef = altSm } else if (altRef - altSm >= 3.0) altRef = altSm
        }
        synchronized(pts) { pts += TrackPt(loc.time, loc.latitude, loc.longitude, if (altSm.isNaN()) 0.0 else altSm) }
        publish(message = "")
    }

    /** The finished activity, or null if it was too short to be worth keeping. */
    fun finish(): Rec? {
        val end = System.currentTimeMillis()
        val p = points()
        if (p.size < 2 || dist < 20) return null
        return Rec(startMs, _state.value.sport, startMs, end, dist, movingS, ((end - startMs - pausedMs) / 1000).toInt(), gain, p)
    }

    fun setSaved(id: Long) { _state.value = State(savedId = id) }
    fun reset() { _state.value = State() }

    private fun publish(status: Status? = null, message: String? = null) {
        val now = System.currentTimeMillis()
        val st = status ?: _state.value.status
        val elapsed = ((if (st == Status.Paused) pauseAt else now) - startMs - pausedMs).coerceAtLeast(0) / 1000
        _state.update {
            it.copy(status = st, elapsedS = elapsed.toInt(), movingS = movingS, distM = dist, speed = speedSm,
                gain = gain, points = synchronized(pts) { pts.size }, version = it.version + 1, message = message ?: it.message)
        }
    }
}
