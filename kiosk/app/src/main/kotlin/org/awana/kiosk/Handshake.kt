package org.awana.kiosk

import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import org.awana.kiosk.shared.ProvisioningBootstrap
import org.awana.kiosk.shared.Telemetry

/**
 * What each step of the setup wizard's handshake was handed.
 *
 * Nothing on the phone shows these steps, and a QR-provisioned phone has no
 * USB debugging, so this record is the only way to see how far a wizard got.
 */
internal object Handshake {

    private const val TAG = "Handshake"

    fun note(context: Context, step: String, extras: PersistableBundle?): ProvisioningBootstrap? {
        Telemetry.init(context)
        Telemetry.setTag("sdk", Build.VERSION.SDK_INT.toString())
        val bootstrap = ProvisioningBootstrap.from(extras)
        Telemetry.info(
            TAG,
            "$step: admin extras ${extras?.keySet()?.sorted() ?: "absent"}, " +
                "bootstrap ${if (bootstrap == null) "missing" else "present"}, " +
                "device owner ${DevicePolicy(context).isDeviceOwner}",
        )
        bootstrap?.let { Provisioner(context).rememberBootstrap(it) }
        return bootstrap
    }
}
