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
    /** How each app appears on the launcher. Apps absent from this list are installed only. */
    val launcher: List<LauncherEntry> = emptyList(),
    /**
     * schemaVersion 2's launcher list. [parse] turns it into [launcher] and
     * clears it, so a device that updates with a v2 config on disk does not
     * come up with a blank launcher.
     */
    @Deprecated("schemaVersion 2. Read by parse() only.", ReplaceWith("launcher"))
    val visibleInLauncher: List<String>? = null,
    /**
     * Networks every device joins at provisioning. Users cannot configure Wi-Fi
     * themselves, so this is how a team reaches its sync network without the
     * admin PIN.
     */
    val wifiNetworks: List<WifiNetwork> = emptyList(),
    val showNotificationShade: Boolean = false,
    val locale: String = "en",
    val screenOffTimeoutMs: Long = 120_000,
    /**
     * Whether the phone keeps its own lock screen.
     *
     * Off by default: with no password set, Android still shows a swipe screen,
     * and that is a barrier for a user who cannot read it. On, the keyguard is
     * left alone and `LOCK_TASK_FEATURE_KEYGUARD` goes in — without that flag
     * lock task suppresses the keyguard and a PIN set in settings would simply
     * never be asked for. It is also the only thing covering the window between
     * boot and the launcher taking the lock.
     */
    val screenLock: Boolean = false,
    /**
     * Version of the kiosk the trainer's app is serving at [DPC_PATH].
     *
     * A phone updates itself when this is higher than what it is running. It
     * travels in the config rather than the manifest because the config is the
     * only thing the QR's hash covers — a kiosk update is the one install that
     * cannot be undone, so what triggers it has to be beyond the network's
     * reach. Null on configs from trainer builds that predate this, which means
     * no self-update.
     */
    val kioskVersionCode: Long? = null,
) {
    /** Entries whose package this config also installs, in launcher order. */
    val launcherEntries: List<LauncherEntry>
        get() = launcher.filter { entry -> packages.any { it.packageName == entry.packageName } }

    val hero: LauncherEntry?
        get() = launcherEntries.firstOrNull { it.role == LauncherRole.HERO }

    val smallEntries: List<LauncherEntry>
        get() = launcherEntries.filter { it.role == LauncherRole.SMALL }

    fun encode(): String = KioskJson.pretty.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA_VERSION = 3

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

        /** Where the trainer's app serves the kiosk APK; see [kioskVersionCode]. */
        const val DPC_PATH = "/dpc.apk"

        fun parse(text: String): KioskConfig =
            KioskJson.pretty.decodeFromString(serializer(), text)
                .migrated()
                .also { it.checkOneHero() }
    }
}

@Suppress("DEPRECATION")
private fun KioskConfig.migrated(): KioskConfig {
    val legacy = visibleInLauncher.orEmpty()
    if (launcher.isNotEmpty() || legacy.isEmpty()) {
        return if (visibleInLauncher == null) this else copy(visibleInLauncher = null)
    }
    // v2 took launcher order from `packages`, so the phone keeps showing the
    // same apps in the same order; the first becomes the hero.
    val visible = packages.map { it.packageName }.filter { it in legacy }
    return copy(
        schemaVersion = KioskConfig.SCHEMA_VERSION,
        launcher = visible.mapIndexed { index, packageName ->
            LauncherEntry(
                packageName = packageName,
                role = if (index == 0) LauncherRole.HERO else LauncherRole.SMALL,
            )
        },
        visibleInLauncher = null,
    )
}

private fun KioskConfig.checkOneHero() {
    val heroes = launcher.filter { it.role == LauncherRole.HERO }
    require(heroes.size <= 1) {
        "A deployment can have at most one hero app, this one names " +
            heroes.joinToString(", ") { it.packageName }
    }
}

/** How one app appears on the launcher. */
@Serializable
data class LauncherEntry(
    val packageName: String,
    val role: LauncherRole = LauncherRole.SMALL,
    /** null uses the app's own label from PackageManager. */
    val label: String? = null,
    /** One short line under the name. Only shown for [LauncherRole.HERO]. */
    val subtitle: String? = null,
    /** Base64 PNG, or null for the app's own icon. Keep under ~40 KB — it rides in the config. */
    val iconPng: String? = null,
)

@Serializable
enum class LauncherRole { HERO, SMALL, HIDDEN }

/**
 * Replaces the entry for the same package, or appends it, upholding the
 * one-hero invariant: promoting an app demotes whichever app held the role.
 */
fun List<LauncherEntry>.withEntry(entry: LauncherEntry): List<LauncherEntry> {
    val others = filterNot { it.packageName == entry.packageName }
    val demoted =
        if (entry.role == LauncherRole.HERO) {
            others.map { if (it.role == LauncherRole.HERO) it.copy(role = LauncherRole.SMALL) else it }
        } else {
            others
        }
    val at = indexOfFirst { it.packageName == entry.packageName }
    return if (at < 0) demoted + entry else demoted.toMutableList().apply { add(at, entry) }
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
        explicitNulls = false
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
        explicitNulls = false
        prettyPrint = false
    }
}
