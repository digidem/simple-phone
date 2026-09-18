package org.awana.kiosk.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.awana.kiosk.shared.Telemetry

/**
 * Keeps the hotspot and server alive for the whole session.
 *
 * A foreground service and not a coroutine in the activity: the
 * `LocalOnlyHotspotReservation` dies with the process that holds it, and the
 * trainer will put the phone down between devices.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) == true
        val profile = profileId?.let { ProfileStore(this).get(it) }
        if (profile == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification(profile.name))

        scope.launch {
            val hotspot = if (manual) {
                ManualHotspot(
                    this@SessionService,
                    HotspotDetails(
                        ssid = intent.getStringExtra(EXTRA_SSID).orEmpty(),
                        passphrase = intent.getStringExtra(EXTRA_PASSPHRASE).orEmpty(),
                        securityType = HotspotDetails.SECURITY_WPA,
                        gatewayAddress = "",
                    ),
                )
            } else {
                LocalOnlyHotspot(this@SessionService)
            }
            if (session.start(profile, hotspot).isSuccess) session.watchAddress()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(profileName: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Provisioning", NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_body, profileName))
            .setSmallIcon(R.drawable.ic_provision)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "provisioning"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PROFILE_ID = "profileId"
        private const val EXTRA_MANUAL = "manual"
        private const val EXTRA_SSID = "ssid"
        private const val EXTRA_PASSPHRASE = "passphrase"

        /**
         * One session at a time, shared between the service and the UI. A
         * singleton rather than binder plumbing: there is exactly one hotspot
         * on the phone, so there is exactly one session.
         */
        lateinit var session: ProvisioningSession
            private set

        fun ensureSession(context: Context) {
            if (!::session.isInitialized) session = ProvisioningSession(context)
        }

        fun start(context: Context, profileId: String) {
            ensureSession(context)
            context.startForegroundService(
                Intent(context, SessionService::class.java).putExtra(EXTRA_PROFILE_ID, profileId),
            )
        }

        fun startManual(context: Context, profileId: String, ssid: String, passphrase: String) {
            ensureSession(context)
            context.startForegroundService(
                Intent(context, SessionService::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
                    .putExtra(EXTRA_MANUAL, true)
                    .putExtra(EXTRA_SSID, ssid)
                    .putExtra(EXTRA_PASSPHRASE, passphrase),
            )
        }

        // stopService rather than a stop action: startService throws once the
        // app is in the background, and onDestroy already ends the session.
        fun stop(context: Context) {
            if (::session.isInitialized) session.stop()
            context.stopService(Intent(context, SessionService::class.java))
        }
    }
}
