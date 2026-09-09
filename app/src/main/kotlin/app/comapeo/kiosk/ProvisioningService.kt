package app.comapeo.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.comapeo.kiosk.policy.DevicePolicy
import app.comapeo.kiosk.policy.KioskConfig
import app.comapeo.kiosk.policy.Provisioner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the provisioning payload.
 *
 * A foreground service rather than a coroutine in the receiver: the payload
 * includes a ~190 MB download, and a receiver's process is killable the moment
 * `onReceive` returns.
 */
class ProvisioningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val json = intent?.getStringExtra(EXTRA_CONFIG)
        val config = json?.let { runCatching { KioskConfig.parse(it) }.getOrNull() }
        if (config == null) {
            Log.e(TAG, "Started without a usable config")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground()

        scope.launch {
            val result = runCatching { Provisioner(applicationContext).provision(config) }
            result.onSuccess {
                Log.i(
                    TAG,
                    "Provisioning finished: ${it.report.failures.size} failure(s), " +
                        "report delivered=${it.reportDelivered}",
                )
            }.onFailure { Log.e(TAG, "Provisioning threw", it) }

            launchHome()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Starting the launcher explicitly rather than relying on the system: it is
     * declared `lockTaskMode="if_whitelisted"`, so being started while its
     * package is in the lock-task allowlist is what puts the device into lock
     * task without any further call.
     */
    private fun launchHome() {
        val home = DevicePolicy.launcherComponent(this) ?: return
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(home)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.provisioning_title))
            .setContentText(getString(R.string.provisioning_body))
            .setSmallIcon(R.drawable.ic_kiosk)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ProvisioningService"
        private const val CHANNEL = "provisioning"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_CONFIG = "config"

        fun start(context: Context, config: KioskConfig) {
            val intent = Intent(context, ProvisioningService::class.java)
                .putExtra(EXTRA_CONFIG, config.encode())
            context.startForegroundService(intent)
        }
    }
}
