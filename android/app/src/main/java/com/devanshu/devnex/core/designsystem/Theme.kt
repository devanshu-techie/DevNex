package com.devanshu.devnex.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.devanshu.devnex.domain.model.Priority
import com.devanshu.devnex.domain.model.TaskStatus

/**
 * Professional palette. Names are semantic roles kept from v1 so every screen keeps compiling:
 *   Violet = primary brand blue · Cyan = information/links · Lime = success · Amber = warning
 *   Red = danger · Magenta = attention (changes requested) · Mute = secondary text.
 * Values switch with the theme (snapshot state), so text stays readable in light and dark.
 */
@Immutable
data class Palette(
    val primary: Color, val primarySoft: Color, val info: Color, val success: Color, val warning: Color,
    val danger: Color, val attention: Color, val text: Color, val mute: Color, val line: Color,
    val surface: Color, val surfaceAlt: Color, val background: Color, val brandDeep: Color,
)

val LightPalette = Palette(
    primary = Color(0xFF1D4ED8), primarySoft = Color(0xFF3B5BDB), info = Color(0xFF0369A1), success = Color(0xFF15803D),
    warning = Color(0xFFB45309), danger = Color(0xFFB91C1C), attention = Color(0xFFC2410C), text = Color(0xFF0F172A),
    mute = Color(0xFF526075), line = Color(0xFFE2E8F0), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF1F5F9),
    background = Color(0xFFF6F8FB), brandDeep = Color(0xFF0B1F4D),
)
val DarkPalette = Palette(
    primary = Color(0xFF6E9BFF), primarySoft = Color(0xFF93B4FF), info = Color(0xFF5CC4F0), success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24), danger = Color(0xFFF87171), attention = Color(0xFFFB923C), text = Color(0xFFE6EAF2),
    mute = Color(0xFFA3AEC2), line = Color(0xFF2A3447), surface = Color(0xFF151C2B), surfaceAlt = Color(0xFF1C2537),
    background = Color(0xFF0E1420), brandDeep = Color(0xFF0B1F4D),
)

object Nx {
    var palette by mutableStateOf(LightPalette)

    val Violet get() = palette.primary
    val VioletSoft get() = palette.primarySoft
    val Cyan get() = palette.info
    val Lime get() = palette.success
    val Amber get() = palette.warning
    val Red get() = palette.danger
    val Magenta get() = palette.attention
    val Mute get() = palette.mute
    val Line get() = palette.line
    val Ink get() = palette.text
    val Void get() = palette.background
    val Abyss get() = palette.surface
    val Deep get() = palette.surfaceAlt
    val Brand get() = palette.brandDeep
    /** Kept as a Brush for call sites that paint backgrounds; now a calm solid brand colour. */
    val Signature: Brush get() = SolidColor(palette.primary)
    val Hot: Brush get() = SolidColor(palette.attention)
}

@Immutable
data class NxColors(val glass: Color, val glassBorder: Color, val mute: Color, val line: Color, val dark: Boolean)

val LocalNx = staticCompositionLocalOf { NxColors(LightPalette.surface, LightPalette.line, LightPalette.mute, LightPalette.line, false) }

val Mono = FontFamily.Monospace

private val NxType = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun DevNexTheme(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val p = if (dark) DarkPalette else LightPalette
    if (Nx.palette != p) Nx.palette = p     // written before any child reads it

    val scheme = if (dark) darkColorScheme(
        primary = p.primary, onPrimary = Color(0xFF0B1220), secondary = p.info, onSecondary = Color(0xFF0B1220), tertiary = p.attention,
        background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.mute, outline = p.line, outlineVariant = p.line, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceAlt, surfaceContainerLow = p.surface,
        secondaryContainer = p.primary.copy(alpha = .22f), onSecondaryContainer = p.text,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = Color.White, secondary = p.info, onSecondary = Color.White, tertiary = p.attention,
        background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceAlt, onSurfaceVariant = p.mute, outline = Color(0xFFCBD5E1), outlineVariant = p.line, error = p.danger,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceAlt, surfaceContainerLow = p.surface,
        secondaryContainer = Color(0xFFDCE6FF), onSecondaryContainer = Color(0xFF0B1F4D),
    )

    // Dark status/navigation-bar icons on the light theme, light icons on the dark theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.decorView.setBackgroundColor(p.background.toArgb())
        }
    }

    val nx = NxColors(glass = p.surface, glassBorder = p.line, mute = p.mute, line = p.line, dark = dark)
    CompositionLocalProvider(LocalNx provides nx) {
        MaterialTheme(colorScheme = scheme, typography = NxType) {
            // Default text/icon colour for everything not inside a Surface — keeps text readable in both themes.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

fun TaskStatus.color(): Color = when (this) {
    TaskStatus.INBOX, TaskStatus.EVALUATION -> Nx.Mute
    TaskStatus.OPEN -> Nx.Cyan
    TaskStatus.ASSIGNED -> Nx.VioletSoft
    TaskStatus.IN_PROGRESS -> Nx.Violet
    TaskStatus.BLOCKED -> Nx.Red
    TaskStatus.SUBMITTED -> Nx.Amber
    TaskStatus.CHANGES_REQUESTED -> Nx.Magenta
    TaskStatus.APPROVED -> Nx.Lime
    TaskStatus.PUBLISHED -> Nx.Lime
    TaskStatus.CANCELLED -> Nx.Mute
}

fun Priority.color(): Color = when (this) {
    Priority.CRITICAL -> Nx.Red; Priority.HIGH -> Nx.Amber; Priority.NORMAL -> Nx.Cyan; Priority.LOW -> Nx.Mute
}
