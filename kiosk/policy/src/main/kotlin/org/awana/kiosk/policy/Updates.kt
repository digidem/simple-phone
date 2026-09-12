package org.awana.kiosk.policy

import android.content.Context
import org.awana.kiosk.shared.Certificates
import org.awana.kiosk.shared.EnrolmentCode
import org.awana.kiosk.shared.KioskConfig
import java.io.File

/**
 * Getting a fresh deployment config onto a phone that is already in service.
 *
 * The phone joins the trainer's hotspot and fetches the config, but stops
 * before applying it. Whether this is even the same deployment is a question
 * only the person holding the phone can answer, and asking it in this order
 * means the name they are shown comes out of hash-verified bytes rather than
 * out of the code, which anyone could have printed.
 */
object Updates {

    /** Six attempts is about half a minute of backoff; see [ConfigFetch.fetch]. */
    private const val ATTEMPTS_WHILE_JOINING = 6

    private const val STAGED = "update-config.json"
    private const val RESTORE = "update-restore-networks"

    sealed interface Refusal {
        /** The session was run by a build signed with a different key. */
        data object OtherFleet : Refusal

        data class CouldNotFetch(val reason: String) : Refusal
    }

    class RefusedException(val refusal: Refusal, message: String) : Exception(message)

    /**
     * Joins the hotspot in [code] and fetches the config it points at.
     *
     * The signing key is checked before the phone does anything at all. A
     * config carries `adminPinHash`, so a session from the debug fleet could
     * otherwise change a production phone's PIN, and KEYS.md is explicit that
     * the two fleets must never mix.
     */
    suspend fun prepare(context: Context, code: EnrolmentCode): Result<KioskConfig> {
        val ours = Certificates.ofInstalledPackage(context, context.packageName)
        if (!code.signedBySameKeyAs(ours)) {
            return Result.failure(
                RefusedException(
                    Refusal.OtherFleet,
                    "This code is from a different Field Kiosk build than the one on this phone.",
                ),
            )
        }

        UpdateProgress.report(UpdateState.JoiningWifi)
        val wifi = WifiAdmin(context)
        wifi.setEnabled(true)
        rememberNetworksToRestore(context, wifi.joinNow(code.wifi.ssid, code.wifi.passphrase))

        UpdateProgress.report(UpdateState.FetchingSettings)
        val staged = File(context.filesDir, STAGED)
        return ConfigFetch.fetch(code.bootstrap, staged, ATTEMPTS_WHILE_JOINING)
            .recoverCatching { error ->
                finish(context)
                throw RefusedException(
                    Refusal.CouldNotFetch(error.message.orEmpty()),
                    error.message ?: "The settings could not be downloaded.",
                )
            }
    }

    /** True when applying [incoming] would move the phone to a different deployment. */
    fun movesDeployment(current: KioskConfig?, incoming: KioskConfig): Boolean =
        current != null && current.deploymentId != incoming.deploymentId

    fun stagedConfig(context: Context): KioskConfig? {
        val file = File(context.filesDir, STAGED)
        if (!file.exists()) return null
        return runCatching { KioskConfig.parse(file.readText()) }.getOrNull()
    }

    /**
     * Puts the phone's own Wi-Fi back and clears the staged config.
     *
     * Written to disk rather than held in memory because joining the trainer's
     * hotspot disables every other saved network: if the process died between
     * the join and here, a phone would be left unable to rejoin its
     * deployment's own network — a worse problem than the one it came for.
     */
    fun finish(context: Context) {
        val file = File(context.filesDir, RESTORE)
        val ids = runCatching {
            file.readText().split(",").filter { it.isNotBlank() }.map { it.toInt() }
        }.getOrDefault(emptyList())
        WifiAdmin(context).reEnable(ids)
        file.delete()
        File(context.filesDir, STAGED).delete()
    }

    private fun rememberNetworksToRestore(context: Context, ids: List<Int>) {
        File(context.filesDir, RESTORE).writeText(ids.joinToString(","))
    }
}
