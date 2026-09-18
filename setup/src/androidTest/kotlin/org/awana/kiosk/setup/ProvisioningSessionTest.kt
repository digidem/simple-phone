package org.awana.kiosk.setup

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.ServerManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

/**
 * A whole session against a hotspot that is already up: the QR the trainer
 * shows and the config the phone being set up fetches have to agree, and the
 * hash in the QR is the only end-to-end integrity the flow has.
 */
@RunWith(AndroidJUnit4::class)
class ProvisioningSessionTest {

    private lateinit var context: Context
    private lateinit var session: ProvisioningSession
    private lateinit var payloadPackage: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "apk-library.json").delete()
        File(context.filesDir, "apks").deleteRecursively()

        // The only APK certain to be on the device is this app's own.
        val library = ApkLibrary(context)
        payloadPackage = library.add(File(context.applicationInfo.sourceDir).toUri())
            .getOrThrow().packageName
        session = ProvisioningSession(context)
    }

    @After
    fun tearDown() {
        session.stop()
    }

    private fun profile(name: String = "Rio Negro") = DeploymentProfile(
        name = name,
        adminPinHash = AdminPin.hash("246813"),
        packages = listOf(payloadPackage),
        launcher = listOf(LauncherEntry(payloadPackage, LauncherRole.HERO)),
        locale = "pt_BR",
        timeZone = "America/Manaus",
    )

    private fun adminExtras(payload: String) = Json.parseToJsonElement(payload)
        .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
        .jsonObject

    private fun get(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.inputStream.readBytes()
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun aSessionWhoseAddressGoesAwayTakesItsCodeDown() = runBlocking {
        session.start(profile(), FakeHotspot()).getOrThrow()
        assertTrue(session.state.value.qrPayload != null)

        // The hotspot switched off under a running session.
        session.watchAddress(isStillLocal = { false })

        val state = session.state.value
        assertEquals("a code that leads nowhere is still on screen", null, state.qrPayload)
        assertTrue("the trainer was not told why", !state.error.isNullOrBlank())
    }

    @Test
    fun theServedConfigHashesToTheValueInTheQr() = runBlocking {
        session.start(profile(), FakeHotspot()).getOrThrow()
        val state = session.state.value

        val served = get(state.serverUrl + KioskConfig.CONFIG_PATH)

        // Hashed as served, never re-encoded: a device refuses a config whose
        // bytes do not hash to what the QR carried.
        assertEquals(
            Digests.sha256Hex(served),
            adminExtras(state.qrPayload!!)[KioskConfig.EXTRA_CONFIG_SHA256]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun theServedConfigCarriesTheProfileIdAsItsDeploymentId() = runBlocking {
        val profile = profile()
        session.start(profile, FakeHotspot()).getOrThrow()

        val config = KioskConfig.parse(
            get(session.state.value.serverUrl + KioskConfig.CONFIG_PATH).decodeToString(),
        )

        assertEquals(profile.id, config.deploymentId)
        assertEquals(profile.name, config.deploymentName)
        assertEquals(listOf(payloadPackage), config.packages.map { it.packageName })

        val manifest = KioskJson.pretty.decodeFromString(
            ServerManifest.serializer(),
            get(session.state.value.serverUrl + "/manifest.json").decodeToString(),
        )
        assertEquals(profile.id, manifest.deploymentId)
    }

    @Test
    fun twoSessionsOfOneDeploymentShareItsId() = runBlocking {
        val profile = profile()

        session.start(profile, FakeHotspot()).getOrThrow()
        val first = KioskConfig.parse(
            get(session.state.value.serverUrl + KioskConfig.CONFIG_PATH).decodeToString(),
        )
        session.start(profile, FakeHotspot()).getOrThrow()
        val second = KioskConfig.parse(
            get(session.state.value.serverUrl + KioskConfig.CONFIG_PATH).decodeToString(),
        )

        // Phones set up on different days still belong to one deployment.
        assertEquals(first.deploymentId, second.deploymentId)
        assertEquals(profile.id, second.deploymentId)
    }

    @Test
    fun theSessionKnowsWhichProfileItIsRunning() = runBlocking {
        val profile = profile()

        session.start(profile, FakeHotspot()).getOrThrow()

        // A recreated activity finds the running session through this rather
        // than starting a second one and tearing the hotspot down.
        assertTrue(session.isActiveFor(profile.id))
        assertEquals(profile.id, session.state.value.profileId)
        assertTrue(session.state.value.running)
    }

    @Test
    fun aDeploymentWithAnAppThatIsNotInTheLibraryStopsTheHotspot() = runBlocking {
        val hotspot = FakeHotspot()

        val result = session.start(
            profile().copy(packages = listOf("com.example.missing")),
            hotspot,
        )

        assertTrue(result.isFailure)
        assertTrue(hotspot.stopped)
        assertTrue(session.state.value.error!!.contains("com.example.missing"))
    }

    @Test
    fun theBundledKioskApkIsServed() = runBlocking {
        session.start(profile(), FakeHotspot()).getOrThrow()

        val apk = get(session.state.value.serverUrl + "/dpc.apk")

        assertTrue(apk.size > 1_000)
        // The zip local file header, so this is an APK and not an error page.
        assertEquals("PK", apk.copyOfRange(0, 2).decodeToString())
    }
}
