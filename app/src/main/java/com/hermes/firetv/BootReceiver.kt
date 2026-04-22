package com.hermes.firetv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Launches DashboardActivity when the device finishes booting.
 *
 * On Android 10+ / Fire OS 7+ a BroadcastReceiver cannot reliably start an
 * Activity directly — background-activity-start is blocked by the framework.
 * Instead we start the foreground KeepAwakeService with an extra asking it to
 * bring the Activity up itself during its foregrounding window, which IS
 * permitted.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HermesBoot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.d(TAG, "BOOT_COMPLETED — starting KeepAwakeService with launch flag")
        val svc = Intent(context, KeepAwakeService::class.java).apply {
            putExtra(KeepAwakeService.EXTRA_LAUNCH_ACTIVITY, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc)
        } else {
            context.startService(svc)
        }
    }
}
