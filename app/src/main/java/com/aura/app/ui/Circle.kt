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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.aura.app.data.TOPICS
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
import com.aura.app.data.Earn
import java.time.LocalDate
import com.aura.app.data.EarnRules
import com.aura.app.data.Metric
import com.aura.app.data.Person
import com.aura.app.data.Post
import com.aura.app.data.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private enum class Pane(val label: String) { FEED("Feed"), TRENDING("Trending"), BOARD("Board"), MINE("Mine") }

internal fun UiState.sharer() = Sharer(profile.name, profile.occupation, aura)

@Composable
fun Circle(vm: AuraViewModel, ui: UiState, onThread: (String) -> Unit) {
    var pane by rememberSaveable { mutableStateOf(Pane.FEED) }
    Text("Circle", style = T.title)
    Gap(6)
    Text("Ask, share what you finished, and compare notes.", style = T.label)
    Gap(20)
    Seg(Pane.entries.map { it to it.label }, pane) { pane = it; if (ui.signedIn && it != Pane.MINE) vm.loadSocial() }
    Gap(18)
    if (ui.signedIn && ui.cloudError.isNotBlank()) { Text(ui.cloudError, style = T.label.copy(color = C.bad)); Gap(10) }
    AnimatedContent(
        targetState = pane, label = "pane",
        transitionSpec = { fadeIn(tween(240, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
    ) { p ->
        Column(Modifier.fillMaxWidth()) {
            when (p) {
                Pane.FEED, Pane.TRENDING, Pane.BOARD -> if (!ui.signedIn) SignIn(vm, ui) else when (p) {
                    Pane.FEED -> Feed(vm, ui, onThread, trending = false)
                    Pane.TRENDING -> Feed(vm, ui, onThread, trending = true)
                    else -> Board(vm, ui)
                }
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

// ---------- feed, trending and threads ----------

@Composable
private fun Feed(vm: AuraViewModel, ui: UiState, onThread: (String) -> Unit, trending: Boolean) {
    var topic by rememberSaveable { mutableStateOf("") }
    var following by rememberSaveable { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val posts = ui.feed
        .filter { topic.isEmpty() || it.topic == topic }
        .filter { !following || it.uid == ui.myUid || it.uid in ui.following }
        .let { list ->
            if (!trending) list.sortedByDescending { it.ts }
            else list.filter { now - it.ts < 7 * 86_400_000L }
                .sortedByDescending { it.score / Math.pow((now - it.ts) / 3_600_000.0 + 2.0, 1.5) }
        }
    Btn("New post", { composing = true }, small = true)
    Gap(14)
    Seg(listOf(false to "Everyone", true to "Following"), following) { following = it }
    Gap(12)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("All", topic.isEmpty()) { topic = "" }
        TOPICS.forEach { t -> Chip(t, topic == t) { topic = t } }
    }
    Gap(16)
    if (posts.isEmpty()) Text(
        if (trending) "Nothing has picked up this week yet." else "Nothing here yet. Ask something, or post what you have finished.",
        style = T.body,
    )
    posts.forEachIndexed { i, p -> Reveal(minOf(i, 5)) { PostCard(p, ui, vm, onOpen = { onThread(p.id) }); Gap(10) } }
    if (composing) NewPost(vm, ui.cloudError, topic.ifEmpty { "General" }) { composing = false }
}

@Composable
private fun VoteBtn(up: Boolean, on: Boolean, label: String, onClick: () -> Unit) {
    val tone = if (on) (if (up) C.accent else C.ink) else C.muted
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height; val sw = 1.8.dp.toPx()
            val d = if (up) 1f else -1f
            fun y(v: Float) = h / 2f - d * (v - 0.5f) * h
            fun l(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(tone, Offset(x0 * w, y(y0)), Offset(x1 * w, y(y1)), sw, StrokeCap.Round)
            l(0.5f, 0.95f, 0.5f, 0.1f); l(0.5f, 0.1f, 0.12f, 0.5f); l(0.5f, 0.1f, 0.88f, 0.5f)
        }
    }
}

@Composable
private fun Votes(p: Post, vm: AuraViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteBtn(true, p.myVote == 1, "Upvote") { vm.vote(p, 1) }
        Text("${p.score}", style = T.head.copy(color = if (p.myVote == 1) C.accent else C.ink), modifier = Modifier.widthIn(min = 28.dp), textAlign = TextAlign.Center)
        VoteBtn(false, p.myVote == -1, "Downvote") { vm.vote(p, -1) }
    }
}

@Composable
private fun PostCard(p: Post, ui: UiState, vm: AuraViewModel, onOpen: (() -> Unit)?) {
    val ask = p.type == "ask"
    Panel(onClick = onOpen, pad = 16) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caps(if (ask) "Ask" else "Done", color = if (ask) C.accent else C.good)
            GapW(8)
            Caps("${if (p.uid == ui.myUid) "You" else p.name} · ${p.topic} · ${ago(p.ts)}", Modifier.weight(1f))
        }
        Gap(6)
        Text(p.title, style = T.head)
        if (p.body.isNotBlank()) {
            Gap(4)
            if (onOpen != null) Text(p.body, style = T.label, maxLines = 3, overflow = TextOverflow.Ellipsis) else Text(p.body, style = T.body)
        }
        if (!ask && p.points > 0) { Gap(6); Text("+${p.points} aura", style = T.label.copy(color = C.accent)) }
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Votes(p, vm)
            if (p.uid != ui.myUid) {
                val block = Earn.giveBlock(ui.ledger, ui.aura, p.id, LocalDate.now().toEpochDay())
                GapW(6)
                Btn(block ?: "Give 5", { vm.give(p) }, kind = Kind.Quiet, small = true, enabled = block == null)
            }
            Spacer(Modifier.weight(1f))
            Caps("${p.comments} ${if (p.comments == 1) "comment" else "comments"}")
        }
    }
}

@Composable
private fun TextBox(value: String, onChange: (String) -> Unit, label: String, minLines: Int, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, onChange, modifier = modifier, label = { Text(label) }, minLines = minLines, shape = RoundedCornerShape(14.dp), textStyle = T.body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.ink, unfocusedBorderColor = C.line, focusedLabelColor = C.ink, unfocusedLabelColor = C.muted,
            cursorColor = C.accent, focusedTextColor = C.ink, unfocusedTextColor = C.ink,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewPost(vm: AuraViewModel, error: String, startTopic: String, onDismiss: () -> Unit) {
    var topic by remember { mutableStateOf(startTopic) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(error) { busy = false }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.surface, contentColor = C.ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Caps("Ask")
            Text("Ask for advice", style = T.title)
            Gap(16)
            Caps("Topic")
            Gap(10)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TOPICS.forEach { t -> Chip(t, topic == t) { topic = t } }
            }
            Gap(18)
            TextBox(title, { title = it.take(80) }, "Title", 1, Modifier.fillMaxWidth())
            Gap(4)
            Caps("${title.length} / 80")
            Gap(12)
            TextBox(body, { body = it.take(500) }, "Details", 4, Modifier.fillMaxWidth())
            Gap(4)
            Caps("${body.length} / 500")
            Gap(18)
            Btn(if (busy) "Posting..." else "Post", { busy = true; vm.postAsk(topic, title, body) { onDismiss() } }, enabled = title.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ThreadScreen(vm: AuraViewModel, ui: UiState, id: String, onBack: () -> Unit) {
    LaunchedEffect(id) { vm.openThread(id) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(ui.cloudError) { busy = false }
    val post = ui.feed.firstOrNull { it.id == id }
    ScreenFrame("Thread", onBack) {
        Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (post == null) { Text("This post is no longer there.", style = T.body); return@Column }
            PostCard(post, ui, vm, onOpen = null)
            if (post.uid == ui.myUid) { Gap(8); Btn("Delete post", { vm.deletePost(post.id, onBack) }, kind = Kind.Quiet, small = true, tint = C.bad, flush = true) }
            if (ui.cloudError.isNotBlank()) { Gap(10); Text(ui.cloudError, style = T.label.copy(color = C.bad)) }
            Gap(22)
            Caps("Comments")
            Gap(6)
            val list = if (ui.commentsFor == id) ui.comments else emptyList()
            if (list.isEmpty()) Text("No comments yet.", style = T.label)
            list.forEachIndexed { i, c ->
                if (i > 0) Rule()
                Column(Modifier.padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Caps("${if (c.uid == ui.myUid) "You" else c.name} · ${ago(c.ts)}", Modifier.weight(1f))
                        if (c.uid == ui.myUid) Btn("Delete", { vm.deleteComment(id, c.id) }, kind = Kind.Quiet, small = true, tint = C.bad, flush = true)
                    }
                    Gap(4)
                    Text(c.text, style = T.body)
                    if (post.helped == c.id) { Gap(4); Caps("Helped", color = C.good) }
                    if (post.type == "ask" && post.uid == ui.myUid && c.uid != ui.myUid) {
                        Btn(if (post.helped == c.id) "Unmark helped" else "Mark as helped", { vm.markHelped(post, c) }, kind = Kind.Quiet, small = true, flush = true)
                    }
                }
            }
            Gap(16)
            TextBox(text, { text = it.take(300) }, "Add a comment", 2, Modifier.fillMaxWidth())
            Gap(4)
            Caps("${text.length} / 300")
            Gap(12)
            Btn(if (busy) "Posting..." else "Comment", { busy = true; vm.addComment(id, text) { text = ""; busy = false } }, small = true, enabled = text.isNotBlank() && !busy)
        }
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
    var finding by rememberSaveable { mutableStateOf(false) }
    if (finding) {
        Btn("Back to board", { finding = false }, kind = Kind.Quiet, small = true, flush = true)
        Gap(10)
        Find(vm, ui)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Seg(listOf(false to "Everyone", true to "Following"), following, Modifier.weight(1f)) { following = it }
        GapW(10)
        Btn("Find people", { finding = true }, kind = Kind.Outline, small = true)
    }
    Gap(20)
    val list = if (following) ui.boardFollowing else ui.board
    if (list.isEmpty()) { Text(if (following) "Follow someone under Find people and they show up here." else "No one is on the board yet.", style = T.body); return }
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
                val posted = ui.feed.any { it.id == "${ui.myUid}_lvl_$lvl" } || "lvl_$lvl" in sent
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

    val upvoted = ui.myPosts.filter { it.score > 0 }.sortedByDescending { it.ts }.take(5)
    if (upvoted.isNotEmpty()) {
        Gap(24)
        Caps("Your posts")
        Gap(2)
        upvoted.forEachIndexed { i, p ->
            if (i > 0) Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                GapW(10)
                Text("+${p.score.coerceIn(0, EarnRules.VOTE_CAP)} from upvotes", style = T.label.copy(color = C.accent))
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
            val posted = ui.feed.any { it.id == "${ui.myUid}_${entryId(e)}" } || entryId(e) in sent
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
