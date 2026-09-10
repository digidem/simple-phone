package org.awana.kiosk.policy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.util.Log

/**
 * Holds a temporary break from lock task and puts the device back into it when
 * the timer expires, so a device can never be left unlocked by accident.
 *
 * Restarting the launcher is what re-locks the device: it is declared
 * `lockTaskMode="if_whitelisted"`, so it re-enters lock task as it starts.
 * Starting an activity from a service is normally blocked in the background,
 * but a Device Owner is exempt.
 */
class LockTaskBreakService : Service() {

    private var timer: CountDownTimer? = null
    private var remainingMs: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOCK) {
            relock()
            return START_NOT_STICKY
        }

        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS)
            ?: DEFAULT_DURATION_MS
        remainingMs = durationMs
        startForeground(NOTIFICATION_ID, notification(remainingMs))

        timer?.cancel()
        timer = object : CountDownTimer(durationMs, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMs = millisUntilFinished
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(millisUntilFinished))
            }

            override fun onFinish() = relock()
        }.start()

        return START_NOT_STICKY
    }

    private fun relock() {
        timer?.cancel()
        val home = DevicePolicy.launcherComponent(this)
        if (home == null) {
            Log.e(TAG, "No launcher to return to")
        } else {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(home)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    private fun notification(remainingMs: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Unlocked", NotificationManager.IMPORTANCE_LOW),
        )
        val minutes = (remainingMs / 60_000) + 1
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.lock_break_title))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.lock_break_body,
                    minutes.toInt(),
                    minutes.toInt(),
                ),
            )
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LockTaskBreak"
        private const val CHANNEL = "lock-break"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_DURATION_MS = "duration"
        private const val ACTION_RELOCK = "org.awana.kiosk.RELOCK"

        const val DEFAULT_DURATION_MS = 10 * 60_000L

        fun start(context: Context, durationMs: Long = DEFAULT_DURATION_MS) {
            context.startForegroundService(
                Intent(context, LockTaskBreakService::class.java)
                    .putExtra(EXTRA_DURATION_MS, durationMs),
            )
        }

        fun relockNow(context: Context) {
            context.startForegroundService(
                Intent(context, LockTaskBreakService::class.java).setAction(ACTION_RELOCK),
            )
        }
    }
}
