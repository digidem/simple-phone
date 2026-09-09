package app.comapeo.kiosk

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import app.comapeo.kiosk.policy.ConfigStore
import app.comapeo.kiosk.policy.DevicePolicy
import app.comapeo.kiosk.policy.Provisioner

/**
 * Wiring, done by hand. There is not enough of it to justify a DI framework,
 * and the people maintaining this do not write Kotlin daily.
 *
 * Implementing [Configuration.Provider] with WorkManager's automatic
 * initialiser removed from the manifest means WorkManager starts on first use
 * rather than on every process start — see the comment in the manifest.
 */
class KioskApp : Application(), Configuration.Provider {

    val configStore: ConfigStore by lazy { ConfigStore(this) }
    val policy: DevicePolicy by lazy { DevicePolicy(this) }
    val provisioner: Provisioner by lazy { Provisioner(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
