package org.awana.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.uiautomator.UiDevice
import org.awana.kiosk.policy.DevicePolicy
import org.junit.Assert.fail

/**
 * Getting the device into and out of lock task without a handle on the launcher
 * activity, which is what the tests that inject keys and open the shade need
 * before they touch anything.
 */
object LockTaskHarness {

    /**
     * Starts the launcher with a real HOME intent and returns once the device
     * is locked to it and its window holds focus.
     *
     * The wait is on lock-task state and `dumpsys window`, not on anything the
     * launcher draws. While the kiosk holds lock task, UiAutomator's window
     * enumeration keeps handing back the stock launcher's stale window
     * ("Skipping null root node for window ... Quickstep"), so a
     * `Until.hasObject` wait for a kiosk view never matches and burns its whole
     * timeout even though the launcher came up in about four seconds.
     */
    /** [expectLock] false is for an unlocked phone, where the launcher must not take it. */
    fun startLauncher(context: Context, device: UiDevice, expectLock: Boolean = true) {
        // A freshly created emulator turns its screen off between runs; an
        // activity started behind a dark screen is stopped at once and never
        // gets a focused window, so every injected key would ANR the app.
        device.wakeUp()
        val home = DevicePolicy.launcherComponent(context)
            ?: error("${context.packageName} declares no HOME activity")
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(home)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        if (expectLock) {
            awaitLocked(context, device, "the launcher did not take lock task")
        } else {
            // Long enough for onResume to have taken the lock if it was going to.
            device.waitForIdle(SETTLE_MS)
            Thread.sleep(UNLOCKED_GRACE_MS)
        }
    }

    /** Waits until the kiosk holds lock task and its window has focus, or fails [what]. */
    fun awaitLocked(
        context: Context,
        device: UiDevice,
        what: String,
        timeoutMs: Long = TIMEOUT_MS,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (lockTaskModeState(context) == ActivityManager.LOCK_TASK_MODE_LOCKED &&
                isKioskFocused(context, device)
            ) {
                device.waitForIdle(SETTLE_MS)
                return
            }
            Thread.sleep(POLL_MS)
        }
        fail(
            "$what within ${timeoutMs}ms: lockTaskModeState=${lockTaskModeState(context)}, " +
                "focus=${currentFocus(device)}",
        )
    }

    /**
     * Ends lock task and leaves the allowlist as it found it, which is what the
     * admin screen's `stopLockTask` amounts to. Emptying the allowlist is the
     * only way to end it without a handle on the activity; re-applying it does
     * not lock the device again, because `if_whitelisted` only takes effect
     * when the activity next starts.
     */
    fun leave(context: Context) {
        val policy = DevicePolicy(context)
        if (!policy.isDeviceOwner) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val allowlist = policy.lockTaskPackages().toTypedArray()
        dpm.setLockTaskPackages(policy.admin, emptyArray())
        awaitUnlocked(context)
        dpm.setLockTaskPackages(policy.admin, allowlist)
    }

    fun lockTaskModeState(context: Context): Int =
        context.getSystemService(ActivityManager::class.java).lockTaskModeState

    private fun awaitUnlocked(context: Context) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (lockTaskModeState(context) == ActivityManager.LOCK_TASK_MODE_NONE) return
            Thread.sleep(POLL_MS)
        }
        fail("lock task did not end after the allowlist was emptied")
    }

    private fun isKioskFocused(context: Context, device: UiDevice) =
        currentFocus(device).contains(context.packageName)

    private fun currentFocus(device: UiDevice): String =
        device.executeShellCommand("dumpsys window")
            .lineSequence()
            .filter { it.contains("mCurrentFocus") }
            .joinToString(" ")
            .trim()

    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 250L
    private const val UNLOCKED_GRACE_MS = 3_000L
    const val SETTLE_MS = 2_000L
}
