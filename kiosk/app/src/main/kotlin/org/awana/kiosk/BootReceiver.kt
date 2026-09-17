package org.awana.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.awana.kiosk.policy.ConfigStore
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Reporter
import org.awana.kiosk.shared.Telemetry
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
        // Also what sends reports a phone kept while it was offline, including
        // from a setup that never got as far as making the kiosk HOME.
        Telemetry.init(appContext)
        // Null when the receiver was invoked directly rather than by the system.
        val pending: PendingResult? = goAsync()

        val config = ConfigStore(appContext).load()
        val policy = DevicePolicy(appContext)
        when {
            config == null -> Log.i(TAG, "No config; device is not provisioned")
            !policy.isDeviceOwner -> Telemetry.warn(TAG, "Not device owner at boot; policy not re-applied")
            else -> {
                val result = policy.applyAll(config)
                Log.i(TAG, "Re-applied at boot: ${result.applied}")
                if (result.failures.isNotEmpty()) {
                    Telemetry.report(
                        TAG,
                        "Policy not fully re-applied at boot",
                        extras = mapOf("failures" to result.failures),
                    )
                }
            }
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (config != null && policy.isDeviceOwner) {
                    config.serverUrl?.let { Reporter(appContext).flush(it) }
                }
                Telemetry.flush()
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
