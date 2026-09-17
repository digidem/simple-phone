package org.awana.kiosk.setup

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.DeviceLabel
import org.awana.kiosk.shared.SetupReport
import org.awana.kiosk.shared.InstalledPackage
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.WifiNetwork
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Renders each screen and writes it to external files, for review outside the
 * emulator. Not an assertion suite — it fails only if a screen cannot be drawn
 * at all.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun seed() {
        context = ApplicationProvider.getApplicationContext()
        // The shell reads the session the moment it composes, and MainActivity
        // is what normally creates it.
        SessionService.ensureSession(context)
        File(context.filesDir, "profiles.json").delete()
        runBlocking {
            ApkLibrary(context).add(File(context.applicationInfo.sourceDir).toUri())
        }
        ProfileStore(context).save(rioNegro())
        ProfileStore(context).save(
            DeploymentProfile(
                id = "xingu",
                name = "Xingu",
                adminPinHash = AdminPin.hash("135790"),
                packages = listOf(context.packageName),
                launcher = listOf(LauncherEntry(context.packageName, LauncherRole.SMALL)),
                locale = "pt_BR",
            ),
        )
    }

    private fun rioNegro() = DeploymentProfile(
        id = "rio-negro",
        name = "Rio Negro",
        adminPinHash = AdminPin.hash("246813"),
        packages = listOf(context.packageName),
        launcher = listOf(
            LauncherEntry(
                packageName = context.packageName,
                role = LauncherRole.HERO,
                label = "CoMapeo",
                subtitle = "Mapas e gravações",
            ),
        ),
        locale = "pt_BR",
        timeZone = "America/Manaus",
        wifiNetworks = listOf(WifiNetwork("Awana-Sync", "correcthorsebattery")),
    )

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { ProvisionTheme { content() } }
        write(name)
    }

    // --- deployments ---------------------------------------------------------

    @Test
    fun deploymentsList() = shoot("provision-01-deployments") { ProvisionApp() }

    @Test
    fun deploymentsEmpty() {
        File(context.filesDir, "profiles.json").delete()
        shoot("provision-02-deployments-empty") { ProvisionApp() }
    }

    @Test
    fun deploymentDetail() = shoot("provision-03-deployment-detail") {
        DeploymentDetail(
            profile = rioNegro(),
            onStart = {},
            onEdit = {},
            onDuplicate = {},
            onExport = {},
            onDelete = {},
            onBack = {},
        )
    }

    @Test
    fun deploymentDetailOverflow() {
        compose.setContent { ProvisionTheme { DeploymentDetail(rioNegro(), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag(TAG_DEPLOYMENT_MENU).performClick()
        compose.waitForIdle()
        write("provision-04-deployment-overflow")
    }

    @Test
    fun deploymentDeleteConfirm() {
        compose.setContent { ProvisionTheme { DeploymentDetail(rioNegro(), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag(TAG_DEPLOYMENT_MENU).performClick()
        compose.onNodeWithTag(TAG_DEPLOYMENT_DELETE).performClick()
        compose.waitForIdle()
        write("provision-05-deployment-delete")
    }

    @Test
    fun deploymentEditor() = shoot("provision-06-editor") {
        DeploymentEditor(profile = rioNegro(), onSave = {}, onCancel = {})
    }

    @Test
    fun entryEditor() {
        compose.setContent { ProvisionTheme { DeploymentEditor(rioNegro(), {}, {}) } }
        compose.onNodeWithTag("app-${context.packageName}").performClick()
        compose.waitForIdle()
        write("provision-07-entry-editor")
    }

    @Test
    fun notificationShade() {
        compose.setContent { ProvisionTheme { DeploymentEditor(rioNegro(), {}, {}) } }
        // Below the fold: a click on an off-screen node silently does nothing,
        // which is how this quietly captured the editor instead.
        compose.onNodeWithTag(TAG_EDITOR_SHADE).performScrollTo().performClick()
        compose.waitForIdle()
        write("provision-08-shade")
    }

    @Test
    fun screenTimeout() {
        compose.setContent { ProvisionTheme { DeploymentEditor(rioNegro(), {}, {}) } }
        compose.onNodeWithTag(TAG_EDITOR_TIMEOUT).performScrollTo().performClick()
        compose.waitForIdle()
        write("provision-09-screen-timeout")
    }

    // --- app files -----------------------------------------------------------

    @Test
    fun appLibrary() {
        compose.setContent { ProvisionTheme { ProvisionApp() } }
        compose.onNodeWithTag("tab-apps").performClick()
        write("provision-10-apps")
    }

    @Test
    fun appFile() {
        compose.setContent { ProvisionTheme { ProvisionApp() } }
        compose.onNodeWithTag("tab-apps").performClick()
        compose.onNodeWithTag("apk-${context.packageName}").performClick()
        write("provision-11-app-file")
    }

    @Test
    fun removeWhileInUse() {
        compose.setContent { ProvisionTheme { ProvisionApp() } }
        compose.onNodeWithTag("tab-apps").performClick()
        compose.onNodeWithTag("apk-${context.packageName}").performClick()
        compose.onNodeWithTag(TAG_FILE_REMOVE).performClick()
        write("provision-11b-apk-remove")
    }

    @Test
    fun addingAnApp() {
        compose.setContent { ProvisionTheme { DeploymentEditor(rioNegro(), {}, {}) } }
        compose.onNodeWithTag(TAG_EDITOR_ADD_APP).performScrollTo().performClick()
        write("provision-06b-add-app")
    }

    // --- the session ---------------------------------------------------------

    /**
     * A real session: a hotspot that is already up, the app's own server, and
     * this test standing in for the phones being set up by fetching what they
     * fetch and posting what they post. Nothing here is a synthesised state —
     * every screen below is what the app made of real traffic.
     */
    private fun session(block: (ProvisioningSession) -> Unit) = runBlocking {
        val live = ProvisioningSession(context)
        try {
            live.start(rioNegro(), FakeHotspot()).getOrThrow()
            block(live)
        } finally {
            live.stop()
        }
    }

    private fun get(path: String) {
        val connection = URL("http://127.0.0.1:8080$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        runCatching { connection.inputStream.use { it.readBytes() } }
        connection.disconnect()
    }

    private fun report(id: String, ok: Boolean, make: String, model: String) = SetupReport(
        deviceId = id,
        deviceLabel = DeviceLabel.of(id),
        deploymentId = "rio-negro",
        deploymentName = "Rio Negro",
        manufacturer = make,
        model = model,
        androidVersion = if (make == "Xiaomi") "13" else "12",
        apiLevel = if (make == "Xiaomi") 33 else 31,
        kioskVersion = "0.1.0 (1)",
        buildVariant = "sig:4646982d",
        isDeviceOwner = true,
        installed = if (ok) listOf(InstalledPackage(context.packageName, "0.1.0", 1)) else emptyList(),
        policiesApplied = listOf("Home screen lock", "Notification shade", "Language"),
        failures = if (ok) emptyList() else listOf(
            "Telegram is already on this phone, put there by the maker, so it could not be replaced.",
        ),
        reportedAtEpochMs = System.currentTimeMillis(),
    )

    private fun post(report: SetupReport) {
        val connection = URL("http://127.0.0.1:8080/report").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(report.encode().toByteArray()) }
        connection.inputStream.use { it.readBytes() }
        connection.disconnect()
    }

    /** The session screen as the app renders it, at whatever state the traffic has produced. */
    private fun shootSession(name: String, state: SessionState) = shoot(name) {
        SessionBody(
            state = state,
            now = System.currentTimeMillis(),
            permissionRefused = false,
            onManualStart = { _, _ -> },
            onOpenPhones = {},
            onStop = {},
            onCancel = {},
        )
    }

    @Test
    fun sessionIdle() = session { shootSession("provision-12-session-idle", it.state.value) }

    @Test
    fun sessionInProgress() = session {
        // Three phones finished; two are part-way through the payload.
        repeat(3) { i -> post(report("done$i", true, "Xiaomi", "Redmi 12C")) }
        get("/dpc.apk")
        get(KioskConfig.CONFIG_PATH)
        shootSession("provision-13-session-busy", it.state.value)
    }

    @Test
    fun sessionComplete() = session {
        repeat(5) { i -> post(report("done$i", true, "Xiaomi", "Redmi 12C")) }
        shootSession("provision-14-session-done", it.state.value)
    }

    @Test
    fun sessionProblem() = session {
        repeat(4) { i -> post(report("done$i", true, "Xiaomi", "Redmi 12C")) }
        post(report("bad", false, "HMD Global", "Nokia G22"))
        shootSession("provision-15-session-problem", it.state.value)
    }

    @Test
    fun stopConfirm() = session {
        get("/dpc.apk")
        val state = it.state.value
        compose.setContent {
            ProvisionTheme {
                SessionBody(state, System.currentTimeMillis(), false, { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithTag(TAG_SESSION_STOP).performClick()
        write("provision-16-stop-confirm")
    }

    @Test
    fun phones() = session {
        post(report("done0", true, "Xiaomi", "Redmi 12C"))
        post(report("bad", false, "HMD Global", "Nokia G22"))
        get("/dpc.apk")
        val state = it.state.value
        shoot("provision-17-phones") {
            PhonesScreen(
                phones = state.phones(System.currentTimeMillis()),
                onOpen = {},
                onBack = {},
            )
        }
    }

    @Test
    fun phoneThatWorked() = session {
        post(report("done0", true, "Xiaomi", "Redmi 12C"))
        val delivered = it.state.value.reports.single()
        shoot("provision-18-phone-ok") {
            PhoneDetailScreen(delivered, expected(), onBack = {})
        }
    }

    @Test
    fun phoneThatDidNot() = session {
        post(report("bad", false, "HMD Global", "Nokia G22"))
        val delivered = it.state.value.reports.single()
        shoot("provision-19-phone-problem") {
            PhoneDetailScreen(delivered, expected(), onBack = {})
        }
    }

    private fun expected() = ApkLibrary(context).entries().associate { it.packageName to it.label }

    /**
     * The whole screen, not the compose root: a dialog is its own window, and
     * `onRoot` cannot pick between the two.
     */
    private fun write(name: String) {
        val bitmap = capture(name)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /**
     * `waitForIdle` returns once compose has settled, which is not once the
     * window has been drawn — `takeScreenshot` otherwise catches it before its
     * first frame and writes a blank screen. Which screens lose that race
     * varies from run to run, so retry until something is on it.
     */
    private fun capture(name: String): Bitmap {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitForIdle()
        var bitmap = automation.takeScreenshot().readable()
        var attempts = 1
        while (bitmap.isBlank() && attempts < 20) {
            Thread.sleep(100)
            compose.waitForIdle()
            bitmap = automation.takeScreenshot().readable()
            attempts++
        }
        check(!bitmap.isBlank()) { "nothing was ever drawn on $name" }
        return bitmap
    }

    private fun Bitmap.readable(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    /** One flat colour between the status bar and the navigation bar. */
    private fun Bitmap.isBlank(): Boolean {
        val top = height / 8
        val first = getPixel(width / 2, top)
        for (y in top until height * 7 / 8 step 8) {
            for (x in 0 until width step 8) if (getPixel(x, y) != first) return false
        }
        return true
    }

    private companion object {
        /** Long enough to render as a realistic code, short enough to stay readable. */
        const val QR_SAMPLE =
            """{"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":""" +
                """"org.awana.kiosk/org.awana.kiosk.KioskDeviceAdminReceiver",""" +
                """"android.app.extra.PROVISIONING_WIFI_SSID":"AndroidShare_1234"}"""
    }
}
