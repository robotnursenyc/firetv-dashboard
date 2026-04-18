package com.hermes.firetv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the screen on while the dashboard is displayed.
 *
 * Uses a SCREEN_BRIGHT_WAKE_LOCK (not PARTIAL) to ensure the screen stays bright.
 * Note: On Fire TV API 26+, SCREEN_BRIGHT_WAKE_LOCK requires the app to be visible
 * (foreground service satisfies this). The FLAG_KEEP_SCREEN_ON in the Activity handles
 * the case when the app is in the foreground; this service provides an additional
 * layer of protection and keeps the lock across configuration changes.
 *
 * The notification allows the user to see the app is running and return to it.
 */
class KeepAwakeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "firetv_keep_awake_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        // SCREEN_BRIGHT_WAKE_LOCK keeps screen on AND bright while we hold it
        @Suppress("BatteryLife")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FireTVDashboard::ScreenAwake"
        )

        // Hold for 4 hours max — dashboard should be manually closed before this
        wakeLock?.acquire(4 * 60 * 60 * 1000L)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dashboard Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Family Dashboard is running and keeping your screen on"
                setShowBadge(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, DashboardActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build stop action intent
        val stopIntent = Intent(this, DashboardActivity::class.java).apply {
            action = "com.hermes.firetv.STOP"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val stopPi = PendingIntent.getActivity(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Dashboard")
            .setContentText("Screen kept on — tap to return")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Exit",
                stopPi
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.hermes.firetv.STOP") {
            finish()
        }
        return START_STICKY
    }

    private fun finish() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
