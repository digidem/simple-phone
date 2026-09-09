package app.comapeo.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.comapeo.kiosk.policy.ApkInstaller
import app.comapeo.kiosk.policy.Certificates
import app.comapeo.kiosk.policy.InstallOutcome
import app.comapeo.kiosk.policy.Pins
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * §5.6: certificate verification on first install.
 *
 * Android already rejects signature mismatches and version downgrades against
 * an *installed* package. What has to be caught here is a substituted APK on a
 * first install, where there is no incumbent to compare against — without it a
 * trainer could be socially engineered into deploying a fake CoMapeo across a
 * whole team.
 */
@RunWith(AndroidJUnit4::class)
class InstallVerificationTest {

    private lateinit var context: Context
    private lateinit var ownApk: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ownApk = File(context.applicationInfo.sourceDir)
    }

    @Test
    fun readsTheSameFingerprintFromAnApkFileAsFromTheInstalledPackage() {
        val fromFile = Certificates.ofApkFile(context, ownApk)
        val fromInstalled = Certificates.ofInstalledPackage(context, context.packageName)

        assertTrue("no certificate read from ${ownApk.path}", fromFile.isNotEmpty())
        assertEquals(fromInstalled, fromFile)
    }

    @Test
    fun fingerprintsAreLowercaseHexSha256() {
        val fingerprint = Certificates.ofApkFile(context, ownApk).first()
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
    }

    @Test
    fun matchingToleratesColonsAndCase() {
        val fingerprint = Certificates.ofApkFile(context, ownApk).first()
        val shouted = fingerprint.uppercase().chunked(2).joinToString(":")

        assertTrue(Certificates.matches(listOf(fingerprint), shouted))
    }

    @Test
    fun mismatchedFingerprintIsNotMatched() {
        val fingerprint = Certificates.ofApkFile(context, ownApk).first()
        assertFalse(Certificates.matches(listOf(fingerprint), Pins.COMAPEO_CERT_SHA256))
    }

    @Test
    fun installIsRefusedWhenTheCertificateDoesNotMatch() = runBlocking {
        // The debug-signed kiosk APK standing in for an APK that claims to be
        // CoMapeo. Nothing is committed to PackageInstaller: the check happens
        // before the session is created.
        val outcome = ApkInstaller(context).install(
            apk = ownApk,
            packageName = Pins.COMAPEO_PACKAGE,
            expectedCert = Pins.COMAPEO_CERT_SHA256,
        )

        assertTrue(outcome is InstallOutcome.Failure)
        val reason = (outcome as InstallOutcome.Failure).reason
        assertTrue(
            "the reason must name the problem in words a trainer can act on: $reason",
            reason.contains("signed with a different key"),
        )
    }

    @Test
    fun comapeosPinnedFingerprintOverridesWhateverTheConfigClaims() {
        val attackerSupplied = "0".repeat(64)

        assertEquals(
            Pins.COMAPEO_CERT_SHA256,
            Pins.expectedCertFor(Pins.COMAPEO_PACKAGE, attackerSupplied),
        )
        // Trainer-supplied packages are verified against the config instead,
        // since Awana does not redistribute them and cannot pin them.
        assertEquals(
            attackerSupplied,
            Pins.expectedCertFor("org.telegram.messenger", attackerSupplied),
        )
    }

    @Test
    fun missingFileFailsWithAReadableReason() = runBlocking {
        val outcome = ApkInstaller(context).install(
            apk = File(context.cacheDir, "does-not-exist.apk"),
            packageName = "com.example",
            expectedCert = null,
        )

        assertTrue(outcome is InstallOutcome.Failure)
        assertTrue((outcome as InstallOutcome.Failure).reason.contains("missing or empty"))
    }
}
