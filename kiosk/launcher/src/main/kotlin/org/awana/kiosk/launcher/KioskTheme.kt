package org.awana.kiosk.launcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A solid background colour and nothing else. No wallpaper, no wallpaper
 * picker, no dynamic colour — the home screen should look identical on every
 * device in a deployment so a trainer can support it over the phone.
 */
private val KioskColors = darkColorScheme(
    primary = Color(0xFF74C69D),
    onPrimary = Color(0xFF06281A),
    background = Color(0xFF1B4332),
    onBackground = Color(0xFFF1FAF5),
    surface = Color(0xFF24543F),
    onSurface = Color(0xFFF1FAF5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun KioskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KioskColors,
        typography = Typography(),
        content = content,
    )
}
