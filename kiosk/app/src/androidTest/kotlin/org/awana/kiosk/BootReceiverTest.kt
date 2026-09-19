package org.awana.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.PhoneLock
import org.awana.kiosk.policy.Provisioner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reboot must not be a way out of the kiosk.
 *
 * Nothing runs at process start, so this receiver is the only thing that puts
 * the policy set back after a restart — anything it misses is a gap a user
 * keeps until a trainer next visits the device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var policy: DevicePolicy
    private lateinit var dpm: DevicePolicyManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policy = DevicePolicy(context)
        assumeTrue("Not device owner; run dpm set-device-owner first", policy.isDeviceOwner)
        dpm = context.getSystemService(DevicePolicyManager::class.java)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        // Both tests take the device apart on purpose; the next class along is
        // entitled to find it provisioned.
        if (policy.isDeviceOwner) Provisioner(context).provision(TestConfigs.policyOnly())
    }

    @Test
    fun bootPutsBackARestrictionAndTheLockTaskFeaturesTheDeviceLost() = runBlocking {
        val config = TestConfigs.policyOnly()
        Provisioner(context).provision(config)

        dpm.clearUserRestriction(policy.admin, UserManager.DISALLOW_SAFE_BOOT)
        dpm.setLockTaskFeatures(policy.admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        assertFalse(
            "the restriction is still set, so this proves nothing about re-applying it",
            policy.hasRestriction(UserManager.DISALLOW_SAFE_BOOT),
        )

        deliverBootCompleted()

        assertTrue(
            "DISALLOW_SAFE_BOOT was not re-applied at boot, so safe mode is an escape route " +
                "for anyone who restarts the phone",
            policy.hasRestriction(UserManager.DISALLOW_SAFE_BOOT),
        )
        assertEquals(
            "the lock-task features were not re-applied at boot",
            policy.lockTaskFeatures(config),
            policy.lockTaskFeatures(),
        )
    }

    @Test
    fun aDeviceWithNoStoredConfigIsLeftAloneAtBoot() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())
        ConfigStore(context).clear()
        dpm.setLockTaskPackages(policy.admin, emptyArray())

        deliverBootCompleted()

        assertTrue(
            "boot applied a policy set to a device that has no config to apply",
            policy.lockTaskPackages().isEmpty(),
        )
    }

    @Test
    fun aPhoneAnAdminUnlockedIsStillUnlockedAfterARestart() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())
        try {
            PhoneLock.unlock(context)

            deliverBootCompleted()

            assertFalse(
                "a restart locked a phone someone had unlocked to fix it",
                policy.hasRestriction(UserManager.DISALLOW_SAFE_BOOT),
            )
            assertTrue("the lock task allowlist came back at boot", policy.lockTaskPackages().isEmpty())
        } finally {
            PhoneLock.lock(context)
        }
    }

    /** `BOOT_COMPLETED` is a protected broadcast, so the receiver is called directly. */
    private fun deliverBootCompleted() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }
}
