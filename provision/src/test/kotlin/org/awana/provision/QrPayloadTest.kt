package org.awana.provision

import kotlinx.serialization.json.Json
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.PackageSpec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The provisioning QR, against a fixture.
 *
 * This test generates `testdata/provisioning-qr.golden.json` and the kiosk's
 * `GoldenPayloadTest` parses it exactly as the receiver would. The config and
 * report types are shared code, so the QR shape is the only part of the wire
 * format the type system does not cover.
 */
class QrPayloadTest {

    private fun fixedConfig(packages: List<PackageSpec> = fixedPackages()) = KioskConfig(
        deploymentId = "11111111-2222-3333-4444-555555555555",
        deploymentName = "Rio Negro",
        adminPinHash = "pbkdf2_sha256\$120000\$c2FsdHNhbHRzYWx0c2E9PQ==\$aGFzaGhhc2hoYXNoaGFzaA==",
        serverUrl = "http://192.168.43.1:8080",
        packages = packages,
        visibleInLauncher = listOf("org.example.fieldapp"),
        showNotificationShade = false,
        locale = "pt_BR",
        screenOffTimeoutMs = 120_000,
    )

    /** The same hash the session computes, over the bytes the server serves. */
    private fun configHash(config: KioskConfig): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(KioskJson.compact.encodeToString(KioskConfig.serializer(), config).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun fixedPayload(packages: List<PackageSpec> = fixedPackages()) = QrPayload.build(
        serverUrl = "http://192.168.43.1:8080",
        signatureChecksum = "RmFrZUNoZWNrc3VtRm9yVGVzdHNPbmx5X18wMDAwMDAwMA",
        wifiSsid = "AndroidShare_1234",
        wifiPassphrase = "correcthorsebattery",
        wifiSecurityType = "WPA",
        locale = "pt_BR",
        timeZone = "America/Manaus",
        configSha256 = configHash(fixedConfig(packages)),
    )

    private fun fixedPackages() = listOf(
        PackageSpec(
            packageName = "org.example.fieldapp",
            certSha256 = "f88123ed1f334792c07f1df20ac91038ed2569c3f93f46acb482462d1daf7054",
            path = "/apks/org.example.fieldapp.apk",
            versionName = "13.0",
            versionCode = 40,
        ),
        PackageSpec(
            packageName = "org.telegram.messenger",
            certSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            path = "/apks/org.telegram.messenger.apk",
            versionName = "11.0.0",
            versionCode = 5000,
        ),
    )

    /**
     * Regenerate after an intended payload change with:
     * `./gradlew :provision:testDebugUnitTest -Dgolden=write`. The kiosk's
     * `GoldenPayloadTest` reads the same file out of `testdata/`, so an
     * unintended change fails there instead.
     */
    @Test
    fun matchesTheGoldenFixture() {
        val file = File("../testdata/provisioning-qr.golden.json")
        if (System.getProperty("golden") == "write") {
            file.parentFile.mkdirs()
            file.writeText(fixedPayload())
        }
        val golden = file.readText()

        // Compared as parsed JSON rather than as text, so key order and
        // whitespace do not make this brittle.
        assertEquals(
            Json.parseToJsonElement(golden),
            Json.parseToJsonElement(fixedPayload()),
        )
    }

    @Test
    fun usesTheSignatureChecksumNotThePackageChecksum() {
        val payload = Json.parseToJsonElement(fixedPayload()).jsonObject

        // The signature checksum derives from the signing certificate and is
        // constant across builds. The package checksum is a file hash and would
        // have to be regenerated for every release.
        assertTrue(payload.containsKey("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"))
        assertTrue(!payload.containsKey("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"))
    }

    @Test
    fun leavesSystemAppsEnabled() {
        val payload = Json.parseToJsonElement(fixedPayload()).jsonObject

        // Without this, provisioning disables non-required system apps and can
        // remove components the deployment needs.
        assertEquals(
            true,
            payload["android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"]!!
                .jsonPrimitive.boolean,
        )
    }

    @Test
    fun theQrSizeDoesNotGrowWithTheNumberOfApps() {
        // This is why the config is fetched rather than embedded. Inline, each
        // app cost roughly 200 bytes of escaped JSON and a deployment stopped
        // encoding at all somewhere around eight.
        val two = fixedPayload().toByteArray().size
        val thirty = fixedPayload(
            (1..30).map {
                PackageSpec("com.example.app$it", "0".repeat(64), "/apks/com.example.app$it.apk")
            },
        ).toByteArray().size

        assertEquals(two, thirty)
        assertTrue("a 30-app deployment produced $thirty bytes", thirty <= QrPayload.COMFORTABLE_BYTES)
    }

    @Test
    fun everyAdminExtraIsAString() {
        val extras = Json.parseToJsonElement(fixedPayload())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject

        // Nested or typed values through the QR path are unreliable, so the
        // whole config travels as one JSON string.
        extras.forEach { (key, value) ->
            assertTrue("$key is not a string", value.jsonPrimitive.isString)
        }
    }

    @Test
    fun theBootstrapPointsAtTheConfigAndPinsItsHash() {
        val extras = Json.parseToJsonElement(fixedPayload())
            .jsonObject["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"]!!
            .jsonObject

        assertEquals(
            "http://192.168.43.1:8080",
            extras[KioskConfig.EXTRA_SERVER_URL]!!.jsonPrimitive.content,
        )
        // The hash has to be over exactly the bytes the server hands out, or
        // every device rejects a config that is in fact genuine.
        assertEquals(
            configHash(fixedConfig()),
            extras[KioskConfig.EXTRA_CONFIG_SHA256]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun theConfigItselfIsNotInTheQrAtAll() {
        val payload = fixedPayload()

        // If any of this leaks back into the QR, the size problem returns.
        assertTrue(!payload.contains("adminPinHash"))
        assertTrue(!payload.contains("org.telegram.messenger"))
        assertTrue(!payload.contains("visibleInLauncher"))
    }

    @Test
    fun aDifferentConfigProducesADifferentHash() {
        val shadeOff = configHash(fixedConfig())
        val shadeOn = configHash(fixedConfig().copy(showNotificationShade = true))

        // Otherwise a swapped config would pass the check.
        assertTrue(shadeOff != shadeOn)
    }

    @Test
    fun aTypicalDeploymentFitsInAScannableCode() {
        val size = fixedPayload().toByteArray().size
        assertTrue(
            "a two-app deployment produced $size bytes, over the " +
                "${QrPayload.COMFORTABLE_BYTES} a budget phone camera reads reliably",
            size <= QrPayload.COMFORTABLE_BYTES,
        )
    }

    @Test
    fun theComponentNameMatchesTheReceiverTheKioskDeclares() {
        val payload = Json.parseToJsonElement(fixedPayload()).jsonObject
        assertEquals(
            "org.awana.kiosk/org.awana.kiosk.KioskDeviceAdminReceiver",
            payload["android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun aLongDeploymentNameStillCannotOverflowTheCode() {
        // The app list no longer affects the size, but free text in the QR
        // still does, so the guard stays.
        val huge = QrPayload.build(
            serverUrl = "http://192.168.43.1:8080",
            signatureChecksum = "x".repeat(2_000),
            wifiSsid = "AndroidShare_1234",
            wifiPassphrase = "correcthorsebattery",
            wifiSecurityType = "WPA",
            locale = "pt_BR",
            timeZone = "America/Manaus",
            configSha256 = configHash(fixedConfig()),
        )
        assertTrue(huge.toByteArray().size > QrPayload.COMFORTABLE_BYTES)
    }
}
