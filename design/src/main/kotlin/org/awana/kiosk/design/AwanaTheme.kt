package org.awana.kiosk.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The one place either app names a colour.
 *
 * Generated from a single indigo seed with the Material tonal-spot algorithm —
 * primary chroma 36, secondary 16, tertiary 24 at hue + 60, neutral 6, neutral
 * variant 8, error at hue 25 chroma 84 — so every role is a tone of a palette
 * rather than a value someone picked. No dynamic colour: every phone in a
 * deployment has to look the same, so a trainer can support one over the phone.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF515A92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE0FF),
    onPrimaryContainer = Color(0xFF0B154B),
    inversePrimary = Color(0xFFBAC3FF),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1FA),
    onSecondaryContainer = Color(0xFF171A2C),
    tertiary = Color(0xFF76536D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F3),
    onTertiaryContainer = Color(0xFF2D1228),
    error = Color(0xFFBA1B1B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B20),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B20),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF515A92),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF1EFF7),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF222C61),
    primaryContainer = Color(0xFF394379),
    onPrimaryContainer = Color(0xFFDDE0FF),
    inversePrimary = Color(0xFF515A92),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF43465A),
    onSecondaryContainer = Color(0xFFE0E1FA),
    tertiary = Color(0xFFE5BAD7),
    onTertiary = Color(0xFF44273E),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiaryContainer = Color(0xFFFFD7F3),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF680003),
    errorContainer = Color(0xFF930006),
    onErrorContainer = Color(0xFFFFDAD4),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFBAC3FF),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF303036),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393F),
    surfaceDim = Color(0xFF131318),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF29292F),
    surfaceContainerHighest = Color(0xFF34343A),
    surfaceContainerLow = Color(0xFF1B1B20),
    surfaceContainerLowest = Color(0xFF0D0E13),
)

/**
 * Material has no "it worked" role, and the design needs one — a finished phone
 * must not read as the same thing as one still going. Same tonal construction
 * on a green hue, carried beside the scheme rather than pushed into an
 * unrelated role.
 */
data class OkColors(val container: Color, val onContainer: Color)

private val LightOk = OkColors(container = Color(0xFFC0EEC9), onContainer = Color(0xFF00210D))
private val DarkOk = OkColors(container = Color(0xFF264F34), onContainer = Color(0xFFC0EEC9))

private val LocalOkColors = staticCompositionLocalOf { LightOk }

val MaterialTheme.okColors: OkColors
    @Composable
    @ReadOnlyComposable
    get() = LocalOkColors.current

@Composable
fun AwanaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOkColors provides if (dark) DarkOk else LightOk) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = Typography(),
            content = content,
        )
    }
}
