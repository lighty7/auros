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

    fun earnState(): EarnState {
        val a = JSONArray(p.getString("earnLedger", "[]"))
        val l = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Earned(o.getLong("d"), o.getString("k"), o.getInt("p"), o.getString("t"), o.optString("x"))
        }
        val g = JSONArray(p.getString("earnGoals", "[]"))
        return EarnState(
            start = p.getLong("earnStartEpochDay", 0), lastEval = p.getLong("earnLastEval", 0), quiet = p.getInt("earnQuiet", 0),
            base = p.getInt("earnBase", 0), goalsSeen = (0 until g.length()).map { g.getString(it) }.toSet(), ledger = l,
        )
    }

    fun saveEarn(s: EarnState) {
        val a = JSONArray()
        s.ledger.forEach { a.put(JSONObject().put("d", it.epochDay).put("k", it.kind).put("p", it.points).put("t", it.title).put("x", it.detail)) }
        p.edit().putString("earnLedger", a.toString()).putString("earnGoals", JSONArray(s.goalsSeen.toList()).toString())
            .putLong("earnStartEpochDay", s.start).putLong("earnLastEval", s.lastEval).putInt("earnQuiet", s.quiet).putInt("earnBase", s.base).apply()
    }
}
