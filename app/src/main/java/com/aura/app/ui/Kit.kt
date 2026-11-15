package com.aura.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ---------- spacing and small marks ----------

@Composable fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))
@Composable fun GapW(w: Int = 8) = Spacer(Modifier.width(w.dp))

/** A tracked, capitalised label: section names and the captions under numbers. */
@Composable
fun Caps(text: String, modifier: Modifier = Modifier, color: Color = C.muted) =
    Text(text.uppercase(), style = T.caps.copy(color = color), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable fun Rule(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(C.line))
@Composable fun Dot(color: Color, size: Int = 8) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

// ---------- motion ----------

/** Shrinks slightly while pressed: the touch feedback used instead of a ripple. */
@Composable
fun Modifier.press(src: MutableInteractionSource): Modifier {
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Content fades and rises into place; [index] staggers a list. */
@Composable
fun Reveal(index: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index.coerceAtMost(8) * 55L); a.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
    Column(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 22.dp.toPx() }, content = content)
}

/** A number that counts up to [target]. */
@Composable
fun countUp(target: Int): Int {
    val a = remember { Animatable(0f) }
    LaunchedEffect(target) { a.animateTo(target.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
    return a.value.roundToInt()
}

/** A thin bar that fills to [fraction]. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = C.accent, height: Int = 6) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(fraction) { a.animateTo(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing)) }
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(50)).background(C.line)) {
        Box(Modifier.fillMaxWidth(a.value).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

// ---------- surfaces ----------

/** A hairline-bordered surface. With [onClick] the whole panel is a button. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, pad: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    var m = modifier.fillMaxWidth()
    if (onClick != null) m = m.press(src)
    m = m.clip(shape).background(C.surface).border(1.dp, C.line, shape)
    if (onClick != null) m = m.clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
    Column(m.padding(pad.dp), content = content)
}

// ---------- controls ----------

enum class Kind { Primary, Accent, Outline, Quiet }

@Composable
fun Btn(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: Kind = Kind.Primary,
    enabled: Boolean = true, small: Boolean = false, tint: Color? = null, flush: Boolean = false,
) {
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (small) 11.dp else 14.dp)
    val bg = when (kind) { Kind.Primary -> C.ink; Kind.Accent -> C.accent; else -> Color.Transparent }
    val fg = tint ?: when (kind) { Kind.Primary -> C.bg; Kind.Accent -> C.onAccent; Kind.Outline -> C.ink; Kind.Quiet -> C.muted }
    Box(
        modifier.press(src).alpha(if (enabled) 1f else 0.4f).clip(shape).background(bg)
            .then(if (kind == Kind.Outline) Modifier.border(1.dp, C.ink.copy(alpha = 0.3f), shape) else Modifier)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = if (small) 44.dp else 54.dp)
            .padding(horizontal = if (flush) 0.dp else if (small) 16.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.head.copy(color = fg, fontSize = if (small) 14.sp else 16.sp), maxLines = 1) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) C.ink else Color.Transparent, label = "chip")
    val fg by animateColorAsState(if (selected) C.bg else C.ink, label = "chipText")
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(bg).border(1.dp, if (selected) C.ink else C.line, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = 44.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = T.body.copy(color = fg, fontSize = 14.sp)) }
}

/** A segmented switch: one of a few options, equal widths. */
@Composable
fun <K> Seg(options: List<Pair<K, String>>, selected: K, modifier: Modifier = Modifier, onPick: (K) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(C.raised).padding(3.dp)) {
        options.forEach { (k, label) ->
            val on = k == selected
            val bg by animateColorAsState(if (on) C.surface else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(bg)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onPick(k) }
                    .defaultMinSize(minHeight = 42.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, maxLines = 1, style = T.body.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.ink else C.muted))
            }
        }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    OutlinedTextField(
        value, onChange, modifier = modifier, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp), textStyle = T.body,
        keyboardOptions = KeyboardOptions(imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.ink, unfocusedBorderColor = C.line, focusedLabelColor = C.ink, unfocusedLabelColor = C.muted,
            cursorColor = C.accent, focusedTextColor = C.ink, unfocusedTextColor = C.ink,
        ),
    )
}

enum class Glyph { Minus, Plus, Prev, Next, Back }

/** A round button with a drawn mark. [label] is what a screen reader says. */
@Composable
fun IconBtn(glyph: Glyph, label: String, onClick: () -> Unit, enabled: Boolean = true, bordered: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val ink = C.ink
    Box(
        Modifier.size(48.dp).press(src).alpha(if (enabled) 1f else 0.3f).clip(CircleShape)
            .then(if (bordered) Modifier.border(1.dp, C.line, CircleShape) else Modifier)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width; val h = size.height; val sw = 1.8.dp.toPx()
            fun l(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(ink, Offset(x0 * w, y0 * h), Offset(x1 * w, y1 * h), sw, StrokeCap.Round)
            when (glyph) {
                Glyph.Minus -> l(0.1f, 0.5f, 0.9f, 0.5f)
                Glyph.Plus -> { l(0.1f, 0.5f, 0.9f, 0.5f); l(0.5f, 0.1f, 0.5f, 0.9f) }
                Glyph.Prev -> { l(0.65f, 0.1f, 0.25f, 0.5f); l(0.25f, 0.5f, 0.65f, 0.9f) }
                Glyph.Next -> { l(0.35f, 0.1f, 0.75f, 0.5f); l(0.75f, 0.5f, 0.35f, 0.9f) }
                Glyph.Back -> { l(0.05f, 0.5f, 0.95f, 0.5f); l(0.05f, 0.5f, 0.42f, 0.14f); l(0.05f, 0.5f, 0.42f, 0.86f) }
            }
        }
    }
}

@Composable
fun Stepper(label: String, value: String, unit: String, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Caps(label)
            Gap(4)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = T.num)
                if (unit.isNotBlank()) Text(" $unit", style = T.label, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        IconBtn(Glyph.Minus, "Less", minus)
        GapW(10)
        IconBtn(Glyph.Plus, "More", plus)
    }
}

// ---------- numbers ----------

class Stat(val label: String, val value: String, val unit: String = "")

/** A ruled table of numbers. Columns adapt to the width: two or three on a phone, one row on a tablet. */
@Composable
fun StatGrid(items: List<Stat>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = when {
            maxWidth >= 560.dp -> minOf(items.size, 6)
            maxWidth < 300.dp || items.size % 3 != 0 -> 2
            else -> 3
        }
        val shape = RoundedCornerShape(18.dp)
        Column(Modifier.clip(shape).background(C.surface).border(1.dp, C.line, shape)) {
            items.chunked(cols).forEachIndexed { r, row ->
                if (r > 0) Rule()
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.forEachIndexed { i, s ->
                        if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 14.dp)) {
                            Caps(s.label)
                            Gap(6)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(s.value, style = T.num, maxLines = 1, softWrap = false)
                                if (s.unit.isNotBlank()) Text(" ${s.unit}", style = T.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------- full-screen page ----------

@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Glyph.Back, "Back", onBack, bordered = false)
            GapW(4)
            Caps(title, color = C.ink)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize(), content = content)
        }
    }
}
