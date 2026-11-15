package com.aura.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aura.app.R

/**
 * Palette: warm paper and ink with one signal colour (vermilion). Data has its own fixed colours:
 * heart = cherry, screen = cobalt, good = forest. Follows the phone's light or dark mode.
 */
object C {
    var dark by mutableStateOf(true)

    val bg get() = if (dark) Color(0xFF12110E) else Color(0xFFF4EFE6)
    val surface get() = if (dark) Color(0xFF1A1814) else Color(0xFFFBF8F1)
    val raised get() = if (dark) Color(0xFF26231D) else Color(0xFFEAE3D5)
    val line get() = if (dark) Color(0xFF322E26) else Color(0xFFDDD4C3)
    val ink get() = if (dark) Color(0xFFF1EBDD) else Color(0xFF17150F)
    val muted get() = if (dark) Color(0xFF9B9484) else Color(0xFF6E6759)
    val accent get() = if (dark) Color(0xFFFF6A3D) else Color(0xFFE2491F)
    val onAccent get() = Color(0xFF17150F)
    val butter get() = if (dark) Color(0xFFF2D86B) else Color(0xFFDDA92A)
    val heart get() = if (dark) Color(0xFFFF6B62) else Color(0xFFC8322B)
    val screen get() = if (dark) Color(0xFF7FA6FF) else Color(0xFF2F5BD8)
    val good get() = if (dark) Color(0xFF7FD1A0) else Color(0xFF2F6B4F)
    val bad get() = heart
    /** sleep: a deep sea-teal, so it reads apart from screen-blue and heart-red */
    val sleep get() = if (dark) Color(0xFF4FB3A9) else Color(0xFF1F6F68)
}

private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Space Grotesk (variable): squared-off numerals and a slightly technical edge, for numbers and headlines. */
val Grotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))

/** Type scale: Space Grotesk for numbers and titles, the system sans for everything you read, tracked capitals for labels. */
object T {
    val display get() = TextStyle(color = C.ink, fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 72.sp, lineHeight = 74.sp, letterSpacing = (-3).sp)
    val title get() = TextStyle(color = C.ink, fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp)
    val num get() = TextStyle(color = C.ink, fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.5).sp)
    val head get() = TextStyle(color = C.ink, fontFamily = Grotesk, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body get() = TextStyle(color = C.ink, fontSize = 15.sp, lineHeight = 22.sp)
    val label get() = TextStyle(color = C.muted, fontSize = 13.sp, lineHeight = 19.sp)
    val caps get() = TextStyle(color = C.muted, fontFamily = Grotesk, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    if (C.dark != dark) C.dark = dark
    val scheme = if (dark) darkColorScheme(
        primary = C.ink, onPrimary = C.bg, secondary = C.accent, onSecondary = C.onAccent, background = C.bg, onBackground = C.ink,
        surface = C.surface, onSurface = C.ink, surfaceVariant = C.raised, onSurfaceVariant = C.muted, outline = C.line, error = C.bad,
    ) else lightColorScheme(
        primary = C.ink, onPrimary = C.bg, secondary = C.accent, onSecondary = C.onAccent, background = C.bg, onBackground = C.ink,
        surface = C.surface, onSurface = C.ink, surfaceVariant = C.raised, onSurfaceVariant = C.muted, outline = C.line, error = C.bad,
    )
    MaterialTheme(colorScheme = scheme) { CompositionLocalProvider(LocalContentColor provides C.ink, content = content) }
}
