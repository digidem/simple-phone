package app.comapeo.kiosk

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import app.comapeo.kiosk.policy.KioskConfig

/**
 * The provisioning entry point.
 *
 * Deliberately a thin wrapper: everything the payload does lives in
 * `Provisioner.provision`, which instrumented tests drive directly with a
 * synthesised config. Do not grow logic here.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )
        val config = readConfig(extras)
        if (config == null) {
            Log.e(TAG, "No usable config in the provisioning extras; nothing applied")
            return
        }

        // A receiver's process may be killed once onReceive returns, so the
        // work is handed to a foreground service rather than a bare coroutine.
        ProvisioningService.start(context, config)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    /**
     * Values arrive from the QR path as strings only — nested or typed values
     * through that route are unreliable — so the whole document travels as one
     * JSON string.
     */
    private fun readConfig(extras: PersistableBundle?): KioskConfig? {
        val json = extras?.getString(KioskConfig.EXTRA_KEY) ?: return null
        return runCatching { KioskConfig.parse(json) }
            .onFailure { Log.e(TAG, "Provisioning config was not valid JSON", it) }
            .getOrNull()
    }

    private companion object {
        const val TAG = "KioskDeviceAdmin"
    }
}
