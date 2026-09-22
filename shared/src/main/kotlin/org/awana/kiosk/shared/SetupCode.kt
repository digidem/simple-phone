package org.awana.kiosk.shared

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.awana.kiosk.shared.Digests.hexToBytes
import java.io.File
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
data class SetupCode(
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

        const val DPC_RECEIVER = "org.awana.kiosk.KioskDeviceAdminReceiver"

        /**
         * Beyond roughly this many bytes a QR needs so many modules that a budget
         * phone camera struggles with it in poor light.
         */
        const val COMFORTABLE_BYTES = 1_800

        private const val COMPONENT = "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
        private const val CHECKSUM = "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
        private const val WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID"
        private const val WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD"
        private const val ADMIN_EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"

        /** Base64url SHA-256 of a hex certificate digest, unpadded, as the QR carries it. */
        fun checksumOf(certSha256Hex: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(certSha256Hex.hexToBytes())

        /**
         * Base64url SHA-256 of the signing certificate of an APK on disk, as
         * `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` requires.
         *
         * Computed from the APK being served rather than hardcoded, so debug and
         * release builds both work and there is no constant to forget at release
         * time.
         */
        fun signatureChecksumOf(context: Context, apk: File): String {
            val fingerprint = Certificates.ofApkFile(context, apk).firstOrNull()
                ?: error("Could not read a signing certificate from the kiosk APK at ${apk.path}")
            return checksumOf(fingerprint)
        }

        /**
         * The JSON the Android setup wizard reads from a provisioning QR, and
         * that [parse] reads back.
         *
         * Two details matter and are easy to get wrong:
         *
         * `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` rather than
         * `..._PACKAGE_CHECKSUM`. The signature checksum derives from the signing
         * certificate and so is constant across builds; the package checksum is a
         * file hash and changes with every release. From Android 10 only SHA-256
         * is accepted.
         *
         * `LEAVE_ALL_SYSTEM_APPS_ENABLED` must be true. Without it, provisioning
         * disables non-required system apps, which can take out components the
         * deployment needs.
         *
         * `ALLOW_OFFLINE` must be true. From Android 14 the wizard otherwise
         * insists on internet to update the platform's provisioning role holder,
         * and the hotspot has none: it says "couldn't connect to the internet" and
         * returns to the scanner.
         */
        fun build(
            serverUrl: String,
            /** Base64url SHA-256 of the kiosk's signing certificate, no padding. */
            signatureChecksum: String,
            wifiSsid: String,
            wifiPassphrase: String,
            wifiSecurityType: String,
            locale: String,
            timeZone: String,
            /** Lowercase hex SHA-256 of the config bytes the server will hand out. */
            configSha256: String,
        ): String {
            val payload = buildJsonObject {
                put(COMPONENT, "$DPC_PACKAGE/$DPC_RECEIVER")
                put(
                    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION",
                    "${serverUrl.trimEnd('/')}/dpc.apk",
                )
                put(CHECKSUM, signatureChecksum)
                put(WIFI_SSID, wifiSsid)
                put(WIFI_PASSWORD, wifiPassphrase)
                put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", wifiSecurityType)
                put(
                    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED",
                    JsonPrimitive(true),
                )
                put("android.app.extra.PROVISIONING_SKIP_ENCRYPTION", JsonPrimitive(true))
                put("android.app.extra.PROVISIONING_ALLOW_OFFLINE", JsonPrimitive(true))
                put("android.app.extra.PROVISIONING_LOCALE", locale)
                put("android.app.extra.PROVISIONING_TIME_ZONE", timeZone)
                put(
                    ADMIN_EXTRAS,
                    buildJsonObject {
                        // Every value here must be a string. Nested or typed values
                        // through the QR path are unreliable.
                        put(KioskConfig.EXTRA_SERVER_URL, serverUrl.trimEnd('/'))
                        put(KioskConfig.EXTRA_CONFIG_SHA256, configSha256)
                    },
                )
            }
            return KioskJson.compact.encodeToString(JsonObject.serializer(), payload)
        }

        /**
         * Null when the text is not one of our provisioning codes at all — a
         * Wi-Fi QR, a URL, someone's boarding pass. The caller says so rather
         * than reporting a parse error nobody can act on.
         */
        fun parse(text: String): SetupCode? {
            val root = runCatching {
                KioskJson.compact.parseToJsonElement(text).jsonObject
            }.getOrNull() ?: return null

            if (root.string(COMPONENT)?.startsWith("$DPC_PACKAGE/") != true) return null

            val extras = runCatching { root[ADMIN_EXTRAS]?.jsonObject }.getOrNull() ?: return null
            val serverUrl = extras.string(KioskConfig.EXTRA_SERVER_URL) ?: return null
            val configSha256 = extras.string(KioskConfig.EXTRA_CONFIG_SHA256) ?: return null
            val checksum = root.string(CHECKSUM) ?: return null
            val ssid = root.string(WIFI_SSID) ?: return null

            return SetupCode(
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
