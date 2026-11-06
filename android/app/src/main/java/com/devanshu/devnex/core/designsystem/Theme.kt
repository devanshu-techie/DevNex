package com.devanshu.devnex.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.devanshu.devnex.domain.model.Priority
import com.devanshu.devnex.domain.model.TaskStatus

object Nx {
    val Void = Color(0xFF070816)
    val Abyss = Color(0xFF0D1028)
    val Deep = Color(0xFF141836)
    val Line = Color(0xFF262B55)
    val Violet = Color(0xFF7C5CFF)
    val VioletSoft = Color(0xFFA594FF)
    val Cyan = Color(0xFF22D3EE)
    val Magenta = Color(0xFFF472B6)
    val Lime = Color(0xFFA3E635)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF87171)
    val Ink = Color(0xFFE8EAFF)
    val Mute = Color(0xFF8A90C2)

    val Signature = Brush.linearGradient(listOf(Violet, Cyan))
    val Hot = Brush.linearGradient(listOf(Magenta, Violet))
}

@Immutable
data class NxColors(
    val glass: Color, val glassBorder: Color, val mute: Color, val line: Color, val dark: Boolean,
)

val LocalNx = staticCompositionLocalOf { NxColors(Color(0x14FFFFFF), Color(0x22FFFFFF), Nx.Mute, Nx.Line, true) }

private val DarkScheme = darkColorScheme(
    primary = Nx.Violet, onPrimary = Color.White, secondary = Nx.Cyan, onSecondary = Nx.Void,
    tertiary = Nx.Magenta, background = Nx.Void, onBackground = Nx.Ink, surface = Nx.Abyss, onSurface = Nx.Ink,
    surfaceVariant = Nx.Deep, onSurfaceVariant = Nx.Mute, outline = Nx.Line, error = Nx.Red,
    surfaceContainer = Nx.Abyss, surfaceContainerHigh = Nx.Deep,
)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B3DF5), onPrimary = Color.White, secondary = Color(0xFF0891B2), tertiary = Color(0xFFDB2777),
    background = Color(0xFFF5F6FC), onBackground = Color(0xFF12142B), surface = Color.White, onSurface = Color(0xFF12142B),
    surfaceVariant = Color(0xFFECEEF8), onSurfaceVariant = Color(0xFF5A5F86), outline = Color(0xFFD8DBEE), error = Color(0xFFDC2626),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F1F9),
)

val Mono = FontFamily.Monospace

private val NxType = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Black, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.2.sp),
)

enum class ThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun DevNexTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val nx = if (dark) NxColors(Color(0x12FFFFFF), Color(0x26FFFFFF), Nx.Mute, Nx.Line, true)
             else NxColors(Color(0xCCFFFFFF), Color(0x1A12142B), Color(0xFF5A5F86), Color(0xFFD8DBEE), false)
    CompositionLocalProvider(LocalNx provides nx) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = NxType, content = content)
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
    TaskStatus.PUBLISHED -> Color(0xFF34D399)
    TaskStatus.CANCELLED -> Nx.Line
}

fun Priority.color(): Color = when (this) {
    Priority.CRITICAL -> Nx.Red; Priority.HIGH -> Nx.Amber; Priority.NORMAL -> Nx.Cyan; Priority.LOW -> Nx.Mute
}
