package org.awana.kiosk.setup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualHotspotTest {

    private fun details(passphrase: String) = HotspotDetails(
        ssid = "Trainer phone",
        passphrase = passphrase,
        securityType = HotspotDetails.SECURITY_WPA,
        gatewayAddress = "",
    )

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The emulator has no hotspot, so the address it would be found at is supplied. */
    private fun hotspot(passphrase: String, address: String? = "192.168.43.1") =
        ManualHotspot(context, details(passphrase)) { address }

    @Test
    fun anOpenHotspotIsDescribedAsOpen() = runBlocking {
        val started = hotspot("").start().getOrThrow()

        // A QR that claims WPA on an open network never connects.
        assertEquals(HotspotDetails.SECURITY_NONE, started.securityType)
    }

    @Test
    fun aHotspotWithAPasswordIsDescribedAsWpa() = runBlocking {
        val started = hotspot("correcthorsebattery").start().getOrThrow()

        assertEquals(HotspotDetails.SECURITY_WPA, started.securityType)
    }

    @Test
    fun theAddressIsReadFromThePhoneRatherThanTyped() = runBlocking {
        val started = hotspot("correcthorsebattery").start().getOrThrow()

        assertEquals("Trainer phone", started.ssid)
        assertEquals("192.168.43.1", started.gatewayAddress)
    }

    @Test
    fun aPhoneWithNoHotspotRunningIsToldToStartOne() = runBlocking {
        // What this emulator really is: its only address is on its own network.
        val started = ManualHotspot(context, details("correcthorsebattery")).start()

        assertTrue("a session started with no hotspot to serve it", started.isFailure)
    }
}
