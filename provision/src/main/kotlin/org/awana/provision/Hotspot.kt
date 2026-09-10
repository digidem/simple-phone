package org.awana.provision

import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

data class HotspotDetails(
    val ssid: String,
    val passphrase: String,
    /** One of the values the provisioning QR accepts: NONE, WPA, WEP, EAP. */
    val securityType: String,
    val gatewayAddress: String,
) {
    companion object {
        const val SECURITY_NONE = "NONE"
        const val SECURITY_WPA = "WPA"
    }
}

/**
 * Why this is an interface: `startLocalOnlyHotspot` does not exist on an
 * emulator, and its behaviour on budget phones is inconsistent enough that a
 * manual fallback is required in the field regardless.
 */
interface Hotspot {
    suspend fun start(): Result<HotspotDetails>
    fun stop()
}

/**
 * The framework picks the SSID and passphrase and they cannot be set without
 * system permissions — which is fine, since the QR is generated fresh from
 * whatever the framework gives us.
 *
 * The reservation must be held for the whole session: the hotspot dies the
 * moment it is released, which is why the caller keeps this alive in a
 * foreground service.
 */
class LocalOnlyHotspot(context: Context) : Hotspot {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    override suspend fun start(): Result<HotspotDetails> =
        suspendCancellableCoroutine { continuation ->
            try {
                wifi.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                            reservation = res
                            continuation.resume(describe(res))
                        }

                        override fun onFailed(reason: Int) {
                            continuation.resume(Result.failure(HotspotError(explain(reason))))
                        }

                        override fun onStopped() {
                            Log.w(TAG, "Hotspot stopped by the system")
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: Exception) {
                continuation.resume(Result.failure(HotspotError("The hotspot could not be started: ${e.message}")))
            }
        }

    private fun describe(res: WifiManager.LocalOnlyHotspotReservation): Result<HotspotDetails> {
        val config = res.softApConfiguration
        val security = when (config.securityType) {
            SoftApConfiguration.SECURITY_TYPE_OPEN -> HotspotDetails.SECURITY_NONE
            SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> HotspotDetails.SECURITY_WPA
            else -> {
                // The QR format has no value for WPA3-SAE, so a phone that only
                // offers it cannot be used this way at all. Better to say so
                // than to emit a QR that silently fails to connect.
                res.close()
                return Result.failure(
                    HotspotError(
                        "This phone's hotspot uses a newer Wi-Fi security type (WPA3) that the " +
                            "setup QR cannot describe. Use the manual hotspot option instead.",
                    ),
                )
            }
        }
        val gateway = localAddress() ?: return Result.failure(
            HotspotError("The hotspot started but has no address yet. Try again."),
        )
        return Result.success(
            HotspotDetails(
                ssid = config.ssid.orEmpty(),
                passphrase = config.passphrase.orEmpty(),
                securityType = security,
                gatewayAddress = gateway,
            ),
        )
    }

    override fun stop() {
        reservation?.close()
        reservation = null
    }

    private fun explain(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel is free for a hotspot. Move away from other Wi-Fi networks and try again."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "This phone does not allow hotspots. Use the manual hotspot option."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "The hotspot cannot run while this phone is using Wi-Fi in another way. Turn Wi-Fi off and try again."
        else -> "The hotspot could not be started on this phone. Use the manual hotspot option."
    }

    private companion object {
        const val TAG = "LocalOnlyHotspot"
    }
}

/**
 * The trainer turns on normal tethering themselves and types the details in.
 *
 * Required rather than optional: local-only hotspot behaviour on budget phones
 * is inconsistent, and some phones only offer WPA3, which the QR cannot express.
 */
class ManualHotspot(
    private val details: HotspotDetails,
) : Hotspot {
    override suspend fun start(): Result<HotspotDetails> {
        val gateway = localAddress()
            ?: return Result.failure(
                HotspotError(
                    "This phone is not sharing a network yet. Turn on the hotspot in Settings, " +
                        "then come back.",
                ),
            )
        return Result.success(details.copy(gatewayAddress = gateway))
    }

    override fun stop() = Unit
}

class HotspotError(message: String) : Exception(message)

/**
 * The gateway address is commonly 192.168.43.1 but varies by OEM, so it is read
 * rather than assumed.
 */
internal fun localAddress(): String? =
    NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        // Tethering interfaces first: on a phone that also has mobile data up,
        // rmnet would otherwise win and the address would be unreachable.
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
