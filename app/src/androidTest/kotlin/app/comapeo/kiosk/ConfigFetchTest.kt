package app.comapeo.kiosk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.comapeo.kiosk.policy.ConfigFetch
import app.comapeo.kiosk.policy.KioskConfig
import app.comapeo.kiosk.policy.ProvisioningBootstrap
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Fetching the deployment config over HTTP instead of carrying it in the QR.
 *
 * The QR is the only part of the flow with end-to-end integrity — it reaches
 * this app through the setup wizard, where nothing on the network can alter it.
 * So the config may travel over plain HTTP, but only because the QR pins its
 * hash. These tests are that guarantee: a config whose bytes do not match is
 * refused outright, because a poisoned `adminPinHash` would hand an attacker
 * the whole device.
 */
@RunWith(AndroidJUnit4::class)
class ConfigFetchTest {

    private lateinit var context: Context
    private var server: ConfigServer? = null
    private lateinit var target: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        target = File(context.cacheDir, "fetched-config.json")
        target.delete()
    }

    @After
    fun tearDown() {
        server?.stop()
        target.delete()
    }

    private fun serve(body: String): String {
        val running = ConfigServer(body)
        running.start(2_000, false)
        server = running
        return "http://127.0.0.1:${running.listeningPort}"
    }

    private fun config() = TestConfigs.policyOnly().copy(serverUrl = "http://192.168.43.1:8080")

    @Test
    fun aConfigMatchingItsHashIsAccepted() = runBlocking {
        val body = config().encode()
        val url = serve(body)

        val result = ConfigFetch.fetch(
            ProvisioningBootstrap(url, ConfigFetch.sha256Hex(body.toByteArray())),
            target,
        )

        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals("test-deployment", result.getOrThrow().deploymentId)
    }

    @Test
    fun aConfigWithASubstitutedPinIsRefused() = runBlocking {
        val genuine = config()
        val genuineHash = ConfigFetch.sha256Hex(genuine.encode().toByteArray())

        // What an attacker on the hotspot would actually do: serve a config
        // whose admin PIN they know, and take the device.
        val poisoned = genuine.copy(adminPinHash = "pbkdf2_sha256\$1\$YQ==\$YQ==")
        val url = serve(poisoned.encode())

        val result = ConfigFetch.fetch(ProvisioningBootstrap(url, genuineHash), target)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(
            "a trainer has to be told not to use the device: $message",
            message.contains("Do not use this device"),
        )
    }

    @Test
    fun aSingleAlteredByteIsEnoughToRefuseIt() = runBlocking {
        val genuine = config().encode()
        val genuineHash = ConfigFetch.sha256Hex(genuine.toByteArray())
        val url = serve(genuine.replaceFirst("Test Deployment", "Test Deploymenu"))

        assertTrue(ConfigFetch.fetch(ProvisioningBootstrap(url, genuineHash), target).isFailure)
    }

    @Test
    fun anUnreachableServerSaysWhatToDoAboutIt() = runBlocking {
        val result = ConfigFetch.fetch(
            ProvisioningBootstrap("http://127.0.0.1:1", "a".repeat(64)),
            target,
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message.orEmpty()
        assertTrue(
            "the message must be an instruction, not a status code: $message",
            message.contains("hotspot"),
        )
    }

    @Test
    fun rubbishServedInsteadOfAConfigIsRefused() = runBlocking {
        val body = "<html>404 not found</html>"
        val url = serve(body)

        // Hash matches, so this is the parse failing rather than the check.
        val result = ConfigFetch.fetch(
            ProvisioningBootstrap(url, ConfigFetch.sha256Hex(body.toByteArray())),
            target,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("could not be read"))
    }

    @Test
    fun theHashIsOverTheBytesAsServedNotTheReparsedForm() = runBlocking {
        // The provisioning app serves its config verbatim for this reason. If
        // either side re-encoded, the hashes would differ despite the config
        // being identical, and every device would refuse a genuine deployment.
        val body = config().encode()
        val reEncoded = KioskConfig.parse(body).encode()
        val url = serve(body)

        val result = ConfigFetch.fetch(
            ProvisioningBootstrap(url, ConfigFetch.sha256Hex(reEncoded.toByteArray())),
            target,
        )

        assertEquals(
            "this fixture no longer detects re-encoding",
            ConfigFetch.sha256Hex(body.toByteArray()),
            ConfigFetch.sha256Hex(reEncoded.toByteArray()),
        )
        assertTrue(result.isSuccess)
    }

    private class ConfigServer(private val body: String) : NanoHTTPD(0) {
        override fun serve(session: IHTTPSession): Response =
            if (session.uri == KioskConfig.CONFIG_PATH) {
                newFixedLengthResponse(Response.Status.OK, "application/json", body)
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no")
            }
    }
}
