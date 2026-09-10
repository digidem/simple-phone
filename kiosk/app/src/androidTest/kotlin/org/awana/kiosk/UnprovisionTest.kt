package org.awana.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.ApkInstaller
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.PackageSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Removing the lock has to leave a phone somebody can actually use: apps
 * deletable again, restrictions gone, and this app no longer in charge.
 *
 * This test gives the device owner away, so it puts it back through `dpm` in
 * teardown — if that ever stops working, every test after it here is skipped by
 * its own `assumeTrue` and the emulator needs wiping and
 * `dpm set-device-owner org.awana.kiosk/.KioskDeviceAdminReceiver` by hand.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class UnprovisionTest {

    private lateinit var context: Context
    private lateinit var apk: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(DevicePolicy(context).isDeviceOwner)

        apk = SamplePayload.stage(context)
        runBlocking { ApkInstaller(context).install(apk, SamplePayload.PACKAGE, null) }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        SamplePayload.discardStaged(context)
        val restored = if (DevicePolicy(context).isDeviceOwner) {
            "already device owner"
        } else {
            SamplePayload.shell("dpm set-device-owner $OWNER")
        }
        SamplePayload.remove(context)
        check(DevicePolicy(context).isDeviceOwner) {
            "the device owner was not restored, so every later test will skip: $restored"
        }
        // A restored owner starts with an empty policy set, and the classes that
        // run after this one are entitled to find a provisioned device.
        Provisioner(context).provision(TestConfigs.policyOnly())
    }

    @Test
    fun removingTheLockLeavesTheAppsDeletableAndNobodyInCharge() = runBlocking {
        val policy = DevicePolicy(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val config = TestConfigs.policyOnly(
            packages = listOf(
                PackageSpec(SamplePayload.PACKAGE, Certificates.ofApkFile(context, apk).first()),
            ),
        )
        Provisioner(context).provision(config)
        assertTrue(
            "the sample was not protected, so this proves nothing about removing that protection",
            dpm.isUninstallBlocked(policy.admin, SamplePayload.PACKAGE),
        )

        val failures = policy.unprovision(config)

        assertTrue("unprovision reported: $failures", failures.isEmpty())
        assertFalse("this app is still the device owner", policy.isDeviceOwner)
        assertEquals(
            "the app installed by the deployment still cannot be uninstalled",
            "Success",
            SamplePayload.shell("pm uninstall ${SamplePayload.PACKAGE}").trim(),
        )
    }

    private companion object {
        const val OWNER = "org.awana.kiosk/.KioskDeviceAdminReceiver"
    }
}
