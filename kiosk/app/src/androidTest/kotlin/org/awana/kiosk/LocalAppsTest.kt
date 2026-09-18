package org.awana.kiosk

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.LocalApps
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.SideloadFromFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The offline way to update a phone: an APK already on it, installed from the
 * admin screen, and — for an app the deployment does not list — let through
 * the lock without a trainer's phone.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LocalAppsTest {

    private lateinit var context: Context
    private lateinit var apk: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(DevicePolicy(context).isDeviceOwner)
        SamplePayload.remove(context)
        apk = SamplePayload.stage(context)
        runBlocking { Provisioner(context).provision(TestConfigs.policyOnly()) }
    }

    @After
    fun tearDown() {
        // Back to a config that does not name the sample, or its uninstall
        // block would outlive this class.
        runBlocking { Provisioner(context).provision(TestConfigs.policyOnly()) }
        SamplePayload.remove(context)
        SamplePayload.discardStaged(context)
    }

    @Test
    fun anAppFromAFileTheDeploymentDoesNotListIsInstalledButNotYetAllowed() = runBlocking {
        val outcome = SideloadFromFile.run(context, Uri.fromFile(apk))

        assertTrue("the file was refused: $outcome", outcome is SideloadFromFile.Outcome.Installed)
        assertEquals(false, (outcome as SideloadFromFile.Outcome.Installed).known)
        assertTrue(SamplePayload.isInstalled(context))
        assertTrue(
            "an app nobody allowed can already open under the lock",
            SamplePayload.PACKAGE !in DevicePolicy(context).lockTaskPackages(),
        )
    }

    @Test
    fun allowingAnInstalledAppLetsItThroughTheLockAndProtectsIt() = runBlocking {
        SideloadFromFile.run(context, Uri.fromFile(apk))

        val problems = LocalApps.allow(context, SamplePayload.PACKAGE)

        assertTrue("allowing reported: $problems", problems.isEmpty())
        assertTrue(SamplePayload.PACKAGE in DevicePolicy(context).lockTaskPackages())
        val spec = ConfigStore(context).load()!!.packages.single { it.packageName == SamplePayload.PACKAGE }
        assertTrue("the key it was allowed with was not recorded", spec.certSha256.isNotBlank())
        assertTrue(
            "an allowed app is still offered as not allowed",
            LocalApps.unlisted(context).none { it.packageName == SamplePayload.PACKAGE },
        )
    }
}
