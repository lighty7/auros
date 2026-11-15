package com.aura.app.move

import com.aura.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A planned route: a line on the map with its length. */
class Route(val sport: String, val loop: Boolean, val label: String, val pts: List<GeoPoint>, val distM: Double, val durS: Double, val ascentM: Double)

/** The route the user chose in Plan, shown on the map while recording. */
object Planned { @Volatile var route: Route? = null }

/** OpenRouteService client: walking/cycling routes, round trips and place search. Blocking; call from a background thread. */
object Router {
    class Place(val label: String, val at: GeoPoint)

    val configured get() = BuildConfig.ORS_KEY.isNotBlank()

    private fun profile(sport: String) = if (sport == "cycle") "cycling-regular" else "foot-walking"

    private fun http(url: String, body: String?): String {
        if (!configured) throw RuntimeException("Route planning needs a free OpenRouteService key (ors.key in local.properties)")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Authorization", BuildConfig.ORS_KEY)
        c.setRequestProperty("Accept", "application/json, application/geo+json")
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try { JSONObject(txt).let { it.optJSONObject("error")?.optString("message") ?: it.optString("error") } } catch (_: Exception) { "" }
            throw RuntimeException(when { code == 429 -> "Route service limit reached, try again later"; code == 403 || code == 401 -> "Route service key was rejected"; msg.isNotBlank() -> msg.take(140); else -> "Route service error $code" })
        }
        return txt
    }

    private fun coord(p: GeoPoint) = JSONArray().put(p.longitude).put(p.latitude)

    /** A loop of about [lengthM] starting and ending at [start]; change [seed] for a different loop. */
    fun loop(sport: String, start: GeoPoint, lengthM: Int, seed: Int): Route {
        // the service's round-trip length is only a rough target, so retry with a corrected length and keep the closest result
        var ask = lengthM; var best: Route? = null
        for (n in 0 until 3) {
            val r = loopOnce(sport, start, ask, seed)
            if (best == null || Math.abs(r.distM - lengthM) < Math.abs(best.distM - lengthM)) best = r
            if (Math.abs(r.distM - lengthM) < lengthM * 0.12) break
            ask = (ask * lengthM / r.distM).toInt().coerceIn(lengthM / 3, lengthM * 2)
        }
        return best!!
    }

    private fun loopOnce(sport: String, start: GeoPoint, lengthM: Int, seed: Int): Route {
        val body = JSONObject().put("coordinates", JSONArray().put(coord(start)))
            .put("options", JSONObject().put("round_trip", JSONObject().put("length", lengthM).put("points", 3).put("seed", seed)))
            .put("elevation", true).toString()
        return parse(http("https://api.openrouteservice.org/v2/directions/${profile(sport)}/geojson", body), sport, true, "Loop")
    }

    fun to(sport: String, from: GeoPoint, dest: GeoPoint, label: String): Route {
        val body = JSONObject().put("coordinates", JSONArray().put(coord(from)).put(coord(dest))).put("elevation", true).toString()
        return parse(http("https://api.openrouteservice.org/v2/directions/${profile(sport)}/geojson", body), sport, false, label)
    }

    private fun parse(txt: String, sport: String, loop: Boolean, label: String): Route {
        val f = JSONObject(txt).getJSONArray("features").getJSONObject(0)
        val cs = f.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = (0 until cs.length()).map { val c = cs.getJSONArray(it); GeoPoint(c.getDouble(1), c.getDouble(0)) }
        val pr = f.getJSONObject("properties")
        val sum = pr.getJSONObject("summary")
        val dist = sum.getDouble("distance")
        // the service's walking pace is slow for runners; use a typical running pace
        val dur = if (sport == "run") dist / 2.8 else sum.getDouble("duration")
        return Route(sport, loop, label, pts, dist, dur, pr.optDouble("ascent", 0.0))
    }

    /** The nearest address to [at], for naming a dropped pin; null if there isn't one. */
    fun reverse(at: GeoPoint): String? {
        val fs = JSONObject(http("https://api.openrouteservice.org/geocode/reverse?point.lon=${at.longitude}&point.lat=${at.latitude}&size=1", null)).getJSONArray("features")
        if (fs.length() == 0) return null
        val p = fs.getJSONObject(0).getJSONObject("properties")
        return p.optString("name").ifBlank { p.optString("label") }.ifBlank { null }
    }

    fun search(text: String, near: GeoPoint?): List<Place> {
        val q = URLEncoder.encode(text, "UTF-8")
        val focus = if (near != null) "&focus.point.lon=${near.longitude}&focus.point.lat=${near.latitude}" else ""
        val fs = JSONObject(http("https://api.openrouteservice.org/geocode/search?text=$q&size=6$focus", null)).getJSONArray("features")
        return (0 until fs.length()).map {
            val o = fs.getJSONObject(it); val c = o.getJSONObject("geometry").getJSONArray("coordinates")
            Place(o.getJSONObject("properties").optString("label"), GeoPoint(c.getDouble(1), c.getDouble(0)))
        }
    }
}
