package org.awana.kiosk.handshake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Holds the deployment server for as long as provisioning runs. A foreground
 * service because ManagedProvisioning takes minutes and the trigger activity
 * is long gone by the time the kiosk asks for its config.
 */
class DeploymentService : Service() {

    private var server: DeploymentServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground()
        if (server == null) {
            val deployment = Deployment.of(this)
            server = DeploymentServer(PORT, deployment).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(TAG, "Serving on 127.0.0.1:$PORT, config ${deployment.configSha256}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun startForeground() {
        val channel = NotificationChannel(TAG, "Handshake", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, TAG)
            .setContentTitle("Serving the deployment")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val TAG = "HandshakeServer"
        const val PORT = 8080

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DeploymentService::class.java))
        }
    }
}

object Assets {
    /** Copies a bundled asset to a file the server can stream with a length. */
    fun stage(context: Context, name: String): File {
        val file = File(context.filesDir, name)
        if (!file.isFile) {
            context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return file
    }

    /** Streamed with a length rather than read in: a payload can be well over 100 MB. */
    fun apk(file: File): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.OK,
        "application/vnd.android.package-archive",
        FileInputStream(file),
        file.length(),
    )
}
