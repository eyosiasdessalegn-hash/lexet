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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.lexet.data.ThemeMode

private val Teal = Color(0xFF0B5D52)
private val TealLight = Color(0xFF7FD3C3)
private val Ink = Color(0xFF1A1C1E)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE6),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDEA6),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF3C5A7A),
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFAFAF7),
    surfaceVariant = Color(0xFFE5E9E6),
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF424845),
    outline = Color(0xFF727974),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045),
    onPrimaryContainer = Color(0xFFA6F2E2),
    secondary = Color(0xFFFFBA45),
    onSecondary = Color(0xFF482A00),
    secondaryContainer = Color(0xFF673E00),
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = Color(0xFFA6C8EE),
    background = Color(0xFF111413),
    surface = Color(0xFF111413),
    surfaceVariant = Color(0xFF3F4945),
    onSurface = Color(0xFFE1E3E0),
    onSurfaceVariant = Color(0xFFBEC9C4),
    outline = Color(0xFF89938E),
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
    upcoming = Color(0xFF3C5A7A), imminent = Color(0xFF9A5B00), inForce = Color(0xFF1B6E3A),
    ended = Color(0xFF6B6B6B), verified = Teal, unconfirmed = Color(0xFF9A5B00), official = Color(0xFF4A3C8C),
)
private val DarkLex = LexColors(
    upcoming = Color(0xFFA6C8EE), imminent = Color(0xFFFFBA45), inForce = Color(0xFF8FD8A6),
    ended = Color(0xFFB0B0B0), verified = TealLight, unconfirmed = Color(0xFFFFBA45), official = Color(0xFFC8BEFF),
)

val LocalLexColors = staticCompositionLocalOf { LightLex }

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
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
