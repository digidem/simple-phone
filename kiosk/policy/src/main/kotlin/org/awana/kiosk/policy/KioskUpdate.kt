package org.awana.kiosk.policy

import android.content.Context
import android.util.Log
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.KioskConfig
import java.io.File

/**
 * The kiosk replacing itself with the build the trainer is serving.
 *
 * Android allows a device owner to be replaced by a package signed with the
 * same key and carrying a higher version code, and the session already serves
 * that APK at [KioskConfig.DPC_PATH]. Without this a bug in the kiosk means
 * factory resetting every phone, since a device owner cannot be uninstalled.
 *
 * It runs last, after the payload, the policy and the report. Committing the
 * install kills this process — that is what replacing a running app means — so
 * anything left undone would stay undone.
 */
object KioskUpdate {

    /**
     * Whether the trainer is serving a newer kiosk than this one.
     *
     * Strictly newer: equal versions are the common case on every phone after
     * the first, and reinstalling for nothing would kill the process each time.
     */
    fun isAvailable(context: Context, config: KioskConfig): Boolean {
        val serving = config.kioskVersionCode ?: return false
        return serving > installedVersionCode(context)
    }

    /**
     * Downloads and installs it. Does not return on success: the process is
     * killed as the install commits, and the launcher comes back as the new
     * build. A returned message means it did not happen and why.
     */
    suspend fun apply(
        context: Context,
        config: KioskConfig,
        installer: ApkInstaller = ApkInstaller(context),
    ): String? {
        val serverUrl = config.serverUrl?.trimEnd('/') ?: return "No server to fetch the update from."
        val apk = File(context.cacheDir, "kiosk-update.apk")
        try {
            val url = serverUrl + KioskConfig.DPC_PATH
            Http.download(url, apk).getOrElse {
                return "Could not download the Simple Phone update from $url: ${it.message}"
            }

            // Our own certificate, not one from the config: this is the install
            // that cannot be undone, and Android would refuse a different key
            // anyway. Checking first turns a silent refusal into a sentence.
            val ours = Certificates.ofInstalledPackage(context, context.packageName).firstOrNull()
                ?: return "This phone could not read its own signature, so the update was not applied."

            return when (val outcome = installer.install(apk, context.packageName, ours)) {
                is InstallOutcome.Success -> {
                    Log.i(TAG, "Replaced this kiosk with ${config.kioskVersionCode}")
                    null
                }

                is InstallOutcome.Failure -> outcome.reason
            }
        } finally {
            apk.delete()
        }
    }

    private fun installedVersionCode(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(Long.MAX_VALUE)

    private const val TAG = "KioskUpdate"
}
