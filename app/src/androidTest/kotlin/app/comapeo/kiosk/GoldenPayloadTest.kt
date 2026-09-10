package app.comapeo.kiosk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.comapeo.kiosk.policy.KioskConfig
import app.comapeo.kiosk.policy.ProvisioningBootstrap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of the drift guard between this repository and
 * comapeo-provision.
 *
 * `testdata/provisioning-qr.golden.json` is checked into both. The provisioning
 * app has a test that generates it; this one parses it exactly as
 * `KioskDeviceAdminReceiver` would. Change the payload on either side without
 * updating the other and one of the two fails.
 */
@RunWith(AndroidJUnit4::class)
class GoldenPayloadTest {

    private fun golden(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("provisioning-qr.golden.json")
            .bufferedReader()
            .readText()

    @Test
    fun theReceiverNamedInThePayloadIsTheOneThisAppDeclares() {
        val component = Json.parseToJsonElement(golden())
            .jsonObject["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"]!!
            .jsonPrimitive.content

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val declared = app.comapeo.kiosk.policy.DevicePolicy.resolveAdminComponent(context)

        assertEquals("${declared.packageName}/${declared.className}", component)
    }

    @Test
    fun theBootstrapIsReadableExactlyAsTheReceiverReadsIt() {
        val extras = Json.parseToJsonElement(golden())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject

        // Built as a PersistableBundle, because that is what the receiver is
        // handed and getString is what it calls.
        val bundle = android.os.PersistableBundle().apply {
            extras.forEach { (key, value) -> putString(key, value.jsonPrimitive.content) }
        }

        val bootstrap = ProvisioningBootstrap.from(bundle)
        assertNotNull("the receiver could not read the bootstrap", bootstrap)
        assertEquals("http://192.168.43.1:8080", bootstrap!!.serverUrl)
        assertEquals(64, bootstrap.configSha256.length)
    }

    @Test
    fun theConfigIsNotCarriedInThePayloadAtAll() {
        // It is fetched over HTTP and checked against the hash above. If it
        // came back into the QR, the code would grow with every app again.
        val payload = golden()
        assertTrue(!payload.contains("adminPinHash"))
        assertTrue(!payload.contains("visibleInLauncher"))
    }

    @Test
    fun aBootstrapMissingEitherHalfIsRefused() {
        // Half a bootstrap means either an unverifiable config or nowhere to
        // fetch it from; both have to fail closed rather than provision.
        val onlyUrl = android.os.PersistableBundle().apply {
            putString(KioskConfig.EXTRA_SERVER_URL, "http://192.168.43.1:8080")
        }
        val onlyHash = android.os.PersistableBundle().apply {
            putString(KioskConfig.EXTRA_CONFIG_SHA256, "a".repeat(64))
        }

        assertNull(ProvisioningBootstrap.from(onlyUrl))
        assertNull(ProvisioningBootstrap.from(onlyHash))
        assertNull(ProvisioningBootstrap.from(null))
    }

    @Test
    fun everyValueInTheExtrasBundleIsAString() {
        val extras = Json.parseToJsonElement(golden())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject

        // PersistableBundle.getString is how the receiver reads these, so a
        // non-string value would arrive as null and provisioning would do
        // nothing at all.
        extras.forEach { (key, value) ->
            assertTrue("$key is not a string", value.jsonPrimitive.isString)
        }
    }
}
