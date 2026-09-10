package org.awana.kiosk.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The HOME activity.
 *
 * Built from scratch rather than forked from a general-purpose launcher: every
 * feature a normal launcher has — app drawer, widgets, folders, gestures, icon
 * packs, wallpaper picker — is either dead weight or an escape route.
 */
class LauncherActivity : ComponentActivity() {

    private var screen by mutableStateOf(Screen.Home)
    private var apps by mutableStateOf(emptyList<LaunchableApp>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this) {
            // Back on the home screen does nothing. Back elsewhere returns to
            // the home screen rather than leaving the launcher.
            if (screen != Screen.Home) screen = Screen.Home
        }

        setContent {
            KioskTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        apps = apps,
                        onLaunch = ::launchApp,
                        onAdminGesture = { screen = Screen.PinEntry },
                    )

                    Screen.PinEntry -> PinEntryScreen(
                        onUnlocked = { screen = Screen.Admin },
                        onCancel = { screen = Screen.Home },
                    )

                    Screen.Admin -> AdminScreen(onDone = { screen = Screen.Home })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Refreshed on every start rather than once: the admin screen can
        // change which apps are visible, and the updater can install new ones.
        lifecycleScope.launch { apps = AppList.load(this@LauncherActivity) }
    }

    override fun onStop() {
        super.onStop()
        // A device left on the admin screen and pocketed must not still be
        // there when it comes back out.
        if (screen != Screen.Home) screen = Screen.Home
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(intent)
    }

    enum class Screen { Home, PinEntry, Admin }
}
