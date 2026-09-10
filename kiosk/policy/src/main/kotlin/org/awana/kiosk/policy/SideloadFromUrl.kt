package org.awana.kiosk.policy

import android.content.Context
import java.io.File

/**
 * Installs an APK from a URL typed into the admin screen.
 *
 * Network-dependent by design; the offline path is a later version. The
 * certificate check still applies: a package already in the config must present
 * the fingerprint recorded for it. A package the config does not know is
 * installed unchecked, on the strength of the admin PIN.
 */
object SideloadFromUrl {

    suspend fun run(context: Context, url: String, packageName: String): String {
        val staging = File(context.cacheDir, "staging").apply { mkdirs() }
        val apk = File(staging, "sideload-$packageName.apk")
        try {
            val downloaded = Http.download(url, apk)
            downloaded.exceptionOrNull()?.let {
                return context.getString(R.string.sideload_download_failed, url, it.message ?: "")
            }

            val expected = ConfigStore(context).load()
                ?.packages
                ?.firstOrNull { it.packageName == packageName }
                ?.certSha256

            return when (val outcome = ApkInstaller(context).install(apk, packageName, expected)) {
                is InstallOutcome.Success ->
                    context.getString(R.string.sideload_ok, packageName)
                is InstallOutcome.Failure -> outcome.reason
            }
        } finally {
            apk.delete()
        }
    }
}
