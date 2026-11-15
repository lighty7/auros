package com.aura.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aura.app.UiState
import com.aura.app.data.Aura
import com.aura.app.data.Earned
import com.aura.app.data.TodayItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Placeholder share: plain text. To be replaced by image cards. */
fun shareEarned(ctx: Context, earned: Earned) {
    val text = "${earned.title}: ${earned.detail} (${if (earned.points > 0) "+" else ""}${earned.points} aura)"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Today's four daily earnings with progress, directly under the halo. */
@Composable
fun TodayStrip(items: List<TodayItem>, streaks: Map<String, Int>, onLedger: () -> Unit) {
    Caps("Today")
    Gap(8)
    items.forEachIndexed { i, it ->
        if (i > 0) Gap(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it.title, style = T.head, modifier = Modifier.weight(1f))
            val s = streaks[it.kind] ?: 0
            if (s >= 2) { Caps("$s days"); GapW(10) }
            Text("+${it.points}", style = T.label.copy(color = if (it.earned) C.accent else C.muted))
        }
        Gap(4)
        Text("${it.current} / ${it.target}", style = T.label)
        Gap(6)
        Meter(it.progress, color = if (it.earned) C.accent else C.muted, height = 4)
    }
    Gap(14)
    Btn("How you earned it", onLedger, kind = Kind.Quiet, small = true)
}

private fun dayLabel(day: Long): String {
    val t = LocalDate.now().toEpochDay()
    return when (day) {
        t -> "Today"
        t - 1 -> "Yesterday"
        else -> LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

private val RULES = listOf(
    "Rested: sleep between 7h and 9h, +10.",
    "On your feet: 8,000 steps, +10; 12,000 steps, +15.",
    "Trained: 30 minutes of exercise, +10.",
    "Screen in check: 3h or less, judged when the day is over, +10.",
    "Full day: all four on the same day, +10 more.",
    "Streaks: 3, 7, 14 and 30 days in a row on one of them add +5, +15, +30 and +60.",
    "A missed day resets that streak.",
    "After two quiet days in a row, aura cools by 5 a day, never below the start of your level.",
    "Completed goals count too.",
)

@Composable
fun LedgerScreen(ui: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(true) }
    val level = Aura.level(ui.aura)
    ScreenFrame("How you earned it", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Caps("Aura")
            Text("${ui.aura}", style = T.display)
            Text("Level $level, ${Aura.levelName(level)}", style = T.label)
            Gap(16)
            Panel(onClick = { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caps("The rules", Modifier.weight(1f))
                    Caps(if (open) "Hide" else "Show", color = C.accent)
                }
                if (open) {
                    Gap(10)
                    RULES.forEach { Text(it, style = T.body); Gap(6) }
                }
            }
            Gap(20)
            if (ui.ledger.isEmpty()) Text("Nothing earned yet. Today's progress is on the Today tab.", style = T.label)
            ui.ledger.groupBy { it.epochDay }.forEach { (day, rows) ->
                Gap(12)
                Caps(dayLabel(day))
                Gap(4)
                rows.forEachIndexed { i, e ->
                    if (i > 0) Rule()
                    EarnedRow(e) { shareEarned(ctx, it) }
                }
            }
            Gap(24)
        }
    }
}

@Composable
private fun EarnedRow(e: Earned, onShare: (Earned) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = T.head)
            if (e.detail.isNotBlank()) Text(e.detail, style = T.label)
        }
        GapW(12)
        Text("${if (e.points > 0) "+" else ""}${e.points}", style = T.num.copy(color = if (e.points > 0) C.accent else C.muted))
        GapW(8)
        Btn("Share", { onShare(e) }, kind = Kind.Quiet, small = true)
    }
}
