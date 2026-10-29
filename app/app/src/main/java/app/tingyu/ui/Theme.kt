package app.tingyu.ui

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

/** Canonical palette: keep DESIGN.md in sync when changing these values. */
object TingYuColors {
    val Blue = Color(0xFF3F5D96)
    val Background = Color(0xFFF5F7FC)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF17243A)
    val Muted = Color(0xFF59677C)
    val SoftBlue = Color(0xFFE3EAF7)
    val Outline = Color(0xFFCED7E6)
    val Error = Color(0xFFA32F38)
    val PlayerSurface = Color(0xFF24354F)
    val PlayerMuted = Color(0xFFB7C6DF)
    val PlayerAccent = Color(0xFFC2D5FF)
    val PlayerError = Color(0xFFFFB4AB)
}

private val LightColors = lightColorScheme(
    primary = TingYuColors.Blue,
    onPrimary = TingYuColors.Surface,
    primaryContainer = TingYuColors.SoftBlue,
    onPrimaryContainer = TingYuColors.Ink,
    secondary = TingYuColors.Blue,
    secondaryContainer = TingYuColors.SoftBlue,
    onSecondaryContainer = TingYuColors.Ink,
    background = TingYuColors.Background,
    onBackground = TingYuColors.Ink,
    surface = TingYuColors.Surface,
    onSurface = TingYuColors.Ink,
    surfaceVariant = TingYuColors.SoftBlue,
    onSurfaceVariant = TingYuColors.Muted,
    outline = TingYuColors.Outline,
    error = TingYuColors.Error,
)

private val PlayerColors = darkColorScheme(
    primary = TingYuColors.PlayerAccent,
    onPrimary = TingYuColors.Ink,
    primaryContainer = TingYuColors.PlayerSurface,
    onPrimaryContainer = TingYuColors.Surface,
    secondary = TingYuColors.PlayerAccent,
    background = TingYuColors.Ink,
    onBackground = TingYuColors.Surface,
    surface = TingYuColors.PlayerSurface,
    onSurface = TingYuColors.Surface,
    surfaceVariant = TingYuColors.PlayerSurface,
    onSurfaceVariant = TingYuColors.PlayerMuted,
    outline = TingYuColors.Muted,
    error = TingYuColors.PlayerError,
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun TingYuTheme(player: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (player) PlayerColors else LightColors, typography = AppTypography, content = content)
}
