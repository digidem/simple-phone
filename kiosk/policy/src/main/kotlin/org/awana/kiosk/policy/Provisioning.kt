package org.awana.kiosk.policy

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Starting provisioning from outside the app module that declares the service. */
object Provisioning {

    const val ACTION_SET_UP_AGAIN = "org.awana.kiosk.action.SET_UP_AGAIN"

    /** Applies the config [Updates.prepare] already fetched and verified. */
    const val ACTION_APPLY_UPDATE = "org.awana.kiosk.action.APPLY_UPDATE"

    /**
     * Re-runs the config fetch against the bootstrap the failed attempt kept.
     * Resolved through `PackageManager` rather than named, so this module needs
     * no compile-time dependency on the app module — the same reason
     * [DevicePolicy.resolveAdminComponent] works that way.
     *
     * Returns false when there is no such service to start.
     */
    fun setUpAgain(context: Context): Boolean = start(context, ACTION_SET_UP_AGAIN)

    /**
     * Runs the staged update in a foreground service rather than in the admin
     * screen's own scope: the payload can be hundreds of megabytes, and a
     * trainer updating a room of phones puts each one down as soon as it starts.
     */
    fun applyStagedUpdate(context: Context): Boolean = start(context, ACTION_APPLY_UPDATE)

    private fun start(context: Context, action: String): Boolean {
        val intent = Intent(action).setPackage(context.packageName)
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
