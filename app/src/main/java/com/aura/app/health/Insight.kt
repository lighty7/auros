package com.aura.app.health

import androidx.compose.ui.graphics.ImageBitmap
import java.time.LocalDate

/** One stretch of an app being on screen. */
data class Session(val pkg: String, val start: Long, val end: Long) { val ms get() = end - start }

/** One app's day: how long it was open and what the watch read while it was. hrAvg is 0 when the watch had no reading in that time. */
class AppUse(val pkg: String, val label: String, val icon: ImageBitmap?, val ms: Long, val opens: Int, val hrAvg: Int, val hrMax: Int)

/** Sleep stages, in the order they're drawn from the top of the chart down. */
enum class Stage(val label: String) { AWAKE("Awake"), REM("REM"), LIGHT("Light"), DEEP("Deep") }

class StageSpan(val start: Long, val end: Long, val stage: Stage) { val ms get() = end - start }

/**
 * The night that ended on a given day: when you slept, how (if the watch reports stages), your heart rate meanwhile,
 * and the phone use around it.
 */
class Night(
    val start: Long, val end: Long,
    /** empty when the source only records "asleep" without stages */
    val stages: List<StageSpan>,
    val asleepMs: Long,
    val stageMs: Map<Stage, Long>,
    val hr: List<Pair<Long, Int>>,
    val hrLow: Int, val hrAvg: Int,
    /** apps opened between falling asleep and waking */
    val phoneDuring: List<Session>,
    /** phone use in the hour before sleep */
    val preBedMs: Long,
    val source: String,
) { val inBedMs get() = end - start }

/** A day of heart rate (from Health Connect) laid over the apps that were on screen (from usage access). */
class DayInsight(
    val day: LocalDate,
    /** local midnight, epoch ms */
    val startMs: Long,
    /** heart-rate samples (epoch ms, bpm), oldest first, thinned for drawing */
    val hr: List<Pair<Long, Int>>,
    val sessions: List<Session>,
    /** apps open for a minute or more, longest first */
    val apps: List<AppUse>,
    val labels: Map<String, String>,
    val hrAvg: Int, val hrMin: Int, val hrMax: Int,
    val screenMs: Long, val opens: Int, val steps: Int,
    /** the main sleep that ended this day, if any */
    val night: Night? = null,
    /** every stretch of sleep that overlaps this day, for shading the day chart */
    val sleep: List<Pair<Long, Long>> = emptyList(),
) {
    fun hrNear(t: Long, tolMs: Long): Int? = nearest(hr, t, tolMs)
}

/** The sample closest to [t], or null if none is within [tolMs]. [hr] must be sorted by time. */
internal fun nearest(hr: List<Pair<Long, Int>>, t: Long, tolMs: Long): Int? {
    if (hr.isEmpty()) return null
    var lo = 0; var hi = hr.size - 1
    while (lo < hi) { val mid = (lo + hi) / 2; if (hr[mid].first < t) lo = mid + 1 else hi = mid }
    val best = listOfNotNull(hr.getOrNull(lo - 1), hr.getOrNull(lo)).minByOrNull { Math.abs(it.first - t) } ?: return null
    return if (Math.abs(best.first - t) > tolMs) null else best.second
}
