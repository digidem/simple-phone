package org.awana.kiosk

import org.awana.kiosk.shared.ProvisioningBootstrap
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
import org.awana.kiosk.policy.ConfigFetch
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

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
        val provisioner = Provisioner(applicationContext)
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
        val configSha256 = intent?.getStringExtra(EXTRA_CONFIG_SHA256)
        // No extras is the launcher's "set this phone up again": the bootstrap
        // from the attempt that failed is the only one this device will ever
        // get, short of a factory reset and another scan.
        val bootstrap = if (serverUrl != null && configSha256 != null) {
            ProvisioningBootstrap(serverUrl, configSha256)
        } else {
            provisioner.pendingBootstrap()
        }
        if (bootstrap == null) {
            Log.e(TAG, "Started without a usable bootstrap")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground()

        scope.launch {
            val staged = File(cacheDir, CONFIG_FILE)
            val config = ConfigFetch.fetch(bootstrap, staged).getOrElse { error ->
                // The config never arrived or did not match its hash, so there
                // is nothing to apply and no server URL that can be trusted to
                // report to. Recorded where the admin screen will show it.
                Log.e(TAG, "Could not obtain the deployment config", error)
                provisioner.recordBootstrapFailure(
                    error.message ?: getString(R.string.provisioning_config_failed),
                    bootstrap,
                )
                launchHome()
                stopSelf(startId)
                return@launch
            }
            staged.delete()

            runCatching { provisioner.provision(config) }
                .onSuccess {
                    Log.i(
                        TAG,
                        "Provisioning finished: ${it.report.failures.size} failure(s), " +
                            "report delivered=${it.reportDelivered}",
                    )
                }
                .onFailure { Log.e(TAG, "Provisioning threw", it) }

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
        private const val EXTRA_SERVER_URL = "serverUrl"
        private const val EXTRA_CONFIG_SHA256 = "configSha256"
        private const val CONFIG_FILE = "deployment-config.json"

        fun start(context: Context, bootstrap: ProvisioningBootstrap) {
            val intent = Intent(context, ProvisioningService::class.java)
                .putExtra(EXTRA_SERVER_URL, bootstrap.serverUrl)
                .putExtra(EXTRA_CONFIG_SHA256, bootstrap.configSha256)
            context.startForegroundService(intent)
        }
    }
}
