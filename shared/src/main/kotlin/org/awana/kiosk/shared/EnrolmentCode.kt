package org.awana.kiosk.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.awana.kiosk.shared.Digests.hexToBytes
import java.util.Base64

/**
 * The provisioning QR, read back.
 *
 * The setup wizard reads this code on a factory-fresh phone. The admin screen
 * reads the same code on a phone already in service, which is how a deployment
 * is updated — everything an update needs is already in it: the trainer's
 * Wi-Fi, where to fetch the config, and the hash those bytes must match.
 *
 * One code in the field rather than two, and a trainer cannot hold up the
 * wrong one.
 */
data class EnrolmentCode(
    val bootstrap: ProvisioningBootstrap,
    val wifi: WifiNetwork,
    /**
     * Base64url SHA-256 of the signing certificate the session expects, from
     * `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`.
     *
     * A phone already in service compares this with its own certificate before
     * applying anything. Debug and production fleets must never mix, and the
     * config carries `adminPinHash` — so without the check, a debug session
     * could change the admin PIN of a production phone.
     */
    val signatureChecksum: String,
) {

    /** True when [certSha256Hex] is one of this device's signing certificates. */
    fun signedBySameKeyAs(certSha256Hex: List<String>): Boolean =
        certSha256Hex.any { checksumOf(it) == signatureChecksum }

    companion object {

        /**
         * Matched on the package rather than the whole component: the receiver
         * class is an implementation detail that may be renamed, but a code
         * naming a different package is not ours to act on.
         */
        const val DPC_PACKAGE = "org.awana.kiosk"

        private const val COMPONENT = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
        private const val CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
        private const val WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID"
        private const val WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD"
        private const val ADMIN_EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"

        /** Base64url SHA-256 of a hex certificate digest, unpadded, as the QR carries it. */
        fun checksumOf(certSha256Hex: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(certSha256Hex.hexToBytes())

        /**
         * Null when the text is not one of our provisioning codes at all — a
         * Wi-Fi QR, a URL, someone's boarding pass. The caller says so rather
         * than reporting a parse error nobody can act on.
         */
        fun parse(text: String): EnrolmentCode? {
            val root = runCatching {
                KioskJson.compact.parseToJsonElement(text).jsonObject
            }.getOrNull() ?: return null

            if (root.string(COMPONENT)?.startsWith("$DPC_PACKAGE/") != true) return null

            val extras = runCatching { root[ADMIN_EXTRAS]?.jsonObject }.getOrNull() ?: return null
            val serverUrl = extras.string(KioskConfig.EXTRA_SERVER_URL) ?: return null
            val configSha256 = extras.string(KioskConfig.EXTRA_CONFIG_SHA256) ?: return null
            val checksum = root.string(CHECKSUM) ?: return null
            val ssid = root.string(WIFI_SSID) ?: return null

            return EnrolmentCode(
                bootstrap = ProvisioningBootstrap(
                    serverUrl = serverUrl.trimEnd('/'),
                    configSha256 = configSha256.lowercase(),
                ),
                wifi = WifiNetwork(ssid = ssid, passphrase = root.string(WIFI_PASSWORD)),
                signatureChecksum = checksum,
            )
        }

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.takeIf { it.isNotBlank() }
    }
}
