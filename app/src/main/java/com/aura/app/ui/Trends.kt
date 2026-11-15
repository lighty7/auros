package com.aura.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.app.AuraViewModel
import com.aura.app.UiState
import com.aura.app.data.Aura
import com.aura.app.data.EarnRules
import com.aura.app.health.Day
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private enum class TM(val label: String, val target: Double, val higherBetter: Boolean) {
    SLEEP("Sleep", 7.0, true), STEPS("Steps", 8000.0, true), SCREEN("Screen", 3.0, false), TRAIN("Training", 30.0, true);

    fun of(d: Day?): Double? = when (this) { SLEEP -> d?.sleepH; STEPS -> d?.steps; SCREEN -> d?.screenH; TRAIN -> d?.exerciseMin }
    fun met(v: Double) = if (higherBetter) v >= target else v <= target
    fun fmt(v: Double): String = when (this) {
        SLEEP, SCREEN -> "%.1f h".format(Locale.US, v)
        STEPS -> "%,d".format(Locale.US, Math.round(v))
        TRAIN -> "${Math.round(v)} min"
    }
    fun delta(v: Double): String = (if (v >= 0) "+" else "-") + fmt(abs(v))
    val tone: Color @Composable get() = when (this) { SLEEP -> C.sleep; STEPS -> C.accent; SCREEN -> C.screen; TRAIN -> C.good }
}

private fun avg(days: Map<LocalDate, Day>, m: TM, from: LocalDate, to: LocalDate): Double? =
    generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.mapNotNull { m.of(days[it]) }.toList().let { if (it.isEmpty()) null else it.average() }

/** 0 to 1 once, over about 600 ms, restarting when [key] changes (data arriving, a new choice). */
@Composable
private fun grow(key: Any): Float {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
    return a.value
}

@Composable
fun TrendsScreen(vm: AuraViewModel, ui: UiState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadTrends() }
    val today = LocalDate.now()
    ScreenFrame("Trends", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("Trends", style = T.title)
            Gap(4)
            Text("The last four weeks. A day without data is left blank, never counted as zero.", style = T.label)
            if (ui.trendsBusy && ui.trends.isEmpty()) { Gap(10); Text("Reading your data...", style = T.label) }
            Gap(24)
            WeekBars(ui.trends, today)
            Gap(32)
            Consistency(ui, today)
            Gap(32)
            AuraLine(ui, today)
            Gap(32)
            Compare(ui.trends, today)
            Gap(24)
        }
    }
}

@Composable
private fun WeekBars(days: Map<LocalDate, Day>, today: LocalDate) {
    var pick by rememberSaveable { mutableStateOf(TM.SLEEP) }
    Caps("This week")
    Gap(10)
    Seg(TM.entries.map { it to it.label }, pick) { pick = it }
    Gap(16)
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val vals = dates.map { pick.of(days[it]) }
    val mean = avg(days, pick, today.minusDays(6), today)
    val tone = pick.tone
    Text(mean?.let { pick.fmt(it) } ?: "No data", style = T.display.copy(fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.5).sp, color = if (mean == null) C.muted else tone))
    Caps("Average, last 7 days")
    Gap(14)
    val g = grow(pick to days.size)
    val top = maxOf(pick.target * 1.3, vals.filterNotNull().maxOrNull() ?: 0.0)
    val muted = C.muted; val line = C.line
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val slot = size.width / 7f
        val bw = slot * 0.5f
        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        vals.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val h = (v / top).toFloat() * size.height * g
            drawRoundRect(
                if (pick.met(v)) tone else tone.copy(alpha = 0.32f),
                Offset(i * slot + (slot - bw) / 2, size.height - h), Size(bw, h), CornerRadius(5.dp.toPx()),
            )
        }
        val ty = size.height - (pick.target / top).toFloat() * size.height
        drawLine(muted, Offset(0f, ty), Offset(size.width, ty), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
    }
    Gap(6)
    Row(Modifier.fillMaxWidth()) {
        dates.forEach { d ->
            Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = T.caps, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
    Gap(8)
    Text("Dashed line: ${if (pick.higherBetter) "target" else "limit"} of ${pick.fmt(pick.target)}. Days that met it are solid.", style = T.label)
}

@Composable
private fun Consistency(ui: UiState, today: LocalDate) {
    Caps("Consistency")
    Gap(6)
    Text("Each square is a day. Brighter means more of the four daily earnings.", style = T.label)
    Gap(14)
    val start = today.minusDays(today.dayOfWeek.value - 1L).minusDays(21)
    val counts = ui.ledger.filter { it.kind in EarnRules.KINDS }.groupBy { it.epochDay }.mapValues { e -> e.value.map { it.kind }.toSet().size }
    val g = grow(ui.ledger.size)
    val accent = C.accent
    val gap = 6.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        (0 until 7).forEach { i -> Text(start.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = T.caps, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
    }
    Gap(6)
    (0 until 4).forEach { w ->
        if (w > 0) Gap(6)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            (0 until 7).forEach { i ->
                val d = start.plusDays(w * 7L + i)
                val shape = RoundedCornerShape(6.dp)
                val future = d.isAfter(today)
                val n = counts[d.toEpochDay()] ?: 0
                val fill = if (future) Color.Transparent else if (n == 0) C.raised else accent.copy(alpha = (0.2f + 0.8f * n / 4f) * g)
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(shape).background(fill)
                        .then(if (d == today) Modifier.border(2.dp, C.ink, shape) else if (future) Modifier.border(1.dp, C.line, shape) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun AuraLine(ui: UiState, today: LocalDate) {
    Caps("Aura over time")
    Gap(8)
    val t = today.toEpochDay()
    val pts = (27 downTo 0).map { back -> val d = t - back; ui.ledger.filter { it.epochDay <= d }.sumOf { it.points } }
    val lo = pts.min(); val hi = maxOf(pts.max(), lo + 10)
    Text("${pts.last()}", style = T.num)
    Text("${if (pts.last() - pts.first() >= 0) "+" else ""}${pts.last() - pts.first()} in 28 days", style = T.label)
    Gap(12)
    val g = grow(ui.ledger.size)
    val accent = C.accent; val faint = C.line
    val thresholds = (2..60).map { Aura.levelStart(it) }.filter { it in lo..hi }
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val pad = 6.dp.toPx()
        fun y(v: Int) = pad + (1f - (v - lo).toFloat() / (hi - lo)) * (size.height - 2 * pad)
        thresholds.forEach { drawLine(faint, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx()) }
        val n = pts.size
        val shown = (n - 1) * g
        for (i in 0 until n - 1) {
            if (i >= shown) break
            val f = minOf(1f, shown - i)
            val x0 = i * size.width / (n - 1); val x1 = (i + 1) * size.width / (n - 1)
            val y0 = y(pts[i]); val y1 = y(pts[i + 1])
            drawLine(accent, Offset(x0, y0), Offset(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
    Gap(6)
    Text(if (thresholds.isEmpty()) "No level threshold falls inside this range." else "Faint lines mark where a level starts.", style = T.label)
}

@Composable
private fun Compare(days: Map<LocalDate, Day>, today: LocalDate) {
    Caps("This week and last week")
    Gap(4)
    TM.entries.forEachIndexed { i, m ->
        if (i > 0) Rule()
        val a = avg(days, m, today.minusDays(6), today)
        val b = avg(days, m, today.minusDays(13), today.minusDays(7))
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label, style = T.head.copy(color = m.tone))
                Gap(2)
                Text("This week ${a?.let { m.fmt(it) } ?: "no data"}  /  last week ${b?.let { m.fmt(it) } ?: "no data"}", style = T.label)
            }
            GapW(10)
            if (a == null || b == null) Text("No change", style = T.label)
            else {
                val d = a - b
                val flat = abs(d) < 1e-9
                val good = if (m.higherBetter) d > 0 else d < 0
                Text(if (flat) "No change" else m.delta(d), style = T.head.copy(color = if (flat) C.muted else if (good) C.good else C.bad))
            }
        }
    }
}
