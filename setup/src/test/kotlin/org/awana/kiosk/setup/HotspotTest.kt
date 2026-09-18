package org.awana.kiosk.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotTest {

    @Test
    fun aTetheringInterfaceBeatsTheOneTheTrainerPhoneIsJoinedTo() {
        // A trainer's phone that is also a Wi-Fi client holds a site-local
        // address on wlan0 that no phone being set up can reach.
        assertTrue(interfaceRank("ap0") < interfaceRank("wlan0"))
        assertTrue(interfaceRank("swlan0") < interfaceRank("wlan0"))
        assertTrue(interfaceRank("wlan1") < interfaceRank("wlan0"))
        assertTrue(interfaceRank("wlan0") < interfaceRank("rmnet_data0"))
    }

    @Test
    fun theAddressOfTheWifiThisPhoneIsJoinedToIsNeverTheHotspots() {
        // A phone with its hotspot on wlan0 and its own Wi-Fi connection on
        // wlan1: the names alone would pick the wrong one.
        val addresses = listOf(
            LocalAddress("wlan1", "192.168.1.66"),
            LocalAddress("wlan0", "10.68.231.1"),
        )

        assertEquals("10.68.231.1", pickHotspotAddress(addresses, clientInterfaces = setOf("wlan1")))
    }

    @Test
    fun aPhoneThatIsOnlyAClientHasNoHotspotAddress() {
        val addresses = listOf(LocalAddress("wlan0", "192.168.1.66"))

        assertEquals(null, pickHotspotAddress(addresses, clientInterfaces = setOf("wlan0")))
    }

    @Test
    fun theInterfacesTheHotspotCanRunOnAreRankedTogether() {
        assertEquals(interfaceRank("ap0"), interfaceRank("swlan0"))
        assertEquals(interfaceRank("ap0"), interfaceRank("wlan9"))
    }

    @Test
    fun foregroundLocationIsGrantedBeforeBackgroundLocation() {
        val ordered = orderedForGrant(
            listOf(
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION",
            ),
        )

        // The background grant is refused outright unless the foreground one
        // has already been made.
        assertTrue(
            ordered.indexOf("android.permission.ACCESS_FINE_LOCATION") <
                ordered.indexOf("android.permission.ACCESS_BACKGROUND_LOCATION"),
        )
        assertEquals(3, ordered.size)
    }
}
