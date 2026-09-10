package org.awana.kiosk

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.AdminPin
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.PinGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdminPinTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("admin-pin", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun theCorrectPinVerifies() {
        val encoded = AdminPin.hash("1234")
        assertTrue(AdminPin.verify("1234", encoded))
        assertFalse(AdminPin.verify("1235", encoded))
    }

    @Test
    fun thePinIsNeverStoredInPlaintext() {
        val encoded = AdminPin.hash("9182")
        assertFalse(encoded.contains("9182"))
        assertTrue(encoded.startsWith("pbkdf2_sha256$"))
    }

    @Test
    fun theSamePinHashesDifferentlyEachTime() {
        assertNotEquals(AdminPin.hash("1234"), AdminPin.hash("1234"))
    }

    @Test
    fun malformedEncodingsAreRejectedRatherThanCrashing() {
        listOf("", "nonsense", "pbkdf2_sha256\$x\$y\$z", "a\$b\$c\$d").forEach {
            assertFalse("'$it' should not verify", AdminPin.verify("1234", it))
        }
    }

    @Test
    fun repeatedWrongGuessesTriggerABackoff() {
        val encoded = AdminPin.hash("1234")
        val gate = PinGate(context)

        repeat(PinGate.FREE_ATTEMPTS) {
            assertFalse(gate.check("0000", encoded))
            assertEquals("no lockout yet", 0L, gate.lockoutRemainingMs())
        }

        assertFalse(gate.check("0000", encoded))
        assertTrue("a lockout should now be running", gate.lockoutRemainingMs() > 0)

        // The right PIN is refused too while locked out, otherwise the backoff
        // would only slow down an attacker who is already wrong.
        assertFalse(gate.check("1234", encoded))
    }

    @Test
    fun aSuccessfulEntryClearsTheFailureCount() {
        val encoded = AdminPin.hash("1234")
        val gate = PinGate(context)

        assertFalse(gate.check("0000", encoded))
        assertEquals(1, gate.failureCount())

        assertTrue(gate.check("1234", encoded))
        assertEquals(0, gate.failureCount())
    }

    @Test
    fun theConfigSurvivesARoundTripThroughJson() {
        val original = TestConfigs.policyOnly(showNotificationShade = true)
        val store = ConfigStore(context)

        store.save(original)
        val loaded = store.load()

        assertEquals(original, loaded)
        assertEquals(KioskConfig.SCHEMA_VERSION, loaded?.schemaVersion)
        assertTrue(AdminPin.verify(TestConfigs.PIN, loaded!!.adminPinHash))
    }

    @Test
    fun unknownFieldsInStoredConfigAreIgnoredRatherThanFatal() {
        val json = TestConfigs.policyOnly().encode()
            .replaceFirst("{", """{"somethingFromAFutureVersion": 42,""")

        val parsed = KioskConfig.parse(json)
        assertEquals("test-deployment", parsed.deploymentId)
    }
}
