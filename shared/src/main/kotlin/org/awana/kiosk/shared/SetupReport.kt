package org.awana.kiosk.shared

import kotlinx.serialization.Serializable

/**
 * What a device tells the setup app when it finishes setting up. This is
 * what turns a fiddly technical sequence into something a trainer can watch and
 * report on, so it is a product feature rather than telemetry.
 */
@Serializable
data class SetupReport(
    val deviceId: String,
    /** Short human-readable code derived from [deviceId]; see [DeviceLabel]. */
    val deviceLabel: String,
    val deploymentId: String,
    val deploymentName: String,
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val kioskVersion: String,
    val buildVariant: String,
    val isDeviceOwner: Boolean,
    val installed: List<InstalledPackage> = emptyList(),
    /**
     * What happened to each app this time round. Empty on reports from before
     * updating existed, so a setup app must not read it as "nothing
     * happened".
     */
    val packageOutcomes: List<PackageOutcome> = emptyList(),
    val policiesApplied: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val permissionFailures: List<String> = emptyList(),
    /** Set when the device ships an OEM battery manager needing a manual step. */
    val hostileOem: String? = null,
    val reportedAtEpochMs: Long,
) {
    /** A device is only "set up" when nothing at all went wrong. */
    val succeeded: Boolean
        get() = isDeviceOwner && failures.isEmpty() && permissionFailures.isEmpty()

    fun encode(): String = KioskJson.pretty.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): SetupReport = KioskJson.pretty.decodeFromString(serializer(), text)
    }
}

@Serializable
data class PackageOutcome(
    val packageName: String,
    val result: InstallResult,
    val versionName: String? = null,
)

/**
 * Why this exists: a trainer running an update session needs to tell a phone
 * that took the new build from one that already had it, and both look
 * identical in [SetupReport.installed].
 */
@Serializable
enum class InstallResult {
    /** The app was not on the phone before. */
    Installed,

    /** An older version was replaced. */
    Updated,

    /** The phone already had this version, so nothing was downloaded. */
    AlreadyCurrent,

    Failed,
}

@Serializable
data class InstalledPackage(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
)

/** Published at `/manifest.json` so a device can see what it is about to get. */
@Serializable
data class ServerManifest(
    val deploymentId: String,
    val deploymentName: String,
    val packages: List<PackageSpec>,
) {
    fun encode(): String = KioskJson.pretty.encodeToString(serializer(), this)
}

/**
 * A short code a trainer can read off a phone and find on the dashboard. Six
 * identical budget phones are otherwise indistinguishable.
 */
object DeviceLabel {
    // No 0/O or 1/I, so the code can be read aloud.
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun of(deviceId: String): String {
        val digest = Digests.sha256(deviceId.toByteArray())
        return (0 until 4).joinToString("") { i ->
            ALPHABET[(digest[i].toInt() and 0xFF) % ALPHABET.length].toString()
        }
    }
}
