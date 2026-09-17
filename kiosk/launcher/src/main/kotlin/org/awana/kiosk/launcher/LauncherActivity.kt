package org.awana.kiosk.launcher

import android.app.ActivityManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.LockTaskBreakService
import org.awana.kiosk.shared.Telemetry
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
    private var home by mutableStateOf<HomeState>(HomeState.Apps(hero = null, small = emptyList()))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Off the main thread: the first window has a hard deadline at boot.
        lifecycleScope.launch(Dispatchers.IO) { Telemetry.init(applicationContext) }

        onBackPressedDispatcher.addCallback(this) {
            // Back on the home screen does nothing. Back elsewhere returns to
            // the home screen rather than leaving the launcher.
            if (screen != Screen.Home) screen = Screen.Home
        }

        setContent {
            KioskTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        state = home,
                        onLaunch = ::launchApp,
                        onSetUpAgain = ::setUpAgain,
                        onRemoveLock = ::removeLock,
                        onAdminGesture = { screen = Screen.PinEntry },
                        onSetUpForTesting = { screen = Screen.TestSetup }
                            .takeIf { canSetUpForTesting(this) },
                    )

                    Screen.TestSetup -> TestSetupScreen(
                        onDone = {
                            screen = Screen.Home
                            lifecycleScope.launch { home = homeState(this@LauncherActivity) }
                        },
                        onCancel = { screen = Screen.Home },
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
        lifecycleScope.launch { home = homeState(this@LauncherActivity) }
    }

    /**
     * Re-runs the config fetch against the bootstrap the failed attempt kept.
     *
     * The service does the work in the background and this activity may never
     * stop while it runs, so `onStart` alone would leave the failure screen up
     * on a phone that had just recovered. It watches until the state changes
     * instead.
     */
    private fun setUpAgain() {
        if (!setUpAgain(this)) return
        lifecycleScope.launch {
            val before = home
            repeat(RETRY_POLLS) {
                delay(RETRY_POLL_MS)
                home = homeState(this@LauncherActivity)
                if (home != before) return@launch
            }
        }
    }

    /**
     * Takes the lock back if the launcher is somehow resumed without it.
     *
     * `lockTaskMode="if_whitelisted"` covers the normal path, but it only fires
     * when the activity *starts*, and this one is `singleInstance` — a resume
     * after a crash or a process restart gets no such call. Belt and braces for
     * a window that should not exist, and a self-repair rather than a phone
     * quietly left unlocked.
     *
     * Not during a break: returning to the home screen must not cut short the
     * ten minutes a trainer deliberately asked for.
     */
    override fun onResume() {
        super.onResume()
        if (LockTaskBreakService.running) return

        val manager = getSystemService(ActivityManager::class.java) ?: return
        if (manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return

        // Calling this for a package that is not allowlisted asks the user to
        // confirm screen pinning, which is worse than doing nothing.
        val policy = DevicePolicy(this)
        if (!policy.isDeviceOwner || packageName !in policy.lockTaskPackages()) return

        runCatching { startLockTask() }
            .onFailure { Log.w(TAG, "Could not take the lock back", it) }
    }

    override fun onStop() {
        super.onStop()
        // A device left on the admin screen and pocketed must not still be
        // there when it comes back out.
        if (screen != Screen.Home) screen = Screen.Home
    }

    /**
     * No PIN gate, deliberately: with no config there is no admin PIN to check,
     * so a gate could never open and the phone would need ADB or a factory
     * reset. It is also still in the deployer's hands, with nothing on it yet.
     */
    private fun removeLock() {
        stopLockTask()
        lifecycleScope.launch {
            unprovision(this@LauncherActivity)
            home = homeState(this@LauncherActivity)
        }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(intent)
    }

    enum class Screen { Home, TestSetup, PinEntry, Admin }

    private companion object {
        /** Long enough to cover a payload download over a hotspot. */
        const val RETRY_POLLS = 150
        const val RETRY_POLL_MS = 2_000L
    }
}

private const val TAG = "LauncherActivity"
