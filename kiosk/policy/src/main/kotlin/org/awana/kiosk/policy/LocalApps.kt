package org.awana.kiosk.policy

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.withEntry

/**
 * Apps on the phone that the deployment does not list, and letting one in.
 *
 * The lock only lets the config's packages open, so an app installed from a
 * file or during an unlock stays unusable until it is added here. It joins the
 * config on this phone only: the next update from the trainer's phone replaces
 * the config, and with it anything added this way.
 */
object LocalApps {

    data class Candidate(val packageName: String, val label: String)

    /** Launchable apps the config does not list, by label. */
    suspend fun unlisted(context: Context): List<Candidate> = withContext(Dispatchers.IO) {
        val listed = ConfigStore(context).load()?.packages.orEmpty().map { it.packageName }.toSet()
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName && it.packageName !in listed }
            .map { Candidate(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Adds [packageName] to the config, recording the key it is signed with now
     * so a later install cannot swap it, and shows it on the home screen. The
     * lock is re-applied unless the phone is unlocked, in which case locking it
     * again picks this up. Returns what could not be applied.
     */
    suspend fun allow(context: Context, packageName: String): List<String> = withContext(Dispatchers.Default) {
        val installed = DeviceFacts.installedPackage(context, packageName)
        val cert = Certificates.ofInstalledPackage(context, packageName).firstOrNull()
        if (installed == null || cert == null) {
            return@withContext listOf(context.getString(R.string.local_app_missing, packageName))
        }
        val config = ConfigStore(context).update { current ->
            current.copy(
                packages = current.packages + PackageSpec(
                    packageName = packageName,
                    certSha256 = cert,
                    versionName = installed.versionName,
                    versionCode = installed.versionCode,
                ),
                launcher = current.launcher.withEntry(LauncherEntry(packageName, LauncherRole.SMALL)),
            )
        } ?: return@withContext listOf(context.getString(R.string.lock_no_config))

        if (PhoneLock.isUnlocked(context)) return@withContext emptyList()
        val policy = DevicePolicy(context)
        runCatching { policy.applyLockTask(config) }
            .exceptionOrNull()?.let { return@withContext listOf(context.getString(R.string.policy_failed_lock_task)) }
        policy.applyUninstallProtection(config)
    }
}
