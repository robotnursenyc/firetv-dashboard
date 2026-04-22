package com.hermes.firetv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ═════════════════════════════════════════════════════════════════════════════
// CrashLogger — lightweight append-only activity/error logger for headless apps
//
// No external dependency. No network. Writes to app-internal storage so it
// survives process death and is readable via ADB:
//
//   adb shell run-as com.hermes.firetv cat files/crashlog.txt
//
// Log is a simple text format — easy to parse, grep, or send to a server
// in a future version (e.g. via a background WorkManager task).
//
// Max log size: 1 MB (oldest entries truncated when exceeded).
// ═════════════════════════════════════════════════════════════════════════════

class CrashLogger(private val ctx: Context) {

    private val logFile: File by lazy {
        File(ctx.filesDir, LOG_FILENAME)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val lock = Any()

    companion object {
        private const val LOG_FILENAME = "crashlog.txt"
        private const val MAX_LOG_BYTES = 1 * 1024 * 1024  // 1 MB
    }

    /**
     * Log a message with timestamp and tag.
     * Thread-safe. Non-blocking (write is fast).
     */
    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val entry = buildString {
            append("[$timestamp] $tag: $message")
            if (throwable != null) {
                append("\n  EXCEPTION: ${throwable.javaClass.simpleName}: ${throwable.message}")
                val cause = throwable.cause
                if (cause != null) {
                    append("\n  CAUSED BY: ${cause.javaClass.simpleName}: ${cause.message}")
                }
            }
            append("\n")
        }
        write(entry)
    }

    /**
     * Log a significant app event (start, resume, crash recovery, etc.).
     * Same as log() but semantically marked for filtering.
     */
    fun logEvent(event: String, detail: String = "") {
        log("EVENT", "$event${if (detail.isNotEmpty()) " — $detail" else ""}")
    }

    /**
     * Read the entire log. Used by debug tools or crash-report upload.
     */
    fun getLog(): String {
        synchronized(lock) {
            return try {
                if (logFile.exists()) logFile.readText() else ""
            } catch (e: Exception) {
                "Error reading crashlog: ${e.message}"
            }
        }
    }

    /**
     * Get the log file path for ADB pull.
     */
    fun getLogPath(): String = logFile.absolutePath

    /**
     * Clear the log. Used by debug tools after extracting a report.
     */
    fun clear() {
        val deleted: Boolean
        synchronized(lock) {
            deleted = logFile.delete()
        }
        if (!deleted) {
            Log.w("CrashLogger", "Could not delete log file (may not exist)")
        }
    }

    private fun write(entry: String) {
        synchronized(lock) {
            try {
                if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                    trimLog()
                }
                FileOutputStream(logFile, true).use { fos ->
                    fos.write(entry.toByteArray(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Log.e("CrashLogger", "Error writing to crashlog: ${e.message}")
            }
        }
    }

    /**
     * Truncate oldest 50% of log when max size is exceeded.
     */
    private fun trimLog() {
        try {
            val content = logFile.readBytes()
            val midpoint = content.size / 2
            // Find the first newline after the midpoint to avoid cutting mid-entry
            val contentStr = String(content, Charsets.UTF_8)
            val afterMidpoint = contentStr.substring(midpoint)
            val nextNewline = afterMidpoint.indexOf('\n')
            val cutIdx = if (nextNewline >= 0) midpoint + nextNewline + 1 else content.size
            val newContent = content.copyOfRange(cutIdx, content.size)
            logFile.writeBytes(newContent)
            Log.d("CrashLogger", "Log trimmed from ${content.size} to ${newContent.size} bytes")
        } catch (e: Exception) {
            Log.e("CrashLogger", "Error trimming log: ${e.message}", e)
        }
    }
}
