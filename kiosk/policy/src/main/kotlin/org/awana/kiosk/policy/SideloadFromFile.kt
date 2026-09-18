package org.awana.kiosk.policy

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs an APK file already on the phone, for a phone with no network and
 * no trainer's phone in reach.
 *
 * The same checks as [SideloadFromUrl]: a package the config knows must carry
 * the fingerprint recorded for it, and one it does not know is installed on the
 * strength of the admin PIN. The package name comes out of the file itself.
 */
object SideloadFromFile {

    sealed interface Outcome {
        val message: String

        /** [known] is false for an app the deployment does not list yet. */
        data class Installed(val packageName: String, val known: Boolean, override val message: String) : Outcome

        data class Refused(override val message: String) : Outcome
    }

    suspend fun run(context: Context, uri: Uri): Outcome {
        val apk = File(File(context.cacheDir, "staging").apply { mkdirs() }, "sideload-file.apk")
        try {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        apk.outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
            }
            val archive = if (copied) {
                context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            } else {
                null
            }
            archive ?: return Outcome.Refused(context.getString(R.string.sideload_file_unreadable))
            val packageName = archive.packageName

            // Android would refuse a downgrade anyway, and reinstalling the same
            // build of this app would only kill the screen showing the result.
            if (packageName == context.packageName) {
                val ours = DeviceFacts.installedPackage(context, packageName)?.versionCode ?: 0
                if (archive.longVersionCode <= ours) {
                    return Outcome.Refused(
                        context.getString(R.string.sideload_file_own_older, archive.versionName.orEmpty()),
                    )
                }
            }

            val spec = ConfigStore(context).load()?.packages?.firstOrNull { it.packageName == packageName }
            return when (val outcome = ApkInstaller(context).install(apk, packageName, spec?.certSha256)) {
                is InstallOutcome.Success -> Outcome.Installed(
                    packageName,
                    known = spec != null || packageName == context.packageName,
                    message = context.getString(R.string.sideload_ok, packageName),
                )
                is InstallOutcome.Failure -> Outcome.Refused(outcome.reason)
            }
        } finally {
            apk.delete()
        }
    }
}
