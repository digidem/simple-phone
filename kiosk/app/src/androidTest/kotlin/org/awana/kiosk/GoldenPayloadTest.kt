package org.awana.kiosk

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.ProvisioningBootstrap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * The QR shape, read back the way the receiver reads it.
 *
 * `:setup`'s `QrPayloadTest` generates
 * `testdata/provisioning-qr.golden.json` and this test parses it exactly as
 * `KioskDeviceAdminReceiver` would. Both apps compile the config and report
 * types out of `shared/`, so those cannot drift; the QR is the one part of the
 * wire format no type covers, which is what the fixture is still for.
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
        val declared = org.awana.kiosk.policy.DevicePolicy.resolveAdminComponent(context)

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
        assertTrue(!payload.contains("launcher"))
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
