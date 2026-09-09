package app.comapeo.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import app.comapeo.kiosk.policy.DevicePolicy
import app.comapeo.kiosk.policy.Pins
import app.comapeo.kiosk.policy.Provisioner
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Phase 1: the policy set is applied and observable.
 *
 * Requires the app to be Device Owner:
 *   adb shell dpm set-device-owner app.comapeo.kiosk/.KioskDeviceAdminReceiver
 * which itself requires an AOSP image with no accounts on it.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PolicyTest {

    private lateinit var context: Context
    private lateinit var policy: DevicePolicy

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        policy = DevicePolicy(context)
        assumeTrue("Not device owner; run dpm set-device-owner first", policy.isDeviceOwner)
    }

    @Test
    fun provisionAppliesTheFullPolicySet() = runBlocking {
        val config = TestConfigs.policyOnly()
        val result = Provisioner(context).provision(config)

        assertTrue(
            "provision reported failures: ${result.report.failures}",
            result.report.failures.isEmpty(),
        )
        assertTrue(result.report.isDeviceOwner)
        assertEquals(config.deploymentId, result.report.deploymentId)
    }

    @Test
    fun lockTaskAllowlistAlwaysIncludesTheKioskItself() = runBlocking {
        val config = TestConfigs.policyOnly()
        Provisioner(context).provision(config)

        val allowed = policy.lockTaskPackages()
        assertTrue(
            "kiosk package missing from $allowed",
            context.packageName in allowed,
        )
        assertTrue(Pins.COMAPEO_PACKAGE in allowed)
    }

    @Test
    fun shadeIsOffByDefaultAndSystemInfoIsOn() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly(showNotificationShade = false))

        val features = policy.lockTaskFeatures()
        assertTrue("SYSTEM_INFO must stay on so battery and signal are visible",
            features and DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO != 0)
        assertTrue(features and DevicePolicyManager.LOCK_TASK_FEATURE_HOME != 0)
        assertTrue("GLOBAL_ACTIONS keeps long-press power working",
            features and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0)

        assertEquals(
            "NOTIFICATIONS must be off in the default configuration",
            0,
            features and DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS,
        )
        assertEquals(
            "OVERVIEW would expose recents as an escape route",
            0,
            features and DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW,
        )
        assertEquals(
            "BLOCK_ACTIVITY_START_IN_TASK would break the share sheet and document picker",
            0,
            features and DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK,
        )
    }

    @Test
    fun shadeFlagTurnsNotificationsOn() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly(showNotificationShade = true))

        val features = policy.lockTaskFeatures()
        assertTrue(features and DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS != 0)
        assertTrue(features and DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO != 0)

        // The restrictions that render the dangerous tiles inert must hold in
        // this mode, not just the default one.
        assertTrue(policy.hasRestriction(UserManager.DISALLOW_AIRPLANE_MODE))
        assertTrue(policy.hasRestriction(UserManager.DISALLOW_CONFIG_WIFI))
        assertTrue(policy.hasRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS))
        assertTrue(policy.hasRestriction(UserManager.DISALLOW_CONFIG_LOCATION))
    }

    @Test
    fun everyIntendedRestrictionIsSet() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())

        (DevicePolicy.NETWORK_RESTRICTIONS + DevicePolicy.NON_NETWORK_RESTRICTIONS).forEach {
            assertTrue("$it should be set", policy.hasRestriction(it))
        }
    }

    @Test
    fun restrictionsExcludedByDesignStayUnset() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())

        DevicePolicy.NOT_SET_BY_DESIGN.forEach {
            assertFalse(
                "$it must not be set — it is excluded deliberately",
                policy.hasRestriction(it),
            )
        }
    }

    @Test
    fun locationIsOnAndCannotBeTurnedOff() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())

        val locationManager = context.getSystemService(android.location.LocationManager::class.java)
        assertTrue("location must be on for track recording", locationManager.isLocationEnabled)
        assertTrue(policy.hasRestriction(UserManager.DISALLOW_CONFIG_LOCATION))
    }

    @Test
    fun kioskIsThePersistentHomeActivity() = runBlocking {
        Provisioner(context).provision(TestConfigs.policyOnly())

        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, 0)
        assertEquals(context.packageName, resolved?.activityInfo?.packageName)
    }

    @Test
    fun forceStopIsBlockedForTheKioskAndItsApps() = runBlocking {
        val config = TestConfigs.policyOnly()
        Provisioner(context).provision(config)

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val protectedPackages = dpm.getUserControlDisabledPackages(policy.admin)
        assertTrue(context.packageName in protectedPackages)
        assertTrue(Pins.COMAPEO_PACKAGE in protectedPackages)
    }

    @Test
    fun provisionIsIdempotent() = runBlocking {
        val config = TestConfigs.policyOnly()
        val first = Provisioner(context).provision(config)
        val second = Provisioner(context).provision(config)

        assertEquals(first.report.failures, second.report.failures)
        assertEquals(policy.lockTaskPackages().sorted(), policy.lockTaskPackages().sorted())
        assertTrue(second.report.failures.isEmpty())
    }
}
