package org.awana.kiosk.launcher

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.AdminPin
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
        compose.onNodeWithTag(TAG_PIN_FIELD).performTextReplacement(digits)
    }

    @Test
    fun theCorrectPinUnlocks() {
        var unlocked = false
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = { unlocked = true }, onCancel = {}) } }

        type(PIN)
        compose.onNodeWithTag(TAG_PIN_SUBMIT).performClick()

        // The check runs off the main thread, so waitForIdle alone would race it.
        compose.waitUntil(CHECK_TIMEOUT_MS) { unlocked }
        assertEquals(true, unlocked)
    }

    @Test
    fun aWrongPinDoesNotUnlock() {
        var unlocked = false
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = { unlocked = true }, onCancel = {}) } }

        type("0000")
        compose.onNodeWithTag(TAG_PIN_SUBMIT).performClick()

        // The check clears what was typed when it refuses it; waiting for that
        // is what tells the test the off-thread check has finished.
        compose.waitUntil(CHECK_TIMEOUT_MS) {
            compose.onNodeWithTag(TAG_PIN_FIELD).editableTextOrNull().isNullOrEmpty()
        }
        assertFalse(unlocked)
    }

    @Test
    fun theEnteredPinIsNeverShownOnScreen() {
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = {}) } }

        type("1234")
        compose.waitForIdle()

        // The password transformation runs before semantics, so not even the
        // accessibility tree carries the digits — nothing on this screen can be
        // read over a trainer's shoulder.
        assertEquals("••••", compose.onNodeWithTag(TAG_PIN_FIELD).editableTextOrNull())
    }

    @Test
    fun onlyDigitsGetIn() {
        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = {}) } }

        compose.onNodeWithTag(TAG_PIN_FIELD).performTextInput("12ab34")
        compose.waitForIdle()

        // Four dots, not six: the letters never made it into the field.
        assertEquals("••••", compose.onNodeWithTag(TAG_PIN_FIELD).editableTextOrNull())
    }

    @Test
    fun theFieldIsDisabledDuringABackoff() {
        val gate = PinGate(context)
        val hash = ConfigStore(context).load()!!.adminPinHash
        repeat(PinGate.FREE_ATTEMPTS + 1) { gate.check("0000", hash) }

        compose.setContent { KioskTheme { PinEntryScreen(onUnlocked = {}, onCancel = {}) } }
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_PIN_FIELD).assertIsNotEnabled()
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

        /** The PIN check is 120k PBKDF2 iterations on a slow emulator. */
        const val CHECK_TIMEOUT_MS = 10_000L
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.textOrNull(): String? =
    fetchSemanticsNode().config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
        ?.joinToString("") { it.text }

private fun androidx.compose.ui.test.SemanticsNodeInteraction.editableTextOrNull(): String? =
    fetchSemanticsNode().config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
        ?.text

private fun <T> androidx.compose.ui.semantics.SemanticsConfiguration.getOrNull(
    key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
): T? = if (contains(key)) this[key] else null
