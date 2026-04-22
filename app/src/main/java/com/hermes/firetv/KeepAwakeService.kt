package com.hermes.firetv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

// ═════════════════════════════════════════════════════════════════════════════
// KeepAwakeService — foreground Service preventing Fire TV CPU from sleeping
//
// Runs as a foreground service with:
//   • PARTIAL_WAKE_LOCK — keeps CPU alive so WebView JS watchdog keeps running
//   • Ongoing notification — prevents the service from being killed by the system
//
// IMPORTANT: This service keeps the CPU alive but does NOT override the
// Fire TV screensaver / ambient mode. The screensaver must be disabled
// manually by the user:
//
//   Settings → Display & Sounds → Screen Saver → Never
//
// Without this setting change the screen WILL go black after the TV's
// configured idle period regardless of this service and the Activity's
// FLAG_KEEP_SCREEN_ON.
//
// The app's first-run dialog (DashboardActivity) prompts the user to
// change this setting.
//
// Recovery: If the service is killed under memory pressure, START_STICKY
// causes the system to restart it. The crash logger captures restarts.
// ═════════════════════════════════════════════════════════════════════════════

class KeepAwakeService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var crashLogger: CrashLogger? = null

    companion object {
        private const val TAG = "HermesKeepAwake"
        private const val CHANNEL_ID = "firetv_keep_awake_channel"
        private const val NOTIFICATION_ID = 1001
        // Watchdog interval: restart the wake lock before it times out.
        // The timeout is 12h; we ping at 1h to be safe.
        private const val WAKELOCK_PING_INTERVAL_MS = 60 * 60 * 1000L  // 1 hour
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
        crashLogger?.log(TAG, "KeepAwakeService onCreate")
        Log.d(TAG, "KeepAwakeService onCreate")

        acquireWakeLock()
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        crashLogger?.log(TAG, "KeepAwakeService started — foreground notification active")
        Log.d(TAG, "Wake lock acquired — foreground service running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: system will restart this service if it is killed.
        // This is critical for a kiosk app — the service must survive.
        crashLogger?.log(TAG, "onStartCommand START_STICKY")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        crashLogger?.log(TAG, "KeepAwakeService onDestroy — releasing wake lock")
        Log.d(TAG, "KeepAwakeService onDestroy")
        releaseWakeLock()
        super.onDestroy()
    }

    // ── Wake lock ─────────────────────────────────────────────────────────────

    /**
     * Acquires a PARTIAL_WAKE_LOCK.
     *
     * Why PARTIAL and not SCREEN_BRIGHT:
     *   - PARTIAL_WAKE_LOCK keeps the CPU alive so the WebView's JS watchdog
     *     timer and network timers continue running.
     *   - SCREEN_BRIGHT_WAKE_LOCK throws SecurityException on Android 12+
     *     (Fire OS 7+) when held by a foreground service that is not the
     *     topmost app. PARTIAL_WAKE_LOCK avoids this restriction entirely.
     *   - The Activity's FLAG_KEEP_SCREEN_ON (DashboardActivity) handles
     *     keeping the physical display on — this is independent of the
     *     wake lock type used here.
     *
     * The 12-hour timeout is a safety net. If the service is somehow orphaned
     * without being destroyed, the wake lock will auto-release. In normal
     * operation, the service lives for the lifetime of the app.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FireTVDashboard::ScreenAwake"
        )

        try {
            //noinspectionWakelockTimeout
            wakeLock?.acquire(12 * 60 * 60 * 1000L)  // 12 hour timeout
            crashLogger?.log(TAG, "PARTIAL_WAKE_LOCK acquired (12h timeout)")
            Log.d(TAG, "PARTIAL_WAKE_LOCK acquired")
        } catch (e: SecurityException) {
            crashLogger?.log(TAG, "Failed to acquire wake lock — permission missing: ${e.message}", e)
            Log.e(TAG, "Failed to acquire wake lock — permission missing: ${e.message}", e)
        } catch (e: Exception) {
            crashLogger?.log(TAG, "Failed to acquire wake lock — unexpected: ${e.message}", e)
            Log.e(TAG, "Failed to acquire wake lock — unexpected: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                crashLogger?.log(TAG, "Wake lock released")
                Log.d(TAG, "Wake lock released")
            } else {
                Log.d(TAG, "Wake lock was not held — no-op")
            }
        } catch (e: Exception) {
            crashLogger?.log(TAG, "Exception releasing wake lock: ${e.message}", e)
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
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
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
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
