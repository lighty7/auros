package com.aura.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** One GPS fix. */
data class TrackPt(val t: Long, val lat: Double, val lon: Double, val alt: Double)

/** Watch data for the time window of an activity, read from Health Connect and merged onto the recorded route. */
data class Merged(
    val hrAvg: Int = 0,
    val hrMax: Int = 0,
    val steps: Int = 0,
    val kcal: Double = 0.0,
    val hcDistM: Double = 0.0,
    /** heart-rate samples (epoch ms, bpm), thinned to at most ~300 */
    val hr: List<Pair<Long, Int>> = emptyList(),
    val readAt: Long = 0,
) { val hasAny get() = hrAvg > 0 || steps > 0 || kcal > 0 || hcDistM > 0 }

/** A recorded walk, run or ride. */
data class Rec(
    val id: Long,
    val sport: String,
    val startMs: Long,
    val endMs: Long,
    val distM: Double,
    val movingS: Int,
    val elapsedS: Int,
    val gainM: Double,
    val pts: List<TrackPt>,
    val merged: Merged = Merged(),
) {
    val day get() = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val title get() = when (sport) { "walk" -> "Walk"; "cycle" -> "Ride"; else -> "Run" }
}

/** Activities are one JSON file each in the app's private storage. */
class ActivityStore(private val ctx: Context) {
    private val dir get() = File(ctx.filesDir, "activities").also { it.mkdirs() }

    fun save(r: Rec): Long {
        File(dir, "${r.id}.json").writeText(toJson(r).toString())
        return r.id
    }

    fun list(): List<Rec> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.startMs }

    fun get(id: Long): Rec? = runCatching { fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()
    fun delete(id: Long) { File(dir, "$id.json").delete(); setPosted(id, false) }

    private val prefs get() = ctx.getSharedPreferences("aura_posted", Context.MODE_PRIVATE)
    fun isPosted(id: Long) = prefs.getBoolean(id.toString(), false)
    fun setPosted(id: Long, v: Boolean) = prefs.edit().apply { if (v) putBoolean(id.toString(), true) else remove(id.toString()) }.apply()

    /** Kilometres recorded in the app per day, to merge with the watch's distance for distance goals. */
    fun kmPerDay(): Map<java.time.LocalDate, Double> = list().groupBy { it.day }.mapValues { e -> e.value.sumOf { it.distM } / 1000.0 }

    private fun toJson(r: Rec): JSONObject {
        val p = JSONArray(); r.pts.forEach { p.put(JSONArray().put(it.t).put(it.lat).put(it.lon).put(it.alt)) }
        val m = r.merged
        val hr = JSONArray(); m.hr.forEach { hr.put(JSONArray().put(it.first).put(it.second)) }
        return JSONObject().put("id", r.id).put("sport", r.sport).put("start", r.startMs).put("end", r.endMs).put("dist", r.distM)
            .put("moving", r.movingS).put("elapsed", r.elapsedS).put("gain", r.gainM).put("p", p)
            .put("m", JSONObject().put("hrAvg", m.hrAvg).put("hrMax", m.hrMax).put("steps", m.steps).put("kcal", m.kcal)
                .put("hcDist", m.hcDistM).put("readAt", m.readAt).put("hr", hr))
    }

    private fun fromJson(o: JSONObject): Rec {
        val p = o.getJSONArray("p")
        val pts = (0 until p.length()).map { val a = p.getJSONArray(it); TrackPt(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getDouble(3)) }
        val m = o.optJSONObject("m")
        val merged = if (m == null) Merged() else Merged(
            m.optInt("hrAvg"), m.optInt("hrMax"), m.optInt("steps"), m.optDouble("kcal"), m.optDouble("hcDist"),
            m.optJSONArray("hr")?.let { h -> (0 until h.length()).map { h.getJSONArray(it).let { a -> a.getLong(0) to a.getInt(1) } } } ?: emptyList(),
            m.optLong("readAt"),
        )
        return Rec(o.getLong("id"), o.getString("sport"), o.getLong("start"), o.getLong("end"), o.getDouble("dist"),
            o.getInt("moving"), o.getInt("elapsed"), o.getDouble("gain"), pts, merged)
    }
}
