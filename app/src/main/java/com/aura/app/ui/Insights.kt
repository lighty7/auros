package com.aura.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.app.AuraViewModel
import com.aura.app.UiState
import com.aura.app.health.AppUse
import com.aura.app.health.DayInsight
import com.aura.app.health.Night
import com.aura.app.health.Stage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

private const val DAY_MS = 24 * 3_600_000L

internal fun dur(ms: Long): String {
    val m = ms / 60_000
    return when { m < 1 -> "under 1m"; m < 60 -> "${m}m"; else -> "${m / 60}h ${m % 60}m" }
}

private fun dayTitle(back: Int) = when (back) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> LocalDate.now().minusDays(back.toLong()).format(DateTimeFormatter.ofPattern("EEEE d MMM"))
}

private fun hhmm(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** The day's heart rate from the watch laid over the apps that were on screen, with a per-app breakdown. */
@Composable
fun BodyAndScreen(vm: AuraViewModel, ui: UiState) {
    val d = ui.insight
    var focus by remember(ui.insightBack) { mutableStateOf<String?>(null) }
    var showAll by remember(ui.insightBack) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Caps("Body and screen")
            Gap(4)
            Text(dayTitle(ui.insightBack), style = T.title.copy(fontSize = 26.sp, lineHeight = 32.sp))
        }
        IconBtn(Glyph.Prev, "Previous day", { vm.loadInsight(ui.insightBack + 1) }, enabled = ui.insightBack < 6)
        GapW(8)
        IconBtn(Glyph.Next, "Next day", { vm.loadInsight(ui.insightBack - 1) }, enabled = ui.insightBack > 0)
    }
    Gap(6)
    Text("Your watch's heart rate, laid over the apps you had open.", style = T.label)
    Gap(16)

    if (!ui.healthOk && !ui.usageOk) {
        Text("Connect Health Connect and allow usage access to see this.", style = T.body)
        return
    }
    if (d == null) { Text("Reading your day…", style = T.label); return }

    val dim by animateFloatAsState(if (ui.insightBusy) 0.45f else 1f, tween(200), label = "dim")
    Column(Modifier.alpha(dim)) {
        d.night?.let { NightView(it, ui.usageOk); Gap(28); Caps("The day"); Gap(4) }
        Timeline(d, focus)
        Gap(18)
        StatGrid(listOf(
            Stat("Heart avg", if (d.hrAvg > 0) "${d.hrAvg}" else "–", if (d.hrAvg > 0) "bpm" else ""),
            Stat("Heart range", if (d.hrMax > 0) "${d.hrMin}–${d.hrMax}" else "–", if (d.hrMax > 0) "bpm" else ""),
            Stat("Screen time", if (ui.usageOk) dur(d.screenMs) else "–"),
            Stat("Steps", if (ui.healthOk) "%,d".format(d.steps) else "–"),
        ))

        val note = when {
            !ui.healthOk -> "Connect Health Connect to add your heart rate."
            d.hr.isEmpty() -> "No heart-rate readings in Health Connect for this day. Open your watch's app so it syncs, then come back."
            !ui.usageOk -> "Allow usage access to add the apps you used."
            else -> ""
        }
        if (note.isNotBlank()) { Gap(12); Text(note, style = T.label) }

        // the two ends of the day: which app ran with the highest and the lowest heart rate
        val rated = d.apps.filter { it.hrAvg > 0 && it.ms >= 3 * 60_000 }
        val top = rated.maxByOrNull { it.hrAvg }; val low = rated.minByOrNull { it.hrAvg }
        if (top != null && low != null && top.hrAvg != low.hrAvg) {
            Gap(14)
            Panel {
                Callout(C.heart, "Highest heart rate", "${top.label}, averaging ${top.hrAvg} bpm")
                Gap(12); Rule(); Gap(12)
                Callout(C.good, "Lowest heart rate", "${low.label}, averaging ${low.hrAvg} bpm")
            }
        }

        if (ui.usageOk) {
            Gap(24)
            Caps("Apps, by time on screen")
            Gap(6)
            if (d.apps.isEmpty()) Text("No app was open for a minute or more.", style = T.label)
            val longest = d.apps.firstOrNull()?.ms ?: 1L
            Column(Modifier.animateContentSize(tween(260))) {
                (if (showAll) d.apps else d.apps.take(5)).forEachIndexed { i, a ->
                    if (i > 0) Rule()
                    AppRow(a, d.hrAvg, a.ms.toFloat() / longest, focus == a.pkg) { focus = if (focus == a.pkg) null else a.pkg }
                }
            }
            if (d.apps.size > 5) {
                Gap(4)
                Btn(if (showAll) "Show fewer" else "Show all ${d.apps.size} apps", { showAll = !showAll }, kind = Kind.Quiet, small = true)
            }
            if (d.apps.isNotEmpty()) {
                Gap(8)
                Text(
                    "Tap an app to light it up on the chart. Heart rate per app is the average of your watch's readings while it was open; " +
                        "a watch that samples every few minutes leaves short sessions without one.",
                    style = T.label,
                )
            }
        }
    }
}

@Composable
private fun Callout(color: Color, label: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 10)
        GapW(12)
        Column { Caps(label); Gap(3); Text(text, style = T.body) }
    }
}

@Composable
private fun AppRow(a: AppUse, dayAvg: Int, share: Float, focused: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (focused) C.raised else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (a.icon != null) Image(a.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)))
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(C.raised), contentAlignment = Alignment.Center) {
            Text(a.label.take(1).uppercase(), style = T.head)
        }
        GapW(12)
        Column(Modifier.weight(1f)) {
            Text(a.label, style = T.head, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(2)
            Caps("${dur(a.ms)} · ${a.opens} ${if (a.opens == 1) "open" else "opens"}")
            Gap(8)
            Meter(share, color = if (focused) C.accent else C.screen, height = 3)
        }
        GapW(16)
        Column(horizontalAlignment = Alignment.End) {
            if (a.hrAvg > 0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${a.hrAvg}", style = T.num)
                    Text(" bpm", style = T.label, modifier = Modifier.padding(bottom = 3.dp))
                }
                val delta = a.hrAvg - dayAvg
                if (dayAvg > 0) Caps(
                    if (delta == 0) "your average" else "${abs(delta)} ${if (delta > 0) "above" else "below"} avg",
                    color = when { delta > 0 -> C.heart; delta < 0 -> C.good; else -> C.muted },
                )
            } else Caps("no reading")
        }
    }
}

/**
 * Midnight to midnight: the heart-rate line on top, a strip of phone use underneath.
 * Dragging across it reads out the time, the heart rate and the app that was open at that moment.
 */
@Composable
private fun Timeline(d: DayInsight, focus: String?) {
    var scrub by remember(d.day) { mutableStateOf<Float?>(null) }
    val draw = remember(d.day) { Animatable(0f) }
    LaunchedEffect(d.day) { draw.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }

    // today only runs up to now, so the readout never points at the future
    val maxF = ((System.currentTimeMillis() - d.startMs).toFloat() / DAY_MS).coerceIn(0.02f, 1f)
    val lo = if (d.hrMin > 0) (d.hrMin - 6).coerceAtLeast(30) else 50
    val hi = if (d.hrMax > 0) max(d.hrMax + 6, lo + 24) else 120
    val t = scrub?.let { d.startMs + (it * DAY_MS).toLong() }
    val hrNow = t?.let { d.hrNear(it, 10 * 60_000L) }
    val sess = t?.let { tt -> d.sessions.firstOrNull { tt >= it.start && tt <= it.end } }
    val lit = sess?.pkg ?: focus
    val heart = C.heart; val screen = C.screen; val line = C.line; val ink = C.ink; val bg = C.bg; val accent = C.accent; val sleepCol = C.sleep

    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t == null) Caps("Drag across the chart to read any moment")
        else {
            Text(hhmm(t), style = T.num)
            GapW(14); Dot(heart); GapW(6)
            Text(hrNow?.let { "$it bpm" } ?: "no reading", style = T.body, maxLines = 1)
            GapW(14); Dot(if (sess != null) screen else line); GapW(6)
            val asleep = d.sleep.any { t >= it.first && t <= it.second }
            Text(sess?.let { d.labels[it.pkg] ?: it.pkg } ?: if (asleep) "asleep" else "screen off", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    Gap(6)
    Box {
        Canvas(
            Modifier.fillMaxWidth().height(200.dp).pointerInput(d.day) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scrub = (down.position.x / size.width).coerceIn(0f, maxF)
                    // only a sideways drag is taken, so the page still scrolls up and down from here
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) horizontalDrag(drag.id) { change ->
                        scrub = (change.position.x / size.width).coerceIn(0f, maxF)
                        change.consume()
                    }
                }
            },
        ) {
            val w = size.width; val h = size.height
            val strip = 16.dp.toPx(); val chart = h - strip - 14.dp.toPx()
            fun x(ms: Long) = (ms - d.startMs).toFloat() / DAY_MS * w
            fun y(v: Int) = chart - (v - lo).toFloat() / (hi - lo) * chart

            d.sleep.forEach { (a, b) ->
                val x0 = x(a).coerceAtLeast(0f); val x1 = x(b).coerceAtMost(w)
                if (x1 > x0) drawRect(sleepCol.copy(alpha = 0.13f), Offset(x0, 0f), Size(x1 - x0, h - strip))
            }
            for (i in 0..4) drawLine(line, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1f)
            drawRect(line.copy(alpha = 0.45f), Offset(0f, h - strip), Size(w, strip))
            if (d.hrAvg > 0) drawLine(ink.copy(alpha = 0.28f), Offset(0f, y(d.hrAvg)), Offset(w, y(d.hrAvg)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 9f)))

            clipRect(right = w * draw.value) {
                d.sessions.forEach { s ->
                    val x0 = x(s.start); val x1 = max(x(s.end), x0 + 1.5f)
                    val c = when { lit == null -> screen; s.pkg == lit -> accent; else -> screen.copy(alpha = 0.3f) }
                    drawRect(c, Offset(x0, h - strip), Size(x1 - x0, strip))
                }
                val path = Path(); var prev = 0L; var open = false
                d.hr.forEach { (ms, v) ->
                    // a long gap means the watch wasn't reading; don't draw a line across it
                    if (!open || ms - prev > 20 * 60_000) path.moveTo(x(ms), y(v)) else path.lineTo(x(ms), y(v))
                    open = true; prev = ms
                }
                drawPath(path, heart, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (d.hr.size <= 40) d.hr.forEach { (ms, v) -> drawCircle(heart, 2.dp.toPx(), Offset(x(ms), y(v))) }
            }

            scrub?.let { f ->
                val sx = f * w
                drawLine(ink, Offset(sx, 0f), Offset(sx, h), 1.dp.toPx())
                hrNow?.let { drawCircle(bg, 7.dp.toPx(), Offset(sx, y(it))); drawCircle(heart, 4.5.dp.toPx(), Offset(sx, y(it))) }
            }
        }
        if (d.hrMax > 0) {
            Caps("$hi", Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 2.dp))
            Caps("$lo", Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 34.dp))
        }
    }
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("00", "06", "12", "18", "24").forEach { Caps(it) } }
    Gap(10)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(heart); GapW(6); Caps("Heart rate")
        GapW(18); Dot(screen); GapW(6); Caps("Phone in use")
        if (d.sleep.isNotEmpty()) { GapW(18); Dot(sleepCol.copy(alpha = 0.5f)); GapW(6); Caps("Asleep") }
    }
}

// ---------- last night ----------

private fun stageColor(k: Stage): Color = when (k) {
    Stage.AWAKE -> C.accent
    Stage.REM -> C.butter
    Stage.LIGHT -> C.sleep.copy(alpha = 0.55f)
    Stage.DEEP -> C.sleep
}

/**
 * The night that ended this day: a stage chart (awake at the top, deep at the bottom) with the heart rate drawn faintly across it
 * and a strip under it marking every time the phone was in use.
 */
@Composable
private fun NightView(n: Night, usageOk: Boolean) {
    val draw = remember(n.start) { Animatable(0f) }
    LaunchedEffect(n.start) { draw.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val span = (n.end - n.start).coerceAtLeast(1)
    val rows = Stage.entries
    val colors = rows.associateWith { stageColor(it) }
    val heart = C.heart; val screen = C.screen; val line = C.line; val sleepCol = C.sleep

    Caps("Last night")
    Gap(6)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(dur(n.asleepMs), style = T.display.copy(fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-2).sp))
        GapW(10)
        Text("asleep", style = T.label, modifier = Modifier.padding(bottom = 8.dp))
    }
    Gap(2)
    Text("${hhmm(n.start)} to ${hhmm(n.end)}, ${dur(n.inBedMs)} in bed", style = T.label)
    Gap(16)

    Row {
        Column(Modifier.height(150.dp), verticalArrangement = Arrangement.SpaceAround) {
            rows.forEach { Caps(it.label, Modifier.padding(end = 10.dp), color = if (n.stages.isEmpty()) C.muted.copy(alpha = 0.4f) else C.muted) }
        }
        Canvas(Modifier.weight(1f).height(150.dp)) {
            val w = size.width; val h = size.height; val rowH = h / rows.size
            fun x(t: Long) = (t - n.start).toFloat() / span * w
            rows.indices.forEach { i -> drawLine(line, Offset(0f, rowH * (i + 0.5f)), Offset(w, rowH * (i + 0.5f)), 1f) }
            clipRect(right = w * draw.value) {
                if (n.stages.isEmpty()) {
                    // this source doesn't record stages: one band for the whole sleep
                    drawRoundRect(sleepCol, Offset(0f, rowH * 2.15f), Size(w, rowH * 0.7f), CornerRadius(6f))
                } else n.stages.forEach { s ->
                    val r = rows.indexOf(s.stage)
                    drawRoundRect(colors.getValue(s.stage), Offset(x(s.start), rowH * r + rowH * 0.18f), Size(max(x(s.end) - x(s.start), 2f), rowH * 0.64f), CornerRadius(5f))
                }
                if (n.hr.size >= 2) {
                    val lo = n.hr.minOf { it.second } - 4; val hi = n.hr.maxOf { it.second } + 4
                    val p = Path(); var first = true
                    n.hr.forEach { (t, v) ->
                        val px = x(t); val py = h - (v - lo).toFloat() / (hi - lo).coerceAtLeast(1) * h
                        if (first) { p.moveTo(px, py); first = false } else p.lineTo(px, py)
                    }
                    drawPath(p, heart.copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
    if (usageOk) {
        Gap(4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caps("Phone", Modifier.padding(end = 10.dp))
            Canvas(Modifier.weight(1f).height(12.dp)) {
                drawRect(line.copy(alpha = 0.45f), Offset.Zero, size)
                n.phoneDuring.forEach { s ->
                    val x0 = (s.start - n.start).toFloat() / span * size.width; val x1 = (s.end - n.start).toFloat() / span * size.width
                    drawRect(screen, Offset(x0, 0f), Size(max(x1 - x0, 2.dp.toPx()), size.height))
                }
            }
        }
    }
    Gap(6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Caps(hhmm(n.start)); Caps(hhmm(n.end)) }
    Gap(10)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (n.stages.isNotEmpty()) rows.forEach { Dot(colors.getValue(it)); GapW(5); Caps(it.label); GapW(12) }
        Box(Modifier.width(12.dp).height(2.dp).background(heart.copy(alpha = 0.6f))); GapW(5); Caps("Heart")
    }

    Gap(18)
    if (n.stages.isNotEmpty()) {
        StatGrid(listOf(
            Stat("Deep", dur(n.stageMs[Stage.DEEP] ?: 0)), Stat("REM", dur(n.stageMs[Stage.REM] ?: 0)),
            Stat("Light", dur(n.stageMs[Stage.LIGHT] ?: 0)), Stat("Awake", dur(n.stageMs[Stage.AWAKE] ?: 0)),
        ))
        Gap(14)
    }

    // what the heart and the phone did around the night
    // a pickup is a run of app use; switching apps within a minute is still the same pickup
    val pickups = n.phoneDuring.sortedBy { it.start }.fold(0L to 0) { (lastEnd, count), x -> x.end to (if (count == 0 || x.start - lastEnd > 60_000) count + 1 else count) }.second
    val inBedMs = n.phoneDuring.sumOf { it.ms }
    val topApp = n.phoneDuring.groupBy { it.pkg }.maxByOrNull { e -> e.value.sumOf { it.ms } }?.key
    val topName = topApp?.let { appName(it) }
    if (n.hrLow > 0 || usageOk) Panel {
        if (n.hrLow > 0) NightLine(C.heart, "Lowest heart rate", "${n.hrLow} bpm, averaging ${n.hrAvg} while asleep")
        if (usageOk) {
            if (n.hrLow > 0) { Gap(12); Rule(); Gap(12) }
            NightLine(C.screen, "Phone in the hour before sleep", if (n.preBedMs < 60_000) "None" else dur(n.preBedMs))
            Gap(12); Rule(); Gap(12)
            NightLine(
                if (pickups == 0) C.good else C.accent, "Phone during the night",
                if (pickups == 0) "Not picked up once"
                else "$pickups ${if (pickups == 1) "time" else "times"}, ${dur(inBedMs)}" + (topName?.let { ", mostly $it" } ?: ""),
            )
        }
    }
    Gap(8)
    Caps("Sleep from ${n.source}")
}

@Composable
private fun NightLine(color: Color, label: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color, 10)
        GapW(12)
        Column { Caps(label); Gap(3); Text(text, style = T.body) }
    }
}

@Composable
private fun appName(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching { ctx.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg.substringAfterLast('.'))
    }
}
