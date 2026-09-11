package org.awana.kiosk.launcher

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing the home screen exists to do.
 *
 * `HomeScreenTest` only proves the card reports a tap; this drives the real
 * activity, so it covers `getLaunchIntentForPackage` and the `startActivity`
 * behind it — the step that silently does nothing if an app is in the config
 * but has no launcher entry point.
 *
 * uiautomator rather than Compose: the assertion is about another app being in
 * front, which is outside the compose hierarchy entirely.
 */
@RunWith(AndroidJUnit4::class)
class LaunchAppTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    /** Installed and launchable on the UI emulator, unlike a deployment's real payload. */
    private val target = "com.android.deskclock"

    @Before
    fun seedConfig() {
        context = ApplicationProvider.getApplicationContext()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ConfigStore(context).save(
            KioskConfig(
                deploymentId = "launch-test",
                deploymentName = "Launch test",
                adminPinHash = AdminPin.hash("246813"),
                packages = listOf(PackageSpec(target, "a".repeat(64))),
                launcher = listOf(LauncherEntry(target, LauncherRole.HERO)),
            ),
        )
    }

    @After
    fun goHome() {
        device.pressHome()
        ConfigStore(context).clear()
    }

    @Test
    fun tappingTheHeroBringsThatAppToTheFront() {
        val label = context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(target, 0))
            .toString()

        context.startActivity(
            Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertTrue(
            "the launcher did not come up",
            device.wait(Until.hasObject(By.text(label)), TIMEOUT_MS),
        )

        device.findObject(By.text(label)).click()

        assertTrue(
            "tapping $label did not bring $target to the front",
            device.wait(Until.hasObject(By.pkg(target).depth(0)), TIMEOUT_MS),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
