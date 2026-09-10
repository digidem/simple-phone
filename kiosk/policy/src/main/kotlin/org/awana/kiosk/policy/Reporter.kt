package org.awana.kiosk.policy

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.InstalledPackage
import java.io.File

/**
 * Queues reports and retries them, because the trainer's hotspot may well be
 * gone before the POST lands.
 */
class Reporter(context: Context) {

    private val appContext = context.applicationContext
    private val queueDir = File(appContext.filesDir, "report-queue")

    /** Sends [report], queueing it for a later retry if the POST fails. */
    suspend fun send(serverUrl: String, report: EnrolmentReport): Boolean {
        val body = report.encode()
        val sent = Http.postJson("${serverUrl.trimEnd('/')}/report", body).isSuccess
        if (!sent) {
            queueDir.mkdirs()
            File(queueDir, "${report.deviceId}-${report.reportedAtEpochMs}.json").writeText(body)
            Log.w(TAG, "Report queued; server at $serverUrl unreachable")
        }
        return sent
    }

    /** Retries anything queued. Safe to call whenever a network appears. */
    suspend fun flush(serverUrl: String): Int {
        val pending = queueDir.listFiles()?.sortedBy { it.name } ?: return 0
        var sent = 0
        for (file in pending) {
            val ok = Http.postJson("${serverUrl.trimEnd('/')}/report", file.readText()).isSuccess
            if (!ok) break
            file.delete()
            sent++
        }
        return sent
    }

    fun queuedCount(): Int = queueDir.listFiles()?.size ?: 0

    private companion object {
        const val TAG = "Reporter"
    }
}

object DeviceFacts {

    /**
     * OEMs whose battery managers kill background work in ways no DPM API can
     * reach. Detected so the trainer is told a manual per-vendor step is
     * needed; the settings screens themselves are deliberately not automated.
     */
    private val HOSTILE_OEMS = setOf(
        "xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme",
        "vivo", "oneplus", "meizu", "asus", "wiko", "lenovo", "samsung",
    )

    fun hostileOem(): String? {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        return manufacturer.takeIf { it.lowercase() in HOSTILE_OEMS }
    }

    /** Stable per-device, survives app reinstall, resets on factory reset. */
    @Suppress("HardwareIds")
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-${Build.FINGERPRINT.hashCode()}"

    fun installedPackage(context: Context, packageName: String): InstalledPackage? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        InstalledPackage(packageName, info.versionName, info.longVersionCode)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
