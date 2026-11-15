package io.github.jeiel85.findmywifephone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * "Warm home" identity: cream paper, ink text, one loud coral for the thing you came to do
 * (make it ring), indigo for location, sage for "found", amber for "not accepting requests".
 * Same hex values as the landing page (docs/assets/site.css).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSoft: Color,
    val ink: Color,
    val inkSoft: Color,
    val hairline: Color,
    val coral: Color,
    val coralDeep: Color,
    val onCoral: Color,
    val indigo: Color,
    val sage: Color,
    val amber: Color,
    val danger: Color,
)

private val Light = Palette(
    dark = false,
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFFFFFF),
    surfaceSoft = Color(0xFFF4EEE6),
    ink = Color(0xFF1F2235),
    inkSoft = Color(0xFF5F6375),   // 5.9:1 on cream
    hairline = Color(0xFFE9E1D6),
    coral = Color(0xFFFF6B5B),
    coralDeep = Color(0xFFE04A3A),
    onCoral = Color(0xFFFFFFFF),
    indigo = Color(0xFF4B5BDB),
    sage = Color(0xFF23936B),
    amber = Color(0xFFC77D0A),
    danger = Color(0xFFC8372D),
)

private val Dark = Palette(
    dark = true,
    background = Color(0xFF13141B),
    surface = Color(0xFF1D1F29),
    surfaceSoft = Color(0xFF262935),
    ink = Color(0xFFF4F1EC),
    inkSoft = Color(0xFFA9ACBC),
    hairline = Color(0xFF2E3140),
    coral = Color(0xFFFF7A6B),
    coralDeep = Color(0xFFFF5A48),
    onCoral = Color(0xFF1B0B08),
    indigo = Color(0xFF8C98FF),
    sage = Color(0xFF4CC797),
    amber = Color(0xFFF2B54A),
    danger = Color(0xFFFF7B70),
)

val LocalPalette = staticCompositionLocalOf { Light }

object P {
    val c: Palette @Composable get() = LocalPalette.current
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.coral, onPrimary = p.onCoral, secondary = p.indigo, background = p.background,
            surface = p.surface, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfaceSoft,
            onSurfaceVariant = p.inkSoft, outline = p.hairline, error = p.danger)
    } else {
        lightColorScheme(primary = p.coral, onPrimary = p.onCoral, secondary = p.indigo, background = p.background,
            surface = p.surface, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfaceSoft,
            onSurfaceVariant = p.inkSoft, outline = p.hairline, error = p.danger)
    }
    val base = Typography()
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
