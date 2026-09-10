package org.awana.provision

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
    suspend fun add(uri: Uri): Result<ApkEntry> = withContext(Dispatchers.IO) {
        val staged = File(dir, "staging-${System.currentTimeMillis()}.apk")
        try {
            val copied = appContext.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
            if (copied == null) {
                return@withContext Result.failure(ApkImportError("That file could not be opened."))
            }

            val info = appContext.packageManager.getPackageArchiveInfo(
                staged.absolutePath,
                PackageManager.GET_PERMISSIONS,
            ) ?: return@withContext Result.failure(
                ApkImportError("That file is not an Android app (APK)."),
            )

            val fingerprint = Certificates.ofApkFile(appContext, staged).firstOrNull()
                ?: return@withContext Result.failure(
                    ApkImportError("That APK is not signed, so it cannot be deployed."),
                )

            val appInfo = info.applicationInfo?.apply {
                // getPackageArchiveInfo leaves these unset, and the label cannot
                // be read without them.
                sourceDir = staged.absolutePath
                publicSourceDir = staged.absolutePath
            }
            val label = appInfo
                ?.let { appContext.packageManager.getApplicationLabel(it).toString() }
                ?: info.packageName

            val fileName = "${info.packageName}.apk"
            val target = File(dir, fileName)
            staged.copyTo(target, overwrite = true)

            val entry = ApkEntry(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                certSha256 = fingerprint,
                fileName = fileName,
                sizeBytes = target.length(),
                addedAtEpochMs = System.currentTimeMillis(),
                permissions = runtimePermissions(info.requestedPermissions.orEmpty().toList()),
            )
            save(entries().filterNot { it.packageName == entry.packageName } + entry)
            Result.success(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            Result.failure(ApkImportError("That file could not be read: ${e.message}"))
        } finally {
            staged.delete()
        }
    }

    fun remove(packageName: String) {
        entries().firstOrNull { it.packageName == packageName }?.let { File(dir, it.fileName).delete() }
        save(entries().filterNot { it.packageName == packageName })
    }

    private fun save(entries: List<ApkEntry>) {
        index.writeText(Json.encodeList(entries.sortedBy { it.label }))
    }

    /**
     * Only runtime ("dangerous") permissions can be pre-granted by a Device
     * Owner, and background location is refused unless foreground location was
     * granted first, so that pair is ordered here.
     */
    private fun runtimePermissions(requested: List<String>): List<String> {
        val pm = appContext.packageManager
        val runtime = requested.filter { name ->
            runCatching { pm.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS }
                .getOrDefault(false)
        }
        val foregroundLocation = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return runtime.filter { it in foregroundLocation } + runtime.filterNot { it in foregroundLocation }
    }

    private companion object {
        const val TAG = "ApkLibrary"
    }
}

class ApkImportError(message: String) : Exception(message)
