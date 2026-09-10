package org.awana.kiosk.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The deployment configuration document.
 *
 * Built by the provision app, served at [CONFIG_PATH], hashed into the
 * provisioning QR, and held by the kiosk in private storage afterwards. Both
 * apps compile this exact type, so the wire format cannot drift.
 */
@Serializable
data class KioskConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** The provision app's profile id: stable across sessions of one deployment. */
    val deploymentId: String,
    val deploymentName: String,
    val adminPinHash: String,
    /** Base URL of the provisioning server the device enrolled from. */
    val serverUrl: String? = null,
    /** Installed, uninstall-blocked and lock-task allowlisted. */
    val packages: List<PackageSpec> = emptyList(),
    /** Shown as an icon on the launcher. A subset of [packages]. */
    val visibleInLauncher: List<String> = emptyList(),
    /**
     * Networks every device joins at provisioning. Users cannot configure Wi-Fi
     * themselves, so this is how a team reaches its sync network without the
     * admin PIN.
     */
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val showNotificationShade: Boolean = false,
    val locale: String = "en",
    val screenOffTimeoutMs: Long = 120_000,
) {
    val launcherPackages: List<String>
        get() = packages.map { it.packageName }.filter { it in visibleInLauncher }

    fun encode(): String = KioskJson.pretty.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 2

        /**
         * The two admin extras the QR carries. Every extra on the QR path
         * arrives as a string, so both of these are strings.
         */
        const val EXTRA_SERVER_URL = "org.awana.kiosk.SERVER_URL"

        /**
         * Lowercase hex SHA-256 of the config bytes as served. The hash arrives
         * through the setup wizard, which nothing on the network can touch, so
         * it is what makes fetching the config over plain HTTP trustworthy.
         */
        const val EXTRA_CONFIG_SHA256 = "org.awana.kiosk.CONFIG_SHA256"

        /** Path the config is served from, relative to the server URL. */
        const val CONFIG_PATH = "/config.json"

        fun parse(text: String): KioskConfig = KioskJson.pretty.decodeFromString(serializer(), text)
    }
}

/** A package to install, with the signing certificate it must present. */
@Serializable
data class PackageSpec(
    val packageName: String,
    /**
     * Lowercase hex SHA-256 of the signing certificate, recorded by the
     * provision app when the trainer added the APK. The kiosk refuses a first
     * install that presents anything else.
     */
    val certSha256: String,
    /** Path on the provisioning server, relative to the server URL. */
    val path: String = "/apks/$packageName.apk",
    val versionName: String? = null,
    val versionCode: Long? = null,
    /**
     * Runtime permissions to pre-grant, in grant order. Foreground location
     * must precede background location or the background grant is refused.
     */
    val permissions: List<String> = emptyList(),
)

@Serializable
data class WifiNetwork(
    val ssid: String,
    /** Null or empty for an open network. */
    val passphrase: String? = null,
)

/** One configured [Json] per purpose, so both apps encode identically. */
object KioskJson {
    val pretty = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * No pretty-printing and no defaults. Used for what the provision app
     * serves and hashes; the fewer bytes the better on a hotspot, and the
     * served bytes are what the QR's hash is over.
     */
    val compact = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }
}
