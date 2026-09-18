package org.awana.kiosk

import org.awana.kiosk.shared.Telemetry
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log

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
        val bootstrap = Handshake.note(context, "Provisioning complete", extras)

        // A receiver's process may be killed once onReceive returns, so the
        // work is handed to a foreground service rather than a bare coroutine.
        // With no bootstrap here the service uses the one the handshake kept.
        // On current Android the compliance activity has normally done the
        // work already, and this is only the fallback.
        try {
            ProvisioningService.startAfterCompletion(context, bootstrap)
        } catch (e: Exception) {
            Telemetry.report(TAG, "Could not start provisioning", error = e)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Telemetry.init(context)
        Telemetry.info(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin disabled")
    }

    private companion object {
        const val TAG = "KioskDeviceAdmin"
    }
}
