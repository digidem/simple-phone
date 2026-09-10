package app.comapeo.kiosk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.comapeo.kiosk.policy.KioskConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
    fun theConfigInTheExtrasBundleParses() {
        val extras = Json.parseToJsonElement(golden())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject

        val json = extras[KioskConfig.EXTRA_KEY]!!.jsonPrimitive.content
        val config = KioskConfig.parse(json)

        assertEquals("Rio Negro", config.deploymentName)
        assertEquals(KioskConfig.SCHEMA_VERSION, config.schemaVersion)
        assertEquals(
            listOf("com.comapeo", "org.telegram.messenger"),
            config.packages.map { it.packageName },
        )
        assertEquals(listOf("com.comapeo"), config.visibleInLauncher)
        assertEquals(false, config.showNotificationShade)
        assertEquals("http://192.168.43.1:8080", config.serverUrl)
    }

    @Test
    fun everyPackageInThePayloadCarriesAFingerprint() {
        val extras = Json.parseToJsonElement(golden())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject
        val config = KioskConfig.parse(extras[KioskConfig.EXTRA_KEY]!!.jsonPrimitive.content)

        config.packages.forEach {
            assertEquals("${it.packageName} has a malformed fingerprint", 64, it.certSha256.length)
        }
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
