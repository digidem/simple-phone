package org.awana.kiosk

import org.awana.kiosk.shared.KioskConfig
import org.awana.kiosk.shared.PackageSpec
import org.awana.kiosk.shared.WifiNetwork
import org.awana.kiosk.shared.AdminPin
import org.awana.kiosk.shared.LauncherEntry
import org.awana.kiosk.shared.LauncherRole

/**
 * Synthesised configs for driving `Provisioner.provision` directly, which is
 * the whole point of keeping the provisioning callback a thin wrapper.
 */
object TestConfigs {

    const val PIN = "246813"

    /** A package that is never installed on the test device, so policy tests exercise the not-yet-installed case. */
    const val APP_PACKAGE = "org.example.fieldapp"
    const val APP_CERT_SHA256 = "f88123ed1f334792c07f1df20ac91038ed2569c3f93f46acb482462d1daf7054"

    /** No `serverUrl`, so policy is applied without any download step. */
    fun policyOnly(
        showNotificationShade: Boolean = false,
        packages: List<PackageSpec> = listOf(appSpec()),
        visible: List<String> = packages.map { it.packageName },
        wifiNetworks: List<WifiNetwork> = emptyList(),
    ) = KioskConfig(
        deploymentId = "test-deployment",
        deploymentName = "Test Deployment",
        adminPinHash = AdminPin.hash(PIN),
        serverUrl = null,
        packages = packages,
        launcher = visible.mapIndexed { index, packageName ->
            LauncherEntry(packageName, if (index == 0) LauncherRole.HERO else LauncherRole.SMALL)
        },
        wifiNetworks = wifiNetworks,
        showNotificationShade = showNotificationShade,
        locale = "en",
        screenOffTimeoutMs = 120_000,
    )

    fun withServer(serverUrl: String, packages: List<PackageSpec>) =
        policyOnly(packages = packages).copy(serverUrl = serverUrl)

    fun appSpec() = PackageSpec(
        packageName = APP_PACKAGE,
        certSha256 = APP_CERT_SHA256,
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.CAMERA,
        ),
    )
}
