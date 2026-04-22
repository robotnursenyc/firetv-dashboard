package com.hermes.firetv

import android.app.Application
import android.util.Log

/**
 * Application class — minimal, no crash reporting library.
 *
 * ACRA was removed: ch.acra:acra-telegram:5.11.3 does not exist in Maven Central.
 * Future crash-reporting can be re-added using a verified artifact.
 */

class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }
}
