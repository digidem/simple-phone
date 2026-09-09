package app.comapeo.kiosk.policy

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Wi-Fi management for the admin screen.
 *
 * `DISALLOW_CONFIG_WIFI` means the user cannot add a network, and the
 * provisioning hotspot is gone by the time a device is in the field — so
 * without this a deployed device could never join a network again, and the
 * updater would only ever work over mobile data. A Device Owner is exempt from
 * the restriction and from `addNetwork`'s deprecation, so it can do both.
 */
class WifiAdmin(context: Context) {

    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isEnabled: Boolean get() = wifi.isWifiEnabled

    /** Still permitted for Device Owner apps, unlike for ordinary apps. */
    fun setEnabled(enabled: Boolean): Boolean = wifi.setWifiEnabled(enabled)

    fun savedNetworks(): List<String> = try {
        @Suppress("DEPRECATION")
        wifi.configuredNetworks.orEmpty().mapNotNull { it.SSID?.trim('"') }
    } catch (e: SecurityException) {
        emptyList()
    }

    /**
     * Adds a WPA/WPA2 network and connects to it. Returns false if the platform
     * refused, which for a non-Device-Owner caller it always will.
     */
    @Suppress("DEPRECATION")
    fun addNetwork(ssid: String, passphrase: String?): Boolean {
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (passphrase.isNullOrEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$passphrase\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }
        val networkId = wifi.addNetwork(config)
        if (networkId == -1) {
            Log.w(TAG, "addNetwork refused for $ssid")
            return false
        }
        wifi.enableNetwork(networkId, true)
        return wifi.saveConfiguration()
    }

    private companion object {
        const val TAG = "WifiAdmin"
    }
}
