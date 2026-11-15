package com.aura.app.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aura.app.R
import com.aura.app.data.Aura
import com.aura.app.data.EarnRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Who the card is for. No ids, no location. */
data class Sharer(val name: String, val occupation: String, val aura: Int)

/** What a share card shows. */
sealed class ShareCard {
    data class Entry(val title: String, val detail: String, val points: Int) : ShareCard()
    data class Streak(val kind: String, val days: Int) : ShareCard()
    data class GoalDone(val title: String, val detail: String, val points: Int) : ShareCard()
    data class Move(val sport: String, val distM: Double, val elapsedS: Int, val pace: String, val hrAvg: Int) : ShareCard()
    object Level : ShareCard()
}

private class Parts(val big: String, val title: String, val detail: String, val caption: String)

private fun parts(c: ShareCard, who: Sharer): Parts {
    val lvl = Aura.level(who.aura)
    return when (c) {
        is ShareCard.Entry -> Parts(
            (if (c.points > 0) "+" else "") + c.points, c.title, c.detail,
            "${c.title}, +${c.points} aura on Aura.",
        )
        is ShareCard.Streak -> Parts(
            "${c.days}-day streak", EarnRules.title(c.kind), "${EarnRules.title(c.kind)} ${c.days} days in a row",
            "${c.days} days in a row: ${EarnRules.streakWord(c.kind)}. On Aura.",
        )
        is ShareCard.GoalDone -> Parts("+${c.points}", c.title, c.detail, "Goal completed: ${c.title}. +${c.points} aura on Aura.")
        is ShareCard.Move -> Parts(
            "${km(c.distM)} km", c.sport,
            listOf(clock(c.elapsedS), c.pace).filter { it.isNotBlank() }.joinToString("  ·  ") + if (c.hrAvg > 0) "  ·  ${c.hrAvg} bpm" else "",
            "${c.sport}: ${km(c.distM)} km in ${clock(c.elapsedS)}. On Aura.",
        )
        ShareCard.Level -> Parts("${who.aura}", "Level $lvl, ${Aura.levelName(lvl)}", "${Aura.levelStart(lvl + 1) - who.aura} aura to level ${lvl + 1}", "Level $lvl on Aura, ${who.aura} aura.")
    }
}

fun cardCaption(c: ShareCard, who: Sharer) = parts(c, who).caption

private const val W = 1080
private const val H = 1920
private const val BG = 0xFF12110E.toInt()
private const val BONE = 0xFFF1EBDD.toInt()
private const val ACCENT = 0xFFFF6A3D.toInt()
private const val MUTED = 0xFF9B9484.toInt()
private const val LINE = 0xFF322E26.toInt()

/** Draws the 1080x1920 story card. Never includes routes or places. */
fun renderCard(ctx: Context, card: ShareCard, who: Sharer): Bitmap {
    val p = parts(card, who)
    val tf = runCatching { ctx.resources.getFont(R.font.space_grotesk) }.getOrDefault(Typeface.DEFAULT)
    val medium = Typeface.create(tf, 500, false)
    val semi = Typeface.create(tf, 600, false)
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    cv.drawColor(BG)

    fun tp(color: Int, size: Float, face: Typeface, spacing: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = face; letterSpacing = spacing }

    // wordmark
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    cv.drawCircle(96f, 118f, 14f, dot)
    cv.drawText("aura", 128f, 138f, tp(BONE, 60f, medium, -0.02f))

    // halo: a ring of ticks, a track and an accent arc that fills with progress to the next level
    val cx = W / 2f; val cy = 800f; val r = 360f
    val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 4f; strokeCap = Paint.Cap.ROUND }
    for (i in 0 until 96) {
        val a = Math.toRadians(i * 360.0 / 96 - 90)
        val k = i / 96f
        tick.color = (0xF2D86B.toInt() and 0xFFFFFF) or ((15 + 200 * k * k).toInt().coerceIn(0, 255) shl 24)
        val r0 = r + 44f; val r1 = r0 + 22f
        cv.drawLine(cx + cos(a).toFloat() * r0, cy + sin(a).toFloat() * r0, cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, tick)
    }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 26f; strokeCap = Paint.Cap.ROUND; color = LINE }
    val box = RectF(cx - r, cy - r, cx + r, cy + r)
    cv.drawArc(box, 135f, 270f, false, ring)
    val lvl = Aura.level(who.aura)
    val from = Aura.levelStart(lvl); val to = Aura.levelStart(lvl + 1)
    val frac = ((who.aura - from).toFloat() / (to - from)).coerceIn(0.03f, 1f)
    ring.color = ACCENT
    cv.drawArc(box, 135f, 270f * frac, false, ring)

    // headline, fitted inside the ring
    val big = tp(BONE, 300f, medium, -0.04f)
    val maxW = 600f
    while (big.measureText(p.big) > maxW && big.textSize > 70f) big.textSize -= 6f
    val bw = big.measureText(p.big)
    cv.drawText(p.big, cx - bw / 2, cy + big.textSize * 0.34f, big)

    // title and detail
    fun block(text: String, paint: TextPaint, top: Float, lines: Int): Float {
        if (text.isBlank()) return top
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint, 920)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()
        cv.save(); cv.translate(W / 2f - 460f, top); l.draw(cv); cv.restore()
        return top + l.height
    }
    val y1 = block(p.title, tp(BONE, 84f, semi, -0.02f), 1290f, 2)
    block(p.detail, tp(MUTED, 46f, tf), y1 + 28f, 2)

    // footer: who, level, total
    val rule = Paint().apply { color = LINE; strokeWidth = 3f }
    cv.drawLine(96f, 1650f, W - 96f, 1650f, rule)
    val name = TextUtils.ellipsize(who.name.ifBlank { "Aura" }, tp(BONE, 64f, semi), 888f, TextUtils.TruncateAt.END).toString()
    cv.drawText(name, 96f, 1735f, tp(BONE, 64f, semi, -0.01f))
    cv.drawText("Level $lvl  ·  ${Aura.levelName(lvl)}", 96f, 1800f, tp(MUTED, 42f, tf))
    val tot = tp(ACCENT, 84f, medium, -0.02f)
    val ts = "${who.aura}"
    cv.drawText(ts, W - 96f - tot.measureText(ts), 1735f, tot)
    val al = tp(MUTED, 32f, semi, 0.12f)
    cv.drawText("AURA", W - 96f - al.measureText("AURA"), 1800f, al)
    return bmp
}

/** Writes the PNG under cache/share and returns a FileProvider uri for it. */
fun saveCard(ctx: Context, bmp: Bitmap): Uri {
    val dir = File(ctx.cacheDir, "share").also { it.mkdirs() }
    dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 3_600_000L }?.forEach { it.delete() }
    val f = File(dir, "aura-${System.currentTimeMillis()}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
}

/** Saves to Pictures/Aura in the gallery. */
fun saveToGallery(ctx: Context, bmp: Bitmap): Boolean = runCatching {
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "aura-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aura")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
}.getOrDefault(false)

private const val WHATSAPP = "com.whatsapp"
private const val INSTAGRAM = "com.instagram.android"
private const val SNAPCHAT = "com.snapchat.android"

private fun installed(ctx: Context, pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

private fun launch(ctx: Context, i: Intent, uri: Uri, pkg: String?, fallback: () -> Unit) {
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (pkg != null) runCatching { ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    try { ctx.startActivity(i) } catch (_: Exception) { fallback() }
}

private fun chooser(ctx: Context, uri: Uri, caption: String) {
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) {}
}

private fun toWhatsApp(ctx: Context, uri: Uri, caption: String) = launch(
    ctx, Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, caption).setPackage(WHATSAPP),
    uri, WHATSAPP,
) { chooser(ctx, uri, caption) }

private fun toInstagram(ctx: Context, uri: Uri, caption: String) {
    val story = Intent("com.instagram.share.ADD_TO_STORY").setDataAndType(uri, "image/png").setPackage(INSTAGRAM)
        .putExtra("source_application", ctx.packageName)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).setPackage(INSTAGRAM)
    val storyOk = ctx.packageManager.queryIntentActivities(story, 0).isNotEmpty()
    launch(ctx, if (storyOk) story else send, uri, INSTAGRAM) {
        if (storyOk) launch(ctx, send, uri, INSTAGRAM) { chooser(ctx, uri, caption) } else chooser(ctx, uri, caption)
    }
}

/** Image only, no text: with text Snapchat sends a chat message instead of opening its send-to screen. */
private fun toSnapchat(ctx: Context, uri: Uri, caption: String) = launch(
    ctx, Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).setPackage(SNAPCHAT),
    uri, SNAPCHAT,
) { chooser(ctx, uri, caption) }

/** The card preview and a row of targets. Only installed apps are listed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(card: ShareCard, who: Sharer, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var saved by remember { mutableStateOf(false) }
    val caption = remember(card, who) { cardCaption(card, who) }
    val pop = remember { Animatable(0.92f) }
    LaunchedEffect(card, who) {
        val b = withContext(Dispatchers.Default) { renderCard(ctx, card, who) }
        uri = withContext(Dispatchers.IO) { saveCard(ctx, b) }
        bmp = b
        pop.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 200.dp, height = 356.dp), contentAlignment = Alignment.Center) {
                bmp?.let {
                    Image(
                        it.asImageBitmap(), "Share card", contentScale = ContentScale.Fit,
                        modifier = Modifier.size(width = 200.dp, height = 356.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.92f) / 0.08f).coerceIn(0f, 1f) }
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
            Gap(18)
            val u = uri
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed(ctx, WHATSAPP)) Btn("WhatsApp", { u?.let { toWhatsApp(ctx, it, caption); onDismiss() } }, small = true, enabled = u != null)
                if (installed(ctx, INSTAGRAM)) Btn("Instagram", { u?.let { toInstagram(ctx, it, caption); onDismiss() } }, small = true, enabled = u != null)
                if (installed(ctx, SNAPCHAT)) Btn("Snapchat", { u?.let { toSnapchat(ctx, it, caption); onDismiss() } }, small = true, enabled = u != null)
                Btn("More", { u?.let { chooser(ctx, it, caption); onDismiss() } }, kind = Kind.Outline, small = true, enabled = u != null)
                Btn(if (saved) "Saved" else "Save", { bmp?.let { saved = saveToGallery(ctx, it) } }, kind = Kind.Outline, small = true, enabled = bmp != null && !saved)
            }
        }
    }
}
