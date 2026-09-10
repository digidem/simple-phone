package org.awana.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Reporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Re-applies the policy set at boot.
 *
 * Nothing here starts an activity: background activity launches are blocked
 * from Android 10, and none is needed — the launcher is the persistent
 * preferred HOME activity, so the system starts it, and `if_whitelisted` puts
 * it back into lock task as it starts.
 */
class BootReceiver : BroadcastReceiver() {

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }

        val appContext = context.applicationContext
        val config = ConfigStore(appContext).load()
        if (config == null) {
            Log.i(TAG, "No config; device is not provisioned")
            return
        }

        val policy = DevicePolicy(appContext)
        if (!policy.isDeviceOwner) {
            Log.w(TAG, "Not device owner at boot; policy not re-applied")
            return
        }

        val result = policy.applyAll(config)
        Log.i(TAG, "Re-applied at boot: ${result.applied}")
        if (result.failures.isNotEmpty()) {
            Log.e(TAG, "Policy not fully re-applied at boot: ${result.failures}")
        }

        // Null when the receiver was invoked directly rather than by the system.
        val pending: PendingResult? = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                config.serverUrl?.let { Reporter(appContext).flush(it) }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
