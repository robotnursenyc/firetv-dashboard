package com.hermes.firetv

import android.app.Application
import android.util.Log
import org.acra.annotation.AcraCore
import org.acra.annotation.AcraTelegram

/**
 * ACRA crash reporting — application class.
 *
 * ACRA is only active in release builds (isDebuggable=false in buildType).
 *
 * Telegram credentials are injected by CI into app/src/main/assets/acra.properties.
 * Property names must match ACRA 5.x expected keys exactly (acra.telegram.botToken
 * and acra.telegram.chatId — not camelCase variants).
 *
 * Spooling: reports are queued to disk if the network is unavailable and sent
 * automatically on the next successful app launch.
 */
@AcraCore
@AcraTelegram
class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }
}
