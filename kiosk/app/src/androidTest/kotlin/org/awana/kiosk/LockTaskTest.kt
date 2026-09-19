package org.awana.kiosk

import android.os.UserManager
import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.PhoneLock
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
        // Put the lock back rather than only forgetting it was lifted: the
        // classes that run after this one expect a locked-down device.
        if (PhoneLock.isUnlocked(context)) runBlocking { PhoneLock.lock(context) }
        LockTaskHarness.leave(context)
        device.executeShellCommand("cmd statusbar collapse")
        device.waitForIdle(LockTaskHarness.SETTLE_MS)
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
        device.waitForIdle(LockTaskHarness.SETTLE_MS)

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
            device.waitForIdle(LockTaskHarness.SETTLE_MS)
        }

        assertLocked("back left the launcher")
    }

    @Test
    fun recentsIsNotReachable() {
        enterLockTask()

        device.pressRecentApps()
        device.waitForIdle(LockTaskHarness.SETTLE_MS)

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
        device.waitForIdle(LockTaskHarness.SETTLE_MS)

        assertLocked("home left lock task")
    }

    @Test
    fun unlockingLiftsTheLockButKeepsThePhoneManaged() {
        enterLockTask()
        LockTaskHarness.leave(context)

        val problems = runBlocking { PhoneLock.unlock(context) }

        assertTrue("unlocking reported: $problems", problems.isEmpty())
        assertTrue("the lock task allowlist survived unlocking", policy.lockTaskPackages().isEmpty())
        assertFalse(
            "apps are still protected from uninstalling on an unlocked phone",
            policy.hasRestriction(UserManager.DISALLOW_UNINSTALL_APPS),
        )
        assertTrue("unlocking gave up device ownership, so it could never be locked again", policy.isDeviceOwner)

        // And the launcher coming back does not take it back.
        LockTaskHarness.startLauncher(context, device, expectLock = false)
        assertEquals(
            ActivityManager.LOCK_TASK_MODE_NONE,
            LockTaskHarness.lockTaskModeState(context),
        )
    }

    @Test
    fun lockingAgainPutsTheWholeLockBack() {
        enterLockTask()
        LockTaskHarness.leave(context)
        runBlocking { PhoneLock.unlock(context) }

        val problems = runBlocking { PhoneLock.lock(context) }
        assertTrue("locking again reported: $problems", problems.isEmpty())
        LockTaskHarness.startLauncher(context, device)

        LockTaskHarness.awaitLocked(
            context,
            device,
            "the launcher did not take the lock back after the phone was locked again",
        )
        assertTrue(
            "locking again left the apps uninstallable",
            policy.hasRestriction(UserManager.DISALLOW_UNINSTALL_APPS),
        )
    }

    private fun assertLocked(what: String) {
        assertEquals(
            what,
            ActivityManager.LOCK_TASK_MODE_LOCKED,
            LockTaskHarness.lockTaskModeState(context),
        )
    }

    private fun enterLockTask() {
        runBlocking { Provisioner(context).provision(TestConfigs.policyOnly()) }
        LockTaskHarness.startLauncher(context, device)
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
