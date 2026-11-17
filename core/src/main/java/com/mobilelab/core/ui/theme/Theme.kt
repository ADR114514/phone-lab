package com.mobilelab.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Field-instrument palette: dark, low glare, cyan accents. */
object LabColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A21)
    val SurfaceHigh = Color(0xFF1C242D)
    val Outline = Color(0xFF2E3A46)
    val Primary = Color(0xFF4CC2FF)
    val OnPrimary = Color(0xFF00202E)
    val Secondary = Color(0xFFB388FF)
    val Tertiary = Color(0xFFFFC266)
    val Error = Color(0xFFFF6B6B)
    val Success = Color(0xFF5BD6A0)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B9AA8)
    val Grid = Color(0xFF22303C)
}

private val scheme = darkColorScheme(
    primary = LabColors.Primary,
    onPrimary = LabColors.OnPrimary,
    primaryContainer = Color(0xFF123647),
    onPrimaryContainer = Color(0xFFBFE8FF),
    secondary = LabColors.Secondary,
    onSecondary = Color(0xFF1B1030),
    tertiary = LabColors.Tertiary,
    background = LabColors.Background,
    onBackground = LabColors.TextPrimary,
    surface = LabColors.Surface,
    onSurface = LabColors.TextPrimary,
    surfaceVariant = LabColors.SurfaceHigh,
    onSurfaceVariant = LabColors.TextSecondary,
    outline = LabColors.Outline,
    error = LabColors.Error,
    onError = Color(0xFF2A0505),
)

private val labTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

@Composable
fun LabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = labTypography,
        content = content,
    )
}
