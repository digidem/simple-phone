package org.awana.kiosk.policy

import android.util.Log
import kotlinx.coroutines.delay
import org.awana.kiosk.shared.Digests
import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.ProvisioningBootstrap
import java.io.File

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

    /**
     * [attempts] is raised by the update path: an update arrives already on the
     * trainer's hotspot, but a phone in the field has just been told to join it
     * and association can take longer than the default backoff allows.
     */
    suspend fun fetch(
        bootstrap: ProvisioningBootstrap,
        into: File,
        attempts: Int = ATTEMPTS,
    ): Result<KioskConfig> {
        val url = bootstrap.serverUrl + KioskConfig.CONFIG_PATH

        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
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
            val actual = Digests.sha256Hex(bytes)
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
}

class ConfigFetchError(message: String) : Exception(message)
