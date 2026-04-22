package com.hermes.firetv

import android.app.Application
import android.util.Log
import org.acra.annotation.AcraCore

/**
 * ACRA crash reporting — application class.
 *
 * ACRA is only active in release builds (isDebuggable=false in buildType).
 *
 * Spooling: reports are queued to disk if the network is unavailable and sent
 * automatically on the next successful app launch.
 */
@AcraCore
class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }
}
