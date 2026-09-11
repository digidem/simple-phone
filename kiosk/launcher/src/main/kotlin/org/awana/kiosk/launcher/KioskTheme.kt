package org.awana.kiosk.launcher

import androidx.compose.runtime.Composable
import org.awana.kiosk.design.AwanaTheme

/**
 * The generated scheme, pinned to light.
 *
 * Not the system setting: every phone in a deployment should look identical so
 * a trainer can support one over the phone, and half a fleet in dark mode is
 * exactly the kind of difference that makes that impossible.
 */
@Composable
fun KioskTheme(content: @Composable () -> Unit) {
    AwanaTheme(dark = false, content = content)
}
