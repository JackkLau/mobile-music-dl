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
    val Pine = Color(0xFF145B52)
    val Background = Color(0xFFF3F7F4)
    val Surface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF132E2A)
    val Muted = Color(0xFF48645D)
    val Mint = Color(0xFFDAEEE6)
    val Outline = Color(0xFFC5D6CE)
    val Error = Color(0xFFA32F38)
    val PlayerSurface = Color(0xFF20463F)
    val PlayerMuted = Color(0xFFB3CDC2)
    val PlayerAccent = Color(0xFFB5EFD6)
    val PlayerError = Color(0xFFFFB4AB)
}

private val LightColors = lightColorScheme(
    primary = TingYuColors.Pine,
    onPrimary = TingYuColors.Surface,
    primaryContainer = TingYuColors.Mint,
    onPrimaryContainer = TingYuColors.Ink,
    secondary = TingYuColors.Pine,
    secondaryContainer = TingYuColors.Mint,
    onSecondaryContainer = TingYuColors.Ink,
    background = TingYuColors.Background,
    onBackground = TingYuColors.Ink,
    surface = TingYuColors.Surface,
    onSurface = TingYuColors.Ink,
    surfaceVariant = TingYuColors.Mint,
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
