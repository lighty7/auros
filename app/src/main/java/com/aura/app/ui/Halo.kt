package com.aura.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import com.aura.app.data.Aura
import com.aura.app.data.TodayItem
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val KINDS = listOf("REST", "STEPS", "MOVE", "FOCUS")
private const val IDLE = 360f / 26f

private class Spin {
    var phase by mutableFloatStateOf(0f)
    var vel = IDLE
    var dragging = false
}

private fun segStart(i: Int) = -85f + 90f * i

/**
 * The aura halo: the total inside a level gauge, ringed by four day arcs (sleep, steps, training, screen).
 * Tap an arc or swipe to focus one, drag around the ring to spin the light, long-press the centre for the ledger.
 */
@Composable
fun AuraHalo(points: Int, today: List<TodayItem>, streaks: Map<String, Int>, onLedger: () -> Unit) {
    val lvl = Aura.level(points)
    val from = Aura.levelStart(lvl); val to = Aura.levelStart(lvl + 1)
    val frac = ((points - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(frac) { sweep.animateTo(frac, tween(1300, easing = FastOutSlowInEasing)) }

    val segs = KINDS.map { k -> today.firstOrNull { it.kind == k } }
    val interactive = segs.any { it != null }
    val pages = listOf(-1) + segs.indices.filter { segs[it] != null }
    var focus by remember { mutableIntStateOf(-1) }
    if (focus >= 0 && segs.getOrNull(focus) == null) focus = -1
    val haptic = LocalHapticFeedback.current
    val setFocus = { f: Int -> if (f != focus) { focus = f; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
    val curFocus by rememberUpdatedState(focus)
    val curPages by rememberUpdatedState(pages)
    val curSet by rememberUpdatedState(setFocus)
    val curLedger by rememberUpdatedState(onLedger)

    // animated fill, focus and pulse per segment; all read inside the draw lambda
    val fills = remember { List(4) { Animatable(0f) } }
    val pulses = remember { List(4) { Animatable(0f) } }
    for (i in 0..3) {
        val it = segs[i]
        val target = when {
            it == null -> 0f
            it.kind == "FOCUS" -> if (it.progress >= 1f) 1f else if (it.earned) 1f else (1f - it.progress).coerceIn(0f, 1f)
            it.earned -> 1f
            else -> it.progress.coerceIn(0f, 1f)
        }
        LaunchedEffect(target) { fills[i].animateTo(target, tween(1100, easing = FastOutSlowInEasing)) }
        val earned = it?.earned == true
        var was by remember { mutableStateOf(earned) }
        LaunchedEffect(earned) {
            if (earned && !was) { pulses[i].snapTo(0f); pulses[i].animateTo(1f, tween(800)) }
            was = earned
        }
    }
    val focusA = (0..3).map { i -> animateFloatAsState(if (focus == i) 1f else 0f, tween(260), label = "f$i") }
    val anyA = animateFloatAsState(if (focus >= 0) 1f else 0f, tween(260), label = "any")

    val spin = remember { Spin() }
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val t = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((t - last) / 1e9f).coerceAtMost(0.05f); last = t
            if (!spin.dragging) {
                spin.vel += (IDLE - spin.vel) * (dt * 1.4f).coerceAtMost(1f)
                spin.phase = (spin.phase + spin.vel * dt) % 360f
            }
        }
    }

    val colors = listOf(C.sleep, C.accent, C.good, C.screen)
    val accent = C.accent; val line = C.line; val butter = C.butter; val bad = C.bad
    val scope = rememberCoroutineScope()
    val shown = countUp(points)
    val doneToday = today.filter { it.earned }.sumOf { it.points }

    val describe = if (focus < 0) "Total" else segs[focus]?.let { "${it.title}, ${it.current} of ${it.target}" } ?: "Total"
    val desc = "Aura $points, level $lvl ${Aura.levelName(lvl)}" + if (doneToday > 0) ", $doneToday earned today" else ""

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(272.dp)
                .clearAndSetSemantics {
                    contentDescription = desc
                    stateDescription = describe
                    liveRegion = LiveRegionMode.Polite
                }
                .pointerInput(interactive) {
                    fun geo(): Triple<Offset, Float, Float> {
                        val d = minOf(size.width, size.height) - 56.dp.toPx()
                        return Triple(Offset(size.width / 2f, size.height / 2f), d / 2 + 17.dp.toPx(), d)
                    }
                    detectTapGestures(
                        onLongPress = { p ->
                            val (c, rr, _) = geo()
                            if (!interactive || hypot(p.x - c.x, p.y - c.y) < rr - 18.dp.toPx()) curLedger()
                        },
                        onTap = { p ->
                            if (!interactive) return@detectTapGestures
                            val (c, rr, _) = geo()
                            val dist = hypot(p.x - c.x, p.y - c.y)
                            when {
                                dist < rr - 20.dp.toPx() -> curSet(-1)
                                dist < rr + 26.dp.toPx() -> {
                                    val a = Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
                                    val i = ((((a + 90f) % 360f) + 360f) % 360f / 90f).toInt().coerceIn(0, 3)
                                    if (segs.getOrNull(i) != null) curSet(if (curFocus == i) -1 else i)
                                }
                            }
                        },
                    )
                }
                .pointerInput(interactive) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val rr = (minOf(size.width, size.height) - 56.dp.toPx()) / 2 + 17.dp.toPx()
                        fun ang(p: Offset) = Math.toDegrees(atan2(p.y - c.y, p.x - c.x).toDouble()).toFloat()
                        val onRing = abs(hypot(down.position.x - c.x, down.position.y - c.y) - rr) < 24.dp.toPx()
                        if (onRing) {
                            val slop = awaitTouchSlopOrCancellation(down.id) { ch, _ -> ch.consume() } ?: return@awaitEachGesture
                            spin.dragging = true
                            var prev = ang(slop.position); var prevT = slop.uptimeMillis
                            drag(slop.id) { ch ->
                                val a = ang(ch.position)
                                var delta = a - prev
                                if (delta > 180f) delta -= 360f
                                if (delta < -180f) delta += 360f
                                val dt = ((ch.uptimeMillis - prevT) / 1000f).coerceAtLeast(0.001f)
                                spin.phase = ((spin.phase + delta) % 360f + 360f) % 360f
                                spin.vel = (0.5f * spin.vel + 0.5f * (delta / dt)).coerceIn(-1500f, 1500f)
                                prev = a; prevT = ch.uptimeMillis
                                ch.consume()
                            }
                            spin.dragging = false
                        } else {
                            var total = 0f
                            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { ch, over -> ch.consume(); total += over } ?: return@awaitEachGesture
                            horizontalDrag(slop.id) { ch -> total += ch.positionChange().x; ch.consume() }
                            if (abs(total) > 56.dp.toPx()) {
                                val ps = curPages
                                val at = ps.indexOf(curFocus).coerceAtLeast(0)
                                val next = (at + if (total < 0) 1 else -1 + ps.size) % ps.size
                                scope.launch { curSet(ps[next]) }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 9.dp.toPx()
                val d = minOf(size.width, size.height) - 56.dp.toPx()
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(line, 135f, 270f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                if (sweep.value > 0.004f) drawArc(accent, 135f, 270f * sweep.value, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                if (!interactive) return@Canvas

                val rr = d / 2 + 17.dp.toPx()
                val rtl = Offset(center.x - rr, center.y - rr); val rsz = Size(rr * 2, rr * 2)
                val baseW = 7.dp.toPx()
                val widths = FloatArray(4); val alphas = FloatArray(4)
                for (i in 0..3) {
                    val it = segs[i] ?: continue
                    val f = focusA[i].value
                    val dim = anyA.value * (1f - f)
                    val a = 1f - 0.6f * dim
                    alphas[i] = a
                    val p = pulses[i].value
                    val w = baseW + 3.dp.toPx() * f + 5.dp.toPx() * sin(PI.toFloat() * p)
                    widths[i] = w
                    val s = segStart(i)
                    val over = it.kind == "FOCUS" && it.progress >= 1f
                    val col = if (over) bad else colors[i]
                    drawArc(line.copy(alpha = 0.8f * a), s, 80f, false, rtl, rsz, style = Stroke(w, cap = StrokeCap.Round))
                    val sweepA = 80f * fills[i].value
                    if (sweepA > 0.5f) {
                        if (it.earned) drawArc(col.copy(alpha = 0.18f * a), s, sweepA, false, rtl, rsz, style = Stroke(w + 6.dp.toPx(), cap = StrokeCap.Round))
                        drawArc(col.copy(alpha = (if (it.earned || over) 1f else 0.85f) * a), s, sweepA, false, rtl, rsz, style = Stroke(w, cap = StrokeCap.Round))
                    }
                    if (p > 0f && p < 1f) drawArc(col.copy(alpha = 0.5f * (1f - p)), s, 80f, false, rtl, rsz, style = Stroke(w + 10.dp.toPx() * p, cap = StrokeCap.Round))
                }
                // the moving highlight: a comet that follows the spin along the segments
                val head = spin.phase
                val dir = if (spin.vel >= 0f) 1f else -1f
                val n = 26
                for (k in 0 until n) {
                    val an = head - dir * k * 2.6f
                    val fade = 1f - k / n.toFloat()
                    for (i in 0..3) {
                        if (segs[i] == null) continue
                        val rel = (((an - segStart(i)) % 360f) + 360f) % 360f
                        if (rel <= 80f) drawArc(butter.copy(alpha = 0.5f * fade * fade * alphas[i]), an - 1.3f, 2.6f, false, rtl, rsz, style = Stroke(widths[i], cap = StrokeCap.Butt))
                    }
                }
            }
            Crossfade(focus, animationSpec = tween(260), label = "centre") { f ->
                val it = if (f >= 0) segs[f] else null
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (it == null) {
                        Caps("Aura")
                        val size = when { shown < 1000 -> 76; shown < 10_000 -> 60; else -> 48 }
                        Text("$shown", style = T.display.copy(fontSize = size.sp, lineHeight = size.sp))
                        Caps("Level $lvl · ${Aura.levelName(lvl)}", color = C.ink)
                        if (interactive && doneToday > 0) { Gap(4); Caps("+$doneToday today", color = C.accent) }
                    } else {
                        val over = it.kind == "FOCUS" && it.progress >= 1f
                        Caps(it.title)
                        Gap(2)
                        val big = if (it.current.length > 7) 38 else 48
                        Text(it.current, style = T.display.copy(fontSize = big.sp, lineHeight = (big + 2).sp, color = if (over) C.bad else C.ink), textAlign = TextAlign.Center, maxLines = 1)
                        Text("of ${it.target}", style = T.label)
                        Gap(4)
                        val left = ((1f - it.progress).coerceIn(0f, 1f) * 100).roundToInt()
                        when {
                            over -> Caps("Over the limit", color = C.bad)
                            it.earned -> Caps("+${it.points} earned", color = C.accent)
                            it.kind == "FOCUS" -> Caps("$left% of the limit left")
                            else -> Caps("$left% left")
                        }
                        val s = streaks[it.kind] ?: 0
                        if (s >= 2) { Gap(2); Caps("$s day streak") }
                    }
                }
            }
        }
        if (interactive && pages.size > 1) {
            Gap(6)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                val at = pages.indexOf(focus)
                pages.forEachIndexed { i, _ ->
                    Box(Modifier.size(if (i == at) 7.dp else 5.dp).clip(CircleShape).background(if (i == at) C.ink else C.line))
                }
            }
            Gap(8)
        }
        Text("${to - points} to level ${lvl + 1}", style = T.label)
    }
}
