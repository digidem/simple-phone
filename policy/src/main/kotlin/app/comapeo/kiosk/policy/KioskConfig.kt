package app.comapeo.kiosk.policy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The deployment configuration document, held as JSON in the DPC's private
 * storage. Seeded at provisioning from the admin extras bundle, edited
 * afterwards from the admin screen.
 */
@Serializable
data class KioskConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val deploymentId: String,
    val deploymentName: String,
    val adminPinHash: String,
    /** Base URL of the provisioning server. Absent once provisioning is done. */
    val serverUrl: String? = null,
    /** Installed, uninstall-blocked and lock-task allowlisted. */
    val packages: List<PackageSpec> = emptyList(),
    /** Shown as an icon on the launcher. A subset of [packages]. */
    val visibleInLauncher: List<String> = emptyList(),
    val showNotificationShade: Boolean = false,
    val updateChannel: UpdateChannel = UpdateChannel(),
    val locale: String = "en",
    val screenOffTimeoutMs: Long = 120_000,
    /** Whether the updater may use metered connections. */
    val updateOnMeteredNetworks: Boolean = false,
) {
    val launcherPackages: List<String>
        get() = packages.map { it.packageName }.filter { it in visibleInLauncher }

    companion object {
        const val SCHEMA_VERSION = 1

        /**
         * The extras-bundle key carrying this document. The provisioning QR path
         * delivers every extra as a string, so the whole config travels as one
         * JSON string rather than as a nested bundle.
         */
        const val EXTRA_KEY = "app.comapeo.kiosk.CONFIG_JSON"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun parse(text: String): KioskConfig = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

/**
 * A package to install, with the signing certificate it must present.
 *
 * CoMapeo's fingerprint is pinned in [Pins] and overrides whatever the config
 * carries; every other entry is trainer-supplied and verified against the
 * fingerprint recorded here at provisioning time.
 */
@Serializable
data class PackageSpec(
    val packageName: String,
    /** Lowercase hex SHA-256 of the signing certificate. */
    val certSha256: String,
    /** Path on the provisioning server, relative to `serverUrl`. */
    val path: String = "/apks/$packageName.apk",
    val versionName: String? = null,
    val versionCode: Long? = null,
)

@Serializable
data class UpdateChannel(
    val type: String = TYPE_GITHUB,
    val repo: String = "digidem/comapeo-mobile",
    /** Package the channel updates. */
    @SerialName("package") val packageName: String = Pins.COMAPEO_PACKAGE,
) {
    companion object {
        const val TYPE_GITHUB = "github"
        const val TYPE_URL = "url"
        const val TYPE_NONE = "none"
    }
}

/**
 * Values that must not be substitutable at runtime.
 *
 * If a trainer can be talked into deploying a fake CoMapeo, every device in the
 * team is compromised at once, so this fingerprint is compiled in rather than
 * carried in the provisioning payload.
 */
object Pins {
    const val COMAPEO_PACKAGE = "com.comapeo"

    /** SHA-256 of `CN=com.comapeo, C=US, O=Awana Digital, OU=CoMapeo`. */
    const val COMAPEO_CERT_SHA256 =
        "f88123ed1f334792c07f1df20ac91038ed2569c3f93f46acb482462d1daf7054"

    /**
     * The fingerprint [packageName] must present, preferring the pinned value
     * over anything the config claims.
     */
    fun expectedCertFor(packageName: String, fromConfig: String?): String? =
        if (packageName == COMAPEO_PACKAGE) COMAPEO_CERT_SHA256 else fromConfig
}
