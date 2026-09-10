package org.awana.provision

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.Digests.hexToBytes
import org.awana.kiosk.shared.EnrolmentReport
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ServerManifest
import java.io.File

data class SessionState(
    val running: Boolean = false,
    /** Set as soon as a session is asked for, so a recreated activity can find it. */
    val profileId: String? = null,
    val profileName: String = "",
    val hotspot: HotspotDetails? = null,
    val serverUrl: String? = null,
    val qrPayload: String? = null,
    val reports: List<EnrolmentReport> = emptyList(),
    val error: String? = null,
) {
    val enrolled: Int get() = reports.size
    val succeeded: Int get() = reports.count { it.succeeded }
}

/**
 * Holds one enrolment session: hotspot up, server up, QR shown, reports coming
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

        // The profile id is the deployment id, so devices enrolled in different
        // sessions of the same deployment still belong together.
        val config = KioskConfig(
            deploymentId = profile.id,
            deploymentName = profile.name,
            adminPinHash = profile.adminPinHash,
            serverUrl = serverUrl,
            packages = specs,
            visibleInLauncher = profile.visibleInLauncher,
            wifiNetworks = profile.wifiNetworks,
            showNotificationShade = profile.showNotificationShade,
            locale = profile.locale,
            screenOffTimeoutMs = profile.screenOffTimeoutMs,
        )

        // Encoded once and served verbatim: the QR carries a hash of exactly
        // these bytes, so re-encoding anywhere would break every device's check.
        val configJson = KioskJson.compact.encodeToString(KioskConfig.serializer(), config)
        val configSha256 = Digests.sha256Hex(configJson.toByteArray())

        val manifest = ServerManifest(profile.id, profile.name, specs)
        val running = ProvisioningServer(
            port = PORT,
            kioskApk = kioskApk,
            payload = served,
            manifest = manifest,
            configJson = configJson,
            onReport = { report -> _state.update { it.copy(reports = it.reports.replacing(report)) } },
        )
        runCatching { running.start(SOCKET_TIMEOUT_MS, false) }.getOrElse { error ->
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

    fun stop() {
        server?.stop()
        server = null
        hotspot?.stop()
        hotspot = null
        _state.value = SessionState()
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
        const val PORT = 8080
        const val SOCKET_TIMEOUT_MS = 60_000
        const val KIOSK_APK = "kiosk.apk"
    }
}

/**
 * Base64url SHA-256 of the signing certificate, unpadded, as
 * `EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` requires.
 *
 * Computed from the APK being served rather than hardcoded, so debug and release
 * builds both work and there is no constant to forget at release time.
 */
fun signatureChecksumOf(context: Context, apk: File): String {
    val fingerprint = Certificates.ofApkFile(context, apk).firstOrNull()
        ?: error("Could not read a signing certificate from the kiosk APK at ${apk.path}")
    return Base64.encodeToString(
        fingerprint.hexToBytes(),
        Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
    )
}

private fun List<EnrolmentReport>.replacing(report: EnrolmentReport): List<EnrolmentReport> =
    filterNot { it.deviceId == report.deviceId } + report
