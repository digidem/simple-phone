package org.awana.kiosk.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.shared.SetupReport
import org.awana.kiosk.shared.InstalledPackage
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ServerManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

/**
 * The four endpoints a device needs while being set up.
 *
 * Driven over real HTTP against a real socket on the loopback interface, which
 * is the same path a client takes over the hotspot minus the radio.
 */
@RunWith(AndroidJUnit4::class)
class ProvisioningServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: ProvisioningServer
    private lateinit var base: String
    private val received = mutableListOf<SetupReport>()

    private lateinit var kioskApk: File
    private lateinit var payloadApk: File

    private val configJson = KioskJson.compact.encodeToString(
        KioskConfig.serializer(),
        KioskConfig(
            deploymentId = "d1",
            deploymentName = "Test",
            adminPinHash = "pbkdf2_sha256\$120000\$c2FsdA==\$aGFzaA==",
            serverUrl = "http://127.0.0.1",
            packages = listOf(
                PackageSpec("org.example.fieldapp", "a".repeat(64), "/apks/org.example.fieldapp.apk"),
            ),
            launcher = listOf(LauncherEntry("org.example.fieldapp", LauncherRole.HERO)),
        ),
    )

    @Before
    fun startServer() {
        kioskApk = temp.newFile("dpc.apk").apply { writeBytes(ByteArray(2048) { 7 }) }
        payloadApk = temp.newFile("payload.apk").apply { writeBytes(ByteArray(4096) { 9 }) }

        val manifest = ServerManifest(
            deploymentId = "d1",
            deploymentName = "Test",
            packages = listOf(
                PackageSpec("org.example.fieldapp", "a".repeat(64), "/apks/org.example.fieldapp.apk", "13.0", 40),
            ),
        )

        // Port 0 lets the OS pick a free one, so a leftover server from an
        // earlier run cannot make this flaky.
        server = ProvisioningServer(
            port = 0,
            kioskApk = kioskApk,
            payload = listOf(ProvisioningServer.ServedApk("org.example.fieldapp", payloadApk)),
            manifest = manifest,
            configJson = configJson,
            onReport = { _, report -> received += report },
        )
        server.start(5_000, false)
        base = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun get(path: String): Pair<Int, ByteArray> {
        val connection = (URL(base + path).openConnection() as HttpURLConnection)
        return try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.readBytes()
            } else {
                connection.errorStream?.readBytes() ?: ByteArray(0)
            }
            code to body
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String, body: String): Int {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun servesTheKioskApkByteForByte() {
        val (code, body) = get("/dpc.apk")

        assertEquals(200, code)
        // The setup wizard verifies this against the signature checksum in the
        // QR, so a single altered byte would fail provisioning.
        assertTrue(kioskApk.readBytes().contentEquals(body))
    }

    @Test
    fun servesPayloadApksByPackageName() {
        val (code, body) = get("/apks/org.example.fieldapp.apk")

        assertEquals(200, code)
        assertEquals(payloadApk.length().toInt(), body.size)
    }

    @Test
    fun refusesApksThatAreNotPartOfThisDeployment() {
        val (code, _) = get("/apks/com.example.malware.apk")
        assertEquals(404, code)
    }

    @Test
    fun servesTheConfigVerbatimSoItsHashStillMatches() {
        val (code, body) = get("/config.json")

        assertEquals(200, code)
        // Byte-for-byte, not re-encoded: the QR pins a SHA-256 of exactly these
        // bytes, so any reformatting here would make every device reject a
        // config that is in fact genuine.
        assertEquals(configJson, body.decodeToString())
    }

    @Test
    fun publishesTheManifestWithFingerprints() {
        val (code, body) = get("/manifest.json")

        assertEquals(200, code)
        val manifest = KioskJson.pretty.decodeFromString(
            ServerManifest.serializer(),
            body.decodeToString(),
        )
        assertEquals("Test", manifest.deploymentName)
        assertEquals(1, manifest.packages.size)
        assertEquals("a".repeat(64), manifest.packages.first().certSha256)
    }

    @Test
    fun acceptsAnSetupReport() {
        val report = SetupReport(
            deviceId = "device-1",
            deviceLabel = "AB2C",
            deploymentId = "d1",
            deploymentName = "Test",
            manufacturer = "Xiaomi",
            model = "Redmi 12C",
            androidVersion = "13",
            apiLevel = 33,
            kioskVersion = "0.1.0 (1)",
            buildVariant = "sig:abcdef0123456789",
            isDeviceOwner = true,
            installed = listOf(InstalledPackage("org.example.fieldapp", "13.0", 40)),
            policiesApplied = listOf("lockTask", "home"),
            failures = emptyList(),
            permissionFailures = emptyList(),
            hostileOem = "Xiaomi",
            reportedAtEpochMs = 1_700_000_000_000,
        )

        val code = post("/report", report.encode())

        assertEquals(200, code)
        assertEquals(1, received.size)
        assertEquals("device-1", received.first().deviceId)
        assertEquals("Xiaomi", received.first().hostileOem)
        assertTrue(received.first().succeeded)
    }

    @Test
    fun aReportWithFailuresIsNotCountedAsSucceeded() {
        val report = SetupReport(
            deviceId = "device-2",
            deviceLabel = "XY7Z",
            deploymentId = "d1",
            deploymentName = "Test",
            manufacturer = "Nokia",
            model = "G22",
            androidVersion = "12",
            apiLevel = 31,
            kioskVersion = "0.1.0 (1)",
            buildVariant = "sig:abcdef0123456789",
            isDeviceOwner = true,
            failures = listOf("This org.example.fieldapp APK is signed with a different key than expected"),
            reportedAtEpochMs = 1_700_000_000_001,
        )

        post("/report", report.encode())

        assertEquals(false, received.first { it.deviceId == "device-2" }.succeeded)
    }

    @Test
    fun rubbishPostedToReportIsRejectedWithoutKillingTheServer() {
        assertEquals(400, post("/report", "not json at all"))

        // The trainer's session must survive one confused client.
        assertEquals(200, get("/manifest.json").first)
    }

    @Test
    fun unknownPathsAreNotFound() {
        assertEquals(404, get("/../../etc/passwd").first)
        assertEquals(404, get("/").first)
    }
}
