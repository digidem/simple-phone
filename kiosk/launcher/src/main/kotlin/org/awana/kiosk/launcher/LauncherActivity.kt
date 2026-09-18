package org.awana.kiosk.launcher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.PhoneLock
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
    private var notice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Off the main thread: the first window has a hard deadline at boot.
        lifecycleScope.launch(Dispatchers.IO) { Telemetry.init(applicationContext) }

        onBackPressedDispatcher.addCallback(this) {
            // Back on the home screen does nothing. Back elsewhere returns to
            // the home screen rather than leaving the launcher.
            if (screen != Screen.Home) backToHome()
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
                        onScanCode = { screen = Screen.Scan },
                        onRelock = ::relock,
                        onOpenOtherApps = ::openOtherApps,
                        onChooseHome = ::chooseHome,
                    )

                    // No PIN, as for removing the lock here: a phone with no
                    // config has no PIN, and the code is checked against this
                    // app's own signing key before the phone does anything.
                    Screen.Scan -> UpdateScreen(onBack = ::backToHome, title = R.string.setup_scan_title)

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

                    Screen.Admin -> AdminScreen(onDone = ::backToHome)
                }
                notice?.let {
                    AlertDialog(
                        onDismissRequest = { notice = null },
                        text = { Text(it) },
                        confirmButton = {
                            TextButton(onClick = { notice = null }) { Text(stringResource(R.string.action_ok)) }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Refreshed on every start rather than once: the admin screen can
        // change which apps are visible, and the updater can install new ones.
        lifecycleScope.launch { refresh() }
    }

    /**
     * The admin screen and the scanner can both change what the phone is —
     * unlocked, locked again, set up — without this activity ever stopping.
     */
    private fun backToHome() {
        screen = Screen.Home
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        home = homeState(this)
        if (home == HomeState.LockRemoved) leaveForOtherHome() else takeLockIfAllowed()
    }

    private fun relock() {
        lifecycleScope.launch {
            val problems = PhoneLock.lock(this@LauncherActivity)
            refresh()
            if (problems.isNotEmpty()) {
                notice = getString(R.string.launcher_lock_problems, problems.joinToString("\n\n"))
            }
        }
    }

    private fun openOtherApps() {
        otherHome(this)?.let { runCatching { startActivity(it) } }
    }

    private fun chooseHome() {
        runCatching {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /**
     * Hands over to the phone's own launcher once this one has stepped aside.
     * Only then: if disabling it did not take, the system would bring this
     * activity straight back and it would leave again, forever.
     */
    private fun leaveForOtherHome() {
        val self = ComponentName(this, javaClass)
        if (packageManager.getComponentEnabledSetting(self) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return
        val other = otherHome(this) ?: return
        runCatching { startActivity(other) }
        finish()
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
     * Not on a phone an admin unlocked: returning to the home screen must not
     * undo what they deliberately did.
     */
    override fun onResume() {
        super.onResume()
        takeLockIfAllowed()
    }

    private fun takeLockIfAllowed() {
        if (PhoneLock.isUnlocked(this) || PhoneLock.isRemoved(this)) return

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
        // there when it comes back out. Only when the screen went off: the
        // file picker and the phone's settings also stop this activity, and
        // the admin page that opened them is waiting for them to come back.
        val asleep = getSystemService(PowerManager::class.java)?.isInteractive == false
        if (asleep && screen != Screen.Home) screen = Screen.Home
    }

    /**
     * No PIN gate, deliberately: with no config there is no admin PIN to check,
     * so a gate could never open and the phone would need ADB or a factory
     * reset. It is also still in the deployer's hands, with nothing on it yet.
     */
    private fun removeLock() {
        runCatching { stopLockTask() }
        lifecycleScope.launch {
            unprovision(this@LauncherActivity)
            refresh()
        }
    }

    private fun launchApp(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        startActivity(intent)
    }

    enum class Screen { Home, TestSetup, PinEntry, Admin, Scan }

    private companion object {
        /** Long enough to cover a payload download over a hotspot. */
        const val RETRY_POLLS = 150
        const val RETRY_POLL_MS = 2_000L
    }
}

private const val TAG = "LauncherActivity"
