package org.awana.kiosk

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.Certificates
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import fi.iki.elonen.NanoHTTPD
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
import java.io.FileInputStream

/**
 * The whole post-scan path against a real HTTP server: download, verify the
 * signing certificate, install, pre-grant, apply policy, report.
 *
 * This is the §8.1 seam paying off — `provision()` is called directly with a
 * synthesised config, so none of this needs a camera or a setup wizard. The
 * server stands in for comapeo-provision, whose own endpoints are tested in
 * that repository; between them and the shared golden payload fixture, the only
 * untested part of the loop is the radio.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EndToEndProvisioningTest {

    private lateinit var context: Context
    private lateinit var payloadApk: File
    private var server: PayloadServer? = null
    private val reports = mutableListOf<EnrolmentReport>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue(DevicePolicy(context).isDeviceOwner)
        uninstallSample()

        payloadApk = File(context.cacheDir, "sample-payload.apk")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample-payload.apk")
            .use { input -> payloadApk.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun tearDown() {
        server?.stop()
        uninstallSample()
    }

    private fun uninstallSample() {
        // Uninstall protection is applied to every package in the config, so
        // this has to be lifted first — which is itself the feature working.
        val policy = DevicePolicy(context)
        if (policy.isDeviceOwner) {
            val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            // Three separate layers stop a user removing an app, and all three
            // have to come off to clean up between tests: the per-package
            // block, the force-stop block, and DISALLOW_UNINSTALL_APPS.
            runCatching { dpm.setUninstallBlocked(policy.admin, SAMPLE_PACKAGE, false) }
            runCatching { dpm.setUserControlDisabledPackages(policy.admin, emptyList()) }
            runCatching {
                dpm.clearUserRestriction(policy.admin, android.os.UserManager.DISALLOW_UNINSTALL_APPS)
            }
        }
        val output = shell("pm uninstall $SAMPLE_PACKAGE")
        check(!isSampleInstalled() || output.contains("Unknown package")) {
            "could not remove the sample payload between tests: $output"
        }
    }

    private fun isSampleInstalled() =
        context.packageManager.getInstalledPackages(0).any { it.packageName == SAMPLE_PACKAGE }

    /**
     * Reads the command's output to completion. `executeShellCommand` runs
     * asynchronously, so closing the descriptor straight away kills the command
     * before it has done anything.
     */
    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it) }
            .use { it.readBytes().decodeToString() }

    private fun startServer(): String {
        val running = PayloadServer(payloadApk) { reports += it }
        running.start(5_000, false)
        server = running
        return "http://127.0.0.1:${running.listeningPort}"
    }

    private fun sampleFingerprint() = Certificates.ofApkFile(context, payloadApk).first()

    @Test
    fun aDeviceFetchesInstallsAndReportsInOnePass() = runBlocking {
        val serverUrl = startServer()
        val config = TestConfigs.withServer(
            serverUrl,
            listOf(PackageSpec(SAMPLE_PACKAGE, sampleFingerprint(), "/apks/$SAMPLE_PACKAGE.apk")),
        )

        val result = Provisioner(context).provision(config)

        assertTrue("provision reported: ${result.report.failures}", result.report.failures.isEmpty())
        assertTrue(
            "the payload was not installed",
            isSampleInstalled(),
        )
        assertTrue("the completion report was not delivered", result.reportDelivered)
        assertEquals(1, reports.size)
    }

    @Test
    fun theReportCarriesWhatATrainerNeedsToSeeOnTheDashboard() = runBlocking {
        val serverUrl = startServer()
        Provisioner(context).provision(
            TestConfigs.withServer(
                serverUrl,
                listOf(PackageSpec(SAMPLE_PACKAGE, sampleFingerprint(), "/apks/$SAMPLE_PACKAGE.apk")),
            ),
        )

        val report = reports.single()
        assertEquals("test-deployment", report.deploymentId)
        assertTrue(report.isDeviceOwner)
        assertTrue(report.deviceId.isNotBlank())
        assertTrue(report.policiesApplied.contains("lockTask"))
        assertTrue(report.policiesApplied.contains("networkRestrictions"))
        assertTrue(report.installed.any { it.packageName == SAMPLE_PACKAGE })
        // Which key signed this build, so a debug fleet cannot be mistaken for
        // a production one.
        assertTrue(report.buildVariant.startsWith("sig:"))
    }

    @Test
    fun installedAppsCannotBeUninstalledAfterwards() = runBlocking {
        val serverUrl = startServer()
        Provisioner(context).provision(
            TestConfigs.withServer(
                serverUrl,
                listOf(PackageSpec(SAMPLE_PACKAGE, sampleFingerprint(), "/apks/$SAMPLE_PACKAGE.apk")),
            ),
        )

        // Not through DPM, but the way a user would: apps getting accidentally
        // uninstalled is one of the problems this project exists to solve.
        val output = shell("pm uninstall $SAMPLE_PACKAGE")

        assertTrue("uninstall was not refused: $output", output.contains("Failure"))
        assertTrue(
            "the payload was uninstalled despite uninstall protection",
            isSampleInstalled(),
        )
    }

    @Test
    fun anApkSignedWithTheWrongKeyIsRefusedAndSaidSoPlainly() = runBlocking {
        val serverUrl = startServer()
        val wrongFingerprint = "0".repeat(64)
        val config = TestConfigs.withServer(
            serverUrl,
            listOf(PackageSpec(SAMPLE_PACKAGE, wrongFingerprint, "/apks/$SAMPLE_PACKAGE.apk")),
        )

        val result = Provisioner(context).provision(config)

        assertFalse(
            "an APK with a mismatched fingerprint was installed",
            isSampleInstalled(),
        )
        val reason = result.report.failures.single()
        assertTrue(
            "the failure must be readable by a trainer, not a status code: $reason",
            reason.contains("signed with a different key"),
        )
        // And it reaches the dashboard rather than dying in a log.
        assertTrue(reports.single().failures.isNotEmpty())
    }

    @Test
    fun anUnreachableServerFailsWithoutLosingThePolicySet() = runBlocking {
        val config = TestConfigs.withServer(
            "http://127.0.0.1:1",
            listOf(PackageSpec(SAMPLE_PACKAGE, sampleFingerprint(), "/apks/$SAMPLE_PACKAGE.apk")),
        )

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
        assertTrue(reports.isNotEmpty())
    }

    private class PayloadServer(
        private val apk: File,
        private val onReport: (EnrolmentReport) -> Unit,
    ) : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response = when {
            session.method == Method.POST && session.uri == "/report" -> {
                val body = HashMap<String, String>()
                session.parseBody(body)
                onReport(EnrolmentReport.parse(body["postData"].orEmpty()))
                newFixedLengthResponse("ok")
            }

            session.uri.startsWith("/apks/") -> newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.android.package-archive",
                FileInputStream(apk),
                apk.length(),
            )

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no")
        }
    }

    private companion object {
        const val SAMPLE_PACKAGE = "org.awana.kiosk.sample"
    }
}
