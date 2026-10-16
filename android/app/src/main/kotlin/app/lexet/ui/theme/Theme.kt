package app.lexet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lexet.data.ThemeMode

// Aristophanes palette: navy and beige. Light theme = navy on beige; dark theme = beige on navy.
private val Navy = Color(0xFF1B2A4A)
private val NavyDeep = Color(0xFF14213D)
private val NavyNight = Color(0xFF0E1626)
private val Beige = Color(0xFFF6F0E3)
private val Sand = Color(0xFFE8D9B5)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Beige,
    primaryContainer = Color(0xFFE6D8B8),
    onPrimaryContainer = NavyDeep,
    secondary = Color(0xFF3A4A6B),
    onSecondary = Beige,
    secondaryContainer = Color(0xFFE6D8B8),
    onSecondaryContainer = NavyDeep,
    tertiary = Color(0xFF7A5C2E),
    onTertiary = Beige,
    tertiaryContainer = Color(0xFFF0E2C2),
    onTertiaryContainer = Color(0xFF2B1D05),
    background = Beige,
    onBackground = Color(0xFF1A2238),
    surface = Beige,
    onSurface = Color(0xFF1A2238),
    surfaceVariant = Color(0xFFEAE0CB),
    onSurfaceVariant = Color(0xFF4A4D5C),
    surfaceTint = Navy,
    inverseSurface = Navy,
    inverseOnSurface = Beige,
    inversePrimary = Sand,
    outline = Color(0xFF7A7466),
    outlineVariant = Color(0xFFD5C9B0),
    surfaceBright = Color(0xFFFBF7EE),
    surfaceDim = Color(0xFFE3D9C4),
    surfaceContainerLowest = Color(0xFFFFFCF6),
    surfaceContainerLow = Color(0xFFF9F3E7),
    surfaceContainer = Color(0xFFF1E8D6),
    surfaceContainerHigh = Color(0xFFECE2CE),
    surfaceContainerHighest = Color(0xFFE7DCC5),
)

private val DarkColors = darkColorScheme(
    primary = Sand,
    onPrimary = Navy,
    primaryContainer = Color(0xFF2C3D63),
    onPrimaryContainer = Color(0xFFF2E7CC),
    secondary = Color(0xFFCDBF9F),
    onSecondary = Navy,
    secondaryContainer = Color(0xFF34466E),
    onSecondaryContainer = Color(0xFFF2E7CC),
    tertiary = Color(0xFFE3C48A),
    onTertiary = Color(0xFF3A2A0C),
    tertiaryContainer = Color(0xFF574322),
    onTertiaryContainer = Color(0xFFF7E5C4),
    background = NavyNight,
    onBackground = Color(0xFFEEE6D3),
    surface = NavyNight,
    onSurface = Color(0xFFEEE6D3),
    surfaceVariant = Color(0xFF2A3756),
    onSurfaceVariant = Color(0xFFC9C0AC),
    surfaceTint = Sand,
    inverseSurface = Color(0xFFEEE6D3),
    inverseOnSurface = Navy,
    inversePrimary = Navy,
    outline = Color(0xFF8F8775),
    outlineVariant = Color(0xFF3A4664),
    surfaceBright = Color(0xFF26324E),
    surfaceDim = NavyNight,
    surfaceContainerLowest = Color(0xFF0A111E),
    surfaceContainerLow = Color(0xFF121C30),
    surfaceContainer = Color(0xFF16213A),
    surfaceContainerHigh = Color(0xFF1C2944),
    surfaceContainerHighest = Color(0xFF233150),
)

/** Colours that carry meaning (stage of a law, verification state), tuned per theme. */
@Immutable
data class LexColors(
    val upcoming: Color,
    val imminent: Color,
    val inForce: Color,
    val ended: Color,
    val verified: Color,
    val unconfirmed: Color,
    val official: Color,
)

private val LightLex = LexColors(
    upcoming = Color(0xFF3A5A8C), imminent = Color(0xFF94600A), inForce = Color(0xFF2E6B45),
    ended = Color(0xFF6E6A60), verified = Color(0xFF2E6B45), unconfirmed = Color(0xFF94600A), official = Navy,
)
private val DarkLex = LexColors(
    upcoming = Color(0xFFB9C6E4), imminent = Color(0xFFF0C070), inForce = Color(0xFF9ED3AE),
    ended = Color(0xFFB3AC9C), verified = Color(0xFF9ED3AE), unconfirmed = Color(0xFFF0C070), official = Sand,
)

val LocalLexColors = staticCompositionLocalOf { LightLex }

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 20.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

@Composable
fun LexetTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalLexColors provides if (dark) DarkLex else LightLex) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
