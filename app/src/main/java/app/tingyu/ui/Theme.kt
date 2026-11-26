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
    val Coral = Color(0xFFC04734)
    val Background = Color(0xFFFFF8F4)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF30211F)
    val Muted = Color(0xFF70574F)
    val Peach = Color(0xFFFFE4DA)
    val Outline = Color(0xFFDBC7BF)
    val Error = Color(0xFFA12640)
    val PlayerSurface = Color(0xFF492E2A)
    val PlayerMuted = Color(0xFFDEC2B8)
    val PlayerAccent = Color(0xFFFFB69F)
    val PlayerError = Color(0xFFFFB4AB)
}

private val LightColors = lightColorScheme(
    primary = TingYuColors.Coral,
    onPrimary = TingYuColors.Surface,
    primaryContainer = TingYuColors.Peach,
    onPrimaryContainer = TingYuColors.Ink,
    secondary = TingYuColors.Coral,
    secondaryContainer = TingYuColors.Peach,
    onSecondaryContainer = TingYuColors.Ink,
    background = TingYuColors.Background,
    onBackground = TingYuColors.Ink,
    surface = TingYuColors.Surface,
    onSurface = TingYuColors.Ink,
    surfaceVariant = TingYuColors.Peach,
    onSurfaceVariant = TingYuColors.Muted,
    outline = TingYuColors.Outline,
    surfaceTint = TingYuColors.Coral,
    surfaceContainerLowest = TingYuColors.Surface,
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFAEBE4),
    surfaceContainerHigh = Color(0xFFF7E6DE),
    surfaceContainerHighest = Color(0xFFF1DED6),
    inverseSurface = TingYuColors.Ink,
    inverseOnSurface = TingYuColors.Background,
    inversePrimary = TingYuColors.PlayerAccent,
    outlineVariant = TingYuColors.Outline,
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
    surfaceTint = TingYuColors.PlayerAccent,
    secondaryContainer = TingYuColors.PlayerSurface,
    onSecondaryContainer = TingYuColors.PlayerAccent,
    surfaceContainerLow = Color(0xFF382724),
    surfaceContainer = Color(0xFF402B27),
    surfaceContainerHigh = TingYuColors.PlayerSurface,
    surfaceContainerHighest = Color(0xFF533831),
    outlineVariant = TingYuColors.Muted,
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
