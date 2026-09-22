package org.awana.kiosk.handshake

import android.content.Context
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.KioskJson
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.ServerManifest
import java.io.File

/**
 * The one deployment this emulator is set up from: what a trainer's session
 * would be serving, built on the device the same way `ProvisioningSession`
 * builds it.
 *
 * The trigger needs the config hash for the setup code and the server needs the
 * config bytes, so both come from one instance held for the life of the
 * process.
 */
class Deployment private constructor(
    val kioskApk: File,
    val sampleApk: File,
    /** Encoded once and served verbatim; the setup code's hash is over these bytes. */
    val configJson: String,
    val configSha256: String,
    val manifest: ServerManifest,
) {

    companion object {

        const val SERVER_URL = "http://127.0.0.1:${DeploymentService.PORT}"

        /** The `:sample` APK, bundled by Gradle, standing in for a deployment's app. */
        const val SAMPLE_PACKAGE = "org.awana.kiosk.sample"

        const val SAMPLE_PATH = "/apks/$SAMPLE_PACKAGE.apk"

        const val PIN = "246813"

        /** Applied by the wizard mid-run, which is what recreates the trigger activity. */
        const val LOCALE = "pt_BR"

        const val TIME_ZONE = "America/Manaus"

        private const val ID = "handshake-deployment"

        private var instance: Deployment? = null

        @Synchronized
        fun of(context: Context): Deployment = instance ?: build(context).also { instance = it }

        private fun build(context: Context): Deployment {
            val kioskApk = Assets.stage(context, "kiosk.apk")
            val sampleApk = Assets.stage(context, "sample.apk")
            val spec = PackageSpec(
                packageName = SAMPLE_PACKAGE,
                certSha256 = Certificates.ofApkFile(context, sampleApk).first(),
                path = SAMPLE_PATH,
            )
            val config = KioskConfig(
                deploymentId = ID,
                deploymentName = "Handshake Deployment",
                adminPinHash = AdminPin.hash(PIN),
                serverUrl = SERVER_URL,
                packages = listOf(spec),
                launcher = listOf(LauncherEntry(SAMPLE_PACKAGE, LauncherRole.HERO)),
                locale = LOCALE,
                kioskVersionCode = versionCodeOf(context, kioskApk),
            )
            val configJson = KioskJson.compact.encodeToString(KioskConfig.serializer(), config)
            return Deployment(
                kioskApk = kioskApk,
                sampleApk = sampleApk,
                configJson = configJson,
                configSha256 = Digests.sha256Hex(configJson.toByteArray()),
                manifest = ServerManifest(ID, config.deploymentName, listOf(spec)),
            )
        }

        /** Equal to what is installed, so the kiosk does not replace itself mid-handshake. */
        private fun versionCodeOf(context: Context, apk: File): Long? = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.longVersionCode
        }.getOrNull()
    }
}
