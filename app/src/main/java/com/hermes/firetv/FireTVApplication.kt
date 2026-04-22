package com.hermes.firetv

import android.app.Application
import android.util.Log
import ch.acra.BuildConfig
import ch.acra.annotation.AcraCore
import ch.acra.annotation.AcraHttpSender
import ch.acra.config.CoreConfigurationBuilder
import ch.acra.config.HttpConfigurationBuilder
import ch.acra.sender.HttpSender

/**
 * ACRA crash reporting — application class.
 *
 * Reports are JSON-POSTed to a crash relay on the VPS.
 * The relay forwards to Telegram (BOT_TOKEN stored server-side, never in APK).
 * Reports are also spooled to disk if the network is unavailable and
 * sent on the next successful app launch.
 *
 * ACRA is only enabled in release builds (see build.gradle.kts).
 */
@AcraCore(
    reportFormat = ch.acra.ReportFormat.JSON
)
@AcraHttpSender(
    uri = "https://dashboard.cashlabnyc.com/api/crash",
    httpMethod = HttpSender.Method.POST,
    enabled = true
)
class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }
}
