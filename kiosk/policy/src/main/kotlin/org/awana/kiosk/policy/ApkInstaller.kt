package org.awana.kiosk.policy

import org.awana.kiosk.shared.Certificates
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import java.io.File

sealed interface InstallOutcome {
    data class Success(val packageName: String) : InstallOutcome

    /** [reason] is written for a trainer to act on, not for a log grep. */
    data class Failure(val packageName: String, val reason: String) : InstallOutcome
}

/**
 * Silent installs via [PackageInstaller]. As Device Owner these need no user
 * interaction, though the system still posts an unsuppressable "installed by
 * your admin" notification.
 *
 * Signature matching and version downgrades are left to the platform, which
 * already rejects both. What is added here is the certificate check on *first*
 * install, where the platform has no incumbent package to compare against.
 */
class ApkInstaller(context: Context) {

    private val appContext = context.applicationContext

    suspend fun install(apk: File, packageName: String, expectedCert: String?): InstallOutcome {
        if (!apk.isFile || apk.length() == 0L) {
            return InstallOutcome.Failure(packageName, "The downloaded file for $packageName is missing or empty.")
        }

        val actualCerts = Certificates.ofApkFile(appContext, apk)
        if (actualCerts.isEmpty()) {
            return InstallOutcome.Failure(
                packageName,
                "Could not read a signature from the $packageName file. It may be corrupt or not an APK.",
            )
        }
        if (expectedCert != null && !Certificates.matches(actualCerts, expectedCert)) {
            return InstallOutcome.Failure(
                packageName,
                "This $packageName APK is signed with a different key than expected, so it was not installed. " +
                    "Expected ${expectedCert.take(16)}…, found ${actualCerts.first().take(16)}…",
            )
        }

        return commit(apk, packageName)
    }

    private suspend fun commit(apk: File, packageName: String): InstallOutcome {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(packageName) }

        val sessionId = try {
            installer.createSession(params)
        } catch (e: Exception) {
            return InstallOutcome.Failure(packageName, "Could not start the install: ${e.message}")
        }

        val result = CompletableDeferred<InstallOutcome>()
        val action = "$ACTION_PREFIX.$sessionId"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                // STATUS_PENDING_USER_ACTION should never arrive for a Device
                // Owner; if it does, treat it as failure rather than launching
                // a dialog into a locked-down device.
                result.complete(toOutcome(packageName, status, intent))
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(packageName, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val pending = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    Intent(action).setPackage(appContext.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            return result.await()
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            return InstallOutcome.Failure(packageName, "Could not write the $packageName APK: ${e.message}")
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    private fun toOutcome(packageName: String, status: Int, intent: Intent): InstallOutcome {
        if (status == PackageInstaller.STATUS_SUCCESS) return InstallOutcome.Success(packageName)

        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val message = when {
            detail.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") || detail.contains("INCONSISTENT_CERTIFICATES") ->
                "$packageName is already installed and signed with a different key. It must be uninstalled first, " +
                    "which will delete its data."
            detail.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "A newer version of $packageName is already installed, so this older one was refused."
            detail.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
                "There is not enough free space on the device to install $packageName."
            detail.contains("INSTALL_FAILED_MISSING_SPLIT") ->
                "$packageName was installed as a split app and cannot be replaced by a single APK. " +
                    "Uninstall it first."
            status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "$packageName is not compatible with this device (wrong processor type, or the Android version is too old)."
            status == PackageInstaller.STATUS_FAILURE_STORAGE ->
                "There is not enough free space on the device to install $packageName."
            status == PackageInstaller.STATUS_FAILURE_INVALID ->
                "The $packageName file is not a valid APK."
            status == PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "$packageName conflicts with an app already on the device."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED ->
                "The device blocked the install of $packageName."
            status == PackageInstaller.STATUS_FAILURE_ABORTED ->
                "The install of $packageName was cancelled."
            status == PackageInstaller.STATUS_PENDING_USER_ACTION ->
                "The device asked for manual confirmation to install $packageName, which means this app is not " +
                    "Device Owner. Re-provision the device."
            else -> "$packageName failed to install (status $status)${detail.ifEmpty { "" }}"
        }
        Log.w(TAG, "Install of $packageName failed: status=$status detail=$detail")
        return InstallOutcome.Failure(packageName, message)
    }

    private companion object {
        const val TAG = "ApkInstaller"
        const val ACTION_PREFIX = "org.awana.kiosk.INSTALL_RESULT"
    }
}
