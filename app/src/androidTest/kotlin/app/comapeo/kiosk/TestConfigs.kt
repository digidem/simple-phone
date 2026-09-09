package app.comapeo.kiosk

import app.comapeo.kiosk.policy.AdminPin
import app.comapeo.kiosk.policy.KioskConfig
import app.comapeo.kiosk.policy.PackageSpec
import app.comapeo.kiosk.policy.Pins

/**
 * Synthesised configs for driving `Provisioner.provision` directly, which is
 * the whole point of keeping the provisioning callback a thin wrapper.
 */
object TestConfigs {

    const val PIN = "246813"

    /** No `serverUrl`, so policy is applied without any download step. */
    fun policyOnly(
        showNotificationShade: Boolean = false,
        packages: List<PackageSpec> = listOf(comapeoSpec()),
        visible: List<String> = packages.map { it.packageName },
    ) = KioskConfig(
        deploymentId = "test-deployment",
        deploymentName = "Test Deployment",
        adminPinHash = AdminPin.hash(PIN),
        serverUrl = null,
        packages = packages,
        visibleInLauncher = visible,
        showNotificationShade = showNotificationShade,
        locale = "en",
        screenOffTimeoutMs = 120_000,
    )

    fun withServer(serverUrl: String, packages: List<PackageSpec>) =
        policyOnly(packages = packages).copy(serverUrl = serverUrl)

    fun comapeoSpec() = PackageSpec(
        packageName = Pins.COMAPEO_PACKAGE,
        certSha256 = Pins.COMAPEO_CERT_SHA256,
    )
}
