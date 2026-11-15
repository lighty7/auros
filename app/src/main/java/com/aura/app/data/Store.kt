package com.aura.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local storage for v1 (profile + goals). The shape is deliberately plain so a Firebase-backed
 * implementation can replace it later without touching the UI.
 */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

    fun profile() = Profile(p.getString("name", "") ?: "", p.getString("occupation", "") ?: "")
    fun saveProfile(x: Profile) = p.edit().putString("name", x.name).putString("occupation", x.occupation).apply()

    fun goals(): List<Goal> {
        val a = JSONArray(p.getString("goals", "[]"))
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Goal(
                id = o.getString("id"), title = o.getString("title"),
                metric = Metric.valueOf(o.getString("metric")), target = o.getDouble("target"),
                days = o.getInt("days"), startEpochDay = o.getLong("start"),
                awarded = o.optInt("awarded", 0), completedEpochDay = o.optLong("done", -1),
            )
        }
    }

    fun saveGoals(list: List<Goal>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("metric", it.metric.name)
                .put("target", it.target).put("days", it.days).put("start", it.startEpochDay)
                .put("awarded", it.awarded).put("done", it.completedEpochDay))
        }
        p.edit().putString("goals", a.toString()).apply()
    }
}
