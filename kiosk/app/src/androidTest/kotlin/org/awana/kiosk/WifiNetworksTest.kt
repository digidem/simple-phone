package org.awana.kiosk

import android.content.Context
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.policy.WifiAdmin
import org.awana.kiosk.shared.WifiNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The deployment's own networks are added at provisioning. A user cannot add
 * one afterwards — `DISALLOW_CONFIG_WIFI` is set — so this is the only way a
 * team reaches its sync network without someone typing the admin PIN.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WifiNetworksTest {

    private lateinit var context: Context
    private lateinit var wifi: WifiAdmin

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        assumeTrue("Not device owner; run dpm set-device-owner first", DevicePolicy(context).isDeviceOwner)
        wifi = WifiAdmin(context)
        assumeTrue("This device has no Wi-Fi", wifi.isEnabled)
        forget(SSID)
    }

    @After
    fun tearDown() {
        forget(SSID)
    }

    @Test
    fun aConfiguredNetworkIsJoinedAtProvisioning() = runBlocking {
        val config = TestConfigs.policyOnly(
            wifiNetworks = listOf(WifiNetwork(SSID, "correcthorsebattery")),
        )

        val result = Provisioner(context).provision(config)

        assertTrue(
            "provision reported: ${result.report.failures}",
            result.report.failures.isEmpty(),
        )
        assertTrue(
            "${savedNetworks()} does not contain $SSID",
            SSID in savedNetworks(),
        )
    }

    @Test
    fun addingTheSameNetworkTwiceLeavesOneCopy() = runBlocking {
        val config = TestConfigs.policyOnly(wifiNetworks = listOf(WifiNetwork(SSID, "correcthorsebattery")))
        Provisioner(context).provision(config)
        Provisioner(context).provision(config)

        assertEquals(1, savedNetworks().count { it == SSID })
    }

    /**
     * From Android 10 `getConfiguredNetworks` needs location permission, which
     * the kiosk does not hold, so on a real device [WifiAdmin.savedNetworks]
     * comes back empty however many networks are saved. The shell's identity is
     * borrowed here to see what was actually written.
     */
    private fun savedNetworks(): List<String> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        return try {
            wifi.savedNetworks()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    /**
     * Removes the test network and re-enables everything else: `addNetwork`
     * connects to what it adds, which disables every other saved network, and
     * the emulator needs its own back.
     */
    @Suppress("DEPRECATION")
    private fun forget(ssid: String) {
        val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity()
        try {
            manager.configuredNetworks.orEmpty().forEach { network ->
                if (network.SSID?.trim('"') == ssid) {
                    manager.removeNetwork(network.networkId)
                } else {
                    manager.enableNetwork(network.networkId, false)
                }
            }
            manager.reconnect()
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private companion object {
        const val SSID = "kiosk-test-network"
    }
}
