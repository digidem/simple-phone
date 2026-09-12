package org.awana.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.Updates
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.EnrolmentCode
import org.awana.kiosk.shared.ProvisioningBootstrap
import org.awana.kiosk.shared.WifiNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The step between scanning a code on a phone already in service and applying
 * anything: join, fetch, verify — and stop.
 *
 * Driven with an [EnrolmentCode] rather than a photograph of a QR, the same way
 * `provision()` is driven with a config rather than a setup wizard. The QR
 * itself is a parsing problem and is covered by `:shared`'s unit tests.
 */
@RunWith(AndroidJUnit4::class)
class UpdatesTest {

    private lateinit var context: Context
    private var server: DeploymentServer? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        server?.stop()
        Updates.finish(context)
    }

    private fun serve(config: String): DeploymentServer {
        val running = DeploymentServer(File(context.cacheDir, "unused.apk"))
        running.config = config
        running.begin()
        server = running
        return running
    }

    private fun ourChecksum() =
        EnrolmentCode.checksumOf(Certificates.ofInstalledPackage(context, context.packageName).first())

    private fun code(url: String, hash: String, checksum: String = ourChecksum()) = EnrolmentCode(
        bootstrap = ProvisioningBootstrap(url, hash),
        // No hotspot in a test; the emulator reaches the server on loopback.
        wifi = WifiNetwork("Awana-Setup", "correcthorsebattery"),
        signatureChecksum = checksum,
    )

    private fun servedConfig() = TestConfigs.policyOnly(packages = emptyList())
        .copy(deploymentId = "rio-negro", deploymentName = "Rio Negro")
        .encode()

    @Test
    fun aCodeFromTheTrainerFetchesTheConfigWithoutApplyingIt() = runBlocking {
        val config = servedConfig()
        val running = serve(config)

        val prepared = Updates.prepare(
            context,
            code(running.url, Digests.sha256Hex(config.toByteArray())),
        )

        assertEquals("Rio Negro", prepared.getOrThrow().deploymentName)
        // Fetched and staged, but nothing has been applied to the phone yet.
        assertEquals("rio-negro", Updates.stagedConfig(context)?.deploymentId)
    }

    /**
     * The config carries `adminPinHash`, so a session from the debug fleet
     * could otherwise change a production phone's PIN. Refused before the
     * phone joins anything or fetches a byte.
     */
    @Test
    fun aCodeFromTheOtherFleetIsRefusedBeforeAnythingIsFetched() = runBlocking {
        val config = servedConfig()
        val running = serve(config)

        val prepared = Updates.prepare(
            context,
            code(
                running.url,
                Digests.sha256Hex(config.toByteArray()),
                checksum = EnrolmentCode.checksumOf("0".repeat(64)),
            ),
        )

        val refusal = prepared.exceptionOrNull() as Updates.RefusedException
        assertEquals(Updates.Refusal.OtherFleet, refusal.refusal)
        assertTrue("the phone fetched from a session it had refused", running.fetched.isEmpty())
        assertEquals(null, Updates.stagedConfig(context))
    }

    /**
     * The hash comes off the code, which the network cannot reach. A config
     * that does not match it is an impostor, not a bad download.
     */
    @Test
    fun aConfigThatDoesNotMatchTheCodeIsRefused() = runBlocking {
        val running = serve(servedConfig())

        val prepared = Updates.prepare(context, code(running.url, "0".repeat(64)))

        val refusal = prepared.exceptionOrNull() as Updates.RefusedException
        assertTrue(refusal.refusal is Updates.Refusal.CouldNotFetch)
        assertEquals(null, Updates.stagedConfig(context))
    }

    @Test
    fun anUnreachableTrainerIsRefusedRatherThanLeavingSomethingHalfStaged() = runBlocking {
        val prepared = Updates.prepare(context, code("http://127.0.0.1:1", "0".repeat(64)))

        assertTrue(prepared.exceptionOrNull() is Updates.RefusedException)
        assertEquals(null, Updates.stagedConfig(context))
    }

    @Test
    fun movingDeploymentIsOnlyWhatItSays() {
        val current = TestConfigs.policyOnly(packages = emptyList()).copy(deploymentId = "rio-negro")
        val same = current.copy(deploymentName = "Rio Negro (renamed)")
        val other = current.copy(deploymentId = "xingu")

        assertTrue(Updates.movesDeployment(current, other))
        assertTrue(!Updates.movesDeployment(current, same))
        // A phone that has never been set up is not being moved anywhere.
        assertTrue(!Updates.movesDeployment(null, other))
    }
}
