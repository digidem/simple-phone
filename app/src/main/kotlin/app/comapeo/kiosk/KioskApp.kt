package app.comapeo.kiosk

import android.app.Application
import app.comapeo.kiosk.policy.ConfigStore
import app.comapeo.kiosk.policy.DevicePolicy
import app.comapeo.kiosk.policy.Provisioner

/**
 * Wiring, done by hand. There is not enough of it to justify a DI framework,
 * and the people maintaining this do not write Kotlin daily.
 */
class KioskApp : Application() {

    val configStore: ConfigStore by lazy { ConfigStore(this) }
    val policy: DevicePolicy by lazy { DevicePolicy(this) }
    val provisioner: Provisioner by lazy { Provisioner(this) }
}
