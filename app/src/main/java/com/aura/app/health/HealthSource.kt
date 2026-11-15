package com.aura.app.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.aura.app.data.Merged
import com.aura.app.data.Metric
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** One day of measurements. A null value means "no data", which is not the same as zero. */
data class Day(val steps: Double?, val exerciseMin: Double?, val sleepH: Double?, val kcal: Double?, val screenH: Double?, val distKm: Double? = null) {
    fun value(m: Metric): Double? = when (m) {
        Metric.STEPS -> steps
        Metric.EXERCISE -> exerciseMin
        Metric.SLEEP -> sleepH
        Metric.CALORIES -> kcal
        Metric.SCREEN -> screenH
        Metric.DISTANCE -> distKm
    }
}

/**
 * Reads from Health Connect (any watch or app that writes to it: Samsung Health, Fitbit, Garmin, Pixel Watch...)
 * and from the phone's usage stats for screen time.
 */
class HealthSource(private val ctx: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    val sdkStatus: Int get() = HealthConnectClient.getSdkStatus(ctx)
    val available: Boolean get() = sdkStatus == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    suspend fun hasHealthAccess(): Boolean =
        available && runCatching { client.permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Daily values for [from]..[to] inclusive. Missing sources simply give null values. */
    suspend fun days(from: LocalDate, to: LocalDate, health: Boolean, usage: Boolean): Map<LocalDate, Day> {
        val hc = if (health) healthDays(from, to) else emptyMap()
        val out = LinkedHashMap<LocalDate, Day>()
        var d = from
        while (!d.isAfter(to)) {
            val h = hc[d]
            out[d] = Day(h?.steps, h?.exerciseMin, h?.sleepH, h?.kcal, if (usage) screenHours(d) else null, h?.distKm)
            d = d.plusDays(1)
        }
        return out
    }

    private suspend fun healthDays(from: LocalDate, to: LocalDate): Map<LocalDate, Day> = runCatching {
        val rows = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        val sleepByDay = sleepPerDay(from, to)
        rows.associate { r ->
            val res = r.result
            r.startTime.toLocalDate() to Day(
                steps = res[StepsRecord.COUNT_TOTAL]?.toDouble(),
                exerciseMin = res[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toDouble(),
                sleepH = sleepByDay[r.startTime.toLocalDate()],
                kcal = res[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                screenH = null,
                distKm = res[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.div(1000.0),
            )
        }
    }.getOrDefault(emptyMap())

    /** Time asleep of a session: its length minus awake stages, or the whole session when it has no stages. */
    private fun asleepMs(r: SleepSessionRecord): Long {
        val total = Duration.between(r.startTime, r.endTime).toMillis()
        if (r.stages.isEmpty()) return total
        val awake = r.stages.filter {
            it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE || it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED || it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        }.sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
        val asleep = r.stages.filter {
            it.stage != SleepSessionRecord.STAGE_TYPE_AWAKE && it.stage != SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED && it.stage != SleepSessionRecord.STAGE_TYPE_OUT_OF_BED &&
                it.stage != SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }.sumOf { Duration.between(it.startTime, it.endTime).toMillis() }
        return if (asleep > 0) asleep else (total - awake).coerceAtLeast(0)
    }

    /** Sleep per local day: sessions count whole on the day they end, so a night is never split at midnight. */
    private suspend fun sleepPerDay(from: LocalDate, to: LocalDate): Map<LocalDate, Double> = runCatching {
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant().minusSeconds(18 * 3600L), to.plusDays(1).atStartOfDay(zone).toInstant())
        val out = HashMap<LocalDate, Double>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 500, pageToken = token))
            page.records.forEach { r ->
                val d = r.endTime.atZone(zone).toLocalDate()
                if (!d.isBefore(from) && !d.isAfter(to)) out[d] = (out[d] ?: 0.0) + asleepMs(r) / 3_600_000.0
            }
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
        out
    }.getOrDefault(emptyMap())

    /**
     * What the watch measured between [startMs] and [endMs]: heart rate, steps, active calories and distance.
     * Returns null when Health Connect can't be read. Watches sync into Health Connect now and then, so a fresh activity may be empty at first.
     */
    suspend fun window(startMs: Long, endMs: Long): Merged? = runCatching {
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val agg = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, DistanceRecord.DISTANCE_TOTAL, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                timeRangeFilter = range,
            )
        )
        val samples = ArrayList<Pair<Long, Int>>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 1000, pageToken = token))
            page.records.forEach { r -> r.samples.forEach { samples += it.time.toEpochMilli() to it.beatsPerMinute.toInt() } }
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null && samples.size < 20_000)
        samples.sortBy { it.first }
        val thinned = if (samples.size <= 300) samples else samples.filterIndexed { i, _ -> i % (samples.size / 300 + 1) == 0 }
        Merged(
            hrAvg = (agg[HeartRateRecord.BPM_AVG] ?: 0L).toInt(), hrMax = (agg[HeartRateRecord.BPM_MAX] ?: 0L).toInt(),
            steps = (agg[StepsRecord.COUNT_TOTAL] ?: 0L).toInt(), kcal = agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            hcDistM = agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0, hr = thinned, readAt = System.currentTimeMillis(),
        )
    }.getOrNull()

    // ---------- body and screen: heart rate laid over app use ----------

    private val icons = HashMap<String, ImageBitmap?>()

    private suspend fun heart(startMs: Long, endMs: Long): List<Pair<Long, Int>> = runCatching {
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val samples = ArrayList<Pair<Long, Int>>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 1000, pageToken = token))
            page.records.forEach { r -> r.samples.forEach { samples += it.time.toEpochMilli() to it.beatsPerMinute.toInt() } }
            token = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null && samples.size < 40_000)
        samples.sortBy { it.first }
        samples
    }.getOrDefault(emptyList())

    /** Every stretch of an app being on screen between [start] and [end], from the system's usage events. The launcher and system UI are left out. */
    private fun sessions(start: Long, end: Long): List<Session> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val skip = HashSet<String>().apply {
            add("com.android.systemui")
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { add(it) }
        }
        val raw = ArrayList<Session>()
        val events = usm.queryEvents(start, end) ?: return emptyList()
        val e = UsageEvents.Event()
        var cur: String? = null; var curStart = 0L
        fun close(t: Long) { val p = cur; if (p != null && t - curStart >= 1000 && p !in skip) raw += Session(p, curStart, t); cur = null }
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (cur != e.packageName) { close(e.timeStamp); cur = e.packageName; curStart = e.timeStamp }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (cur == e.packageName) close(e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN -> close(e.timeStamp)
            }
        }
        close(end)
        // moving between screens of one app pauses and resumes it; join those back into one session
        val out = ArrayList<Session>()
        for (s in raw) {
            val last = out.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.start - last.end < 5000) out[out.size - 1] = Session(s.pkg, last.start, s.end) else out += s
        }
        return out
    }

    private fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    private fun icon(pm: PackageManager, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching {
            val d = pm.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96); d.draw(Canvas(b))
            b.asImageBitmap()
        }.getOrNull()
    }

    /**
     * One day of heart rate merged with app use. For each app session the heart rate is the average of the watch's readings
     * inside it, or the nearest reading within five minutes when the session fell between two readings.
     */
    suspend fun dayInsight(day: LocalDate, health: Boolean, usage: Boolean): DayInsight = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        val ok = end > start
        val all = if (health && ok) heart(start, end) else emptyList()
        val steps = if (health && ok) runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))))[StepsRecord.COUNT_TOTAL]?.toInt()
        }.getOrNull() ?: 0 else 0
        val sessions = if (usage && ok) runCatching { sessions(start, end) }.getOrDefault(emptyList()) else emptyList()

        fun lower(t: Long): Int { var lo = 0; var hi = all.size; while (lo < hi) { val m = (lo + hi) / 2; if (all[m].first < t) lo = m + 1 else hi = m }; return lo }
        class Acc { var ms = 0L; var opens = 0; var weighted = 0.0; var weight = 0L; var max = 0 }
        val acc = LinkedHashMap<String, Acc>()
        for (s in sessions) {
            val a = acc.getOrPut(s.pkg) { Acc() }
            a.ms += s.ms; a.opens++
            var i = lower(s.start); var sum = 0; var n = 0
            while (i < all.size && all[i].first <= s.end) { sum += all[i].second; if (all[i].second > a.max) a.max = all[i].second; n++; i++ }
            val est = if (n > 0) sum.toDouble() / n else nearest(all, (s.start + s.end) / 2, 5 * 60_000L)?.toDouble()
            if (est != null) { a.weighted += est * s.ms; a.weight += s.ms; if (est.toInt() > a.max) a.max = est.toInt() }
        }
        val pm = ctx.packageManager
        val labels = acc.keys.associateWith { label(pm, it) }
        val apps = acc.entries.filter { it.value.ms >= 60_000 }.sortedByDescending { it.value.ms }.take(30).map { (p, a) ->
            AppUse(p, labels.getValue(p), icon(pm, p), a.ms, a.opens, if (a.weight > 0) (a.weighted / a.weight).roundToInt() else 0, a.max)
        }
        val (night, sleep) = if (health && ok) sleepFor(start, end, usage) else (null to emptyList())
        val thin = all.size / 1500 + 1
        DayInsight(
            night = night, sleep = sleep,
            day = day, startMs = start, hr = if (thin == 1) all else all.filterIndexed { i, _ -> i % thin == 0 },
            sessions = sessions, apps = apps, labels = labels,
            hrAvg = if (all.isEmpty()) 0 else all.sumOf { it.second } / all.size, hrMin = all.minOfOrNull { it.second } ?: 0, hrMax = all.maxOfOrNull { it.second } ?: 0,
            screenMs = sessions.sumOf { it.ms }, opens = sessions.size, steps = steps,
        )
    }

    /**
     * Sleep around the day [dayStart, dayEnd): the longest session that ended inside it (last night), with its stages,
     * heart rate and phone use, plus every sleep span overlapping the day for the day chart.
     */
    private suspend fun sleepFor(dayStart: Long, dayEnd: Long, usage: Boolean): Pair<Night?, List<Pair<Long, Long>>> = runCatching {
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(dayStart - 18 * 3_600_000L), Instant.ofEpochMilli(dayEnd))
        val recs = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 100)).records
        val spans = recs.map { it.startTime.toEpochMilli() to it.endTime.toEpochMilli() }.filter { it.second > dayStart && it.first < dayEnd }
        val main = recs.filter { it.endTime.toEpochMilli() in (dayStart + 1)..dayEnd }.maxByOrNull { Duration.between(it.startTime, it.endTime) }
            ?: return@runCatching null to spans
        val s = main.startTime.toEpochMilli(); val e = main.endTime.toEpochMilli()

        val stages = main.stages.mapNotNull { st ->
            val k = when (st.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
                SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> Stage.LIGHT
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> Stage.AWAKE
                else -> null
            }
            k?.let { StageSpan(st.startTime.toEpochMilli(), st.endTime.toEpochMilli(), it) }
        }.sortedBy { it.start }
        val stageMs = Stage.entries.associateWith { k -> stages.filter { it.stage == k }.sumOf { it.ms } }
        val asleep = if (stages.isEmpty()) e - s else stages.filter { it.stage != Stage.AWAKE }.sumOf { it.ms }

        val hr = heart(s, e)
        val phone = if (usage) runCatching { sessions(s, e) }.getOrDefault(emptyList()) else emptyList()
        val pre = if (usage) runCatching { sessions(s - 3_600_000L, s).sumOf { it.ms } }.getOrDefault(0L) else 0L
        val pm = ctx.packageManager
        Night(
            start = s, end = e, stages = stages, asleepMs = asleep, stageMs = stageMs,
            hr = hr, hrLow = hr.minOfOrNull { it.second } ?: 0, hrAvg = if (hr.isEmpty()) 0 else hr.sumOf { it.second } / hr.size,
            phoneDuring = phone, preBedMs = pre, source = label(pm, main.metadata.dataOrigin.packageName),
        ) to spans
    }.getOrDefault(null to emptyList())

    /** Foreground time of every app that day, in hours (own app excluded). */
    private fun screenHours(d: LocalDate): Double? = runCatching {
        val zone = ZoneId.systemDefault()
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), System.currentTimeMillis())
        if (end <= start) return null
        val ev = sessions(start, end).sumOf { it.ms }
        if (ev > 0) return ev / 3_600_000.0
        if (d.isAfter(LocalDate.now().minusDays(6))) return 0.0
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ms = usm.queryAndAggregateUsageStats(start, end).filterKeys { it != ctx.packageName }.values.sumOf { it.totalTimeInForeground }
        ms / 3_600_000.0
    }.getOrNull()
}
