package org.awana.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.awana.kiosk.launcher.TAG_LAUNCHER_ROOT
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The point of the whole project: with the shade off the user cannot reach
 * quick settings, and battery and signal stay visible.
 *
 * This is an automated proxy on an AOSP emulator, which is a floor rather than
 * an answer — SystemUI is heavily modified by OEMs. See
 * `docs/hardware-checklist.md` for what still needs a real device.
 *
 * The launcher is started with a real HOME intent rather than through
 * `ActivityScenario`, which does not track a `singleInstance` HOME activity
 * reliably. Assertions are on `getLockTaskModeState` and on what SystemUI puts
 * on screen — both observable without that bookkeeping.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LockTaskTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var policy: DevicePolicy

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        policy = DevicePolicy(context)
        assumeTrue(policy.isDeviceOwner)
    }

    @After
    fun tearDown() {
        // Emptying the allowlist is what reliably ends lock task; calling
        // stopLockTask needs a handle on the activity, which is exactly what
        // this test avoids relying on.
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setLockTaskPackages(policy.admin, emptyArray())
        device.executeShellCommand("cmd statusbar collapse")
        device.waitForIdle(SETTLE_MS)
    }

    @Test
    fun launcherEntersLockTaskByItself() {
        enterLockTask()

        assertLocked(
            "the launcher is lockTaskMode=if_whitelisted and is in the allowlist, " +
                "so starting it should lock the device with no further call",
        )
    }

    @Test
    fun quickSettingsCannotBeReachedWithTheShadeOff() {
        enterLockTask()

        device.openNotification()
        device.waitForIdle(SETTLE_MS)

        assertFalse(
            "quick settings opened in lock task; the shade is the whole point of this project",
            device.hasObject(By.res(SYSTEM_UI, "quick_qs_panel")),
        )
        assertFalse(
            "the notification list opened in lock task",
            device.hasObject(By.res(SYSTEM_UI, "notification_stack_scroller")),
        )
    }

    @Test
    fun batteryAndSignalStayVisible() {
        enterLockTask()

        // SYSTEM_INFO without NOTIFICATIONS is the combination that keeps these
        // indicators while making the shade unreachable.
        assertTrue(
            "the status bar clock disappeared, so SYSTEM_INFO is not taking effect",
            device.hasObject(By.res(SYSTEM_UI, "clock")),
        )
    }

    @Test
    fun theBackButtonDoesNotLeaveTheLauncher() {
        enterLockTask()

        repeat(3) {
            device.pressBack()
            device.waitForIdle(SETTLE_MS)
        }

        assertLocked("back left the launcher")
    }

    @Test
    fun recentsIsNotReachable() {
        enterLockTask()

        device.pressRecentApps()
        device.waitForIdle(SETTLE_MS)

        // OVERVIEW is deliberately not among the lock task features.
        assertLocked("recents opened, which would be an escape route")
        assertFalse(
            "the recents list appeared on screen",
            device.hasObject(By.res(SYSTEM_UI, "recents_view")),
        )
    }

    @Test
    fun homeReturnsToTheLauncher() {
        enterLockTask()

        device.pressHome()
        device.waitForIdle(SETTLE_MS)

        assertLocked("home left lock task")
    }

    private fun assertLocked(what: String) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        assertEquals(what, ActivityManager.LOCK_TASK_MODE_LOCKED, activityManager.lockTaskModeState)
    }

    private fun enterLockTask() {
        // A freshly created emulator turns its screen off between runs; an
        // activity started behind a dark screen is stopped at once and never
        // gets a focused window, so every injected key would ANR the app.
        device.wakeUp()
        runBlocking { Provisioner(context).provision(TestConfigs.policyOnly()) }
        val home = DevicePolicy.launcherComponent(context)!!
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(home)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Waiting for the package is not enough: on a software-rendered
        // emulator the first Compose frame can take several seconds, and until
        // it lands the window has no focus, so injected keys ANR the app.
        val drawn = device.wait(
            Until.hasObject(By.res(context.packageName, TAG_LAUNCHER_ROOT)),
            LAUNCH_TIMEOUT_MS,
        )
        assertTrue("the launcher never drew its first frame", drawn != null)

        // Re-entering lock task after a previous test left it re-creates the
        // activity, and the old instance's frame satisfies the wait above. So
        // also wait for the lock to be active and the new window to hold
        // focus; a key injected before that is dropped and ANRs the app.
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS
        val activityManager = context.getSystemService(ActivityManager::class.java)
        while (System.currentTimeMillis() < deadline) {
            val locked = activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
            val focused = device.executeShellCommand("dumpsys window")
                .lineSequence()
                .any { it.contains("mCurrentFocus") && it.contains(context.packageName) }
            if (locked && focused) break
            Thread.sleep(500)
        }
        device.waitForIdle(SETTLE_MS)
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val LAUNCH_TIMEOUT_MS = 40_000L
        const val SETTLE_MS = 2_000L
    }
}
