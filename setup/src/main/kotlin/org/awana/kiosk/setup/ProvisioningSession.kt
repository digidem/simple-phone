package org.awana.kiosk.setup

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.Digests.hexToBytes
import org.awana.kiosk.shared.SetupReport
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ServerManifest
import org.awana.kiosk.shared.Telemetry
import io.sentry.SentryLevel
import java.io.File

data class SessionState(
    val running: Boolean = false,
    /** Set as soon as a session is asked for, so a recreated activity can find it. */
    val profileId: String? = null,
    val profileName: String = "",
    val hotspot: HotspotDetails? = null,
    val serverUrl: String? = null,
    val qrPayload: String? = null,
    val reports: List<SetupReport> = emptyList(),
    /** Phones that have asked the server for something but have not reported yet. */
    val downloading: List<Downloading> = emptyList(),
    val error: String? = null,
) {
    val finished: Int get() = reports.size
    val succeeded: Int get() = reports.count { it.succeeded }
    val failed: Int get() = reports.count { !it.succeeded }

    /** In flight means still asking for files. A phone that went quiet is not. */
    fun inFlight(now: Long): List<Downloading> = downloading.filter { !it.isSilent(now) }

    /**
     * Everything the session knows about, newest activity first. A phone has no
     * identity until it reports, so the two halves cannot be merged earlier
     * than this.
     */
    fun phones(now: Long): List<Phone> =
        downloading.sortedByDescending { it.lastSeenEpochMs }.map {
            if (it.isSilent(now)) Phone.Silent(it, now - it.lastSeenEpochMs) else Phone.Copying(it)
        } + reports.sortedByDescending { it.reportedAtEpochMs }.map { Phone.Reported(it) }

    fun status(now: Long): SessionStatus = when {
        // A problem outranks a count: it is the only thing here that needs
        // acting on.
        failed > 0 -> SessionStatus.Problem
        inFlight(now).isNotEmpty() -> SessionStatus.InProgress
        finished > 0 -> SessionStatus.Complete
        else -> SessionStatus.Idle
    }
}

/**
 * A phone mid-download. All the server has is an address and a sequence of
 * requests, which is why this carries no name.
 */
data class Downloading(
    val address: String,
    /** The app being fetched, or null while the kiosk and the config are. */
    val step: String? = null,
    val percent: Int = 0,
    val lastSeenEpochMs: Long = 0,
) {
    fun isSilent(now: Long): Boolean = now - lastSeenEpochMs > SILENT_AFTER_MS

    companion object {
        /**
         * Long enough to cover a large APK arriving over a hotspot without a
         * single further request: "stopped responding" is inferred from
         * silence, and nothing on the device reports it.
         */
        const val SILENT_AFTER_MS = 3 * 60_000L
    }
}

sealed interface Phone {
    data class Copying(val device: Downloading) : Phone
    data class Silent(val device: Downloading, val silentForMs: Long) : Phone
    data class Reported(val report: SetupReport) : Phone
}

enum class SessionStatus { Idle, InProgress, Complete, Problem }

/**
 * Holds one session: hotspot up, server up, QR shown, reports coming
 * in. Owned by a foreground service, because releasing the hotspot reservation
 * kills the hotspot.
 */
class ProvisioningSession(context: Context) {

    private val appContext = context.applicationContext
    private val library = ApkLibrary(appContext)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    private var hotspot: Hotspot? = null
    private var server: ProvisioningServer? = null

    /** True while this profile's session is starting, running or showing its error. */
    fun isActiveFor(profileId: String): Boolean = _state.value.profileId == profileId

    suspend fun start(profile: DeploymentProfile, hotspot: Hotspot): Result<Unit> {
        stop()
        this.hotspot = hotspot
        _state.value = SessionState(profileId = profile.id, profileName = profile.name)

        val details = hotspot.start().getOrElse { error ->
            Telemetry.report(
                TAG,
                "The hotspot did not start: ${error.message}",
                level = SentryLevel.WARNING,
                extras = mapOf(
                    "hotspot" to hotspot.javaClass.simpleName,
                    "interfaces" to describeInterfaces(appContext),
                ),
                context = appContext,
            )
            _state.value = failed(profile, error.message)
            return Result.failure(error)
        }

        val kioskApk = bundledKioskApk().getOrElse { error ->
            hotspot.stop()
            _state.value = failed(profile, error.message)
            return Result.failure(error)
        }

        val served = profile.packages.mapNotNull { packageName ->
            library.find(packageName)?.let {
                ProvisioningServer.ServedApk(packageName, library.fileFor(it))
            }
        }
        val missing = profile.packages.filter { name -> served.none { it.packageName == name } }
        if (missing.isNotEmpty()) {
            hotspot.stop()
            val message = "These apps are not in the library yet: ${missing.joinToString(", ")}"
            _state.value = failed(profile, message)
            return Result.failure(IllegalStateException(message))
        }

        val serverUrl = "http://${details.gatewayAddress}:$PORT"
        val specs = profile.packages.map { packageName ->
            val entry = library.find(packageName)!!
            PackageSpec(
                packageName = packageName,
                certSha256 = entry.certSha256,
                path = "/apks/$packageName.apk",
                versionName = entry.versionName,
                versionCode = entry.versionCode,
                permissions = entry.permissions,
            )
        }

        // The profile id is the deployment id, so phones set up in different
        // sessions of the same deployment still belong together.
        val config = KioskConfig(
            deploymentId = profile.id,
            deploymentName = profile.name,
            adminPinHash = profile.adminPinHash,
            serverUrl = serverUrl,
            packages = specs,
            launcher = profile.launcher.filter { it.packageName in profile.packages },
            wifiNetworks = profile.wifiNetworks,
            showNotificationShade = profile.showNotificationShade,
            screenLock = profile.screenLock,
            kioskVersionCode = kioskVersionCodeOf(appContext, kioskApk),
            locale = profile.locale,
            screenOffTimeoutMs = profile.screenOffTimeoutMs,
        )

        // Encoded once and served verbatim: the QR carries a hash of exactly
        // these bytes, so re-encoding anywhere would break every device's check.
        val configJson = KioskJson.compact.encodeToString(KioskConfig.serializer(), config)
        val configSha256 = Digests.sha256Hex(configJson.toByteArray())

        // What a device fetches, in the order it fetches it: the only progress
        // signal there is, since nothing reports until the very end.
        val steps = listOf(DPC_PATH, KioskConfig.CONFIG_PATH) + specs.map { it.path }
        val labels = specs.associate { spec ->
            spec.path to (library.find(spec.packageName)?.label ?: spec.packageName)
        }

        val manifest = ServerManifest(profile.id, profile.name, specs)
        val running = ProvisioningServer(
            port = PORT,
            kioskApk = kioskApk,
            payload = served,
            manifest = manifest,
            configJson = configJson,
            onReport = { address, report ->
                noteReport(address, report)
                _state.update {
                    it.copy(
                        reports = it.reports.replacing(report),
                        // It has a name now, so its anonymous half is spent.
                        downloading = it.downloading.filterNot { d -> d.address == address },
                    )
                }
            },
            onRequest = { address, uri -> noteRequest(address, uri, steps, labels) },
        )
        runCatching { running.start(SOCKET_TIMEOUT_MS, false) }.getOrElse { error ->
            Telemetry.report(TAG, "The server could not start", error = error, context = appContext)
            hotspot.stop()
            _state.value = failed(profile, "The server could not start: ${error.message}")
            return Result.failure(error)
        }
        server = running

        val payload = QrPayload.build(
            serverUrl = serverUrl,
            signatureChecksum = signatureChecksumOf(appContext, kioskApk),
            wifiSsid = details.ssid,
            wifiPassphrase = details.passphrase,
            wifiSecurityType = details.securityType,
            locale = profile.locale,
            timeZone = profile.timeZone,
            configSha256 = configSha256,
        )

        Telemetry.info(
            TAG,
            "Session started at $serverUrl: ${hotspot.javaClass.simpleName}, " +
                "security ${details.securityType}, ${specs.size} app(s), QR ${payload.length} bytes, " +
                "interfaces ${describeInterfaces(appContext)}",
        )
        _state.value = SessionState(
            running = true,
            profileId = profile.id,
            profileName = profile.name,
            hotspot = details,
            serverUrl = serverUrl,
            qrPayload = payload,
        )
        return Result.success(Unit)
    }

    /**
     * Returns when the session ends. If the address the code points at stops
     * being this phone's — the hotspot turned off, Wi-Fi switched — the code
     * would send every phone that scans it to nothing, and the setup wizard
     * waits on that forever without saying why. So the code comes down and
     * the trainer is told instead.
     */
    suspend fun watchAddress(isStillLocal: (String) -> Boolean = ::isLocalAddress) {
        var misses = 0
        while (true) {
            delay(WATCH_MS)
            val now = _state.value
            if (!now.running) return
            val address = now.hotspot?.gatewayAddress ?: return
            // A hotspot restarting on a new channel drops its address for a
            // moment; only a sustained absence means phones cannot reach it.
            misses = if (isStillLocal(address)) 0 else misses + 1
            if (misses < MISSES_BEFORE_TEARDOWN) continue

            Telemetry.report(
                TAG,
                "The session's address went away",
                level = SentryLevel.WARNING,
                extras = mapOf("address" to address, "interfaces" to describeInterfaces(appContext)),
            )
            // Only if the trainer has not stopped it meanwhile.
            if (!_state.value.running) return
            stop()
            _state.value = SessionState(
                profileId = now.profileId,
                profileName = now.profileName,
                error = appContext.getString(R.string.session_address_lost),
            )
            return
        }
    }

    fun stop() {
        server?.stop()
        server = null
        hotspot?.stop()
        hotspot = null
        _state.value = SessionState()
    }

    private fun noteRequest(
        address: String,
        uri: String,
        steps: List<String>,
        labels: Map<String, String>,
    ) {
        Telemetry.info(TAG, "$address requested $uri")
        if (uri == REPORT_PATH) return
        val at = steps.indexOf(uri)
        if (at < 0) return
        // Asking for step n means the n before it arrived. 100% is a report,
        // never a request.
        val percent = at * 100 / steps.size
        _state.update { state ->
            val existing = state.downloading.firstOrNull { it.address == address }
            val updated = (existing ?: Downloading(address)).copy(
                step = labels[uri],
                percent = maxOf(existing?.percent ?: 0, percent),
                lastSeenEpochMs = System.currentTimeMillis(),
            )
            state.copy(
                downloading = state.downloading.filterNot { it.address == address } + updated,
            )
        }
    }

    /** The field phone may never get online itself; this phone often is. */
    private fun noteReport(address: String, report: SetupReport) {
        val failures = report.failures + report.permissionFailures
        // A clean setup is a log line: as an issue it would alert on every phone.
        if (failures.isEmpty()) {
            Telemetry.info(TAG, "Setup report from ${report.deviceLabel} (${report.manufacturer} ${report.model}): no failures")
            return
        }
        Telemetry.report(
            TAG,
            "Setup report with failures",
            level = SentryLevel.WARNING,
            extras = mapOf(
                "deviceLabel" to report.deviceLabel,
                "failureCount" to failures.size,
                "address" to address,
                "device" to "${report.manufacturer} ${report.model}",
                "android" to "${report.androidVersion} (API ${report.apiLevel})",
                "kioskVersion" to report.kioskVersion,
                "isDeviceOwner" to report.isDeviceOwner,
                "failures" to failures,
                "packages" to report.packageOutcomes,
                "hostileOem" to report.hostileOem,
            ),
        )
    }

    private fun failed(profile: DeploymentProfile, message: String?) =
        SessionState(profileId = profile.id, profileName = profile.name, error = message)

    /**
     * Copies the bundled kiosk APK out of assets so it can be served by length.
     *
     * Copied every session: the cache directory survives an update of this app,
     * so a kept copy would go on serving the kiosk build that shipped before it.
     */
    private fun bundledKioskApk(): Result<File> = runCatching {
        val target = File(appContext.cacheDir, KIOSK_APK)
        appContext.assets.open(KIOSK_APK).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        if (target.length() == 0L) error("The bundled kiosk app is missing from this build.")
        target
    }

    private companion object {
        const val TAG = "ProvisioningSession"
        const val WATCH_MS = 5_000L
        const val MISSES_BEFORE_TEARDOWN = 3
        const val PORT = 8080
        const val SOCKET_TIMEOUT_MS = 60_000
        const val KIOSK_APK = "kiosk.apk"
        const val DPC_PATH = KioskConfig.DPC_PATH
        const val REPORT_PATH = "/report"
    }
}

/**
 * Base64url SHA-256 of the signing certificate, unpadded, as
 * `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` requires.
 *
 * Computed from the APK being served rather than hardcoded, so debug and release
 * builds both work and there is no constant to forget at release time.
 */
/**
 * The version of the kiosk this build bundles, read from the APK it will serve
 * rather than from `BuildConfig`: the setup app and the kiosk it carries
 * are versioned separately, and it is the served file that phones will get.
 */
private fun kioskVersionCodeOf(context: Context, apk: File): Long? = runCatching {
    context.packageManager
        .getPackageArchiveInfo(apk.absolutePath, 0)
        ?.longVersionCode
}.getOrNull()

fun signatureChecksumOf(context: Context, apk: File): String {
    val fingerprint = Certificates.ofApkFile(context, apk).firstOrNull()
        ?: error("Could not read a signing certificate from the kiosk APK at ${apk.path}")
    return Base64.encodeToString(
        fingerprint.hexToBytes(),
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
    )
}

private fun List<SetupReport>.replacing(report: SetupReport): List<SetupReport> =
    filterNot { it.deviceId == report.deviceId } + report
