package com.hermes.firetv

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
import android.util.Log
import androidx.core.app.NotificationCompat

// ═════════════════════════════════════════════════════════════════════════════
// KeepAwakeService — foreground Service preventing Fire TV screen sleep
//
// Runs as a foreground service with:
//   • SCREEN_BRIGHT_WAKE_LOCK  — keeps the screen on (not just the CPU)
//   • Ongoing notification     — prevents the service from being killed
//   • No daemon thread         — the old fake-touch approach was non-functional
//
// IMPORTANT: This service prevents the CPU from sleeping (WAKE_LOCK).
// It does NOT override the Fire TV screensaver / ambient mode.
// The screensaver must be disabled manually by the user:
//
//   Settings → Display & Sounds → Screen Saver → Never
//
// Without this setting change the screen WILL go black after the TV's
// configured idle period regardless of this service.
//
// Why the old fake-touch approach was removed:
//   MotionEvent.obtain()...recycle() constructs events but never dispatches
//   them to the system. Without root + INPUT_INJECT_Suddendeath permission,
//   fake touch injection is not possible from a normal app. The daemon thread
//   was consuming battery and achieving nothing.
// ═════════════════════════════════════════════════════════════════════════════

class KeepAwakeService : Service() {

    // Reference to the wake lock. SCREEN_BRIGHT_WAKE_LOCK keeps the screen on.
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "HermesKeepAwake"
        private const val CHANNEL_ID = "firetv_keep_awake_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "KeepAwakeService onCreate")

        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        Log.d(TAG, "Wake lock acquired — screen will stay on while this service is alive")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: tells the system to restart this service if it kills it.
        // This is the correct behaviour for a kiosk app that must stay alive.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "KeepAwakeService onDestroy — releasing wake lock")
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    /**
     * Acquires a SCREEN_BRIGHT_WAKE_LOCK.
     *
     * This is the correct wake lock type for keeping the screen on.
     *   • PARTIAL_WAKE_LOCK  — keeps CPU on, screen can turn off  ← WRONG
     *   • SCREEN_BRIGHT_WAKE_LOCK — keeps screen on at max brightness
     *   • SCREEN_DIM_WAKE_LOCK   — keeps screen on but dim
     *
     * The AndroidManifest already declares android.permission.WAKE_LOCK.
     *
     * On Fire OS 7 (API 31+) background foreground services have stricter
     * execution-time limits. The service should survive because it is
     * a foreground service with a persistent notification. If the system
     * kills it under extreme memory pressure, START_STICKY will restart it.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FireTVDashboard::ScreenAwake"
        )

        try {
            // Timeout: 12 hours. The service will either be stopped by
            // onDestroy or will be re-acquired when the service restarts.
            // Using a timeout is defensive — it prevents an unreleased
            // wake lock if the service is somehow orphaned.
            @Suppress("WARNINGS")
            wakeLock?.acquire(12 * 60 * 60 * 1000L)
            Log.d(TAG, "SCREEN_BRIGHT_WAKE_LOCK acquired")
        } catch (e: SecurityException) {
            // Thrown if WAKE_LOCK permission is missing from the manifest
            // (it is declared, but may be stripped by some build configs).
            Log.e(TAG, "Failed to acquire wake lock — permission missing: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock — unexpected: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            } else {
                Log.d(TAG, "Wake lock was not held — no-op")
            }
        } catch (e: Exception) {
            // IllegalStateException if the wake lock was already released.
            Log.w(TAG, "Exception releasing wake lock: ${e.message}", e)
        } finally {
            wakeLock = null
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keep Awake",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Prevents Fire TV screen from turning off"
                setShowBadge(false)
                // Do not show notification in notification drawer — it's just
                // a foreground service marker; we don't want staff seeing it.
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // When the notification is tapped, open the dashboard Activity.
        val contentIntent = Intent(this, DashboardActivity::class.java)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPi = PendingIntent.getActivity(this, 0, contentIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Keeping screen on…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setOngoing(true)           // Cannot be swiped away
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
