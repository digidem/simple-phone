package org.awana.kiosk.setup

import android.content.Context
import android.net.ConnectivityManager
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
        val gateway = localAddress(appContext) ?: return Result.failure(
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
    context: Context,
    private val details: HotspotDetails,
    private val findAddress: () -> String? = { localAddress(context.applicationContext) },
) : Hotspot {
    override suspend fun start(): Result<HotspotDetails> {
        val gateway = findAddress()
            ?: return Result.failure(
                HotspotError(
                    "This phone is not sharing a network yet. Turn on the hotspot in Settings, " +
                        "then come back.",
                ),
            )
        val security = if (details.passphrase.isBlank()) {
            HotspotDetails.SECURITY_NONE
        } else {
            HotspotDetails.SECURITY_WPA
        }
        return Result.success(details.copy(gatewayAddress = gateway, securityType = security))
    }

    override fun stop() = Unit
}

class HotspotError(message: String) : Exception(message)

/**
 * The gateway address is commonly 192.168.43.1 but varies by OEM, so it is read
 * rather than assumed.
 */
internal fun localAddress(context: Context): String? =
    pickHotspotAddress(siteLocalAddresses(), clientInterfaces(context))

internal data class LocalAddress(val interfaceName: String, val address: String)

internal fun siteLocalAddresses(): List<LocalAddress> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface ->
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                .map { LocalAddress(iface.name.orEmpty(), it.hostAddress.orEmpty()) }
        }

/**
 * Interfaces this phone reaches a network *through*: its own Wi-Fi connection,
 * mobile data. A hotspot is not a network the phone is a client of, so its
 * interface never appears here.
 */
internal fun clientInterfaces(context: Context): Set<String> {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptySet()
    @Suppress("DEPRECATION")
    return manager.allNetworks.mapNotNull { manager.getLinkProperties(it)?.interfaceName }.toSet()
}

/**
 * The address phones joining the hotspot can reach this one at. An address on
 * a client interface is ruled out even when it is the only one: it works only
 * while that network stays up: a session served from the phone's own Wi-Fi
 * address stops working for every phone the moment that Wi-Fi goes off. Interface names vary too
 * much by OEM to decide this alone; [interfaceRank] only breaks ties.
 */
internal fun pickHotspotAddress(candidates: List<LocalAddress>, clientInterfaces: Set<String>): String? =
    candidates
        .filter { it.interfaceName !in clientInterfaces }
        .minByOrNull { interfaceRank(it.interfaceName) }
        ?.address

/** Whether [address] is still one of this phone's own, for a session to notice its hotspot going. */
internal fun isLocalAddress(address: String): Boolean =
    runCatching { siteLocalAddresses().any { it.address == address } }.getOrDefault(false)

internal fun describeInterfaces(context: Context): String {
    val clients = clientInterfaces(context)
    return siteLocalAddresses().joinToString(", ") {
        "${it.interfaceName}=${it.address}" + if (it.interfaceName in clients) " (client)" else ""
    }.ifEmpty { "none" }
}

private val TETHERED_WLAN = Regex("wlan[1-9]\\d*")

/**
 * Lower sorts first. A trainer's phone joined to a Wi-Fi network as a client
 * holds a site-local address on `wlan0` that no phone being set up can reach, so
 * the interface the hotspot actually runs on has to win: `ap0` on most phones,
 * `swlan0` on Samsung, `wlan1` where the radio is shared.
 */
internal fun interfaceRank(name: String): Int = when {
    name.startsWith("ap") || name.startsWith("swlan") -> 0
    name.matches(TETHERED_WLAN) -> 0
    name.startsWith("wlan") -> 1
    else -> 2
}
