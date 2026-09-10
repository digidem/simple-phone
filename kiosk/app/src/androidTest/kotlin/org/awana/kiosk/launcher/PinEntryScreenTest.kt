package org.awana.kiosk.launcher

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.AdminPin
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.PinGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PinEntryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context

    @Before
    fun seedConfig() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("admin-pin", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigStore(context).save(
            KioskConfig(
                deploymentId = "pin-test",
                deploymentName = "PIN test",
                adminPinHash = AdminPin.hash(PIN),
            ),
        )
    }

    private fun type(digits: String) {
        digits.forEach { compose.onNodeWithTag("pin-key-$it").performClick() }
    }

    @Test
    fun theCorrectPinUnlocks() {
        var unlocked = false
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = { unlocked = true }, onCancel = {}) } }

        type(PIN)
        compose.onNodeWithTag("pin-key-✓").performClick()
        compose.waitForIdle()

        assertEquals(true, unlocked)
    }

    @Test
    fun aWrongPinDoesNotUnlock() {
        var unlocked = false
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = { unlocked = true }, onCancel = {}) } }

        type("0000")
        compose.onNodeWithTag("pin-key-✓").performClick()
        compose.waitForIdle()

        assertFalse(unlocked)
    }

    @Test
    fun theEnteredPinIsNeverShownOnScreen() {
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = {}) } }

        type("1234")
        compose.waitForIdle()

        // Only dots, so the PIN cannot be read over a trainer's shoulder.
        compose.onNodeWithTag(TAG_PIN_DOTS).assertTextEquals("••••")
    }

    @Test
    fun theKeypadIsDisabledDuringABackoff() {
        val gate = PinGate(context)
        val hash = ConfigStore(context).load()!!.adminPinHash
        repeat(PinGate.FREE_ATTEMPTS + 1) { gate.check("0000", hash) }

        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag("pin-key-1").assertIsNotEnabled()
    }

    @Test
    fun cancelReturnsWithoutUnlocking() {
        var cancelled = false
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = { cancelled = true }) } }

        compose.onNodeWithTag(TAG_PIN_CANCEL).performClick()
        compose.waitForIdle()

        assertEquals(true, cancelled)
    }

    private companion object {
        const val PIN = "246813"
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEquals(expected: String) {
    val node = fetchSemanticsNode()
    val text = node.config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
        ?.joinToString("") { it.text }
    org.junit.Assert.assertEquals(expected, text)
}

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = if (contains(key)) this[key] else null
