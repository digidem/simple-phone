package org.awana.kiosk

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.UpdateProgress
import org.awana.kiosk.policy.UpdateState
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ProvisioningBootstrap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the receiver actually hands off: a bootstrap out of the QR, the config
 * fetched from the trainer's phone and checked against the hash it carried, and
 * only then the provisioning run.
 *
 * `EndToEndProvisioningTest` starts from a config that already exists.
 * Everything before that — and the service that has to survive the receiver's
 * process going away mid-download — is only covered here.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProvisioningServiceTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice
    private lateinit var payloadApk: File
    private var server: DeploymentServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(DevicePolicy(context).isDeviceOwner)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SamplePayload.remove(context)
        payloadApk = SamplePayload.stage(context)
        lastReportFile().delete()
        // A run from the previous test may still be finishing, and the service
        // ignores a start while one is running.
        await("the previous test's setup never finished") { !ProvisioningService.isRunning }
    }

    @After
    fun tearDown() {
        server?.stop()
        // The service finishes by starting the launcher, which takes lock task.
        LockTaskHarness.leave(context)
        SamplePayload.remove(context)
        SamplePayload.discardStaged(context)
    }

    @Test
    fun theServiceFetchesTheConfigInstallsThePayloadAndReports() {
        val running = serve()
        val config = TestConfigs.withServer(
            running.url,
            listOf(
                PackageSpec(
                    SamplePayload.PACKAGE,
                    Certificates.ofApkFile(context, payloadApk).first(),
                    SamplePayload.PATH,
                ),
            ),
        )
        running.config = config.encode()

        ProvisioningService.start(context, bootstrapFor(running))

        await("no setup report reached the server") { running.reports.isNotEmpty() }
        val report = running.reports.single()
        assertTrue("the service reported failures: ${report.failures}", report.failures.isEmpty())
        assertTrue(
            "the payload the fetched config named was not installed",
            SamplePayload.isInstalled(context),
        )
        assertEquals(config.deploymentId, report.deploymentId)
        assertTrue(report.installed.any { it.packageName == SamplePayload.PACKAGE })
    }

    @Test
    fun aConfigThatDoesNotMatchItsHashProvisionsNothingAndSaysWhy() {
        val running = serve()
        running.config = TestConfigs.withServer(
            running.url,
            listOf(PackageSpec(SamplePayload.PACKAGE, "0".repeat(64), SamplePayload.PATH)),
        ).encode()

        // What an attacker on the hotspot can do: serve their own config. The
        // hash came through the setup wizard, where they cannot reach it.
        ProvisioningService.start(
            context,
            ProvisioningBootstrap(running.url, Digests.sha256Hex("not what is served".toByteArray())),
        )

        await("the service recorded nothing for the admin screen to show") {
            Provisioner(context).lastReport() != null
        }
        val report = Provisioner(context).lastReport()!!
        val reason = report.failures.single()
        assertTrue(
            "a trainer has to be told not to use the device: $reason",
            reason.contains("Do not use this device"),
        )
        assertFalse(
            "a payload was installed from a config that failed its hash check",
            SamplePayload.isInstalled(context),
        )
        assertTrue(
            "a report was sent to a server the config could not be trusted from",
            running.reports.isEmpty(),
        )
    }

    @Test
    fun insideTheSetupWizardEachStepIsShownAndTheOutcomeReleasesTheWizard() {
        val running = serve()
        running.config = TestConfigs.withServer(running.url, emptyList()).encode()
        UpdateProgress.clear()

        // What PolicyComplianceActivity starts, and then waits on.
        ProvisioningService.startInSetupWizard(context, bootstrapFor(running))

        await("the setup never reported an outcome for the wizard") { UpdateProgress.state.value.finished }
        val outcome = UpdateProgress.state.value
        assertTrue("setup failed inside the wizard: $outcome", outcome is UpdateState.Done)
        assertTrue("the report never reached the trainer's phone", running.reports.isNotEmpty())
        UpdateProgress.clear()
    }

    @Test
    fun aSecondStartWhileSettingUpDoesNotCancelTheFirst() {
        val running = serve()
        running.config = TestConfigs.withServer(running.url, emptyList()).encode()
        UpdateProgress.clear()

        ProvisioningService.startInSetupWizard(context, bootstrapFor(running))
        // The completion broadcast arriving while the wizard's run is going.
        ProvisioningService.startAfterCompletion(context, bootstrapFor(running))

        await("the setup never reported an outcome") { UpdateProgress.state.value.finished }
        val outcome = UpdateProgress.state.value
        assertTrue("the second start cut the first short: $outcome", outcome is UpdateState.Done)
        assertEquals("setup ran twice", 1, running.fetched.count { it == "/config.json" })
        UpdateProgress.clear()
    }

    @Test
    fun aLateCompletionBroadcastDoesNotSetThePhoneUpAgain() {
        val running = serve()
        running.config = TestConfigs.withServer(running.url, emptyList()).encode()
        val store = ConfigStore(context)
        store.save(TestConfigs.withServer(running.url, emptyList()))
        try {
            ProvisioningService.startAfterCompletion(context, bootstrapFor(running))

            // Nothing to wait for but the absence of a fetch; give it the time one takes.
            Thread.sleep(3_000)
            assertTrue("the config was fetched again", running.fetched.none { it == "/config.json" })
        } finally {
            store.clear()
        }
    }

    private fun serve(): DeploymentServer {
        val running = DeploymentServer(payloadApk)
        running.begin()
        server = running
        return running
    }

    /** The hash is over the bytes as served, which is what the setup app publishes. */
    private fun bootstrapFor(running: DeploymentServer) = ProvisioningBootstrap(
        running.url,
        Digests.sha256Hex(running.config!!.toByteArray()),
    )

    /**
     * Where [Provisioner.lastReport] keeps the one report the admin screen
     * shows, cleared so a run from an earlier test cannot be mistaken for this
     * one's.
     */
    private fun lastReportFile() = File(context.filesDir, "last-report.json")

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        fail("$what within ${TIMEOUT_MS}ms")
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
        const val POLL_MS = 250L
    }
}
