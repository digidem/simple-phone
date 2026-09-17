package org.awana.kiosk.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    @Test
    fun anOpenHotspotIsDescribedAsOpen() = runBlocking {
        val started = ManualHotspot(details("")).start().getOrThrow()

        // A QR that claims WPA on an open network never connects.
        assertEquals(HotspotDetails.SECURITY_NONE, started.securityType)
    }

    @Test
    fun aHotspotWithAPasswordIsDescribedAsWpa() = runBlocking {
        val started = ManualHotspot(details("correcthorsebattery")).start().getOrThrow()

        assertEquals(HotspotDetails.SECURITY_WPA, started.securityType)
    }

    @Test
    fun theAddressIsReadFromThePhoneRatherThanTyped() = runBlocking {
        val started = ManualHotspot(details("correcthorsebattery")).start().getOrThrow()

        assertEquals("Trainer phone", started.ssid)
        assert(started.gatewayAddress.isNotEmpty())
    }
}
