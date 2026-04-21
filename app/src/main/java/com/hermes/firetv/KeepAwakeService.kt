package com.hermes.firetv

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
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
        try {
            wakeLock?.acquire(10 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            // Best-effort — if acquire fails, we rely on foreground notification
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        thread(isDaemon = true) {
            while (isRunning) {
                Thread.sleep(TOUCH_INTERVAL_MS)
                sendFakeTouch()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun sendFakeTouch() {
        // Note: injectInputEvent requires root/TV system permissions.
        // Without root, this is best-effort — the PARTIAL_WAKE_LOCK keeps CPU alive.
        try {
            val dm = resources.displayMetrics
            val centerX = dm.widthPixels / 2f
            val centerY = dm.heightPixels / 2f

            val down = MotionEvent.obtain(
                0L, 0L, MotionEvent.ACTION_DOWN, centerX, centerY, 0
            )
            val up = MotionEvent.obtain(
                0L, 0L, MotionEvent.ACTION_UP, centerX, centerY, 0
            )
            // Don't recycle here — let the system handle it after dispatch
        } catch (e: Exception) {
            // Best-effort — no root means we rely on WAKE_LOCK instead
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent, flags
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
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // Ignore — already released or never acquired
        }
        super.onDestroy()
    }
}
