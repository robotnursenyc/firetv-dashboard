package com.hermes.firetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Launches DashboardActivity when the device finishes booting.
 *
 * Registered in AndroidManifest.xml with:
 *   <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
 *
 * This ensures the dashboard automatically resumes after:
 *   - device power loss recovery
 *   - firmware update
 *   - manual reboot
 *
 * Without this the app only starts if a user manually launches it from the launcher.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HermesBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "BOOT_COMPLETED received — launching DashboardActivity")
            val launchIntent = Intent(context, DashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
