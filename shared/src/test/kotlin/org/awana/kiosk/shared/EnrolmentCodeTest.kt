package org.awana.kiosk.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is pinned here by hand rather than round-tripped through the
 * builder, because the setup wizard reads these bytes too: a change the builder
 * and parser agreed on could still stop a factory-fresh phone enrolling.
 */
class EnrolmentCodeTest {

    private val certHex = "8766564a627e0cccac979be06ed3fb7203358df1a8b78ca66798f56850f85d42"
    private val certChecksum = "h2ZWSmJ-DMysl5vgbtP7cgM1jfGot4ymZ5j1aFD4XUI"

    private fun payload(
        component: String = "org.awana.kiosk/org.awana.kiosk.KioskDeviceAdminReceiver",
        checksum: String = certChecksum,
        ssid: String = "Awana-Setup",
        password: String? = "correcthorsebattery",
        serverUrl: String = "http://192.168.43.1:8080/",
        configSha256: String = "AABBCC",
        extras: Boolean = true,
    ): String {
        val wifiPassword = password?.let { """"android.app.extra.PROVISIONING_WIFI_PASSWORD":"$it",""" }.orEmpty()
        val adminExtras = if (!extras) "" else """
            ,"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{
              "${KioskConfig.EXTRA_SERVER_URL}":"$serverUrl",
              "${KioskConfig.EXTRA_CONFIG_SHA256}":"$configSha256"
            }
        """.trimIndent()
        return """
            {"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":"$component",
             "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":"$checksum",
             "android.app.extra.PROVISIONING_WIFI_SSID":"$ssid",
             $wifiPassword
             "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED":true$adminExtras}
        """.trimIndent()
    }

    @Test
    fun `reads what an update needs out of a setup code`() {
        val code = EnrolmentCode.parse(payload())!!
        assertEquals("http://192.168.43.1:8080", code.bootstrap.serverUrl)
        assertEquals("Awana-Setup", code.wifi.ssid)
        assertEquals("correcthorsebattery", code.wifi.passphrase)
        assertEquals(certChecksum, code.signatureChecksum)
    }

    @Test
    fun `the trailing slash and the hash case do not travel`() {
        val code = EnrolmentCode.parse(payload(serverUrl = "http://10.0.0.1:8080/", configSha256 = "AABBCC"))!!
        assertEquals("http://10.0.0.1:8080", code.bootstrap.serverUrl)
        assertEquals("aabbcc", code.bootstrap.configSha256)
    }

    @Test
    fun `an open hotspot has no passphrase`() {
        assertNull(EnrolmentCode.parse(payload(password = null))!!.wifi.passphrase)
    }

    @Test
    fun `anything that is not one of our codes is not a parse failure`() {
        assertNull(EnrolmentCode.parse("WIFI:S:Cafe;T:WPA;P:hunter2;;"))
        assertNull(EnrolmentCode.parse("https://example.org"))
        assertNull(EnrolmentCode.parse(""))
        assertNull(EnrolmentCode.parse("""{"hello":"world"}"""))
    }

    @Test
    fun `a code for someone else's device owner is refused`() {
        assertNull(EnrolmentCode.parse(payload(component = "com.example.other/com.example.other.Receiver")))
    }

    /** Without these there is nowhere to fetch from and nothing to check against. */
    @Test
    fun `a code carrying no bootstrap is refused`() {
        assertNull(EnrolmentCode.parse(payload(extras = false)))
    }

    @Test
    fun `a code with no signature checksum is refused`() {
        assertNull(EnrolmentCode.parse(payload(checksum = "")))
    }

    @Test
    fun `a session signed with our key is recognised`() {
        assertTrue(EnrolmentCode.parse(payload())!!.signedBySameKeyAs(listOf(certHex)))
    }

    /** The debug fleet's key. Applying its config would reset this phone's PIN. */
    @Test
    fun `a session from the other fleet is not`() {
        val other = "4646982d" + "0".repeat(56)
        assertFalse(EnrolmentCode.parse(payload())!!.signedBySameKeyAs(listOf(other)))
    }

    @Test
    fun `a rotated device matches on any certificate in its history`() {
        val other = "4646982d" + "0".repeat(56)
        assertTrue(EnrolmentCode.parse(payload())!!.signedBySameKeyAs(listOf(other, certHex)))
    }
}
