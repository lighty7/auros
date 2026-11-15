package com.aura.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.app.AuraViewModel
import com.aura.app.BuildConfig
import com.aura.app.UiState
import com.aura.app.data.ActivityStore
import com.aura.app.data.Rec
import com.aura.app.move.Planned
import com.aura.app.move.RecordService
import com.aura.app.move.Recorder
import com.aura.app.move.Route
import com.aura.app.move.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface MoveScreen {
    data object Plan : MoveScreen
    data object Record : MoveScreen
    data object Ledger : MoveScreen
    data object Trends : MoveScreen
    data class Thread(val id: String) : MoveScreen
    data class Detail(val id: Long) : MoveScreen
}

private val SPORTS = listOf("walk" to "Walk", "run" to "Run", "cycle" to "Ride")

// map colours (the map is drawn by a view, so these are plain ints)
private val ROUTE = 0xFF5B8DEF.toInt()
private val TRACK = 0xFFFF6A3D.toInt()
private val START = 0xFF4FB286.toInt()
private val DEST = 0xFFE5B93C.toInt()
/** heart-rate zones 0 (unknown) to 5 (maximum) */
private val ZONES = intArrayOf(0xFF9B9484.toInt(), 0xFF5B8DEF.toInt(), 0xFF4FB286.toInt(), 0xFFE5B93C.toInt(), 0xFFFF6A3D.toInt(), 0xFFD83A34.toInt())

internal fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
internal fun km(m: Double) = "%.2f".format(m / 1000)
private fun date(ms: Long) = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(ms))

/** Pace as (value, unit): min/km for walking and running, km/h for riding. */
private fun paceParts(sport: String, distM: Double, secs: Int): Pair<String, String> {
    if (distM < 10 || secs <= 0) return "–" to ""
    return if (sport == "cycle") "%.1f".format(distM / secs * 3.6) to "km/h"
    else { val s = (secs / (distM / 1000)).toInt(); "%d:%02d".format(s / 60, s % 60) to "/km" }
}

internal fun pace(sport: String, distM: Double, secs: Int): String = paceParts(sport, distM, secs).let { (v, u) -> if (u.isBlank()) v else "$v $u" }

private fun zone(hr: Int, max: Int = 190): Int {
    if (hr <= 0) return 0
    val p = hr * 100.0 / max
    return when { p < 60 -> 1; p < 70 -> 2; p < 80 -> 3; p < 90 -> 4; else -> 5 }
}

/** Colours the recorded route by the heart rate the watch measured at that moment: the watch data merged onto the GPS track. */
private fun heartSegs(r: Rec): List<Seg> {
    val hr = r.merged.hr
    val geo = r.pts.map { GeoPoint(it.lat, it.lon) }
    if (hr.isEmpty()) return listOf(Seg(geo, TRACK))
    fun at(t: Long): Int {
        var lo = 0; var hi = hr.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (hr[mid].first < t) lo = mid + 1 else hi = mid }
        val best = listOfNotNull(hr.getOrNull(lo - 1), hr.getOrNull(lo)).minByOrNull { Math.abs(it.first - t) } ?: return 0
        return if (Math.abs(best.first - t) > 180_000) 0 else best.second
    }
    val out = ArrayList<Seg>()
    var cur = ArrayList<GeoPoint>(); var curZ = -1
    r.pts.forEachIndexed { i, p ->
        val z = zone(at(p.t))
        if (curZ == -1) curZ = z
        cur += geo[i]
        if (z != curZ) { out += Seg(cur, ZONES[curZ]); cur = arrayListOf(geo[i]); curZ = z }
    }
    if (cur.size >= 2) out += Seg(cur, ZONES[curZ])
    return out
}

// ---------- Move tab ----------

@Composable
fun MoveHome(onPlan: () -> Unit, onRecord: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Rec>?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { ActivityStore(ctx).list() } }

    Text("Move", style = T.title)
    Gap(6)
    Text("Plan a route, record it with GPS, then see it merged with what your watch measured.", style = T.label)
    Gap(20)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("Record", onRecord, Modifier.weight(1f), Kind.Accent)
        Btn("Plan route", onPlan, Modifier.weight(1f), Kind.Outline)
    }
    Gap(32)
    Caps("Activities")
    Gap(4)
    val items = list ?: return
    if (items.isEmpty()) { Gap(6); Text("Nothing recorded yet. Your first walk, run or ride lands here.", style = T.body) }
    items.forEachIndexed { i, r ->
        Reveal(i) {
            if (i > 0) Rule()
            Row(
                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(r.id) }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoutePreview(r, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(C.raised))
                GapW(14)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(km(r.distM), style = T.num)
                        Text(" km", style = T.label, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Gap(2)
                    Caps("${r.title} · ${clock(r.elapsedS)} · ${pace(r.sport, r.distM, r.movingS.takeIf { it > 0 } ?: r.elapsedS)}")
                    Gap(2)
                    Caps(date(r.startMs))
                }
                if (r.merged.hrAvg > 0) Column(horizontalAlignment = Alignment.End) {
                    Text("${r.merged.hrAvg}", style = T.num.copy(color = C.heart))
                    Caps("bpm")
                }
            }
        }
    }
}

@Composable
private fun RoutePreview(r: Rec, modifier: Modifier) {
    val ink = C.ink; val accent = C.accent
    Canvas(modifier) {
        if (r.pts.size < 2) return@Canvas
        val minLat = r.pts.minOf { it.lat }; val maxLat = r.pts.maxOf { it.lat }
        val minLon = r.pts.minOf { it.lon }; val maxLon = r.pts.maxOf { it.lon }
        val k = Math.cos(Math.toRadians((minLat + maxLat) / 2))
        val span = maxOf(maxLat - minLat, (maxLon - minLon) * k, 1e-6)
        val pad = 10.dp.toPx(); val side = size.minDimension - 2 * pad
        fun pt(i: Int) = Offset(pad + ((r.pts[i].lon - minLon) * k / span * side).toFloat(), pad + (side - ((r.pts[i].lat - minLat) / span * side)).toFloat())
        val path = Path()
        r.pts.indices.forEach { i -> val o = pt(i); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(accent, 3.dp.toPx(), pt(0))
    }
}

// ---------- plan ----------

@SuppressLint("MissingPermission")
private fun lastFix(ctx: Context): GeoPoint? = try {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { GeoPoint(it.latitude, it.longitude) }
} catch (_: Exception) { null }

@Composable
fun PlanScreen(onBack: () -> Unit, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sport by remember { mutableStateOf("run") }
    var loop by remember { mutableStateOf(true) }
    var lenKm by remember { mutableIntStateOf(5) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Router.Place>>(emptyList()) }
    var dest by remember { mutableStateOf<Router.Place?>(null) }
    var route by remember { mutableStateOf<Route?>(null) }
    var seed by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var here by remember { mutableStateOf(lastFix(ctx)) }

    /** Plan from where you are to the chosen place, or a loop of the chosen length. */
    fun build() {
        val from = here ?: lastFix(ctx)?.also { here = it }
        if (from == null) { err = "Waiting for your location. Check that location is on, then try again."; return }
        if (!Router.configured) { err = "Route planning needs a free OpenRouteService key: add ors.key to local.properties and rebuild."; return }
        val to = dest
        if (!loop && to == null) return
        busy = true; err = ""
        scope.launch {
            try {
                route = withContext(Dispatchers.IO) { if (loop) Router.loop(sport, from, lenKm * 1000, seed) else Router.to(sport, from, to!!.at, to.label) }
            } catch (e: Exception) { err = e.message ?: "Couldn't plan that route" }
            busy = false
        }
    }

    /** A pin dropped on the map becomes the destination; it gets a name from the nearest address when there is one. */
    fun drop(at: GeoPoint) {
        val pin = Router.Place("Dropped pin", at)
        dest = pin; route = null; results = emptyList(); err = ""
        build()
        if (Router.configured) scope.launch {
            val name = runCatching { withContext(Dispatchers.IO) { Router.reverse(at) } }.getOrNull()
            if (name != null && dest === pin) dest = Router.Place(name, at)
        }
    }

    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) here = lastFix(ctx) else err = "Location permission is needed to plan from where you are."
    }
    LaunchedEffect(Unit) {
        if (here == null) locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun search() {
        if (query.isBlank()) return
        busy = true; err = ""
        scope.launch {
            try { results = withContext(Dispatchers.IO) { Router.search(query, here) } } catch (e: Exception) { err = e.message ?: "Search failed" }
            busy = false
        }
    }

    ScreenFrame("Plan a route", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp)) {
            Caps("Sport")
            Gap(8)
            Seg(SPORTS, sport) { sport = it; route = null; if (!loop && dest != null) build() }
            Gap(20)
            Caps("Shape")
            Gap(8)
            Seg(listOf(true to "Loop from here", false to "To a place"), loop) { loop = it; route = null; err = "" }
            Gap(20)

            if (loop) Stepper("Distance", "$lenKm", "km", { lenKm = (lenKm - 1).coerceAtLeast(1); route = null }, { lenKm = (lenKm + 1).coerceAtMost(60); route = null })
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(query, { query = it }, "Search, or tap the map", Modifier.weight(1f), onSearch = { search() })
                    GapW(10)
                    Btn("Search", { search() }, kind = Kind.Outline, small = true)
                }
                results.forEachIndexed { i, p ->
                    if (i > 0) Rule()
                    Text(p.label, style = T.body, modifier = Modifier.fillMaxWidth().clickable { query = ""; dest = p; results = emptyList(); route = null; build() }.padding(vertical = 14.dp))
                }
            }
            Gap(16)

            // the map is always there: where you are, the pin, and the route once there is one
            val pins = buildList { here?.let { add(it to START) }; dest?.takeIf { !loop }?.let { add(it.at to DEST) } }
            val segs = route?.let { listOf(Seg(it.pts, ROUTE)) } ?: emptyList()
            Box {
                TrackMap(
                    Modifier.fillMaxWidth().height(if (loop && route == null) 240.dp else 340.dp).clip(RoundedCornerShape(20.dp)),
                    segs, fit = route != null || (!loop && dest != null), pins = pins, center = here,
                    onTap = if (loop) null else { p -> drop(p) },
                )
                if (here == null) Box(Modifier.matchParentSize().clip(RoundedCornerShape(20.dp)).background(C.raised), contentAlignment = Alignment.Center) {
                    Text("Finding where you are…", style = T.label)
                }
            }
            Gap(10)
            Caps(
                when {
                    loop -> "Green is where you start"
                    dest == null -> "Tap anywhere on the map to drop a pin"
                    else -> "Going to ${dest!!.label}"
                },
                color = if (!loop && dest != null) C.ink else C.muted,
            )

            Gap(20)
            if (loop || route == null) Btn(
                if (busy) "Working…" else if (route != null) "Try another loop" else "Plan route",
                { if (loop && route != null) seed++; build() }, Modifier.fillMaxWidth(), enabled = !busy && (loop || dest != null),
            )
            if (err.isNotBlank()) { Gap(10); Text(err, style = T.label.copy(color = C.bad)) }
            route?.let { r ->
                Gap(16)
                Reveal {
                    StatGrid(listOf(Stat("Distance", km(r.distM), "km"), Stat("About", clock(r.durS.toInt())), Stat("Climb", "${r.ascentM.toInt()}", "m")))
                    Gap(14)
                    Btn("Use this route", { Planned.route = r; onStart() }, Modifier.fillMaxWidth(), Kind.Accent)
                }
            }
        }
    }
}

// ---------- record ----------

@Composable
fun RecordScreen(onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val ctx = LocalContext.current
    val st by Recorder.state.collectAsState()
    val planned = remember { Planned.route }
    var sport by remember { mutableStateOf(planned?.sport ?: "run") }
    var finishing by remember { mutableStateOf(false) }
    var pendingSim by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val active = st.status != Recorder.Status.Idle
    val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) { msg = ""; RecordService.start(ctx, sport, pendingSim) }
        else msg = "Precise location is needed to record a route."
    }

    LaunchedEffect(st.savedId) { if (st.savedId >= 0) { val id = st.savedId; Recorder.reset(); Planned.route = null; onSaved(id) } }
    LaunchedEffect(st.status) { if (finishing && st.status == Recorder.Status.Idle && st.savedId < 0) { finishing = false; msg = "Too short to save (under 20 m)." } }

    ScreenFrame(if (active) st.sport.let { s -> SPORTS.first { it.first == s }.second } else "Record", onBack = { if (!active) onBack() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp)) {
            if (!active) {
                Text("Ready when you are", style = T.title)
                Gap(20)
                Caps("Sport")
                Gap(8)
                Seg(SPORTS, sport) { sport = it }
                planned?.let {
                    Gap(16)
                    Panel {
                        Caps("Following a planned route")
                        Gap(6)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${km(it.distM)} km ${it.label.lowercase()}", style = T.head, modifier = Modifier.weight(1f))
                            Btn("Clear", { Planned.route = null; onBack() }, kind = Kind.Quiet, small = true)
                        }
                    }
                }
                Gap(16)
                Text("GPS runs while recording, also with the screen off. Your watch's heart rate, steps and calories are merged in afterwards from Health Connect.", style = T.label)
                if (msg.isNotBlank()) { Gap(10); Text(msg, style = T.label.copy(color = C.bad)) }
                Gap(24)
                Btn("Start", { pendingSim = false; permLauncher.launch(perms) }, Modifier.fillMaxWidth(), Kind.Accent)
                if (BuildConfig.DEBUG) { Gap(10); Btn("Simulate (debug test)", { pendingSim = true; permLauncher.launch(perms) }, Modifier.fillMaxWidth(), Kind.Outline) }
            } else {
                val paused = st.status == Recorder.Status.Paused
                val pts = remember(st.version) { Recorder.points() }
                val segs = buildList {
                    planned?.let { add(Seg(it.pts, ROUTE)) }
                    if (pts.size >= 2) add(Seg(pts.map { GeoPoint(it.lat, it.lon) }, TRACK))
                }
                TrackMap(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)), segs, me = if (st.lat != 0.0) GeoPoint(st.lat, st.lon) else null, follow = true)
                Gap(16)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
                    Box(Modifier.alpha(if (paused) 1f else pulse)) { Dot(if (paused) C.muted else C.accent, 10) }
                    GapW(10)
                    Caps(if (paused) "Paused" else st.message.ifBlank { "Recording" }, color = C.ink)
                }
                Gap(8)
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1.25f)) {
                        Text(clock(st.elapsedS), style = T.display.copy(fontSize = 60.sp, lineHeight = 62.sp))
                        Caps("Time")
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) { Text(km(st.distM), style = T.num); Text(" km", style = T.label, modifier = Modifier.padding(bottom = 3.dp)) }
                        Caps("Distance")
                        Gap(10)
                        val (pv, pu) = paceParts(st.sport, st.distM, st.movingS.coerceAtLeast(1))
                        Row(verticalAlignment = Alignment.Bottom) { Text(pv, style = T.num); if (pu.isNotBlank()) Text(" $pu", style = T.label, modifier = Modifier.padding(bottom = 3.dp)) }
                        Caps("Pace")
                    }
                }
                Gap(18)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn(if (paused) "Resume" else "Pause", { RecordService.send(ctx, if (paused) RecordService.ACTION_RESUME else RecordService.ACTION_PAUSE) }, Modifier.weight(1f), Kind.Outline)
                    Btn("Finish", { finishing = true; RecordService.send(ctx, RecordService.ACTION_STOP, true) }, Modifier.weight(1f), Kind.Accent)
                }
            }
        }
    }
}

// ---------- detail: the route merged with the watch's data ----------

@Composable
fun DetailScreen(vm: AuraViewModel, ui: UiState, id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ActivityStore(ctx) }
    var rec by remember { mutableStateOf<Rec?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var posted by remember { mutableStateOf(store.isPosted(id)) }
    var posting by remember { mutableStateOf(false) }
    var shareCard by remember { mutableStateOf<ShareCard?>(null) }

    suspend fun merge() {
        val r = rec ?: return
        busy = true
        val m = vm.source.window(r.startMs - 60_000, r.endMs + 60_000)
        if (m == null) note = "Couldn't read Health Connect."
        else {
            val r2 = r.copy(merged = m); withContext(Dispatchers.IO) { store.save(r2) }; rec = r2
            note = if (m.hasAny) "" else "No watch data for this time yet. Open your watch's app to sync it, then refresh."
        }
        busy = false
    }

    LaunchedEffect(id, ui.healthOk) {
        rec = withContext(Dispatchers.IO) { store.get(id) }
        val r = rec
        if (r != null && ui.healthOk && System.currentTimeMillis() - r.merged.readAt > 10 * 60_000) merge()
    }

    ScreenFrame(rec?.title ?: "Activity", onBack) {
        val r = rec
        if (r == null) { Text("Loading…", style = T.label, modifier = Modifier.padding(20.dp)); return@ScreenFrame }
        val m = r.merged
        val (pv, pu) = paceParts(r.sport, r.distM, r.movingS.takeIf { it > 0 } ?: r.elapsedS)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp)) {
            Caps(date(r.startMs))
            Gap(6)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(km(r.distM), style = T.display.copy(fontSize = 60.sp, lineHeight = 62.sp))
                Text(" km", style = T.title, modifier = Modifier.padding(bottom = 6.dp))
            }
            Gap(14)
            TrackMap(
                Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(20.dp)), heartSegs(r), fit = true,
                pins = listOf(GeoPoint(r.pts.first().lat, r.pts.first().lon) to START, GeoPoint(r.pts.last().lat, r.pts.last().lon) to DEST),
            )
            if (m.hr.isNotEmpty()) {
                Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caps("Heart rate")
                    GapW(10)
                    Caps("Low")
                    GapW(8)
                    (1..5).forEach { Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(ZONES[it]))); GapW(3) }
                    GapW(5)
                    Caps("High")
                }
            }
            Gap(18)
            StatGrid(listOf(
                Stat("Time", clock(r.elapsedS)),
                Stat("Pace", pv, pu),
                Stat("Climb", "${r.gainM.toInt()}", "m"),
                Stat("Heart avg", if (m.hrAvg > 0) "${m.hrAvg}" else "–", if (m.hrAvg > 0) "bpm" else ""),
                Stat("Heart max", if (m.hrMax > 0) "${m.hrMax}" else "–", if (m.hrMax > 0) "bpm" else ""),
                Stat("Steps", if (m.steps > 0) "%,d".format(m.steps) else "–"),
                Stat("Energy", if (m.kcal > 0) "${m.kcal.toInt()}" else "–", if (m.kcal > 0) "kcal" else ""),
                Stat("By watch", if (m.hcDistM > 0) km(m.hcDistM) else "–", if (m.hcDistM > 0) "km" else ""),
                Stat("Moving", clock(r.movingS)),
            ))
            if (m.hr.size >= 2) { Gap(24); Caps("Heart rate"); Gap(10); HrChart(r) }

            Gap(20)
            if (!ui.healthOk) Connections(vm, ui)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Btn(if (busy) "Reading…" else "Refresh watch data", { scope.launch { merge() } }, kind = Kind.Outline, small = true, enabled = !busy)
                if (m.readAt > 0) { GapW(12); Caps("Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.readAt))}") }
            }
            if (note.isNotBlank()) { Gap(10); Text(note, style = T.label) }

            Gap(20)
            Panel {
                Caps("Circle")
                Gap(6)
                Text("Post this to your feed", style = T.head)
                Gap(4)
                Text("Shares the sport, distance, time and average heart rate. Your route and location are never shared.", style = T.label)
                Gap(14)
                if (!ui.signedIn) Text("Sign in under Circle to post.", style = T.label)
                else Btn(
                    if (posting) "Working…" else if (posted) "Posted. Tap to remove" else "Post to feed",
                    { posting = true; vm.postActivity(r, !posted) { ok -> posting = false; if (ok) posted = !posted else note = "Couldn't update the feed. Check your connection and the Firestore rules." } },
                    kind = if (posted) Kind.Outline else Kind.Primary, small = true, enabled = !posting,
                )
            }
            Gap(16)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn("Share", { shareCard = ShareCard.Move(r.title, r.distM, r.elapsedS, pace(r.sport, r.distM, r.elapsedS), m.hrAvg) }, kind = Kind.Outline, small = true)
                Btn("Delete", { confirmDelete = true }, kind = Kind.Quiet, small = true, tint = C.bad)
            }
        }
        shareCard?.let { ShareSheet(it, ui.sharer()) { shareCard = null } }
        if (confirmDelete) AlertDialog(
            onDismissRequest = { confirmDelete = false }, containerColor = C.surface,
            title = { Text("Delete this activity?", style = T.head) },
            text = { Text("The route and its stats are removed from this phone" + if (posted) ", and the post from your feed." else ".", style = T.label) },
            confirmButton = { Btn("Delete", { if (posted) vm.postActivity(r, false) {}; store.delete(id); onBack() }, kind = Kind.Quiet, small = true, tint = C.bad) },
            dismissButton = { Btn("Keep", { confirmDelete = false }, kind = Kind.Quiet, small = true) },
        )
    }
}

@Composable
private fun HrChart(r: Rec) {
    val hr = r.merged.hr
    val lo = hr.minOf { it.second }; val hi = hr.maxOf { it.second }
    val t0 = r.startMs; val span = (r.endMs - r.startMs).coerceAtLeast(1)
    val draw = remember(r.id) { Animatable(0f) }
    LaunchedEffect(r.id) { draw.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val heart = C.heart; val line = C.line
    Panel(pad = 14) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            for (i in 0..2) drawLine(line, Offset(0f, size.height * i / 2f), Offset(size.width, size.height * i / 2f), 1f)
            val path = Path(); var first = true
            hr.filter { it.first in t0..r.endMs }.forEach { (t, v) ->
                val x = ((t - t0).toFloat() / span) * size.width
                val y = size.height - ((v - lo).toFloat() / (hi - lo).coerceAtLeast(1)) * size.height
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            clipRect(right = size.width * draw.value) { drawPath(path, heart, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
        Gap(8)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caps("Low $lo bpm"); Caps("High $hi bpm")
        }
    }
}
