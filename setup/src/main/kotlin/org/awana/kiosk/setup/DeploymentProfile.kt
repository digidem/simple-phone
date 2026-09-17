package org.awana.kiosk.setup

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.WifiNetwork
import org.awana.kiosk.shared.withEntry
import java.io.File
import java.util.UUID

/**
 * A saved deployment setup. Created, edited, duplicated, exported and imported
 * so trainers can share one out of band.
 */
@Serializable
data class DeploymentProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Stored as a PBKDF2 hash; the clear PIN never touches disk. */
    val adminPinHash: String,
    /** Package names, in the order they should appear. */
    val packages: List<String> = emptyList(),
    /** How each app appears on the launcher; see [org.awana.kiosk.shared.KioskConfig.launcher]. */
    val launcher: List<LauncherEntry> = emptyList(),
    /** Profiles written before [launcher] existed. Read by [ProfileStore] only. */
    @Deprecated("Replaced by launcher", ReplaceWith("launcher"))
    val visibleInLauncher: List<String>? = null,
    val locale: String = "en",
    val timeZone: String = "UTC",
    val screenOffTimeoutMs: Long = 120_000,
    /**
     * Off by default. See `showNotificationShadeCaveats` — the trainer is shown
     * those next to the toggle rather than being expected to find them in
     * separate documentation.
     */
    val showNotificationShade: Boolean = false,
    /** See `KioskConfig.screenLock`. Off by default. */
    val screenLock: Boolean = false,
    /** Networks every device joins at provisioning; see `KioskConfig.wifiNetworks`. */
    val wifiNetworks: List<WifiNetwork> = emptyList(),
) {
    fun duplicate(): DeploymentProfile =
        copy(id = UUID.randomUUID().toString(), name = "$name (copy)")

    fun entryFor(packageName: String): LauncherEntry? =
        launcher.firstOrNull { it.packageName == packageName }

    fun withEntry(entry: LauncherEntry): DeploymentProfile = copy(launcher = launcher.withEntry(entry))

    fun withoutEntry(packageName: String): DeploymentProfile =
        copy(launcher = launcher.filterNot { it.packageName == packageName })
}

@Suppress("DEPRECATION")
private fun DeploymentProfile.migrated(): DeploymentProfile {
    val legacy = visibleInLauncher.orEmpty()
    if (launcher.isNotEmpty() || legacy.isEmpty()) {
        return if (visibleInLauncher == null) this else copy(visibleInLauncher = null)
    }
    val visible = packages.filter { it in legacy }
    return copy(
        launcher = visible.mapIndexed { index, packageName ->
            LauncherEntry(
                packageName = packageName,
                role = if (index == 0) LauncherRole.HERO else LauncherRole.SMALL,
            )
        },
        visibleInLauncher = null,
    )
}

/**
 * What turning the notification shade on actually costs. Shown inline beside the
 * toggle: a trainer has to be able to understand what they are enabling.
 */
val showNotificationShadeCaveats = listOf(
    "Battery saver and Data Saver stay switched on in the shade, and either can " +
        "stop GPS and background sync working. There is no way to block them.",
    "Do Not Disturb stays available, and it can silence the very notifications " +
        "this setting exists to deliver.",
    "The settings button in the shade may open the full Settings app on some " +
        "phones. Check this on the actual phones before deploying with this on.",
)

class ProfileStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "profiles.json")

    fun all(): List<DeploymentProfile> {
        if (!file.exists()) return emptyList()
        return runCatching { Json.decodeProfiles(file.readText()) }
            .onFailure { Log.e(TAG, "Profiles unreadable", it) }
            .getOrDefault(emptyList())
    }

    fun get(id: String): DeploymentProfile? = all().firstOrNull { it.id == id }

    /**
     * The deployments that install [packageName]. Taking the file away breaks
     * every one of them, so removal has to be able to name them.
     */
    fun using(packageName: String): List<DeploymentProfile> =
        all().filter { packageName in it.packages }

    fun save(profile: DeploymentProfile) {
        val others = all().filterNot { it.id == profile.id }
        file.writeText(Json.encodeProfiles((others + profile).sortedBy { it.name }))
    }

    fun delete(id: String) {
        file.writeText(Json.encodeProfiles(all().filterNot { it.id == id }))
    }

    /** Exported and imported as plain JSON so profiles travel by any means. */
    fun export(profile: DeploymentProfile): String = Json.encodeProfile(profile)

    fun import(text: String): Result<DeploymentProfile> = runCatching {
        // A new id, so importing a profile a colleague exported does not
        // silently overwrite a local one that happens to share it.
        Json.decodeProfile(text).copy(id = UUID.randomUUID().toString())
    }

    private companion object {
        const val TAG = "ProfileStore"
    }
}

/** Encoding helpers over the shared configuration, so profiles and library entries match the wire format. */
object Json {
    val format = KioskJson.pretty

    fun encodeList(entries: List<ApkEntry>): String =
        format.encodeToString(kotlinx.serialization.builtins.ListSerializer(ApkEntry.serializer()), entries)

    fun decodeList(text: String): List<ApkEntry> =
        format.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ApkEntry.serializer()), text)

    fun encodeProfiles(profiles: List<DeploymentProfile>): String =
        format.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(DeploymentProfile.serializer()),
            profiles,
        )

    fun decodeProfiles(text: String): List<DeploymentProfile> =
        format.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(DeploymentProfile.serializer()),
            text,
        ).map { it.migrated() }

    fun encodeProfile(profile: DeploymentProfile): String =
        format.encodeToString(DeploymentProfile.serializer(), profile)

    fun decodeProfile(text: String): DeploymentProfile =
        format.decodeFromString(DeploymentProfile.serializer(), text).migrated()
}
