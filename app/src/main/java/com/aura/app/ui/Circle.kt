package com.aura.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.app.AuraViewModel
import com.aura.app.UiState
import com.aura.app.data.Aura
import com.aura.app.data.EarnRules
import com.aura.app.data.Metric
import com.aura.app.data.Person
import com.aura.app.data.Post
import com.aura.app.data.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private enum class Pane(val label: String) { FEED("Feed"), BOARD("Board"), FIND("Find"), MINE("Mine") }

internal fun UiState.sharer() = Sharer(profile.name, profile.occupation, aura)

@Composable
fun Circle(vm: AuraViewModel, ui: UiState) {
    var pane by rememberSaveable { mutableStateOf(Pane.FEED) }
    Text("Circle", style = T.title)
    Gap(6)
    Text("What you and the people you follow have earned.", style = T.label)
    Gap(20)
    if (!ui.signedIn) {
        SignIn(vm, ui)
        Gap(28)
        Mine(vm, ui)
        return
    }
    Seg(Pane.entries.map { it to it.label }, pane) { pane = it; if (it != Pane.FIND && it != Pane.MINE) vm.loadSocial() }
    Gap(18)
    if (ui.cloudError.isNotBlank()) { Text(ui.cloudError, style = T.label.copy(color = C.bad)); Gap(10) }
    AnimatedContent(
        targetState = pane, label = "pane",
        transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
    ) { p ->
        Column(Modifier.fillMaxWidth()) {
            when (p) {
                Pane.FEED -> Feed(vm, ui)
                Pane.BOARD -> Board(vm, ui)
                Pane.FIND -> Find(vm, ui)
                Pane.MINE -> Mine(vm, ui)
            }
        }
    }
}

@Composable
private fun SignIn(vm: AuraViewModel, ui: UiState) {
    val ctx = LocalContext.current
    Panel {
        Text("Join the circle", style = T.head)
        Gap(4)
        Text("Sign in to follow friends, post what you complete and climb the board. Only goal names, points and the activity totals you choose to post are shared. Your health data stays on this phone.", style = T.label)
        Gap(14)
        Btn(if (ui.cloudBusy) "Signing in..." else "Sign in with Google", { (ctx as? android.app.Activity)?.let(vm::signIn) }, enabled = !ui.cloudBusy, small = true)
        if (ui.cloudError.isNotBlank()) { Gap(8); Text(ui.cloudError, style = T.label.copy(color = C.bad)) }
    }
}

internal fun ago(ts: Long): String {
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        m < 60 * 24 -> "${m / 60}h ago"
        else -> "${m / (60 * 24)}d ago"
    }
}

internal fun describe(metric: String, days: Int, target: Double): String {
    val m = runCatching { Metric.valueOf(metric) }.getOrNull() ?: return "$days days"
    return "$days days of ${m.label.lowercase()} ${if (m.lowerIsBetter) "under" else "over"} ${m.fmt(target)} ${m.unit}"
}

// ---------- feed ----------

@Composable
private fun Feed(vm: AuraViewModel, ui: UiState) {
    if (ui.feed.isEmpty()) Text("Nothing here yet. Follow someone under Find, or complete a goal of your own.", style = T.body)
    ui.feed.forEachIndexed { i, p -> Reveal(i) { FeedCard(p, ui, vm); Gap(10) } }
}

@Composable
private fun FeedCard(p: Post, ui: UiState, vm: AuraViewModel) {
    val tone: Color; val label: String; val big: String; val unit: String; val line: String
    when (p.kind) {
        "activity" -> {
            tone = C.good; label = "Activity"; big = km(p.distM); unit = "km"
            line = "${clock(p.elapsedS)} · ${pace(p.sport, p.distM, p.elapsedS)}" + if (p.hrAvg > 0) " · ${p.hrAvg} bpm average" else ""
        }
        "streak" -> { tone = C.butter; label = "Streak"; big = "+${p.points}"; unit = "aura"; line = p.detail }
        "full" -> { tone = C.butter; label = "Full day"; big = "+${p.points}"; unit = "aura"; line = p.detail }
        "level" -> { tone = C.ink; label = "Level"; big = "${p.points}"; unit = "aura"; line = p.detail }
        else -> { tone = C.accent; label = "Goal"; big = "+${p.points}"; unit = "aura"; line = describe(p.metric, p.days, p.target) }
    }
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(C.surface).border(1.dp, C.line, shape)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(tone))
        Column(Modifier.weight(1f).padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Caps("${if (p.uid == ui.myUid) "You" else p.name} · $label · ${ago(p.ts)}")
                    Gap(6)
                    Text(p.title, style = T.head)
                    if (line.isNotBlank()) { Gap(2); Text(line, style = T.label) }
                }
                GapW(12)
                Column(horizontalAlignment = Alignment.End) { Text(big, style = T.num.copy(color = tone)); Caps(unit) }
            }
            if (p.props >= 0) { Gap(12); PropsBtn(p.props, p.propped) { vm.toggleProps(p) } }
        }
    }
}

@Composable
private fun PropsBtn(count: Int, on: Boolean, onClick: () -> Unit) {
    val s = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.graphicsLayer { scaleX = s.value; scaleY = s.value }
            .clip(shape).background(if (on) C.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (on) C.accent else C.line, shape)
            .clickable(role = Role.Button) {
                onClick()
                scope.launch { s.animateTo(1.28f, tween(110)); s.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Props", style = T.label.copy(color = if (on) C.accent else C.ink, fontWeight = FontWeight.SemiBold))
        GapW(8)
        AnimatedContent(
            targetState = count, label = "props",
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(tween(180)) { if (up) it else -it } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(180)) { if (up) -it else it } + fadeOut(tween(120)))
            },
        ) { n -> Text("$n", style = T.label.copy(color = if (on) C.accent else C.muted, fontWeight = FontWeight.SemiBold)) }
    }
}

// ---------- board ----------

@Composable
private fun PersonRow(rank: Int?, p: Person, ui: UiState, vm: AuraViewModel) {
    val me = p.uid == ui.myUid
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) Text("$rank", style = T.num.copy(color = if (me) C.accent else C.muted), modifier = Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(if (me) "${p.name} (you)" else p.name, style = T.head.copy(color = if (me) C.accent else C.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Gap(2)
            Caps("${p.occupation} · ${p.aura} aura")
        }
        if (!me) {
            val on = p.uid in ui.following
            GapW(10)
            Btn(if (on) "Following" else "Follow", { vm.toggleFollow(p.uid) }, kind = if (on) Kind.Outline else Kind.Primary, small = true)
        }
    }
}

@Composable
private fun Board(vm: AuraViewModel, ui: UiState) {
    var following by rememberSaveable { mutableStateOf(false) }
    Seg(listOf(false to "Everyone", true to "Following"), following) { following = it }
    Gap(20)
    val list = if (following) ui.boardFollowing else ui.board
    if (list.isEmpty()) { Text(if (following) "Follow someone under Find and they show up here." else "No one is on the board yet.", style = T.body); return }
    Podium(list.take(3), ui.myUid, key = following)
    Gap(8)
    list.drop(3).forEachIndexed { i, p -> Reveal(i) { Rule(); PersonRow(i + 4, p, ui, vm) } }
    if (list.none { it.uid == ui.myUid }) {
        Gap(14)
        Panel(pad = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (!following && ui.myRank > 0) "${ui.myRank}" else "-", style = T.num.copy(color = C.accent), modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ui.profile.name} (you)", style = T.head.copy(color = C.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Gap(2)
                    Caps("${ui.profile.occupation} · ${ui.aura} aura")
                }
            }
        }
    }
}

/** First, second and third: bars that grow to height with a spring, the first one in the accent. */
@Composable
private fun Podium(top: List<Person>, me: String, key: Any) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.Bottom) {
        listOf(1, 0, 2).filter { it < top.size }.forEach { idx ->
            val p = top[idx]
            val grow = remember(key) { Animatable(0f) }
            LaunchedEffect(key) { delay(120L + idx * 110L); grow.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 130f)) }
            val full = when (idx) { 0 -> 140; 1 -> 104; else -> 80 }
            val first = idx == 0
            Column(Modifier.fillMaxWidth(0.28f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (p.uid == me) "You" else p.name.substringBefore(' '), style = T.head, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${countUp(p.aura)}", style = T.num.copy(color = if (first) C.accent else C.ink))
                Gap(8)
                Box(
                    Modifier.fillMaxWidth().height((full * grow.value).coerceAtLeast(0f).dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(if (first) C.accent else C.raised),
                    contentAlignment = Alignment.TopCenter,
                ) { Text("${idx + 1}", style = T.display.copy(fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = 0.sp, color = if (first) C.onAccent else C.muted)) }
            }
        }
    }
}

// ---------- find ----------

@Composable
private fun Find(vm: AuraViewModel, ui: UiState) {
    var q by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(q, { q = it }, "Search by name", Modifier.weight(1f), onSearch = { vm.search(q) })
        GapW(10)
        Btn("Search", { vm.search(q) }, small = true)
    }
    Gap(14)
    if (ui.results.isEmpty()) Text("Search for a friend by the name they signed up with.", style = T.label)
    ui.results.forEachIndexed { i, p -> if (i > 0) Rule(); PersonRow(null, p, ui, vm) }
    Gap(26)
    Caps("People like you")
    Gap(4)
    if (ui.similar.isEmpty()) Text("No one else has picked ${ui.profile.occupation.ifBlank { "your line of work" }} yet.", style = T.label)
    ui.similar.forEachIndexed { i, p -> Reveal(i) { if (i > 0) Rule(); PersonRow(null, p, ui, vm) } }
}

// ---------- mine ----------

private fun entryId(e: com.aura.app.data.Earned) = "ent_${e.epochDay}_${e.kind}"

@Composable
internal fun Mine(vm: AuraViewModel, ui: UiState) {
    var card by remember { mutableStateOf<ShareCard?>(null) }
    var sent by remember { mutableStateOf(setOf<String>()) }
    val lvl = Aura.level(ui.aura)
    val to = Aura.levelStart(lvl + 1)
    val frac = ((ui.aura - Aura.levelStart(lvl)).toFloat() / (to - Aura.levelStart(lvl))).coerceIn(0f, 1f)
    Panel {
        Caps(ui.profile.occupation)
        Text(ui.profile.name, style = T.head)
        Gap(8)
        Text("${countUp(ui.aura)}", style = T.display)
        Caps("Level $lvl · ${Aura.levelName(lvl)}", color = C.ink)
        Gap(12)
        Meter(frac)
        Gap(6)
        Text("${to - ui.aura} to level ${lvl + 1}", style = T.label)
        Gap(14)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn("Share", { card = ShareCard.Level }, kind = Kind.Outline, small = true)
            if (ui.signedIn) {
                val posted = ui.feed.any { it.uid == ui.myUid && it.id == "lvl_$lvl" } || "lvl_$lvl" in sent
                if (posted) Caps("Level $lvl posted", Modifier.align(Alignment.CenterVertically))
                else Btn("Post level to Circle", { sent = sent + "lvl_$lvl"; vm.postLevel(lvl, ui.aura) }, kind = Kind.Quiet, small = true)
            }
        }
    }

    val active = EarnRules.KINDS.filter { (ui.streaks[it] ?: 0) > 0 }
    if (active.isNotEmpty()) {
        Gap(24)
        Caps("Streaks")
        Gap(8)
        active.chunked(2).forEachIndexed { r, row ->
            if (r > 0) Gap(8)
            Reveal(r) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val n = ui.streaks[k] ?: 0
                        Panel(Modifier.weight(1f), onClick = { card = ShareCard.Streak(k, n) }, pad = 14) {
                            Caps(EarnRules.streakWord(k))
                            Text("$n ${if (n == 1) "day" else "days"}", style = T.num)
                            Gap(4)
                            Caps("Share", color = C.accent)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    val recent = ui.ledger.filter { it.points > 0 }.take(5)
    if (recent.isNotEmpty()) {
        Gap(24)
        Caps("Recent")
        Gap(2)
        recent.forEachIndexed { i, e ->
            if (i > 0) Rule()
            val canPost = ui.signedIn && (e.kind.startsWith("STREAK_") || e.kind == "FULL")
            val posted = ui.feed.any { it.uid == ui.myUid && it.id == entryId(e) } || entryId(e) in sent
            EarnedRow(e, onShare = { card = e.toCard() }, onPost = if (canPost) ({ sent = sent + entryId(e); vm.postEntry(e) }) else null, posted = posted)
        }
    }

    val done = ui.goals.filter { it.status == Status.COMPLETED }.sortedByDescending { it.goal.completedEpochDay }
    Gap(24)
    Caps("Completed goals")
    Gap(10)
    if (done.isEmpty()) Text("Complete a goal and it shows up here, ready to share.", style = T.body)
    done.forEach { p ->
        val g = p.goal
        val detail = describe(g.metric.name, g.days, g.target)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = T.head)
                    Gap(2)
                    Text(detail, style = T.label)
                }
                GapW(12)
                Column(horizontalAlignment = Alignment.End) { Text("+${g.awarded}", style = T.num.copy(color = C.accent)); Caps("aura") }
            }
            Gap(14)
            Btn("Share", { card = ShareCard.GoalDone(g.title, detail, g.awarded) }, kind = Kind.Outline, small = true)
        }
        Gap(10)
    }
    card?.let { ShareSheet(it, ui.sharer()) { card = null } }
}

// ---------- the level-up moment ----------

/** A full-screen beat: the ring draws once, the level name rises, then it clears itself. Tap to skip. */
@Composable
fun LevelUp(level: Int, onDone: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        text.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        delay(1200)
        onDone()
    }
    val accent = C.accent; val line = C.line; val butter = C.butter
    Box(
        Modifier.fillMaxSize().background(C.bg.copy(alpha = 0.96f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(300.dp)) {
            val sw = 8.dp.toPx()
            val d = size.minDimension - 60.dp.toPx()
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(line, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            drawArc(accent, -90f, 360f * draw.value, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
            val r0 = d / 2 + 14.dp.toPx(); val r1 = r0 + 8.dp.toPx(); val n = 72
            val shown = (n * draw.value).toInt()
            for (i in 0 until shown) {
                val a = Math.toRadians(i * 360.0 / n - 90)
                val k = i.toFloat() / n
                drawLine(butter.copy(alpha = 0.1f + 0.8f * k * k), Offset(center.x + cos(a).toFloat() * r0, center.y + sin(a).toFloat() * r0), Offset(center.x + cos(a).toFloat() * r1, center.y + sin(a).toFloat() * r1), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Column(Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 18.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Caps("Level $level")
            Text(Aura.levelName(level), style = T.title.copy(fontSize = 38.sp, lineHeight = 44.sp))
        }
    }
}
