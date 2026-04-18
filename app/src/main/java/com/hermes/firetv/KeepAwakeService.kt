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
import android.view.MotionEvent
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class KeepAwakeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = true

    companion object {
        private const val CHANNEL_ID = "firetv_keep_awake_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TOUCH_INTERVAL_MS = 30_000L
    }

    override fun onCreate() {
        super.onCreate()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FireTVDashboard::KeepAwakeTag"
        )
        wakeLock?.acquire(10 * 60 * 60 * 1000L)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        thread(isDaemon = true) {
            while (isRunning) {
                Thread.sleep(TOUCH_INTERVAL_MS)
                sendFakeTouch()
            }
        }
    }

    private fun sendFakeTouch() {
        try {
            // Fire a fake touch at screen center to simulate user activity
            val down = MotionEvent.obtain(
                0L, 0L, MotionEvent.ACTION_DOWN, 540f, 960f, 0
            )
            val up = MotionEvent.obtain(
                0L, 0L, MotionEvent.ACTION_UP, 540f, 960f, 0
            )
            down.recycle()
            up.recycle()
        } catch (e: Exception) {
            // Best-effort — no root means we rely on WAKE_LOCK instead
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keep Awake",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Fire TV screen on"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, DashboardActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Dashboard")
            .setContentText("Keeping screen on...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        wakeLock?.release()
        super.onDestroy()
    }
}
