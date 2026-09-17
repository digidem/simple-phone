package org.awana.kiosk.setup

/**
 * A hotspot that is already up. `startLocalOnlyHotspot` does not exist on an
 * emulator, so this is the only way to drive a whole session in a test.
 */
class FakeHotspot(
    private val details: HotspotDetails = HotspotDetails(
        ssid = "AndroidShare_0000",
        passphrase = "correcthorsebattery",
        securityType = HotspotDetails.SECURITY_WPA,
        gatewayAddress = "127.0.0.1",
    ),
    private val failure: Throwable? = null,
) : Hotspot {

    var stopped = false
        private set

    override suspend fun start(): Result<HotspotDetails> =
        failure?.let { Result.failure(it) } ?: Result.success(details)

    override fun stop() {
        stopped = true
    }
}
