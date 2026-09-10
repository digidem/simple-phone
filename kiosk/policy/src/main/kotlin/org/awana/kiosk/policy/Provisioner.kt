package org.awana.kiosk.policy

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.DeviceLabel
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.Certificates
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
        val failures = mutableListOf<String>()

        configStore.save(config)

        // Everything except the restrictions that could disturb the hotspot the
        // payload is about to arrive over.
        val applied = policy.applyAll(config).toMutableList()

        if (config.serverUrl != null) {
            failures += installPayload(config)
        }

        // Uninstall blocking and permission grants both only take for packages
        // that exist, so they are re-run now that the payload is installed.
        runCatching { policy.applyUninstallProtection(config) }
            .onFailure { failures += "Could not protect apps from being uninstalled: ${it.message}" }
        val permissionFailures = runCatching { policy.applyPermissions(config) }
            .getOrElse {
                failures += "Could not pre-grant permissions: ${it.message}"
                emptyList()
            }

        // Now that nothing else needs the provisioning network.
        runCatching {
            policy.applyNetworkRestrictions()
            applied += "networkRestrictions"
        }.onFailure { failures += "Could not lock down network settings: ${it.message}" }

        val report = buildReport(config, applied, failures, permissionFailures)
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

    private fun buildReport(
        config: KioskConfig,
        applied: List<String>,
        failures: List<String>,
        permissionFailures: List<String>,
    ) = EnrolmentReport(
        deviceId = DeviceFacts.deviceId(appContext),
        deviceLabel = DeviceLabel.of(DeviceFacts.deviceId(appContext)),
        deploymentId = config.deploymentId,
        deploymentName = config.deploymentName,
        manufacturer = android.os.Build.MANUFACTURER,
        model = android.os.Build.MODEL,
        androidVersion = android.os.Build.VERSION.RELEASE,
        apiLevel = android.os.Build.VERSION.SDK_INT,
        kioskVersion = kioskVersion(),
        buildVariant = buildVariant(),
        isDeviceOwner = policy.isDeviceOwner,
        installed = config.packages.mapNotNull {
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
     * this only writes locally, where the admin screen shows it. Without it the
     * device would sit on an empty launcher with no explanation.
     */
    fun recordBootstrapFailure(reason: String) {
        saveLastReport(
            EnrolmentReport(
                deviceId = DeviceFacts.deviceId(appContext),
                deviceLabel = DeviceLabel.of(DeviceFacts.deviceId(appContext)),
                deploymentId = "",
                deploymentName = "",
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
                apiLevel = android.os.Build.VERSION.SDK_INT,
                kioskVersion = kioskVersion(),
                buildVariant = buildVariant(),
                isDeviceOwner = policy.isDeviceOwner,
                installed = emptyList(),
                policiesApplied = emptyList(),
                failures = listOf(reason),
                permissionFailures = emptyList(),
                hostileOem = DeviceFacts.hostileOem(),
                reportedAtEpochMs = System.currentTimeMillis(),
            ),
        )
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
    }
}
