package org.awana.kiosk.policy

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Starting provisioning from outside the app module that declares the service. */
object Provisioning {

    const val ACTION_SET_UP_AGAIN = "org.awana.kiosk.action.SET_UP_AGAIN"

    /**
     * Re-runs the config fetch against the bootstrap the failed attempt kept.
     * Resolved through `PackageManager` rather than named, so this module needs
     * no compile-time dependency on the app module — the same reason
     * [DevicePolicy.resolveAdminComponent] works that way.
     *
     * Returns false when there is no such service to start.
     */
    fun setUpAgain(context: Context): Boolean {
        val intent = Intent(ACTION_SET_UP_AGAIN).setPackage(context.packageName)
        val service = context.packageManager.queryIntentServices(intent, 0).firstOrNull()
            ?: return false
        context.startForegroundService(
            intent.setComponent(
                ComponentName(service.serviceInfo.packageName, service.serviceInfo.name),
            ),
        )
        return true
    }
}
