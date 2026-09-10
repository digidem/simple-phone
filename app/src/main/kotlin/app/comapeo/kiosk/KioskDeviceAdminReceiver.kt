package app.comapeo.kiosk

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import app.comapeo.kiosk.policy.ProvisioningBootstrap

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
        val bootstrap = readBootstrap(extras)
        if (bootstrap == null) {
            Log.e(TAG, "No usable bootstrap in the provisioning extras; nothing applied")
            return
        }

        // A receiver's process may be killed once onReceive returns, so the
        // work is handed to a foreground service rather than a bare coroutine.
        ProvisioningService.start(context, bootstrap)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    /**
     * The QR carries only where to fetch the config and what it should hash to,
     * not the config itself — a deployment's app list made the code too dense
     * to scan long before it hit the format's limit.
     *
     * Values arrive from the QR path as strings only; nested or typed values
     * through that route are unreliable.
     */
    private fun readBootstrap(extras: PersistableBundle?): ProvisioningBootstrap? =
        ProvisioningBootstrap.from(extras)

    private companion object {
        const val TAG = "KioskDeviceAdmin"
    }
}
