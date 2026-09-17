package org.awana.kiosk

import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.InstallResult
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
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
import java.io.File

/**
 * The whole post-scan path against a real HTTP server: download, verify the
 * signing certificate, install, pre-grant, apply policy, report.
 *
 * This is the seam paying off — `provision()` is called directly with a
 * synthesised config, so none of this needs a camera or a setup wizard.
 * [DeploymentServer] stands in for the setup app, whose own endpoints are
 * covered by `:setup`'s tests; `ProvisioningServiceTest` covers the step
 * before this one, where a bootstrap becomes a config. Between them the only
 * untested part of the loop is the radio.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EndToEndProvisioningTest {

    private lateinit var context: Context
    private lateinit var payloadApk: File
    private var server: DeploymentServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(DevicePolicy(context).isDeviceOwner)
        SamplePayload.remove(context)
        payloadApk = SamplePayload.stage(context)
    }

    @After
    fun tearDown() {
        server?.stop()
        SamplePayload.remove(context)
        SamplePayload.discardStaged(context)
        // Reports queued against an unreachable server outlive the test that
        // made them, and the next one counts what is in the queue.
        File(context.filesDir, "report-queue").deleteRecursively()
    }

    private fun startServer(): String {
        val running = DeploymentServer(payloadApk)
        server = running
        return running.begin()
    }

    private fun reports() = server!!.reports

    private fun samplePackages() =
        listOf(PackageSpec(SamplePayload.PACKAGE, sampleFingerprint(), SamplePayload.PATH))

    private fun sampleFingerprint() = Certificates.ofApkFile(context, payloadApk).first()

    @Test
    fun aDeviceFetchesInstallsAndReportsInOnePass() = runBlocking {
        val config = TestConfigs.withServer(startServer(), samplePackages())

        val result = Provisioner(context).provision(config)

        assertTrue("provision reported: ${result.report.failures}", result.report.failures.isEmpty())
        assertTrue(
            "the payload was not installed",
            SamplePayload.isInstalled(context),
        )
        assertTrue("the completion report was not delivered", result.reportDelivered)
        assertEquals(1, reports().size)
    }

    @Test
    fun theReportCarriesWhatATrainerNeedsToSeeOnTheDashboard() = runBlocking {
        Provisioner(context).provision(TestConfigs.withServer(startServer(), samplePackages()))

        val report = reports().single()
        assertEquals("test-deployment", report.deploymentId)
        assertTrue(report.isDeviceOwner)
        assertTrue(report.deviceId.isNotBlank())
        assertTrue(report.policiesApplied.contains("lockTask"))
        assertTrue(report.policiesApplied.contains("networkRestrictions"))
        assertTrue(report.installed.any { it.packageName == SamplePayload.PACKAGE })
        // Which key signed this build, so a debug fleet cannot be mistaken for
        // a production one.
        assertTrue(report.buildVariant.startsWith("sig:"))
    }

    @Test
    fun installedAppsCannotBeUninstalledAfterwards() = runBlocking {
        Provisioner(context).provision(TestConfigs.withServer(startServer(), samplePackages()))

        // Not through DPM, but the way a user would: apps getting accidentally
        // uninstalled is one of the problems this project exists to solve.
        val output = SamplePayload.shell("pm uninstall ${SamplePayload.PACKAGE}")

        assertTrue("uninstall was not refused: $output", output.contains("Failure"))
        assertTrue(
            "the payload was uninstalled despite uninstall protection",
            SamplePayload.isInstalled(context),
        )
    }

    @Test
    fun anApkSignedWithTheWrongKeyIsRefusedAndSaidSoPlainly() = runBlocking {
        val wrongFingerprint = "0".repeat(64)
        val config = TestConfigs.withServer(
            startServer(),
            listOf(PackageSpec(SamplePayload.PACKAGE, wrongFingerprint, SamplePayload.PATH)),
        )

        val result = Provisioner(context).provision(config)

        assertFalse(
            "an APK with a mismatched fingerprint was installed",
            SamplePayload.isInstalled(context),
        )
        val reason = result.report.failures.single()
        assertTrue(
            "the failure must be readable by a trainer, not a status code: $reason",
            reason.contains("signed with a different key"),
        )
        // And it reaches the dashboard rather than dying in a log.
        assertTrue(reports().single().failures.isNotEmpty())
    }

    @Test
    fun anUnreachableServerFailsWithoutLosingThePolicySet() = runBlocking {
        val config = TestConfigs.withServer("http://127.0.0.1:1", samplePackages())

        val result = Provisioner(context).provision(config)

        assertTrue(result.report.failures.any { it.contains("Could not download") })
        // The lock-down still has to hold: a device that failed to get its apps
        // must not also be left unlocked.
        val policy = DevicePolicy(context)
        assertTrue(context.packageName in policy.lockTaskPackages())
        assertTrue(policy.hasRestriction(android.os.UserManager.DISALLOW_CONFIG_WIFI))
    }

    @Test
    fun aQueuedReportIsRetriedWhenTheServerComesBack() = runBlocking {
        // The trainer's hotspot may well be gone before the POST lands.
        val config = TestConfigs.withServer("http://127.0.0.1:1", emptyList())
        Provisioner(context).provision(config)

        val reporter = org.awana.kiosk.policy.Reporter(context)
        assertTrue("nothing was queued", reporter.queuedCount() > 0)

        val serverUrl = startServer()
        val flushed = reporter.flush(serverUrl)

        assertTrue(flushed > 0)
        assertEquals(0, reporter.queuedCount())
        assertTrue(reports().isNotEmpty())
    }

    // --- second pass ---------------------------------------------------------

    private fun installedVersionCode() =
        context.packageManager.getPackageInfo(SamplePayload.PACKAGE, 0).longVersionCode

    private fun spec(versionCode: Long?, cert: String = sampleFingerprint()) = listOf(
        PackageSpec(SamplePayload.PACKAGE, cert, SamplePayload.PATH, versionCode = versionCode),
    )

    /**
     * An update session re-serves the whole deployment to every phone in the
     * room over one hotspot, so not re-fetching what is already installed is
     * the difference between minutes and an afternoon.
     */
    @Test
    fun anAppThePhoneAlreadyHasIsNotDownloadedAgain() = runBlocking {
        val url = startServer()
        Provisioner(context).provision(TestConfigs.withServer(url, samplePackages()))
        assumeTrue(SamplePayload.isInstalled(context))
        server!!.fetched.clear()

        val result = Provisioner(context)
            .provision(TestConfigs.withServer(url, spec(installedVersionCode())))

        assertTrue(
            "an app already on the phone was downloaded again: ${server!!.fetched}",
            server!!.fetched.none { it.startsWith("/apks/") },
        )
        assertEquals(
            InstallResult.AlreadyCurrent,
            result.report.packageOutcomes.single().result,
        )
    }

    @Test
    fun aNewerVersionIsFetchedAndReportedAsAnUpdate() = runBlocking {
        val url = startServer()
        Provisioner(context).provision(TestConfigs.withServer(url, samplePackages()))
        assumeTrue(SamplePayload.isInstalled(context))
        server!!.fetched.clear()

        val result = Provisioner(context)
            .provision(TestConfigs.withServer(url, spec(installedVersionCode() + 1)))

        assertTrue(server!!.fetched.any { it.startsWith("/apks/") })
        assertEquals(InstallResult.Updated, result.report.packageOutcomes.single().result)
    }

    /**
     * The version alone must not decide it. An app of the right version signed
     * by someone else is not the deployment's app, and reporting it as current
     * would be a false all-clear on the one thing the fingerprint exists for.
     */
    @Test
    fun aRightVersionSignedByTheWrongKeyIsNotTakenAsCurrent() = runBlocking {
        val url = startServer()
        Provisioner(context).provision(TestConfigs.withServer(url, samplePackages()))
        assumeTrue(SamplePayload.isInstalled(context))
        server!!.fetched.clear()

        val result = Provisioner(context).provision(
            TestConfigs.withServer(url, spec(installedVersionCode(), cert = "0".repeat(64))),
        )

        assertTrue(
            "the phone skipped an app whose signature it had not checked",
            server!!.fetched.any { it.startsWith("/apks/") },
        )
        assertTrue(result.report.failures.single().contains("signed with a different key"))
    }
}
