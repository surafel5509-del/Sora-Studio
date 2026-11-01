package com.sorastudio.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** SORA Studio brand palette (dark-first, premium, minimal). */
object SoraPalette {
    val Background = Color(0xFF101114)
    val BackgroundSecondary = Color(0xFF191B20)
    val Surface = Color(0xFF22252B)
    val SurfaceElevated = Color(0xFF2A2D34)
    val BorderSubtle = Color(0xFF33373F)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9297A1)
    val Accent = Color(0xFFC5A7FF)
    val AccentSecondary = Color(0xFFB8C4D9)
    val Success = Color(0xFF72C99A)
    val Warning = Color(0xFFE9B96E)
    val Error = Color(0xFFEB7A7A)
    val Playhead = Color(0xFFC5A7FF)
    val Record = Color(0xFFE55B5B)

    // Light counterparts
    val LightBackground = Color(0xFFF3F4F7)
    val LightBackgroundSecondary = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFDDDFE5)
    val LightText = Color(0xFF17181C)
    val LightTextSecondary = Color(0xFF6B7078)
    val LightAccent = Color(0xFF7B5CD6)
}

private val DarkColors = darkColorScheme(
    primary = SoraPalette.Accent,
    onPrimary = Color(0xFF1D1430),
    secondary = SoraPalette.AccentSecondary,
    onSecondary = Color(0xFF101114),
    background = SoraPalette.Background,
    onBackground = SoraPalette.TextPrimary,
    surface = SoraPalette.BackgroundSecondary,
    onSurface = SoraPalette.TextPrimary,
    surfaceVariant = SoraPalette.Surface,
    onSurfaceVariant = SoraPalette.TextSecondary,
    outline = SoraPalette.BorderSubtle,
    error = SoraPalette.Error,
    onError = Color(0xFF101114),
)

private val LightColors = lightColorScheme(
    primary = SoraPalette.LightAccent,
    onPrimary = Color.White,
    secondary = Color(0xFF5B6B82),
    onSecondary = Color.White,
    background = SoraPalette.LightBackground,
    onBackground = SoraPalette.LightText,
    surface = SoraPalette.LightBackgroundSecondary,
    onSurface = SoraPalette.LightText,
    surfaceVariant = Color(0xFFECEEF2),
    onSurfaceVariant = SoraPalette.LightTextSecondary,
    outline = SoraPalette.LightBorder,
    error = Color(0xFFC62828),
    onError = Color.White,
)

private val SoraTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.5.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

@Composable
fun SoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SoraTypography,
        content = content
    )
}

/** Long ARGB (project model) -> Compose Color, and back. */
fun Long.toComposeColor(): Color = Color(this)
fun Color.toArgbLong(): Long = value.toLong()
