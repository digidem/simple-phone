package org.awana.kiosk.policy

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.DeviceLabel
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.ProvisioningBootstrap
import android.content.Context
import android.util.Log
import java.io.File

data class ProvisionResult(
    val report: EnrolmentReport,
    val reportDelivered: Boolean,
) {
    val succeeded: Boolean get() = report.failures.isEmpty()
}

/**
 * The whole post-scan path: persist config, apply policy, fetch and install the
 * payload, pre-grant permissions, report.
 *
 * `onProfileProvisioningComplete` is a thin wrapper around [provision] and
 * nothing else, so instrumented tests can drive the entire sequence with a
 * synthesised config and no camera or setup wizard. Keep it that way.
 *
 * Every step is idempotent, so the admin screen's "re-apply" can re-run this
 * against an already-provisioned device.
 */
class Provisioner(
    context: Context,
    private val policy: DevicePolicy = DevicePolicy(context),
    private val installer: ApkInstaller = ApkInstaller(context),
    private val reporter: Reporter = Reporter(context),
) {

    private val appContext = context.applicationContext
    private val configStore = ConfigStore(appContext)

    suspend fun provision(config: KioskConfig): ProvisionResult {
        configStore.save(config)
        clearPendingBootstrap()

        val before = policy.applyBeforeInstall(config)
        val installFailures = if (config.serverUrl != null) installPayload(config) else emptyList()
        val after = policy.applyAfterInstall(config)

        val report = buildReport(
            config,
            applied = before.applied + after.applied,
            failures = before.failures + installFailures + after.failures,
            permissionFailures = after.permissionFailures,
        )
        saveLastReport(report)

        val delivered = config.serverUrl?.let { reporter.send(it, report) } ?: false
        return ProvisionResult(report, delivered)
    }

    private suspend fun installPayload(config: KioskConfig): List<String> {
        val serverUrl = config.serverUrl!!.trimEnd('/')
        val staging = File(appContext.cacheDir, "staging").apply { mkdirs() }
        val failures = mutableListOf<String>()

        for (spec in config.packages) {
            val apk = File(staging, "${spec.packageName}.apk")
            try {
                val url = serverUrl + spec.path
                val downloaded = Http.download(url, apk)
                if (downloaded.isFailure) {
                    failures += "Could not download ${spec.packageName} from $url: " +
                        "${downloaded.exceptionOrNull()?.message}"
                    continue
                }
                when (val outcome = installer.install(apk, spec.packageName, spec.certSha256)) {
                    is InstallOutcome.Success -> Log.i(TAG, "Installed ${spec.packageName}")
                    is InstallOutcome.Failure -> failures += outcome.reason
                }
            } finally {
                apk.delete()
            }
        }
        return failures
    }

    /** [config] is null only before there was one to apply; see [recordBootstrapFailure]. */
    private fun buildReport(
        config: KioskConfig?,
        applied: List<String> = emptyList(),
        failures: List<String> = emptyList(),
        permissionFailures: List<String> = emptyList(),
    ) = EnrolmentReport(
        deviceId = DeviceFacts.deviceId(appContext),
        deviceLabel = DeviceLabel.of(DeviceFacts.deviceId(appContext)),
        deploymentId = config?.deploymentId.orEmpty(),
        deploymentName = config?.deploymentName.orEmpty(),
        manufacturer = android.os.Build.MANUFACTURER,
        model = android.os.Build.MODEL,
        androidVersion = android.os.Build.VERSION.RELEASE,
        apiLevel = android.os.Build.VERSION.SDK_INT,
        kioskVersion = kioskVersion(),
        buildVariant = buildVariant(),
        isDeviceOwner = policy.isDeviceOwner,
        installed = config?.packages.orEmpty().mapNotNull {
            DeviceFacts.installedPackage(appContext, it.packageName)
        },
        policiesApplied = applied,
        failures = failures,
        permissionFailures = permissionFailures,
        hostileOem = DeviceFacts.hostileOem(),
        reportedAtEpochMs = System.currentTimeMillis(),
    )

    /**
     * Records a failure that happened before there was any config to apply.
     *
     * There is no server URL that can be trusted to report to at this point, so
     * this only writes locally, where the launcher and the admin screen show
     * it. Without it the device would sit on an empty launcher with no
     * explanation.
     *
     * [bootstrap] is kept beside the report because it only ever arrives once,
     * through the setup wizard: without it "set this phone up again" has
     * nowhere to go but a factory reset.
     */
    fun recordBootstrapFailure(reason: String, bootstrap: ProvisioningBootstrap? = null) {
        saveLastReport(buildReport(config = null, failures = listOf(reason)))
        bootstrap?.let { File(appContext.filesDir, PENDING_BOOTSTRAP).writeText(it.encode()) }
    }

    /** The bootstrap of an attempt that never reached a config, for a retry. */
    fun pendingBootstrap(): ProvisioningBootstrap? {
        val file = File(appContext.filesDir, PENDING_BOOTSTRAP)
        if (!file.exists()) return null
        return runCatching { ProvisioningBootstrap.parse(file.readText()) }.getOrNull()
    }

    fun clearPendingBootstrap() {
        File(appContext.filesDir, PENDING_BOOTSTRAP).delete()
    }

    fun lastReport(): EnrolmentReport? {
        val file = File(appContext.filesDir, LAST_REPORT)
        if (!file.exists()) return null
        return runCatching { EnrolmentReport.parse(file.readText()) }.getOrNull()
    }

    private fun saveLastReport(report: EnrolmentReport) {
        File(appContext.filesDir, LAST_REPORT).writeText(report.encode())
    }

    private fun kioskVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    /**
     * A device provisioned with a debug-signed kiosk can never receive
     * production updates, so which key signed this build has to be visible
     * rather than inferred.
     */
    private fun buildVariant(): String {
        val cert = Certificates.ofInstalledPackage(appContext, appContext.packageName).firstOrNull()
        return if (cert == null) "unknown" else "sig:${cert.take(16)}"
    }

    private companion object {
        const val TAG = "Provisioner"
        const val LAST_REPORT = "last-report.json"
        const val PENDING_BOOTSTRAP = "pending-bootstrap.json"
    }
}
