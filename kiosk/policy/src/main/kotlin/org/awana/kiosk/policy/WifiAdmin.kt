package org.awana.kiosk.policy

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

    /**
     * Empty rather than wrong when the platform refuses: from Android 10
     * `getConfiguredNetworks` needs location permission, which this app does not
     * hold, so an empty list here does not mean no networks are saved.
     */
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
        // Not saveConfiguration(): from Android 10 it is a no-op that always
        // returns false. And never disable the others: at provisioning the
        // device is still on the trainer's hotspot with a report to send, so
        // the new network is saved for auto-join rather than switched to.
        if (!wifi.enableNetwork(networkId, false)) {
            Log.w(TAG, "$ssid was saved but could not be connected to")
        }
        return true
    }

    /**
     * Joins [ssid] now, leaving whatever the phone is currently on.
     *
     * Returns the networks that were enabled beforehand, for [reEnable].
     * `enableNetwork(id, true)` disables every other saved network, and a phone
     * left in that state would never rejoin its own deployment's Wi-Fi once the
     * trainer's hotspot is switched off — which would be a worse problem than
     * the one the update solved.
     */
    @Suppress("DEPRECATION")
    fun joinNow(ssid: String, passphrase: String?): List<Int> {
        val enabledBefore = try {
            wifi.configuredNetworks.orEmpty()
                .filter { it.status != WifiConfiguration.Status.DISABLED }
                .map { it.networkId }
        } catch (e: SecurityException) {
            // Without location permission the list comes back empty rather than
            // wrong; nothing to restore is better than restoring the wrong set.
            emptyList()
        }

        val existing = try {
            wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID?.trim('"') == ssid }?.networkId
        } catch (e: SecurityException) {
            null
        }

        val networkId = existing ?: WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (passphrase.isNullOrEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$passphrase\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
        }.let { wifi.addNetwork(it) }

        if (networkId == -1) {
            Log.w(TAG, "addNetwork refused for $ssid")
            return emptyList()
        }
        wifi.enableNetwork(networkId, true)
        wifi.reconnect()
        return enabledBefore.filterNot { it == networkId }
    }

    /** Puts back what [joinNow] disabled. Safe to call with an empty list. */
    @Suppress("DEPRECATION")
    fun reEnable(networkIds: List<Int>) {
        networkIds.forEach { runCatching { wifi.enableNetwork(it, false) } }
    }

    private companion object {
        const val TAG = "WifiAdmin"
    }
}
