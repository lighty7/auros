package com.aura.app.data

import com.aura.app.health.Day
import java.time.LocalDate
import kotlin.math.min

/** Every number of the daily aura scheme lives here. */
object EarnRules {
    const val SLEEP_MIN_H = 7.0
    const val SLEEP_MAX_H = 9.0
    const val STEPS_1 = 8000.0
    const val STEPS_2 = 12000.0
    const val EXERCISE_MIN = 30.0
    const val SCREEN_MAX_H = 3.0
    const val PTS_DAILY = 10
    const val PTS_STEPS_2 = 15
    const val PTS_FULL = 10
    /** streak length to bonus */
    val MILESTONES = linkedMapOf(3 to 5, 7 to 15, 14 to 30, 30 to 60)
    /** completed quiet days before cooling starts to bite */
    const val QUIET_GRACE = 2
    const val COOL_PER_DAY = 5
    const val MAX_BACK_DAYS = 30
    const val LEDGER_CAP = 400

    val KINDS = listOf("REST", "STEPS", "MOVE", "FOCUS")
    fun title(kind: String) = when (kind) {
        "REST" -> "Rested"; "STEPS" -> "On your feet"; "MOVE" -> "Trained"; "FOCUS" -> "Screen in check"; else -> "Full day"
    }
    fun streakWord(kind: String) = when (kind) { "REST" -> "sleep"; "STEPS" -> "steps"; "MOVE" -> "training"; else -> "screen time" }
}

data class Earned(val epochDay: Long, val kind: String, val points: Int, val title: String, val detail: String)

data class TodayItem(
    val kind: String, val title: String, val target: String, val current: String,
    val progress: Float, val earned: Boolean, val points: Int,
)

data class EarnState(
    val start: Long = 0, val lastEval: Long = 0, val quiet: Int = 0,
    /** points of entries trimmed off the ledger, so the total never shrinks */
    val base: Int = 0,
    val goalsSeen: Set<String> = emptySet(),
    /** oldest first */
    val ledger: List<Earned> = emptyList(),
) {
    val total: Int get() = base + ledger.sumOf { it.points }
}

data class EarnResult(val state: EarnState, val gained: Int)

object Earn {
    fun hm(h: Double): String {
        val m = Math.round(h * 60).toInt()
        return if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"
    }
    private fun n(v: Double) = String.format(java.util.Locale.US, "%,d", Math.round(v))

    /**
     * Evaluate completed days (once each) and today (provisionally), upserting ledger entries keyed by day+kind
     * so running it again never awards twice. [access] is false when neither Health Connect nor usage access is
     * granted: then no day is judged and none counts as quiet.
     */
    fun run(s0: EarnState, today: LocalDate, data: Map<LocalDate, Day>, access: Boolean, goals: List<Goal>): EarnResult {
        val t = today.toEpochDay()
        var s = s0
        if (s.start <= 0) s = s.copy(start = t, lastEval = t - 1)
        val list = s.ledger.toMutableList()
        var gained = 0
        var lastEval = s.lastEval
        var quiet = s.quiet
        val seen = s.goalsSeen.toMutableSet()

        fun idx(day: Long, kind: String) = list.indexOfFirst { it.epochDay == day && it.kind == kind }
        fun put(e: Earned) {
            val i = idx(e.epochDay, e.kind)
            if (i < 0) { list.add(e); gained += e.points }
            else if (list[i].points < e.points) { gained += e.points - list[i].points; list[i] = e }
        }

        // goals: one entry per completed goal, recorded once (also migrates goals awarded before the ledger existed)
        goals.filter { it.awarded > 0 && it.id !in seen }.forEach { g ->
            seen.add(g.id)
            list.add(Earned(if (g.completedEpochDay > 0) g.completedEpochDay else t, "GOAL:${g.id}", g.awarded, g.title, "Goal completed"))
        }

        if (access) {
            val from = maxOf(s.start, lastEval + 1, t - EarnRules.MAX_BACK_DAYS)
            if (from > lastEval + 1) quiet = 0
            for (dd in from..t) {
                val final = dd < t
                val day = data[LocalDate.ofEpochDay(dd)]
                val sleep = day?.sleepH
                val steps = day?.steps
                val ex = day?.exerciseMin
                val scr = day?.screenH
                val ok = HashSet<String>()
                if (sleep != null && sleep >= EarnRules.SLEEP_MIN_H && sleep <= EarnRules.SLEEP_MAX_H) {
                    ok.add("REST"); put(Earned(dd, "REST", EarnRules.PTS_DAILY, "Rested", "${hm(sleep)} of sleep"))
                }
                if (steps != null && steps >= EarnRules.STEPS_1) {
                    ok.add("STEPS")
                    put(Earned(dd, "STEPS", if (steps >= EarnRules.STEPS_2) EarnRules.PTS_STEPS_2 else EarnRules.PTS_DAILY, "On your feet", "${n(steps)} steps"))
                }
                if (ex != null && ex >= EarnRules.EXERCISE_MIN) {
                    ok.add("MOVE"); put(Earned(dd, "MOVE", EarnRules.PTS_DAILY, "Trained", "${Math.round(ex)} min of exercise"))
                }
                if (final && scr != null && scr <= EarnRules.SCREEN_MAX_H) {
                    ok.add("FOCUS"); put(Earned(dd, "FOCUS", EarnRules.PTS_DAILY, "Screen in check", "${hm(scr)} on the phone"))
                }
                if (final && ok.size == EarnRules.KINDS.size) put(Earned(dd, "FULL", EarnRules.PTS_FULL, "Full day", "Sleep, steps, training and screen time"))
                for (k in ok) {
                    var len = 0
                    while (idx(dd - len, k) >= 0) len++
                    EarnRules.MILESTONES[len]?.let { pts ->
                        put(Earned(dd, "STREAK_${k}_$len", pts, "$len-day ${EarnRules.streakWord(k)} streak", "${EarnRules.title(k)} $len days in a row"))
                    }
                }
                if (final) {
                    lastEval = dd
                    if (ok.isNotEmpty()) quiet = 0
                    else {
                        quiet++
                        if (quiet > EarnRules.QUIET_GRACE) {
                            val total = s.base + list.sumOf { it.points }
                            val amt = min(EarnRules.COOL_PER_DAY, total - Aura.levelStart(Aura.level(total)))
                            if (amt > 0) put(Earned(dd, "COOL", -amt, "Cooled", "$quiet quiet days in a row"))
                        }
                    }
                }
            }
        }

        var base = s.base
        var out: List<Earned> = list
        if (list.size > EarnRules.LEDGER_CAP) {
            val sorted = list.sortedBy { it.epochDay } // stable
            val drop = sorted.size - EarnRules.LEDGER_CAP
            base += sorted.take(drop).sumOf { it.points }
            out = sorted.drop(drop)
        }
        return EarnResult(EarnState(s.start, lastEval, quiet, base, seen, out), gained)
    }

    /** Ledger newest first (stable: later entries of a day come first). */
    fun newestFirst(l: List<Earned>): List<Earned> = l.asReversed().sortedByDescending { it.epochDay }

    fun streaks(ledger: List<Earned>, today: LocalDate): Map<String, Int> {
        val t = today.toEpochDay()
        val has = ledger.map { it.epochDay to it.kind }.toHashSet()
        return EarnRules.KINDS.associateWith { k ->
            var d = if ((t to k) in has) t else t - 1
            var c = 0
            while ((d to k) in has) { c++; d-- }
            c
        }
    }

    fun todayItems(ledger: List<Earned>, today: LocalDate, day: Day?): List<TodayItem> {
        val t = today.toEpochDay()
        fun e(k: String) = ledger.firstOrNull { it.epochDay == t && it.kind == k }
        val steps = day?.steps
        val big = steps != null && steps >= EarnRules.STEPS_1
        val stepTarget = if (big) EarnRules.STEPS_2 else EarnRules.STEPS_1
        fun frac(v: Double?, target: Double) = if (v == null) 0f else (v / target).toFloat().coerceIn(0f, 1f)
        return listOf(
            TodayItem("REST", "Rested", "7h", day?.sleepH?.let { hm(it) } ?: "none", frac(day?.sleepH, EarnRules.SLEEP_MIN_H), e("REST") != null, e("REST")?.points ?: EarnRules.PTS_DAILY),
            TodayItem("STEPS", "On your feet", n(stepTarget), steps?.let { n(it) } ?: "0", frac(steps, stepTarget), e("STEPS") != null, e("STEPS")?.points ?: EarnRules.PTS_DAILY),
            TodayItem("MOVE", "Trained", "30 min", "${Math.round(day?.exerciseMin ?: 0.0)} min", frac(day?.exerciseMin, EarnRules.EXERCISE_MIN), e("MOVE") != null, EarnRules.PTS_DAILY),
            TodayItem("FOCUS", "Screen in check", "under 3h", day?.screenH?.let { hm(it) } ?: "none", frac(day?.screenH, EarnRules.SCREEN_MAX_H), e("FOCUS") != null, EarnRules.PTS_DAILY),
        )
    }
}
