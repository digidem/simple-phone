package org.awana.kiosk

import org.awana.kiosk.shared.ProvisioningBootstrap
import org.awana.kiosk.shared.Telemetry
import io.sentry.SentryLevel
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.awana.kiosk.policy.ConfigFetch
import org.awana.kiosk.policy.DevicePolicy
import org.awana.kiosk.policy.Provisioning
import org.awana.kiosk.policy.UpdateProgress
import org.awana.kiosk.policy.UpdateState
import org.awana.kiosk.policy.Updates
import org.awana.kiosk.policy.KioskUpdate
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

    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Provisioning.ACTION_APPLY_UPDATE) {
            applyStagedUpdate(startId)
            return START_NOT_STICKY
        }

        val provisioner = Provisioner(applicationContext)
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
        val configSha256 = intent?.getStringExtra(EXTRA_CONFIG_SHA256)
        // No extras is the launcher's "set this phone up again", or a wizard
        // that dropped the admin extras before the completion broadcast: the
        // bootstrap kept earlier is the only one this device will ever get,
        // short of a factory reset and another scan.
        val bootstrap = if (serverUrl != null && configSha256 != null) {
            ProvisioningBootstrap(serverUrl, configSha256)
        } else {
            provisioner.pendingBootstrap()
        }
        if (bootstrap == null) {
            Telemetry.report(TAG, "Started without a usable bootstrap", context = this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Telemetry.info(
            TAG,
            "Provisioning from ${bootstrap.serverUrl} " +
                "(bootstrap ${if (serverUrl != null) "from the intent" else "kept earlier"})",
        )

        startForeground()

        scope.launch {
            val staged = File(cacheDir, CONFIG_FILE)
            val config = ConfigFetch.fetch(bootstrap, staged).getOrElse { error ->
                // The config never arrived or did not match its hash, so there
                // is nothing to apply and no server URL that can be trusted to
                // report to. Recorded where the admin screen will show it.
                Telemetry.report(
                    TAG,
                    "Could not obtain the deployment config: ${error.message}",
                    extras = mapOf("serverUrl" to bootstrap.serverUrl),
                    context = this@ProvisioningService,
                )
                provisioner.recordBootstrapFailure(
                    error.message ?: getString(R.string.provisioning_config_failed),
                    bootstrap,
                )
                launchHome()
                Telemetry.flush()
                stopSelf(startId)
                return@launch
            }
            staged.delete()

            runCatching { provisioner.provision(config) }
                .onSuccess {
                    val failures = it.report.failures + it.report.permissionFailures
                    Telemetry.report(
                        TAG,
                        "Provisioning finished with ${failures.size} failure(s)",
                        level = if (failures.isEmpty()) SentryLevel.INFO else SentryLevel.WARNING,
                        extras = mapOf(
                            "failures" to failures,
                            "reportDelivered" to it.reportDelivered,
                            "isDeviceOwner" to it.report.isDeviceOwner,
                            "packages" to it.report.packageOutcomes,
                        ),
                        context = this@ProvisioningService,
                    )
                }
                .onFailure {
                    Telemetry.report(TAG, "Provisioning threw", error = it, context = this@ProvisioningService)
                }

            launchHome()
            Telemetry.flush()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * The admin screen has already joined the trainer's hotspot, fetched the
     * config and had it verified against the hash on the code, so there is
     * nothing left to trust here — only to apply, which is the part that takes
     * long enough to need a service.
     *
     * No `launchHome()` at the end: the trainer is watching the admin screen,
     * and throwing them back to the home screen would hide the result.
     */
    private fun applyStagedUpdate(startId: Int) {
        val config = Updates.stagedConfig(applicationContext)
        if (config == null) {
            Telemetry.report(TAG, "Asked to apply an update with nothing staged")
            UpdateProgress.report(UpdateState.Failed(getString(R.string.update_nothing_staged)))
            stopSelf(startId)
            return
        }

        startForeground()
        scope.launch {
            val outcome = runCatching {
                Provisioner(applicationContext).provision(config) { UpdateProgress.report(it) }
            }
            // The phone goes back on its own Wi-Fi before anything else: it has
            // to be true both by the time someone reads "up to date" and walks
            // away, and before the kiosk update below kills this process.
            Updates.finish(applicationContext)

            // Last, after the payload, the policy and the report. Committing
            // this replaces the running app, so anything left undone here stays
            // undone — and on success it does not return at all.
            val kioskUpdate = outcome.getOrNull()
                ?.takeIf { KioskUpdate.isAvailable(applicationContext, config) }
                ?.let {
                    UpdateProgress.report(UpdateState.UpdatingKiosk)
                    KioskUpdate.apply(applicationContext, config)
                }

            outcome
                .onSuccess { result ->
                    val failures = result.report.failures + listOfNotNull(kioskUpdate)
                    if (failures.isNotEmpty()) {
                        Telemetry.report(
                            TAG,
                            "Update finished with ${failures.size} failure(s)",
                            level = SentryLevel.WARNING,
                            extras = mapOf("failures" to failures),
                        )
                    }
                    UpdateProgress.report(
                        if (kioskUpdate == null) {
                            UpdateState.Done(result.report)
                        } else {
                            // The phone is updated; only replacing the kiosk
                            // failed, and saying so beats a bare success.
                            UpdateState.Done(
                                result.report.copy(
                                    failures = result.report.failures + kioskUpdate,
                                ),
                            )
                        },
                    )
                }
                .onFailure {
                    Telemetry.report(TAG, "The update threw", error = it, context = this@ProvisioningService)
                    UpdateProgress.report(
                        UpdateState.Failed(it.message ?: getString(R.string.update_failed)),
                    )
                }
            Telemetry.flush()
            stopSelf(startId)
        }
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
        val home = DevicePolicy.launcherComponent(this)
        if (home == null) {
            Telemetry.warn(TAG, "No launcher component to open")
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(home)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Telemetry.report(TAG, "Could not open the launcher", error = e)
        }
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

        /** A null [bootstrap] runs the one [Provisioner.pendingBootstrap] kept. */
        fun start(context: Context, bootstrap: ProvisioningBootstrap?) {
            val intent = Intent(context, ProvisioningService::class.java)
            bootstrap?.let {
                intent.putExtra(EXTRA_SERVER_URL, it.serverUrl)
                    .putExtra(EXTRA_CONFIG_SHA256, it.configSha256)
            }
            context.startForegroundService(intent)
        }
    }
}
