package com.aura.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import com.aura.app.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.app.AuraViewModel
import com.aura.app.UiState
import com.aura.app.data.Aura
import com.aura.app.data.Goal
import com.aura.app.data.GoalProgress
import com.aura.app.data.Metric
import com.aura.app.data.Occupations
import com.aura.app.data.Person
import com.aura.app.data.Profile
import com.aura.app.data.Status
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

private enum class Tab(val label: String) { TODAY("Today"), MOVE("Move"), GOALS("Goals"), CIRCLE("Circle"), YOU("You") }

@Composable
fun App(vm: AuraViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var adding by remember { mutableStateOf<Triple<String, Metric, Double>?>(null) }
    var screen by remember { mutableStateOf<MoveScreen?>(null) }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        if (ui.profile.name.isBlank()) Onboarding { vm.saveProfile(it) }
        else {
            // the Move pages (plan, record, activity) slide in over the tabs
            AnimatedContent(
                targetState = screen, label = "screen",
                transitionSpec = {
                    if (targetState != null) (slideInHorizontally(tween(280)) { it / 6 } + fadeIn(tween(220))) togetherWith fadeOut(tween(120))
                    else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(160)))
                },
            ) { s ->
                when (s) {
                    MoveScreen.Plan -> PlanScreen(onBack = { screen = null }, onStart = { screen = MoveScreen.Record })
                    MoveScreen.Ledger -> LedgerScreen(ui, vm, onBack = { screen = null })
                    MoveScreen.Record -> RecordScreen(onBack = { screen = null }, onSaved = { screen = MoveScreen.Detail(it); vm.refresh() })
                    is MoveScreen.Detail -> DetailScreen(vm, ui, s.id, onBack = { screen = null; vm.refresh() })
                    null -> Shell(vm, ui, tab, onTab = { tab = it }, onAdd = { adding = it }, onScreen = { screen = it })
                }
            }
            adding?.let { start ->
                AddGoalSheet(start, onDismiss = { adding = null }) { title, m, t, d -> vm.addGoal(title, m, t, d); adding = null; tab = Tab.GOALS }
            }
            ui.update?.let { UpdateSheet(it, ui.updateProgress, ui.updateMsg, vm::startUpdate, vm::snoozeUpdate) }
            Celebration(ui.justEarned, vm::celebrated, Modifier.align(Alignment.TopCenter))
            var lv by remember { mutableIntStateOf(0) }
            if (ui.levelUp > 0) lv = ui.levelUp
            AnimatedVisibility(ui.levelUp > 0 && screen == null, enter = fadeIn(tween(250)), exit = fadeOut(tween(300))) { LevelUp(lv, vm::levelSeen) }
        }
    }
}

// ---------- shell: tabs along the bottom on a phone, down the side on a wide screen ----------

@Composable
private fun Shell(vm: AuraViewModel, ui: UiState, tab: Tab, onTab: (Tab) -> Unit, onAdd: (Triple<String, Metric, Double>) -> Unit, onScreen: (MoveScreen) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 720.dp) Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            SideRail(tab, onTab)
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
            Pages(Modifier.weight(1f), vm, ui, tab, onTab, onAdd, onScreen)
        } else Column(Modifier.fillMaxSize()) {
            Pages(Modifier.weight(1f).statusBarsPadding(), vm, ui, tab, onTab, onAdd, onScreen)
            BottomBar(tab, onTab)
        }
    }
}

@Composable
private fun Pages(modifier: Modifier, vm: AuraViewModel, ui: UiState, tab: Tab, onTab: (Tab) -> Unit, onAdd: (Triple<String, Metric, Double>) -> Unit, onScreen: (MoveScreen) -> Unit) {
    AnimatedContent(
        targetState = tab, modifier = modifier, label = "tab",
        transitionSpec = { (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(300)) { it / 28 }) togetherWith fadeOut(tween(90)) },
    ) { t ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 36.dp)) {
                when (t) {
                    Tab.TODAY -> Today(vm, ui, onAdd, goGoals = { onTab(Tab.GOALS) }, onLedger = { onScreen(MoveScreen.Ledger) })
                    Tab.MOVE -> MoveHome(onPlan = { onScreen(MoveScreen.Plan) }, onRecord = { onScreen(MoveScreen.Record) }, onOpen = { onScreen(MoveScreen.Detail(it)) })
                    Tab.GOALS -> Goals(ui.goals, onAdd = { onAdd(Triple("", Metric.STEPS, Metric.STEPS.defaultTarget)) }, onDelete = vm::deleteGoal)
                    Tab.CIRCLE -> Circle(vm, ui)
                    Tab.YOU -> You(vm, ui, onLedger = { onScreen(MoveScreen.Ledger) })
                }
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.bg)) {
        Rule()
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
            val cell = maxWidth / Tab.entries.size
            val x by animateDpAsState(cell * tab.ordinal + (cell - 22.dp) / 2, spring(dampingRatio = 0.72f, stiffness = 380f), label = "nav")
            Box(Modifier.offset(x = x).width(22.dp).height(3.dp).clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)).background(C.accent))
            Row(Modifier.fillMaxWidth()) {
                Tab.entries.forEach { t ->
                    val on = t == tab
                    val col by animateColorAsState(if (on) C.ink else C.muted, label = "navText")
                    Box(
                        Modifier.weight(1f).height(62.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) },
                        contentAlignment = Alignment.Center,
                    ) { Text(t.label, style = T.body.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = col)) }
                }
            }
        }
    }
}

@Composable
private fun SideRail(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.width(184.dp).fillMaxHeight().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("aura", style = T.title)
        Gap(28)
        Tab.entries.forEach { t ->
            val on = t == tab
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(if (on) C.accent else C.bg))
                GapW(12)
                Text(t.label, style = T.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.ink else C.muted))
            }
        }
    }
}

/** Slides down once when a goal completes. */
@Composable
private fun Celebration(earned: Int, onDone: () -> Unit, modifier: Modifier) {
    var shown by remember { mutableIntStateOf(0) }
    if (earned > 0) shown = earned
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(earned) {
        if (earned > 0) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(6000, easing = LinearEasing))
            onDone()
        }
    }
    AnimatedVisibility(
        visible = earned > 0, modifier = modifier.statusBarsPadding().padding(16.dp).widthIn(max = 560.dp),
        enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = 300f)) { -it } + fadeIn(),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.accent).clickable(onClick = onDone)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+${countUp(shown)}", style = T.title.copy(color = C.onAccent))
            GapW(14)
            Column(Modifier.weight(1f)) {
                Text("Aura earned", style = T.head.copy(color = C.onAccent))
                Text("Added to your total. See how under You.", style = T.label.copy(color = C.onAccent.copy(alpha = 0.75f)))
            }
        }
        Box(Modifier.fillMaxWidth(1f - sweep.value).height(3.dp).background(C.onAccent.copy(alpha = 0.55f)))
        }
    }
}

// ---------- onboarding ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Onboarding(onDone: (Profile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var occ by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp)) {
            Reveal(0) {
                Text("aura", style = T.display)
                Gap(8)
                Text("Set a goal. Let your watch prove it.\nEarn aura, and share it.", style = T.body.copy(fontSize = 17.sp, lineHeight = 26.sp))
            }
            Gap(36)
            Reveal(1) {
                Caps("Your name")
                Gap(8)
                Field(name, { name = it }, "Name", Modifier.fillMaxWidth())
            }
            Gap(28)
            Reveal(2) {
                Caps("What do you do")
                Gap(10)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Occupations.all.forEach { o -> Chip(o, occ == o) { occ = o; custom = "" } }
                }
                Gap(12)
                Field(custom, { custom = it; occ = "" }, "Something else", Modifier.fillMaxWidth())
            }
            Gap(32)
            val chosen = if (occ.isNotBlank()) occ else custom.trim()
            Reveal(3) {
                Btn("Continue", { onDone(Profile(name.trim(), chosen)) }, Modifier.fillMaxWidth(), Kind.Accent, enabled = name.isNotBlank() && chosen.isNotBlank())
            }
        }
    }
}

// ---------- today ----------

@Composable
private fun Today(vm: AuraViewModel, ui: UiState, onAdd: (Triple<String, Metric, Double>) -> Unit, goGoals: () -> Unit, onLedger: () -> Unit) {
    val hello = remember { when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" } }
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")) }

    Reveal(0) {
        Caps(date)
        Gap(6)
        Text("$hello, ${ui.profile.name}", style = T.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Gap(12)
    Reveal(1) { AuraHalo(ui.aura) }
    if (ui.healthOk && ui.today.isNotEmpty()) {
        Gap(20)
        Reveal(1) { TodayStrip(ui.today, ui.streaks, onLedger) }
    }
    Connections(vm, ui)
    Gap(32)
    Reveal(2) { BodyAndScreen(vm, ui) }
    Gap(36)
    Reveal(3) {
        val active = ui.goals.filter { it.status == Status.ACTIVE }
        if (active.isEmpty()) {
            Caps("Start a goal")
            Gap(6)
            Text("Made for a ${ui.profile.occupation.lowercase()}", style = T.title.copy(fontSize = 26.sp, lineHeight = 32.sp))
            Gap(14)
            Occupations.suggestions(ui.profile.occupation).forEach { s ->
                Panel(onClick = { onAdd(s) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.first, style = T.head)
                            Gap(2)
                            Text("${s.second.label}: ${if (s.second.lowerIsBetter) "under" else "at least"} ${s.second.fmt(s.third)} ${s.second.unit} a day", style = T.label)
                        }
                        GapW(12)
                        Caps("Start", color = C.accent)
                    }
                }
                Gap(10)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Caps("In progress")
                    Gap(6)
                    Text(if (active.size == 1) "One goal" else "${active.size} goals", style = T.title.copy(fontSize = 26.sp, lineHeight = 32.sp))
                }
                Btn("All goals", goGoals, kind = Kind.Quiet, small = true)
            }
            Gap(14)
            active.forEach { GoalCard(it); Gap(10) }
        }
    }
}

/** The aura total inside a gauge of progress to the next level, with a slow ring of light turning around it. */
@Composable
private fun AuraHalo(points: Int) {
    val lvl = Aura.level(points)
    val from = Aura.levelStart(lvl); val to = Aura.levelStart(lvl + 1)
    val frac = ((points - from).toFloat() / (to - from)).coerceIn(0f, 1f)
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(frac) { sweep.animateTo(frac, tween(1300, easing = FastOutSlowInEasing)) }
    val spin = rememberInfiniteTransition(label = "halo").animateFloat(0f, 360f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "spin")
    val shown = countUp(points)
    val accent = C.accent; val line = C.line; val butter = C.butter

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(272.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 9.dp.toPx()
                val d = size.minDimension - 56.dp.toPx()
                val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(line, 135f, 270f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                if (sweep.value > 0.004f) drawArc(accent, 135f, 270f * sweep.value, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
                // the halo: short ticks whose brightness trails off around the circle, turning slowly
                val r0 = d / 2 + 13.dp.toPx(); val r1 = r0 + 7.dp.toPx(); val n = 96
                for (i in 0 until n) {
                    val a = Math.toRadians(i * 360.0 / n + spin.value)
                    val k = i.toFloat() / n
                    val cx = cos(a).toFloat(); val cy = sin(a).toFloat()
                    drawLine(butter.copy(alpha = 0.06f + 0.9f * k * k), Offset(center.x + cx * r0, center.y + cy * r0), Offset(center.x + cx * r1, center.y + cy * r1), 1.5.dp.toPx(), StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Caps("Aura")
                val size = when { shown < 1000 -> 76; shown < 10_000 -> 60; else -> 48 }
                Text("$shown", style = T.display.copy(fontSize = size.sp, lineHeight = size.sp))
                Caps("Level $lvl · ${Aura.levelName(lvl)}", color = C.ink)
            }
        }
        Text("${to - points} to level ${lvl + 1}", style = T.label)
    }
}

@Composable
internal fun Connections(vm: AuraViewModel, ui: UiState) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refresh() }

    if (!ui.healthOk) {
        Gap(16)
        Panel {
            Caps("Step one")
            Gap(6)
            Text("Connect your watch", style = T.head)
            Gap(4)
            Text(
                if (ui.healthAvailable) "Aura reads steps, exercise, sleep, heart rate and distance from Health Connect, which any watch can sync into: Samsung, Fitbit, Garmin, Pixel Watch and more."
                else "Health Connect isn't ready on this phone. Install or update it, then come back.",
                style = T.label,
            )
            Gap(14)
            Btn(if (ui.healthAvailable) "Connect Health Connect" else "Get Health Connect", {
                if (ui.healthAvailable) launcher.launch(vm.source.permissions)
                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, small = true)
        }
    }
    if (!ui.usageOk) {
        Gap(if (ui.healthOk) 16 else 10)
        Panel {
            Caps(if (ui.healthOk) "One more step" else "Step two")
            Gap(6)
            Text("Allow usage access", style = T.head)
            Gap(4)
            Text("Lets Aura see which apps were open and for how long, to check screen-time goals and to show your heart rate next to each app. It stays on this phone.", style = T.label)
            Gap(14)
            Btn("Open settings", { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, kind = Kind.Outline, small = true)
        }
    }
}

// ---------- goals ----------

@Composable
private fun GoalCard(p: GoalProgress) {
    val g = p.goal
    val done = p.status == Status.COMPLETED
    Panel {
        Row {
            Column(Modifier.weight(1f)) {
                Text(g.title, style = T.head)
                Gap(2)
                Text("${g.metric.label}: ${if (g.metric.lowerIsBetter) "under" else "at least"} ${g.metric.fmt(g.target)} ${g.metric.unit}, on ${g.days} ${if (g.days == 1) "day" else "days"}", style = T.label)
            }
            GapW(12)
            Column(horizontalAlignment = Alignment.End) {
                Text("+${g.reward}", style = T.num.copy(color = if (done) C.good else C.accent))
                Caps("aura")
            }
        }
        Gap(16)
        Meter(p.daysMet.toFloat() / g.days, color = if (done) C.good else C.accent)
        Gap(10)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caps("${p.daysMet} of ${g.days} days")
            Caps(
                when (p.status) {
                    Status.COMPLETED -> "Completed"
                    Status.EXPIRED -> "Time ran out"
                    Status.ACTIVE -> p.today?.let { "Today ${g.metric.fmt(it)} ${g.metric.unit}" } ?: "No data yet today"
                },
                color = when (p.status) { Status.COMPLETED -> C.good; Status.EXPIRED -> C.bad; else -> C.muted },
            )
        }
        if (p.dayResults.isNotEmpty() && !done) {
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                p.dayResults.takeLast(21).forEach { Box(Modifier.width(10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (it) C.good else C.line)) }
            }
        }
    }
}

@Composable
private fun Goals(goals: List<GoalProgress>, onAdd: () -> Unit, onDelete: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Goals", style = T.title, modifier = Modifier.weight(1f))
        Btn("New goal", onAdd, small = true)
    }
    Gap(6)
    Text("Each one is checked against your watch and phone data. Nothing is self-reported.", style = T.label)
    Gap(20)
    if (goals.isEmpty()) Text("No goals yet. Start one here, or pick a suggestion on Today.", style = T.body)
    val sorted = goals.sortedBy { it.status.ordinal }
    sorted.forEachIndexed { i, p ->
        val header = i == 0 || sorted[i - 1].status != p.status
        Reveal(i) {
            if (header) {
                if (i > 0) Gap(14)
                Caps(when (p.status) { Status.ACTIVE -> "In progress"; Status.COMPLETED -> "Completed"; Status.EXPIRED -> "Ran out of time" })
                Gap(10)
            }
            GoalCard(p)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Btn("Remove", { onDelete(p.goal.id) }, kind = Kind.Quiet, small = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddGoalSheet(start: Triple<String, Metric, Double>, onDismiss: () -> Unit, onSave: (String, Metric, Double, Int) -> Unit) {
    var title by remember { mutableStateOf(start.first) }
    var metric by remember { mutableStateOf(start.second) }
    var target by remember { mutableStateOf(start.third) }
    var days by remember { mutableIntStateOf(14) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.surface, contentColor = C.ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Text("New goal", style = T.title)
            Gap(16)
            Field(title, { title = it }, "Name it", Modifier.fillMaxWidth())
            Gap(20)
            Caps("Measured by")
            Gap(10)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric.entries.forEach { m -> Chip(m.label, metric == m) { metric = m; target = m.defaultTarget } }
            }
            Gap(20)
            Stepper(if (metric.lowerIsBetter) "Most per day" else "Target per day", metric.fmt(target), metric.unit,
                { target = (target - metric.step).coerceAtLeast(metric.step) }, { target += metric.step })
            Gap(14); Rule(); Gap(14)
            Stepper("Days to hit it", "$days", if (days == 1) "day" else "days", { days = (days - 1).coerceAtLeast(1) }, { days = (days + 1).coerceAtMost(90) })
            Gap(18)
            Text("Worth ${days * metric.pointsPerDay + Goal.BONUS} aura. You have ${Math.ceil(days * 1.5).toInt()} days to fit them in.", style = T.label)
            Gap(18)
            Btn("Start goal", { onSave(title.ifBlank { metric.label }, metric, target, days) }, Modifier.fillMaxWidth(), Kind.Accent)
        }
    }
}

// ---------- circle: feed, leaderboard, friends ----------
// ---------- you ----------

@Composable
private fun StatusRow(label: String, ok: Boolean, state: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) C.good else C.line, 10)
        GapW(12)
        Text(label, style = T.body, modifier = Modifier.weight(1f))
        Caps(state, color = if (ok) C.good else C.muted)
    }
}

@Composable
private fun You(vm: AuraViewModel, ui: UiState, onLedger: () -> Unit) {
    var shareAura by remember { mutableStateOf(false) }
    Caps(ui.profile.occupation)
    Gap(6)
    Text(ui.profile.name, style = T.title)
    Gap(20)
    StatGrid(listOf(
        Stat("Aura", "${ui.aura}"),
        Stat("Completed", "${ui.goals.count { it.status == Status.COMPLETED }}"),
        Stat("In progress", "${ui.goals.count { it.status == Status.ACTIVE }}"),
    ))
    Gap(12)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Btn("How you earned it", onLedger, kind = Kind.Outline, small = true)
        Btn("Share your aura", { shareAura = true }, kind = Kind.Outline, small = true)
    }
    if (shareAura) ShareSheet(ShareCard.Level, ui.sharer()) { shareAura = false }
    Connections(vm, ui)
    Gap(28)
    Caps("Connections")
    Gap(4)
    StatusRow("Health Connect, your watch data", ui.healthOk, if (ui.healthOk) "Connected" else "Not connected")
    Rule()
    StatusRow("Usage access, screen time and apps", ui.usageOk, if (ui.usageOk) "Allowed" else "Not allowed")
    Rule()
    StatusRow(if (ui.signedIn) ui.email else "Account", ui.signedIn, if (ui.signedIn) "Signed in" else "Signed out")
    Gap(16)
    if (ui.signedIn) Btn("Sign out", vm::signOut, kind = Kind.Outline, small = true)
    else Text("Sign in under Circle to follow friends and post.", style = T.label)
    Gap(28)
    Text("Aura follows your phone's light or dark mode.", style = T.label)
    Gap(28)
    val ctx = LocalContext.current
    Caps("Made by")
    Gap(10)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MakerIcon(R.drawable.ic_github, "GitHub profile") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lighty7"))) }
        MakerIcon(R.drawable.ic_person, "Personal website") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.yashwanth.duckdns.org"))) }
    }
    Gap(18)
    Text("Version ${com.aura.app.BuildConfig.VERSION_NAME}", style = T.label)
    Gap(6)
    Btn("Check for updates", { vm.checkForUpdates(true) }, kind = Kind.Quiet, small = true, flush = true)
    if (ui.updateMsg.isNotBlank() && ui.update == null) { Gap(4); Text(ui.updateMsg, style = T.label) }
    Gap(20)
}

@Composable
private fun MakerIcon(res: Int, label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).press(src).clip(CircleShape).border(1.dp, C.line, CircleShape)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(res), contentDescription = label, tint = C.ink, modifier = Modifier.size(22.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdateSheet(u: com.aura.app.update.Update, progress: Float?, msg: String, onUpdate: () -> Unit, onLater: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (progress == null) onLater() }, containerColor = C.surface, contentColor = C.ink, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Caps("Update available")
            Gap(6)
            Text(u.version, style = T.title)
            Gap(12)
            u.notes.take(8).forEach { Text("- $it", style = T.label); Gap(4) }
            if (u.size > 0) { Gap(6); Text("%.1f MB".format(u.size / 1048576.0), style = T.label) }
            Gap(20)
            if (progress != null) {
                Meter(progress, Modifier.fillMaxWidth())
                Gap(8)
                Text("Downloading... ${(progress * 100).toInt()}%", style = T.label)
            } else {
                if (msg.isNotBlank()) { Text(msg, style = T.label.copy(color = C.bad)); Gap(12) }
                Btn("Update now", onUpdate, Modifier.fillMaxWidth(), kind = Kind.Accent)
                Gap(6)
                Btn("Later", onLater, Modifier.fillMaxWidth(), kind = Kind.Quiet)
            }
        }
    }
}
