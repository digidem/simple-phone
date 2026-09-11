package org.awana.kiosk.shared

import android.os.PersistableBundle
import kotlinx.serialization.Serializable

/**
 * What the QR carries in place of the config itself: where to fetch it, and
 * what those bytes must hash to.
 *
 * Embedded, the config grew by roughly 200 bytes per app and stopped encoding
 * at all somewhere around eight. This is constant however many apps a
 * deployment has.
 */
@Serializable
data class ProvisioningBootstrap(
    val serverUrl: String,
    val configSha256: String,
) {
    fun encode(): String = KioskJson.compact.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): ProvisioningBootstrap =
            KioskJson.compact.decodeFromString(serializer(), text)

        /** Returns null when the extras do not carry a usable bootstrap. */
        fun from(extras: PersistableBundle?): ProvisioningBootstrap? {
            val serverUrl = extras?.getString(KioskConfig.EXTRA_SERVER_URL)?.takeIf { it.isNotBlank() }
                ?: return null
            val hash = extras.getString(KioskConfig.EXTRA_CONFIG_SHA256)?.takeIf { it.isNotBlank() }
                ?: return null
            return ProvisioningBootstrap(serverUrl.trimEnd('/'), hash.trim().lowercase())
        }
    }
}
