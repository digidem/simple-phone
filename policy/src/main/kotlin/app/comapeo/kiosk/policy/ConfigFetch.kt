package app.comapeo.kiosk.policy

import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.delay
import java.io.File
import java.security.MessageDigest

/**
 * What the QR actually carries, in place of the config itself.
 *
 * The config used to travel inside the QR as an escaped JSON string, which grew
 * by roughly 200 bytes per app and stopped encoding at all somewhere around
 * eight. This is constant regardless of how many apps a deployment has.
 */
data class ProvisioningBootstrap(
    val serverUrl: String,
    val configSha256: String,
) {
    companion object {
        /** Returns null when the extras do not carry a usable bootstrap. */
        fun from(extras: PersistableBundle?): ProvisioningBootstrap? {
            val serverUrl = extras?.getString(KioskConfig.EXTRA_SERVER_URL)?.takeIf { it.isNotBlank() }
                ?: return null
            val hash = extras.getString(KioskConfig.EXTRA_CONFIG_SHA256)?.takeIf { it.isNotBlank() }
                ?: return null
            return ProvisioningBootstrap(serverUrl.trimEnd('/'), hash.trim().lowercase())
        }
    }
}

/**
 * Fetches the deployment config from the provisioning server and checks it
 * against the hash the QR carried.
 *
 * Plain HTTP over the trainer's hotspot is fine for *delivery*, but not for
 * trust: anyone who scanned the QR can join that hotspot and could impersonate
 * the gateway. The hash arrives through the setup wizard instead, where nothing
 * on the network can alter it, so a poisoned config — one with an attacker's
 * `adminPinHash`, which would hand over the whole device — cannot be
 * substituted.
 */
object ConfigFetch {

    private const val TAG = "ConfigFetch"
    private const val ATTEMPTS = 4
    private const val FIRST_BACKOFF_MS = 1_000L

    suspend fun fetch(bootstrap: ProvisioningBootstrap, into: File): Result<KioskConfig> {
        val url = bootstrap.serverUrl + KioskConfig.CONFIG_PATH

        var lastError: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) delay(FIRST_BACKOFF_MS shl (attempt - 1))

            // Downloaded to a file and hashed from disk so the bytes that are
            // verified are exactly the bytes that are parsed.
            val downloaded = Http.download(url, into)
            if (downloaded.isFailure) {
                lastError = downloaded.exceptionOrNull()
                Log.w(TAG, "Attempt ${attempt + 1} to fetch $url failed", lastError)
                return@repeat
            }

            val bytes = into.readBytes()
            val actual = sha256Hex(bytes)
            if (actual != bootstrap.configSha256) {
                // Not retried: a mismatch is not a transient network fault, and
                // retrying would just ask the same impostor again.
                return Result.failure(
                    ConfigFetchError(
                        "The settings this device downloaded do not match the code that was " +
                            "scanned. Do not use this device. Expected " +
                            "${bootstrap.configSha256.take(16)}…, got ${actual.take(16)}…",
                    ),
                )
            }

            return runCatching { KioskConfig.parse(bytes.decodeToString()) }
                .recoverCatching {
                    throw ConfigFetchError("The settings file from the trainer's phone could not be read.")
                }
        }

        return Result.failure(
            ConfigFetchError(
                "This device could not reach the trainer's phone at ${bootstrap.serverUrl}. " +
                    "Check the phone is still sharing its hotspot, then set this device up again.",
            ),
        )
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }
}

class ConfigFetchError(message: String) : Exception(message)
