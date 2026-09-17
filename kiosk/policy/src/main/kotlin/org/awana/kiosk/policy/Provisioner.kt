package org.awana.kiosk.policy

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.DeviceLabel
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.InstallResult
import org.awana.kiosk.shared.InstalledPackage
import org.awana.kiosk.shared.PackageOutcome
import org.awana.kiosk.shared.PackageSpec
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

    /**
     * [onStep] lets the admin screen show what is happening. It is optional
     * because the enrolment path has the setup wizard's own progress in front
     * of it, and because every test drives this without a screen.
     */
    suspend fun provision(
        config: KioskConfig,
        onStep: (UpdateState) -> Unit = {},
    ): ProvisionResult {
        configStore.save(config)
        clearPendingBootstrap()

        val before = policy.applyBeforeInstall(config)
        val payload = if (config.serverUrl != null) installPayload(config, onStep) else Payload()
        onStep(UpdateState.ApplyingSettings)
        val after = policy.applyAfterInstall(config)

        val report = buildReport(
            config,
            applied = before.applied + after.applied,
            failures = before.failures + payload.failures + after.failures,
            permissionFailures = after.permissionFailures,
            packageOutcomes = payload.outcomes,
        )
        saveLastReport(report)

        val delivered = config.serverUrl?.let { reporter.send(it, report) } ?: false
        return ProvisionResult(report, delivered)
    }

    private class Payload(
        val failures: MutableList<String> = mutableListOf(),
        val outcomes: MutableList<PackageOutcome> = mutableListOf(),
    )

    private suspend fun installPayload(config: KioskConfig, onStep: (UpdateState) -> Unit): Payload {
        val serverUrl = config.serverUrl!!.trimEnd('/')
        val staging = File(appContext.cacheDir, "staging").apply { mkdirs() }
        val payload = Payload()

        config.packages.forEachIndexed { index, spec ->
            val had = DeviceFacts.installedPackage(appContext, spec.packageName)
            onStep(UpdateState.Installing(spec.packageName, index + 1, config.packages.size))
            if (alreadyCurrent(spec, had)) {
                Log.i(TAG, "${spec.packageName} is already at ${had?.versionCode}")
                payload.outcomes += PackageOutcome(
                    spec.packageName,
                    InstallResult.AlreadyCurrent,
                    had?.versionName,
                )
                return@forEachIndexed
            }

            val apk = File(staging, "${spec.packageName}.apk")
            try {
                val url = serverUrl + spec.path
                val downloaded = Http.download(url, apk)
                if (downloaded.isFailure) {
                    payload.failures += "Could not download ${spec.packageName} from $url: " +
                        "${downloaded.exceptionOrNull()?.message}"
                    payload.outcomes += PackageOutcome(spec.packageName, InstallResult.Failed)
                    return@forEachIndexed
                }
                when (val outcome = installer.install(apk, spec.packageName, spec.certSha256)) {
                    is InstallOutcome.Success -> {
                        Log.i(TAG, "Installed ${spec.packageName}")
                        payload.outcomes += PackageOutcome(
                            spec.packageName,
                            if (had == null) InstallResult.Installed else InstallResult.Updated,
                            DeviceFacts.installedPackage(appContext, spec.packageName)?.versionName,
                        )
                    }

                    is InstallOutcome.Failure -> {
                        payload.failures += outcome.reason
                        payload.outcomes += PackageOutcome(spec.packageName, InstallResult.Failed)
                    }
                }
            } finally {
                apk.delete()
            }
        }
        return payload
    }

    /**
     * Whether what is on the phone already satisfies [spec], so the download
     * can be skipped.
     *
     * An update session re-serves the whole deployment, and one app can be
     * ~90 MB. Over a single hotspot to a room full of phones, fetching what is
     * already installed is the difference between minutes and an afternoon.
     *
     * The signature is part of the question and not only the version: an app of
     * the right version signed by someone else must never be reported as
     * current. Left to the installer, which refuses it with a message naming
     * both keys — after the download, but correctness first.
     */
    private fun alreadyCurrent(spec: PackageSpec, installed: InstalledPackage?): Boolean {
        val wanted = spec.versionCode ?: return false
        if ((installed?.versionCode ?: return false) < wanted) return false
        if (spec.certSha256.isBlank()) return true
        return Certificates.matches(
            Certificates.ofInstalledPackage(appContext, spec.packageName),
            spec.certSha256,
        )
    }

    /** [config] is null only before there was one to apply; see [recordBootstrapFailure]. */
    private fun buildReport(
        config: KioskConfig?,
        applied: List<String> = emptyList(),
        failures: List<String> = emptyList(),
        permissionFailures: List<String> = emptyList(),
        packageOutcomes: List<PackageOutcome> = emptyList(),
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
        packageOutcomes = packageOutcomes,
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
        bootstrap?.let { rememberBootstrap(it) }
    }

    /**
     * Kept from the setup wizard's handshake as well as from a failed fetch:
     * not every wizard still carries the admin extras by the time
     * `onProfileProvisioningComplete` runs.
     */
    fun rememberBootstrap(bootstrap: ProvisioningBootstrap) {
        File(appContext.filesDir, PENDING_BOOTSTRAP).writeText(bootstrap.encode())
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
