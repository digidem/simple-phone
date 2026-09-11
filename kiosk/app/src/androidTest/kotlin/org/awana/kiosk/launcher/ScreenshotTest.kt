package org.awana.kiosk.launcher

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.KioskConfig
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ProvisioningBootstrap
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    companion object {
        /** The PIN check is 120k PBKDF2 iterations on a slow emulator. */
        const val CHECK_TIMEOUT_MS = 10_000L

        /** See HomeScreenTest: lock task refuses to start the compose host. */
        @JvmStatic
        @BeforeClass
        fun leaveLockTask() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val policy = DevicePolicy(context)
            if (policy.isDeviceOwner) {
                val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                dpm.setLockTaskPackages(policy.admin, emptyArray())
            }
        }
    }

    @Before
    fun seed() {
        context = ApplicationProvider.getApplicationContext()
        ConfigStore(context).save(config)
    }

    @After
    fun tidyUp() {
        File(context.filesDir, "pending-bootstrap.json").delete()
    }

    /**
     * Apps that are really installed here, so the icons and labels are the ones
     * `PackageManager` hands the launcher rather than stand-ins.
     */
    private val hero get() = "com.android.camera2"
    private val secondaries get() = listOf(
        "com.android.messaging",
        "com.android.gallery3d",
        "com.android.deskclock",
        "com.android.documentsui",
    )

    private val config: KioskConfig
        get() = KioskConfig(
            deploymentId = "rio-negro",
            deploymentName = "Rio Negro",
            adminPinHash = AdminPin.hash("246813"),
            packages = (listOf(hero) + secondaries).map {
                PackageSpec(it, "a".repeat(64), versionName = "13.0")
            },
            launcher = listOf(
                LauncherEntry(hero, LauncherRole.HERO, subtitle = "Fotos do local"),
            ) + secondaries.map { LauncherEntry(it, LauncherRole.SMALL) },
            locale = "pt_BR",
        )

    /** The production path: same resolver the launcher itself runs. */
    private fun apps(config: KioskConfig = this.config): List<LaunchableApp> =
        runBlocking { AppList.visible(context, config) }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { KioskTheme { content() } }
        compose.waitForIdle()
        write(name)
    }

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

    private fun home(state: HomeState) = @Composable {
        HomeScreen(state, onLaunch = {}, onSetUpAgain = {}, onRemoveLock = {}, onAdminGesture = {})
    }

    private fun state(all: List<LaunchableApp>) = HomeState.Apps(
        hero = all.firstOrNull { it.role == LauncherRole.HERO },
        small = all.filter { it.role == LauncherRole.SMALL },
    )

    @Test
    fun heroAndGrid() = shoot("kiosk-01-home", home(state(apps())))

    @Test
    fun heroAlone() = shoot(
        "kiosk-02-home-one-app",
        home(state(apps().filter { it.role == LauncherRole.HERO })),
    )

    @Test
    fun gridOnly() = shoot(
        "kiosk-03-home-no-hero",
        home(state(apps().filter { it.role == LauncherRole.SMALL }.take(2))),
    )

    @Test
    fun notSetUp() = shoot("kiosk-04-not-set-up", home(HomeState.NotSetUp(canSetUpAgain = false)))

    @Test
    fun setupUnfinished() =
        shoot("kiosk-05-setup-unfinished", home(HomeState.SetupUnfinished(canSetUpAgain = true)))

    @Test
    fun noApps() = shoot("kiosk-06-no-apps", home(HomeState.NoApps))

    @Test
    fun pinEntry() = shoot("kiosk-07-pin") {
        PinEntryScreen(onUnlocked = {}, onCancel = {})
    }

    @Test
    fun pinWrong() {
        compose.setContent { KioskTheme { PinEntryScreen({}, {}) } }
        compose.onNodeWithTag(TAG_PIN_FIELD).performTextReplacement("0000")
        compose.onNodeWithTag(TAG_PIN_SUBMIT).performClick()
        // The check runs off the main thread and clears the field when it
        // refuses; waiting for that is what says the message is on screen.
        compose.waitUntil(CHECK_TIMEOUT_MS) {
            compose.onNodeWithTag(TAG_PIN_FIELD)
                .fetchSemanticsNode()
                .config
                .let { config ->
                    val key = androidx.compose.ui.semantics.SemanticsProperties.EditableText
                    !config.contains(key) || config[key].text.isEmpty()
                }
        }
        compose.waitForIdle()
        write("kiosk-08-pin-wrong")
    }

    @Test
    fun adminDoors() = shoot("kiosk-09-admin") { AdminScreen(onDone = {}) }

    @Test
    fun about() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_ABOUT).performClick()
        compose.waitForIdle()
        write("kiosk-10-about")
    }

    @Test
    fun aboutWithAProblem() {
        Provisioner(context).recordBootstrapFailure(
            "Telegram is already on this phone, put there by the maker, so it could not be replaced.",
            ProvisioningBootstrap("http://192.168.43.1:8080", "a".repeat(64)),
        )
        ConfigStore(context).save(config)
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_ABOUT).performClick()
        compose.waitForIdle()
        write("kiosk-11-about-problem")
    }

    @Test
    fun changeThisPhone() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_CHANGE).performClick()
        compose.waitForIdle()
        write("kiosk-12-change")
    }

    @Test
    fun visibleApps() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_CHANGE).performClick()
        compose.onNodeWithTag(TAG_ROW_VISIBLE_APPS).performClick()
        compose.waitForIdle()
        write("kiosk-13-visible-apps")
    }

    @Test
    fun somethingIsWrong() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_WRONG).performClick()
        compose.waitForIdle()
        write("kiosk-14-something-wrong")
    }

    @Test
    fun removeTheLockConfirm() {
        compose.setContent { KioskTheme { AdminScreen(onDone = {}) } }
        compose.onNodeWithTag(TAG_ROW_WRONG).performClick()
        compose.onNodeWithTag(TAG_ROW_UNPROVISION).performClick()
        compose.waitForIdle()
        write("kiosk-15-remove-lock")
    }

    /** A listed app, whatever this device happens to have on it. */
    private val anApp = SemanticsMatcher("a launchable app") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("test-app-") == true
    }

    @Test
    fun setUpForTesting() {
        compose.setContent { KioskTheme { TestSetupScreen(onDone = {}, onCancel = {}) } }
        // `produceState` reads the package manager off the main thread, so the
        // list arrives after the screen does.
        compose.waitUntil(10_000) {
            compose.onAllNodes(anApp).fetchSemanticsNodes().size >= 2
        }
        // Two chosen, so the screenshot shows both roles and an enabled button.
        compose.onAllNodes(anApp)[0].performClick()
        compose.onAllNodes(anApp)[1].performClick()
        // Let the press ripple finish; `waitForIdle` does not wait for it.
        Thread.sleep(500)
        write("kiosk-16-test-setup")
    }
}
