package com.hermes.firetv

import android.app.Application
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class with file-based crash logging.
 *
 * On an uncaught exception:
 *   1. Writes crash-<timestamp>.log to filesDir as JSON
 *   2. Delegates to the system default handler (app still crashes)
 *
 * On next launch:
 *   1. Scans filesDir for crash-*.log files
 *   2. POSTs each to /api/telemetry/crash
 *   3. Deletes the file on success
 *
 * POST is fire-and-forget on a background thread — network failures are silently
 * swallowed; the crash log persists for the next attempt.
 */

class FireTVApplication : Application() {

    companion object {
        private const val TAG = "FireTVApp"
        private const val CRASH_PREFIX = "crash-"
        private const val CRASH_SUFFIX = ".log"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FireTVApplication created — v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")

        // Install crash handler first — must run before anything else throws
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }

        // Attempt to flush any pending crash logs from previous runs
        flushPendingCrashLogs()
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US).format(Date())
        val fileName = "$CRASH_PREFIX${timestamp}$CRASH_SUFFIX"
        val file = File(filesDir, fileName)

        try {
            val info = JSONObject().apply {
                put("type", "kotlin_uncaught")
                put("thread_name", thread.name)
                put("thread_id", thread.id)
                put("message", throwable.message ?: "no message")
                put("stack_trace", Log.getStackTraceString(throwable))
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_version_code", BuildConfig.VERSION_CODE)
                put("build_type", BuildConfig.BUILD_TYPE)
                put("os_version", android.os.Build.VERSION.SDK_INT)
                put("device_model", android.os.Build.MODEL)
                put("crashed_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date()))
            }
            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                    writer.write(info.toString(2))
                }
            }
            Log.e(TAG, "Crash written to ${file.absolutePath}", throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }

        // Delegate to previous handler (system default — app still crashes)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previous?.uncaughtException(thread, throwable)
    }

    private fun flushPendingCrashLogs() {
        Thread {
            try {
                val files = filesDir.listFiles { _, name ->
                    name.startsWith(CRASH_PREFIX) && name.endsWith(CRASH_SUFFIX)
                } ?: return@Thread

                if (files.isEmpty()) return@Thread
                Log.d(TAG, "Flushing ${files.size} pending crash log(s)")

                for (file in files) {
                    flushCrashLog(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "flushPendingCrashLogs failed", e)
            }
        }.start()
    }

    private fun flushCrashLog(file: File) {
        try {
            val payload = file.readText()
            val url = URL("${BuildConfig.DASHBOARD_URL}/api/telemetry/crash")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-App-Auth", BuildConfig.AUTH_TOKEN)
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                Log.d(TAG, "Crash log POST success — deleting ${file.name}")
                file.delete()
            } else {
                Log.w(TAG, "Crash log POST failed: HTTP $responseCode")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Crash log POST failed", e)
        }
    }
}
