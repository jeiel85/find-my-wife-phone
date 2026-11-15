package io.github.jeiel85.findmywifephone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * "Living room at evening" — the same identity as the landing page (docs/assets/site.css):
 * a periwinkle wall, the indigo sofa, one loud coral for the thing you came to do (make it
 * ring), lamp butter for "not accepting requests", sage for "found". Dark mode is the same room
 * with the lights off and the lamp on.
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val wallTop: Color,
    val wallBottom: Color,
    val surface: Color,
    val surfaceSoft: Color,
    val ink: Color,
    val inkSoft: Color,
    val hairline: Color,
    val coral: Color,
    val coralDeep: Color,
    val coralGlow: Color,
    val onCoral: Color,
    val sofa: Color,
    val sofaSoft: Color,
    val butter: Color,
    val sage: Color,
    val amber: Color,
    val danger: Color,
) {
    val background get() = wallTop
    val indigo get() = sofa
    val wall: Brush get() = Brush.verticalGradient(listOf(wallTop, wallBottom))
}

private val Light = Palette(
    dark = false,
    wallTop = Color(0xFFE6EAFF),
    wallBottom = Color(0xFFF7F8FF),
    surface = Color(0xFFFFFFFF),
    surfaceSoft = Color(0xFFEEF1FF),
    ink = Color(0xFF1F2235),
    inkSoft = Color(0xFF555B78),     // 6.6:1 on white
    hairline = Color(0xFFD9DEF7),
    coral = Color(0xFFFF6B5B),
    coralDeep = Color(0xFFE04A3A),
    coralGlow = Color(0x66FF6B5B),
    onCoral = Color(0xFFFFFFFF),
    sofa = Color(0xFF3340A8),
    sofaSoft = Color(0xFF9AA6FF),
    butter = Color(0xFFFFD66B),
    sage = Color(0xFF1E8A60),
    amber = Color(0xFFB86E00),
    danger = Color(0xFFC8372D),
)

private val Dark = Palette(
    dark = true,
    wallTop = Color(0xFF151832),
    wallBottom = Color(0xFF0C0D1C),
    surface = Color(0xFF1C1F3A),
    surfaceSoft = Color(0xFF252947),
    ink = Color(0xFFF2F2FA),
    inkSoft = Color(0xFFA8ADCF),
    hairline = Color(0xFF2E3357),
    coral = Color(0xFFFF7A6B),
    coralDeep = Color(0xFFFF5A48),
    coralGlow = Color(0x80FF6B5B),
    onCoral = Color(0xFFFFFFFF),
    sofa = Color(0xFF8C98FF),
    sofaSoft = Color(0xFF5A66D6),
    butter = Color(0xFFFFD66B),
    sage = Color(0xFF52D19E),
    amber = Color(0xFFFFC85A),
    danger = Color(0xFFFF8277),
)

val LocalPalette = staticCompositionLocalOf { Light }

object P {
    val c: Palette @Composable get() = LocalPalette.current
}

/** The theme setting wins over the system's dark mode. */
@Composable
fun isAppDark(): Boolean {
    val settings by Hub.store.settings.collectAsStateWithLifecycle()
    return when (settings.theme) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val p = if (isAppDark()) Dark else Light
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.coral, onPrimary = p.onCoral, secondary = p.sofa, background = p.wallTop,
            surface = p.surface, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfaceSoft,
            onSurfaceVariant = p.inkSoft, outline = p.hairline, error = p.danger, surfaceContainerLow = p.surface)
    } else {
        lightColorScheme(primary = p.coral, onPrimary = p.onCoral, secondary = p.sofa, background = p.wallTop,
            surface = p.surface, onSurface = p.ink, onBackground = p.ink, surfaceVariant = p.surfaceSoft,
            onSurfaceVariant = p.inkSoft, outline = p.hairline, error = p.danger, surfaceContainerLow = p.surface)
    }
    val base = Typography()
    val type = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp, lineHeight = 44.sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.8).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
