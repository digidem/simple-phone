package org.awana.kiosk.setup

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.awana.kiosk.shared.Certificates
import java.io.File

/**
 * One APK the trainer has added.
 *
 * The fingerprint is the point of this record. It is shown in the UI before a
 * deployment, published in `/manifest.json`, and carried in the provisioning
 * payload so the kiosk can refuse anything signed with a different key.
 */
@Serializable
data class ApkEntry(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    /** Lowercase hex SHA-256 of the signing certificate. */
    val certSha256: String,
    /** File name inside the library directory. */
    val fileName: String,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    /** Runtime permissions the APK requests, in the order the kiosk should grant them. */
    val permissions: List<String> = emptyList(),
) {
    /** Grouped in fours so a trainer can read it aloud to check it. */
    val readableFingerprint: String
        get() = certSha256.chunked(4).joinToString(" ")
}

/**
 * An APK read but not yet in the library, so the caller can see what committing
 * it would do.
 */
data class StagedApk(
    val entry: ApkEntry,
    /** What the library holds for this package now, if anything. */
    val replaces: ApkEntry?,
    internal val file: File,
) {
    /**
     * Every phone already set up records the fingerprint the deployment was
     * made with and refuses anything else, so a new key means those phones
     * cannot take this update at all.
     */
    val keyChanged: Boolean
        get() = replaces != null && replaces.certSha256 != entry.certSha256
}

/**
 * The trainer-populated APK library.
 *
 * Awana does not redistribute third-party APKs — that removes a trademark and
 * policy question from the deployment process entirely — so everything except
 * the kiosk itself is added here from device storage.
 */
class ApkLibrary(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "apks").apply { mkdirs() }
    private val index = File(appContext.filesDir, "apk-library.json")

    fun entries(): List<ApkEntry> {
        if (!index.exists()) return emptyList()
        return runCatching { Json.decodeList(index.readText()) }
            .onFailure { Log.e(TAG, "APK index unreadable", it) }
            .getOrDefault(emptyList())
    }

    fun fileFor(entry: ApkEntry): File = File(dir, entry.fileName)

    fun find(packageName: String): ApkEntry? = entries().firstOrNull { it.packageName == packageName }

    /**
     * Copies the APK at [uri] into the library and reads its identity.
     *
     * Returns a failure with a trainer-readable message rather than throwing:
     * picking the wrong file is a normal thing to do, not an error condition.
     */
    suspend fun add(uri: Uri): Result<ApkEntry> = stage(uri).map { commit(it) }

    /**
     * Reads an APK without committing it, so the caller can see what it would
     * replace first. A version signed with a different key is refused by every
     * phone already set up, and that has to be said before the file lands.
     *
     * The staged file stays on disk until [commit] or [discard].
     */
    suspend fun stage(uri: Uri): Result<StagedApk> = withContext(Dispatchers.IO) {
        val staged = File(dir, "staging-${System.currentTimeMillis()}.apk")
        try {
            val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            if (copied == null) {
                staged.delete()
                return@withContext Result.failure(ApkImportError("That file could not be opened."))
            }

            val info = appContext.packageManager.getPackageArchiveInfo(
                staged.absolutePath,
                PackageManager.GET_PERMISSIONS,
            )
            if (info == null) {
                staged.delete()
                return@withContext Result.failure(
                    ApkImportError("That file is not an Android app (APK)."),
                )
            }

            val fingerprint = Certificates.ofApkFile(appContext, staged).firstOrNull()
            if (fingerprint == null) {
                staged.delete()
                return@withContext Result.failure(
                    ApkImportError("That APK is not signed, so it cannot be deployed."),
                )
            }

            val appInfo = info.applicationInfo?.apply {
                // getPackageArchiveInfo leaves these unset, and the label cannot
                // be read without them.
                sourceDir = staged.absolutePath
                publicSourceDir = staged.absolutePath
            }
            val label = appInfo
                ?.let { appContext.packageManager.getApplicationLabel(it).toString() }
                ?: info.packageName

            Result.success(
                StagedApk(
                    entry = ApkEntry(
                        packageName = info.packageName,
                        label = label,
                        versionName = info.versionName,
                        versionCode = info.longVersionCode,
                        certSha256 = fingerprint,
                        fileName = "${info.packageName}.apk",
                        sizeBytes = staged.length(),
                        addedAtEpochMs = System.currentTimeMillis(),
                        permissions = runtimePermissions(info.requestedPermissions.orEmpty().toList()),
                    ),
                    replaces = find(info.packageName),
                    file = staged,
                ),
            )
        } catch (e: Exception) {
            staged.delete()
            Log.e(TAG, "Import failed", e)
            Result.failure(ApkImportError("That file could not be read: ${e.message}"))
        }
    }

    /** Replacing is updating: [ApkLibrary.add] has always replaced in place. */
    fun commit(staged: StagedApk): ApkEntry {
        val target = File(dir, staged.entry.fileName)
        staged.file.copyTo(target, overwrite = true)
        staged.file.delete()
        val entry = staged.entry.copy(sizeBytes = target.length())
        save(entries().filterNot { it.packageName == entry.packageName } + entry)
        return entry
    }

    fun discard(staged: StagedApk) {
        staged.file.delete()
    }

    fun remove(packageName: String) {
        entries().firstOrNull { it.packageName == packageName }?.let { File(dir, it.fileName).delete() }
        save(entries().filterNot { it.packageName == packageName })
    }

    /** Lets a test stand in a library entry recorded with a different key. */
    internal fun replaceIndexForTest(entries: List<ApkEntry>) = save(entries)

    private fun save(entries: List<ApkEntry>) {
        index.writeText(Json.encodeList(entries.sortedBy { it.label }))
    }

    /** Only runtime ("dangerous") permissions can be pre-granted by a Device Owner. */
    private fun runtimePermissions(requested: List<String>): List<String> {
        val pm = appContext.packageManager
        return orderedForGrant(
            requested.filter { name ->
                runCatching {
                    pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
                }.getOrDefault(false)
            },
        )
    }

    private companion object {
        const val TAG = "ApkLibrary"
    }
}

/** Background location is refused unless foreground location was granted first. */
internal fun orderedForGrant(runtime: List<String>): List<String> {
    val foregroundLocation = setOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    return runtime.filter { it in foregroundLocation } + runtime.filterNot { it in foregroundLocation }
}

class ApkImportError(message: String) : Exception(message)
