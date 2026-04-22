package com.hermes.firetv

import android.app.Application
import android.util.Log

/**
 * ACRA crash reporting — application class.
 *
 * ACRA is only active in release builds (isDebuggable=false in buildType).
 *
 * Configuration is in app/src/main/assets/acra.properties.
 * Telegram BOT_TOKEN and CHAT_ID are injected by CI into acra.properties
 * at build time — they never appear in source code.
 *
 * Spooling: reports are queued to disk if the network is unavailable and
 * sent automatically on the next successful app launch.
 */
@org.acra.annotation.AcraCore
class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }
}
