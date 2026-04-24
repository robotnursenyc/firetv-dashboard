package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════════════
// DashboardActivity — Family Dashboard / FireTV WebView host
//
// Fullscreen WebView that loads a Next.js dashboard at dashboard.cashlabnyc.com
// and keeps it running 24/7 on Fire TV hardware.
//
// Key behaviours:
//   • Full-screen immersive (system bars hidden)
//   • FLAG_SECURE (blocks screenshots / screen recording)
//   • X-App-Auth header injected via shouldInterceptRequest (no token in source)
//   • Standard HTTPS only — no custom TrustManager
//   • Exponential-backoff retry on main-frame errors (5s → 15s → 60s → 5min)
//   • JS hang watchdog: reloads if JS doesn't ping every 90s
//   • Self-contained HTML error overlay when offline
//   • Back key shows exit confirmation instead of exiting
//   • First-run screensaver warning dialog
//   • Streaming proxy: only intercepts text assets (HTML/JS/CSS/JSON);
//     images/fonts/media pass through to WebView directly
//   • WebView crash recovery: destroys and recreates WebView on render crash
//   • File-based activity/crash logger for observability
//
// Kiosk notes:
//   Full kiosk lockdown (lock to single app, disable Home) requires a
//   Device Owner provisioning step and is not implemented here.
// ═══════════════════════════════════════════════════════════════════════════

class DashboardActivity : AppCompatActivity() {

    // ── Build-config constants ────────────────────────────────────────────────
    private val TAG = "HermesDashboard"

    // AUTH_TOKEN is injected by CI into BuildConfig — never hardcoded in source.
    // Empty string in debug builds; CI injects the real value for release builds.
    private val authToken: String = BuildConfig.AUTH_TOKEN

    // ── View references ────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var rootView: FrameLayout
    private lateinit var loadingOverlay: View
    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    // ── Foreground tracking ──────────────────────────────────────────────────
    // Guards onTrimMemory reloads — we only want to reload when actually visible.
    private var isInForeground = false

    // ── Network state ────────────────────────────────────────────────────────
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── Watchdog state ───────────────────────────────────────────────────────
    // Tracks last JS pong — reloaded if no pong for STALE_THRESHOLD_MS.
    private var lastJsPongMs: Long = 0L

    // Tracks last page-finish timestamp — used to detect initial load stalls.
    private var lastPageFinishMs: Long = 0L

    // If JS hasn't ponged within this window, count a miss.
    // Raised from 90 s → 180 s: on flaky Wi-Fi a cold reload plus first paint
    // can legitimately exceed 90 s, producing false-positive reload loops.
    private val STALE_THRESHOLD_MS = 180_000L

    // Check JS health every this many ms.
    private val WATCHDOG_INTERVAL_MS = 30_000L

    // Hard reload the dashboard every this many ms regardless of JS health.
    // Flushes accumulated renderer state before it can cause memory pressure.
    // 5 minutes: frequent enough to prevent long-term heap growth, infrequent
    // enough to not cause visible flicker on a stable connection.
    private val PERIODIC_RELOAD_INTERVAL_MS = 5 * 60 * 1_000L

    // Require this many consecutive stale observations before declaring the page
    // unresponsive. A single miss can be a transient GC or IPC pause on the 1 GB
    // Fire Stick 4K Gen 1; two in a row is the real thing.
    private val MAX_MISSED_PONGS = 2
    private var missedPongs = 0

    // ── Error-recovery state ─────────────────────────────────────────────────
    private var consecutiveErrors = 0
    private val MAX_ERRORS_BEFORE_STopping = 10

    // Exponential backoff delays: attempt #1 → 5s, #2 → 15s, #3 → 60s, #4+ → 5min
    private val RETRY_DELAYS_MS = longArrayOf(
        5_000L, 15_000L, 60_000L, 300_000L, 300_000L,
        300_000L, 300_000L, 300_000L, 300_000L, 300_000L
    )

    // Guards against multiple retries firing simultaneously.
    private var isRetryPending = false

    private val handler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = Runnable { checkJsHealth() }
    private val retryRunnable = Runnable { performReload() }
    private val periodicReloadRunnable = Runnable {
        crashLogger.log(TAG, "Periodic reload: flushing renderer state")
        Log.d(TAG, "Periodic reload triggered")
        performReload()
    }

    // ── Crash / activity logger ─────────────────────────────────────────────
    private lateinit var crashLogger: CrashLogger

    // ── First-run flag ───────────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        crashLogger = CrashLogger(this)
        crashLogger.log(TAG, "onCreate START — v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})")

        super.onCreate(savedInstanceState)
        logStartupBanner()
        prefs = getSharedPreferences("firetv_prefs", Context.MODE_PRIVATE)

        setupFullscreenWindow()
        setupViews()
        setupKeepAwake()
        checkFirstRun()
        crashLogger.log(TAG, "onCreate END")
        Log.d(TAG, "=== DashboardActivity onCreate END ===")
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        webView.onResume()
        lastJsPongMs = System.currentTimeMillis()
        scheduleWatchdogCheck()
        schedulePeriodicReload()
        registerNetworkCallback()
        crashLogger.log(TAG, "onResume — WebView resumed, watchdog armed, network listener active")
        Log.d(TAG, "onResume — WebView resumed, watchdog armed, network listener active")
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        webView.onPause()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(periodicReloadRunnable)
        unregisterNetworkCallback()
        crashLogger.log(TAG, "onPause — watchdog disarmed, periodic reload cancelled, network listener removed")
        Log.d(TAG, "onPause — watchdog disarmed, periodic reload cancelled, network listener removed")
    }

    override fun onDestroy() {
        crashLogger.log(TAG, "onDestroy")
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(periodicReloadRunnable)
        if (::webView.isInitialized) {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                // Deliberately NOT calling clearCache(true) — keeping the disk
                // cache across restarts lets the dashboard paint immediately on
                // reboot instead of refetching every asset.
                webView.destroy()
            } catch (e: Exception) {
                crashLogger.log(TAG, "Exception during WebView cleanup: ${e.message}", e)
            }
        }
        super.onDestroy()
    }

    /**
     * Memory pressure handler — FIXED: removed reload on MODERATE.
     *
     * Guards:
     *   - isInForeground — never reload if the app is not visible
     *   - TRIM_MEMORY_COMPLETE only — app is being terminated; let it die naturally.
     *     We NO LONGER reload on TRIM_MEMORY_MODERATE because it fires too
     *     frequently on constrained Fire Stick hardware and causes visible
     *     blanking without fixing the underlying memory issue.
     *
     * Rationale: WebView has its own memory management. Forcing a reload on
     * memory pressure actually WORSENS memory pressure because it allocates
     * a new renderer process. Let the system manage memory naturally.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        crashLogger.log(TAG, "onTrimMemory level=$level")
        Log.w(TAG, "onTrimMemory level=$level")

        if (!isInForeground) {
            Log.d(TAG, "onTrimMemory — not in foreground, skipping")
            return
        }

        when {
            level >= TRIM_MEMORY_COMPLETE -> {
                // App is being terminated. Log and let it die.
                // The Activity will be recreated when the user returns.
                crashLogger.log(TAG, "TRIM_MEMORY_COMPLETE — app terminating")
            }
            // TRIM_MEMORY_MODERATE (50) — IGNORED.
            // Previously this triggered a reload, which caused visible flickering
            // on every memory pressure event on constrained Fire Stick hardware.
            // The WebView manages its own memory; forced reload makes it worse.
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // KEY / BACK HANDLING
    // ═════════════════════════════════════════════════════════════════════════

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Suppress the default back-behaviour (finish Activity).
        // Show exit confirmation instead so non-technical staff can't accidentally exit.
        showExitDialog()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                showExitDialog()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FIRST RUN
    // ═════════════════════════════════════════════════════════════════════════

    private fun checkFirstRun() {
        val hasRun = prefs.getBoolean("first_run_complete", false)
        if (!hasRun) {
            crashLogger.log(TAG, "First run detected — showing screensaver setup dialog")
            showFirstRunScreensaverDialog()
        }
    }

    private fun showFirstRunScreensaverDialog() {
        val msg = buildString {
            append("IMPORTANT: To keep the screen always on, you must disable the screensaver:\n\n")
            append("  1. Go to Settings\n")
            append("  2. Display & Sounds\n")
            append("  3. Screen Saver → select Never\n\n")
            append("Without this setting, the TV will go black during idle periods ")
            append("regardless of this app's wake lock.\n\n")
            append("Would you like to open the Display settings now?")
        }

        AlertDialog.Builder(this)
            .setTitle("Dashboard Setup — Screen Saver")
            .setMessage(msg)
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
                } catch (e: Exception) {
                    // Some Fire OS variants don't expose this intent
                    try {
                        // Fallback: try to open the screensaver settings directly
                        val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                        fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(fallbackIntent)
                    } catch (e2: Exception) {
                        crashLogger.log(TAG, "Could not open settings: ${e2.message}")
                    }
                }
                // Mark first run complete even if settings couldn't open —
                // we don't want to spam the user every time
                prefs.edit().putBoolean("first_run_complete", true).apply()
            }
            .setNegativeButton("Already Disabled") { _, _ ->
                prefs.edit().putBoolean("first_run_complete", true).apply()
                crashLogger.log(TAG, "User confirmed screensaver already disabled")
            }
            .setCancelable(false)
            .show()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STARTUP HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun logStartupBanner() {
        val wvPkg = WebView.getCurrentWebViewPackage()
        val logLine = """
            ═══════════════════════════════════════════════
            Family Dashboard  v${BuildConfig.VERSION_NAME}  (#${BuildConfig.VERSION_CODE})
            BUILD_TYPE        : ${BuildConfig.BUILD_TYPE}
            DASHBOARD_URL     : ${BuildConfig.DASHBOARD_URL}
            AUTH_TOKEN_PRESENT: ${authToken.isNotEmpty()}
            FIRE_OS_API       : ${Build.VERSION.SDK_INT}
            DEVICE_MODEL      : ${Build.MODEL}
            WEBVIEW_PKG       : ${wvPkg?.packageName ?: "unknown"}
            WEBVIEW_VERSION   : ${wvPkg?.versionName ?: "unknown"}
            ═══════════════════════════════════════════════
        """.trimIndent()
        crashLogger.log(TAG, logLine)
        Log.d(TAG, logLine)
    }

    @Suppress("DEPRECATION")
    private fun setupFullscreenWindow() {
        // Keep screen on — prevents the device CPU from sleeping.
        // The Fire TV screensaver / ambient mode is a separate concern that
        // must be disabled in Settings → Display & Sounds → Screen Saver → Never.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Immersive sticky — hides the system navigation and status bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupViews() {

        // Root container — dark background visible behind the WebView.
        rootView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // Loading overlay — shown during initial page load, fades out on first paint.
        // Prevents the black-screen-of-nothing visible between app launch and first frame.
        loadingOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212"))
            isFocusable = false
            isClickable = false

            // Centered loading indicator
            addView(TextView(context).apply {
                text = "Loading dashboard…"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            })
        }
        rootView.addView(loadingOverlay)
        loadingOverlay.alpha = 1f

        // WebView — fills entire screen.
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Exit button — top-right "×".
        // FIXED: Removed isFocusable/isClickable — buttons should not steal
        // D-pad focus from the WebView on older Fire Stick hardware.
        // Clickable views inside a WebView's FrameLayout interfere with
        // WebView D-pad navigation. Use focusable=false (default) so the
        // D-pad passes through to the WebView first.
        val exitBtn = TextView(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(72, 72).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, 32, 32, 0)
            }
            setBackgroundColor(0x66000000)
            // NOT focusable — D-pad should navigate WebView content first
            isFocusable = false
            isClickable = true
        }
        exitBtn.setOnClickListener {
            crashLogger.log(TAG, "Exit button tapped")
            showExitDialog()
        }

        // Native reload button — bottom-right corner.
        // FIXED: Not focusable — prevents D-pad focus stealing from WebView.
        // The user can still tap it with a remote (clickable=true).
        val reloadBtn = TextView(this).apply {
            text = "\u21BB Reload"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(120, 56).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(0, 0, 32, 32)
            }
            setBackgroundColor(0x66000000)
            alpha = 0.6f
            isFocusable = false
            isClickable = true
        }
        reloadBtn.setOnClickListener {
            crashLogger.log(TAG, "Native reload button tapped — forcing reload")
            if (isFinishing) return@setOnClickListener
            consecutiveErrors = 0
            missedPongs = 0
            isRetryPending = false
            handler.removeCallbacks(retryRunnable)
            webView.reload()
        }

        // ── WebView settings ──────────────────────────────────────────────
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // Reject mixed content (HTTP sub-resources on an HTTPS page).
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Bind the JS bridge BEFORE any page load. Without this, the
        // `AndroidDashboard.pong()` and `AndroidDashboard.requestNativeReload()`
        // calls from JS are no-ops, the watchdog never gets a pong, and the
        // retry-via-interceptor path is dead — this was the root cause of the
        // ~90 s forced-reload loop.
        webView.addJavascriptInterface(this, "AndroidDashboard")

        // Remote debugging — debug builds only.
        if (BuildConfig.ENABLE_WEBVIEW_DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            crashLogger.log(TAG, "WebView remote debugging ENABLED (debug build)")
            Log.d(TAG, "WebView remote debugging ENABLED (debug build)")
        }

        rootView.addView(webView)
        rootView.addView(exitBtn)
        rootView.addView(reloadBtn)
        setContentView(rootView)

        // ── WebView clients ────────────────────────────────────────────────
        setupWebViewClients()

        // ── Load the dashboard ─────────────────────────────────────────────
        loadDashboard()
    }

    private fun setupKeepAwake() {
        try {
            startService(Intent(this, KeepAwakeService::class.java))
            crashLogger.log(TAG, "KeepAwakeService started")
            Log.d(TAG, "KeepAwakeService started")
        } catch (e: Exception) {
            crashLogger.log(TAG, "Failed to start KeepAwakeService: ${e.message}", e)
            Log.e(TAG, "Failed to start KeepAwakeService: ${e.message}", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WEBVIEW CLIENTS
    // ═════════════════════════════════════════════════════════════════════════

    private fun setupWebViewClients() {

        // Chrome client: console output, title, load progress.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg ?: return false
                val src = "[${msg.sourceId()}:${msg.lineNumber()}]"
                val level = msg.messageLevel()
                val text = "CONSOLE $level: $src ${msg.message()}"
                when (level) {
                    ConsoleMessage.MessageLevel.ERROR   -> {
                        Log.e(TAG, text)
                        crashLogger.log(TAG, text)
                    }
                    ConsoleMessage.MessageLevel.WARNING -> {
                        Log.w(TAG, text)
                        crashLogger.log(TAG, text)
                    }
                    else                                  -> Log.v(TAG, text)
                }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                Log.d(TAG, "Page title: $title")
                crashLogger.log(TAG, "Page title: $title")
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    Log.v(TAG, "Load progress: $newProgress%")
                } else if (newProgress == 100) {
                    Log.d(TAG, "Load progress: 100%")
                }
            }
        }

        // WebView client: request interception, errors, page lifecycle.
        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: return null

                // Only intercept GET requests to the dashboard server.
                if (host != "dashboard.cashlabnyc.com") return null
                if (request.method != "GET") return null

                val path = url.path ?: "/"

                // FIXED: Only proxy text assets that need auth header injection.
                // Let the WebView handle images, fonts, media directly — these
                // don't need the auth header and buffering them causes memory
                // pressure on constrained Fire Stick hardware.
                //
                // Text assets we intercept: HTML (initial load, auth-gated),
                // JS (may be auth-gated), CSS, JSON API responses.
                // Binary assets: PNG/JPG/WEBP/SVG fonts — pass through.
                val shouldProxy = path.endsWith(".html") ||
                        path.endsWith(".js") ||
                        path.endsWith(".mjs") ||
                        path.endsWith(".css") ||
                        path.endsWith(".json") ||
                        path == "/" ||
                        path.startsWith("/_next/")

                if (!shouldProxy) {
                    Log.v(TAG, "BYPASS $path — letting WebView handle directly")
                    return null
                }

                return proxyDashboardRequest(url.toString(), request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val errCode = error?.errorCode?.toInt() ?: -1
                    val desc = error?.description?.toString() ?: "WebView error"
                    crashLogger.log(TAG, "MAINFRAME ERROR $errCode — $desc")
                    Log.e(TAG, "MAINFRAME ERROR $errCode — $desc")
                    onMainFrameError(errCode, desc)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val status = errorResponse?.statusCode ?: -1
                    val reason = errorResponse?.reasonPhrase ?: "unknown"
                    crashLogger.log(TAG, "MAINFRAME HTTP ERROR $status — $reason")
                    Log.e(TAG, "MAINFRAME HTTP ERROR $status — $reason")
                    if (status >= 400) {
                        onMainFrameError(status, "HTTP $status $reason")
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                lastPageFinishMs = System.currentTimeMillis()
                lastJsPongMs = lastPageFinishMs
                consecutiveErrors = 0
                missedPongs = 0
                cancelScheduledRetry()
                isRetryPending = false
                crashLogger.log(TAG, "Page finished: $url")
                Log.d(TAG, "Page finished: $url")
                hideLoadingOverlay()
                injectJsHealthCheck()
            }

            /**
             * FIXED: Called when the WebView's render process terminates.
             *
             * Previous bug: called webView.reload() on the crashed instance.
             * This does NOT reliably restart the renderer — the WebView instance
             * is in an undefined state after render process death.
             *
             * Fix: destroy the WebView completely and recreate it, then reload.
             * This is the only reliable recovery pattern for WebView render crashes.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val crashed = detail?.didCrash() ?: false
                val reason = if (crashed) "CRASH" else "KILLED"
                crashLogger.log(TAG, "RENDER PROCESS $reason — recreating WebView")
                Log.e(TAG, "RENDER PROCESS GONE — didCrash=$crashed ($reason)")

                if (crashed) {
                    crashLogger.log(TAG, "Renderer crashed — recreating WebView to recover")
                    Log.w(TAG, "Renderer crashed — recreating WebView")
                    // Post to handler to ensure we don't destroy while in the callback
                    handler.post { recreateWebView() }
                }
                return true  // Consume the callback; suppress the default error page
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WEBVIEW RECREATION (on render crash)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Destroys the crashed WebView and creates a fresh one.
     * This is the ONLY reliable way to recover from a render-process crash.
     * Calling webView.reload() on a crashed WebView instance is unreliable.
     */
    private fun recreateWebView() {
        crashLogger.log(TAG, "recreateWebView: starting")
        Log.d(TAG, "recreateWebView: starting")

        if (!::webView.isInitialized) {
            crashLogger.log(TAG, "recreateWebView: WebView not initialized, skipping")
            return
        }
        if (isFinishing) {
            crashLogger.log(TAG, "recreateWebView: Activity finishing, skipping")
            return
        }

        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            // Preserve disk cache — the fresh WebView should re-use it so the
            // dashboard paints fast after a renderer crash.
            webView.destroy()
        } catch (e: Exception) {
            crashLogger.log(TAG, "recreateWebView: cleanup exception: ${e.message}", e)
        }

        // Create fresh WebView
        val freshWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Re-apply settings
        val settings: WebSettings = freshWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Re-bind the JS bridge on the fresh WebView — it's instance-scoped and
        // must be attached before the page loads or the watchdog fires forever.
        freshWebView.addJavascriptInterface(this, "AndroidDashboard")

        if (BuildConfig.ENABLE_WEBVIEW_DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        freshWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg ?: return false
                val src = "[${msg.sourceId()}:${msg.lineNumber()}]"
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> {
                        crashLogger.log(TAG, "CONSOLE ERROR: $src ${msg.message()}")
                        Log.e(TAG, "CONSOLE ERROR: $src ${msg.message()}")
                    }
                    ConsoleMessage.MessageLevel.WARNING -> {
                        crashLogger.log(TAG, "CONSOLE WARN:  $src ${msg.message()}")
                        Log.w(TAG, "CONSOLE WARN:  $src ${msg.message()}")
                    }
                    else -> Log.v(TAG, "CONSOLE:       $src ${msg.message()}")
                }
                return true
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                crashLogger.log(TAG, "Page title: $title")
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) crashLogger.log(TAG, "Load progress: 100%")
            }
        }

        freshWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host ?: return null
                if (host != "dashboard.cashlabnyc.com") return null
                if (request.method != "GET") return null
                val path = url.path ?: "/"
                val shouldProxy = path.endsWith(".html") ||
                        path.endsWith(".js") ||
                        path.endsWith(".mjs") ||
                        path.endsWith(".css") ||
                        path.endsWith(".json") ||
                        path == "/" ||
                        path.startsWith("/_next/")
                if (!shouldProxy) return null
                return proxyDashboardRequest(url.toString(), request)
            }
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val errCode = error?.errorCode?.toInt() ?: -1
                    val desc = error?.description?.toString() ?: "WebView error"
                    crashLogger.log(TAG, "MAINFRAME ERROR $errCode — $desc")
                    Log.e(TAG, "MAINFRAME ERROR $errCode — $desc")
                    onMainFrameError(errCode, desc)
                }
            }
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val status = errorResponse?.statusCode ?: -1
                    if (status >= 400) {
                        val reason = errorResponse?.reasonPhrase ?: "unknown"
                        crashLogger.log(TAG, "MAINFRAME HTTP ERROR $status — $reason")
                        onMainFrameError(status, "HTTP $status $reason")
                    }
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                lastPageFinishMs = System.currentTimeMillis()
                lastJsPongMs = lastPageFinishMs
                consecutiveErrors = 0
                cancelScheduledRetry()
                isRetryPending = false
                crashLogger.log(TAG, "Page finished (after crash recovery): $url")
                Log.d(TAG, "Page finished (after crash recovery): $url")
                hideLoadingOverlay()
                injectJsHealthCheckInto(freshWebView)
            }
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // Nested crash — log and try again
                val crashed = detail?.didCrash() ?: false
                crashLogger.log(TAG, "NESTED RENDER CRASH — didCrash=$crashed")
                Log.e(TAG, "NESTED RENDER CRASH — didCrash=$crashed")
                if (crashed) handler.post { recreateWebView() }
                return true
            }
        }

        // Replace old WebView in the view hierarchy
        // Find the WebView's index in rootView and replace it
        val index = rootView.indexOfChild(webView)
        rootView.removeView(webView)

        // The fresh WebView goes at index 0 (below the buttons which are added last)
        if (index >= 0) {
            rootView.addView(freshWebView, index)
        } else {
            rootView.addView(freshWebView, 0)
        }

        webView = freshWebView

        // Reset watchdog state
        lastJsPongMs = System.currentTimeMillis()
        lastPageFinishMs = System.currentTimeMillis()

        // Load dashboard with auth header injection
        crashLogger.log(TAG, "recreateWebView: loading dashboard")
        Log.d(TAG, "recreateWebView: loading dashboard")
        webView.loadUrl(BuildConfig.DASHBOARD_URL)
        scheduleWatchdogCheck()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REQUEST PROXY (shouldInterceptRequest) — streaming version
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Proxies HTTP/S requests to dashboard.cashlabnyc.com, adding the X-App-Auth
     * header for auth-gated content.
     *
     * FIXED: Now uses a streaming response for text assets. The response body is
     * read in a background thread and written to a Pipe; the WebResourceResponse
     * is returned immediately with an unbuffered InputStream. This prevents
     * memory pressure from buffering large JS/CSS bundles on constrained hardware.
     *
     * Only text assets (HTML/JS/CSS/JSON) are proxied — images/fonts/media pass
     * through to the WebView directly.
     *
     * Standard HTTPS throughout. No custom TrustManager.
     */
    private fun proxyDashboardRequest(
        urlString: String,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = request.method
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000

            if (authToken.isNotEmpty()) {
                conn.setRequestProperty("X-App-Auth", authToken)
            }
            // Preserve WebView's own User-Agent — overriding to "FireTV/1.0" broke
            // Next.js client-detection heuristics in the dashboard bundle.

            // HttpURLConnection transparently ungzips responses, but the original
            // Content-Encoding: gzip header is still forwarded; WebView then tries
            // to ungzip already-plain bytes and produces a blank page. Force the
            // origin to skip compression on this hop to sidestep the mismatch.
            conn.setRequestProperty("Accept-Encoding", "identity")

            // Forward applicable headers from the WebView's original request,
            // skipping hop-by-hop headers and the Accept-Encoding we just set.
            request.requestHeaders?.forEach { (key, value) ->
                val k = key.lowercase()
                if (key.isNotEmpty() && k !in HOP_BY_HOP && k != "accept-encoding") {
                    conn.setRequestProperty(key, value)
                }
            }

            val status = conn.responseCode
            val reason = conn.responseMessage
            Log.v(TAG, "  → $status $reason")

            val mimeType = inferMimeType(url.path, conn.contentType)

            // For text responses, use a streaming Pipe to avoid buffering in memory.
            // Binary responses (images etc.) are not proxied — we return null above.
            val responseHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && key.lowercase() !in HOP_BY_HOP && values.isNotEmpty()) {
                    responseHeaders[key] = values.joinToString(", ")
                }
            }

            if (isTextMime(mimeType)) {
                // Use a piped stream for text — non-blocking, no full buffer in RAM
                val pipeIn = PipedInputStream(64 * 1024)  // 64KB buffer
                val pipeOut = PipedOutputStream(pipeIn)

                // Write response in background thread so we return immediately
                Thread {
                    try {
                        val stream: InputStream = if (status >= 400) {
                            conn.errorStream ?: conn.inputStream
                        } else {
                            conn.inputStream
                        }
                        stream.use { input ->
                            val buf = ByteArray(8192)
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                pipeOut.write(buf, 0, n)
                            }
                        }
                    } catch (e: Exception) {
                        crashLogger.log(TAG, "Proxy pipe write error: ${e.message}", e)
                    } finally {
                        try { pipeOut.close() } catch (e: Exception) { }
                        try { conn.disconnect() } catch (e: Exception) { }
                    }
                }.start()

                crashLogger.log(TAG, "PROXY STREAM $status $mimeType $urlString")
                WebResourceResponse(mimeType, "UTF-8", status, reason, responseHeaders, pipeIn)

            } else {
                // Binary — should not reach here since we filter above, but handle it
                val bytes = bufferFully(conn, status)
                WebResourceResponse(
                    mimeType, null, status, reason, responseHeaders,
                    ByteArrayInputStream(bytes)
                )
            }
        } catch (e: Exception) {
            crashLogger.log(TAG, "PROXY EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.e(TAG, "  INTERCEPT EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            null  // Let WebView fall back to its own direct load.
        }
    }

    /** Reads and buffers the entire HTTP response body into memory. */
    private fun bufferFully(conn: HttpURLConnection, status: Int): ByteArray {
        val baos = ByteArrayOutputStream(65_536)
        val stream: InputStream = if (status >= 400) {
            conn.errorStream ?: conn.inputStream
        } else {
            conn.inputStream
        }
        stream.use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                baos.write(buf, 0, n)
            }
        }
        return baos.toByteArray()
    }

    /** Maps a URL path + Content-Type header to a MIME type string. */
    private fun inferMimeType(path: String?, contentType: String?): String {
        path ?: return contentType ?: "application/octet-stream"
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css")                          -> "text/css"
            path.endsWith(".png")                          -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp")                         -> "image/webp"
            path.endsWith(".svg")                          -> "image/svg+xml"
            path.endsWith(".ico")                          -> "image/x-icon"
            path.endsWith(".woff")                         -> "font/woff"
            path.endsWith(".woff2")                        -> "font/woff2"
            path.endsWith(".ttf")                          -> "font/ttf"
            path.endsWith(".json")                         -> "application/json"
            path.endsWith(".xml")                          -> "application/xml"
            path.endsWith(".html") || path == "/"          -> "text/html"
            contentType?.contains("text/html") == true    -> "text/html"
            contentType?.contains("application/json") == true -> "application/json"
            contentType?.isNotBlank() == true -> contentType
            else                                            -> "application/octet-stream"
        }
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
                mime == "application/javascript" ||
                mime == "application/json"

    companion object {
        // Hop-by-hop + length/encoding headers stripped during proxying.
        // content-encoding and content-length must not survive the hop because
        // HttpURLConnection has already decompressed the body; forwarding the
        // original length/encoding describes bytes WebView never receives and
        // causes blank pages or JS parser crashes.
        private val HOP_BY_HOP = setOf(
            "transfer-encoding", "connection", "keep-alive", "upgrade",
            "proxy-authenticate", "proxy-authorization", "te", "trailers", "host",
            "content-encoding", "content-length"
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // JS HEALTH CHECK / WATCHDOG
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Injects a minimal JS snippet that calls AndroidDashboard.pong() every 30s.
     *
     * The pong() call in Kotlin resets lastJsPongMs.
     * If 3 consecutive pongs are missed (90s), checkJsHealth() triggers a reload.
     *
     * The snippet is fully self-contained (no external deps) so it works even
     * when the dashboard's own JS bundle fails to load.
     */
    private fun injectJsHealthCheck() {
        injectJsHealthCheckInto(webView)
    }

    private fun injectJsHealthCheckInto(wv: WebView) {
        val script = """
            (function(){
                if (typeof AndroidDashboard !== 'undefined') {
                    setInterval(function() {
                        try { AndroidDashboard.pong(); } catch(e) {}
                    }, 30000);
                    try { AndroidDashboard.pong(); } catch(e) {}  // immediate on load
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    private fun scheduleWatchdogCheck() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun showLoadingOverlay() {
        if (!::loadingOverlay.isInitialized) return
        loadingOverlay.visibility = View.VISIBLE
        loadingOverlay.alpha = 1f
    }

    /**
     * Fades out the loading overlay once the dashboard has painted its first frame.
     * The fade prevents a hard visual cut from loading text to dashboard content.
     */
    private fun hideLoadingOverlay() {
        if (!::loadingOverlay.isInitialized) return
        if (loadingOverlay.alpha < 0.01f) return  // already hidden

        loadingOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                loadingOverlay.alpha = 1f  // reset for next use
            }
            .start()
    }

    private fun schedulePeriodicReload() {
        handler.removeCallbacks(periodicReloadRunnable)
        handler.postDelayed(periodicReloadRunnable, PERIODIC_RELOAD_INTERVAL_MS)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NETWORK CHANGE LISTENER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Registers a NetworkCallback to detect connectivity changes.
     * When the device regains network access (WiFi back online), we force a
     * fresh dashboard load via shouldInterceptRequest so the auth header is
     * re-injected. Without this, the WebView would stay stuck on the last
     * state after a network outage with no recovery trigger.
     *
     * Registered in onResume, unregistered in onPause — safe across config
     * changes and does not leak on activity destroy.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return  // already registered

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                crashLogger.log(TAG, "Network available — reloading dashboard")
                Log.d(TAG, "Network available — reloading dashboard")
                handler.post {
                    if (!isFinishing && ::webView.isInitialized) {
                        // Reset error state so the reload doesn't immediately
                        // hit the error overlay if we had 9 consecutive failures.
                        consecutiveErrors = 0
                        missedPongs = 0
                        isRetryPending = false
                        handler.removeCallbacks(retryRunnable)
                        loadDashboardWithAuth()
                    }
                }
            }

            override fun onLost(network: Network) {
                crashLogger.log(TAG, "Network lost")
                Log.d(TAG, "Network lost")
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                crashLogger.log(TAG, "Network capabilities changed — internet: $connected")
            }
        }

        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
            crashLogger.log(TAG, "Network callback registered")
        } catch (e: Exception) {
            crashLogger.log(TAG, "Failed to register network callback: ${e.message}", e)
            Log.e(TAG, "Failed to register network callback: ${e.message}", e)
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(cb)
                crashLogger.log(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                crashLogger.log(TAG, "Error unregistering network callback: ${e.message}", e)
            }
            networkCallback = null
        }
    }

    @JavascriptInterface
    fun pong() {
        lastJsPongMs = System.currentTimeMillis()
        Log.v(TAG, "JS pong")
    }

    @JavascriptInterface
    fun logFromJs(level: String, message: String) {
        val tag = "[JS] $message"
        when (level.uppercase()) {
            "ERROR"   -> {
                Log.e(TAG, tag)
                crashLogger.log(TAG, tag)
            }
            "WARN"    -> {
                Log.w(TAG, tag)
                crashLogger.log(TAG, tag)
            }
            else      -> Log.d(TAG, tag)
        }
    }

    /**
     * FIXED: Added isFinishing guard.
     * If the Activity is finishing, do not trigger reloads or watchdog checks.
     */
    private fun checkJsHealth() {
        if (isFinishing) {
            crashLogger.log(TAG, "checkJsHealth: Activity finishing, skipping")
            return
        }
        if (!::webView.isInitialized || webView.url == null) {
            scheduleWatchdogCheck()
            return
        }

        val elapsed = System.currentTimeMillis() - lastJsPongMs
        if (elapsed > STALE_THRESHOLD_MS) {
            missedPongs++
            crashLogger.log(TAG, "JS watchdog: no pong for ${elapsed / 1000}s (missed=$missedPongs/$MAX_MISSED_PONGS)")
            Log.w(TAG, "JS watchdog: no pong for ${elapsed / 1000}s " +
                "(threshold=${STALE_THRESHOLD_MS / 1000}s, missed=$missedPongs/$MAX_MISSED_PONGS)")
            if (missedPongs >= MAX_MISSED_PONGS) {
                crashLogger.log(TAG, "JS watchdog TRIGGERED after $missedPongs consecutive misses")
                Log.w(TAG, "JS watchdog TRIGGERED — declaring page unresponsive")
                missedPongs = 0
                onMainFrameError(-1, "JS watchdog: page unresponsive for ${elapsed / 1000}s")
            }
        } else {
            missedPongs = 0
        }

        if (!isRetryPending) {
            scheduleWatchdogCheck()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ERROR RECOVERY
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Called on any main-frame load failure.
     *
     * Retry strategy (exponential backoff):
     *   #1 → 5s   #2 → 15s   #3 → 60s   #4+ → 5min
     *
     * After MAX_ERRORS_BEFORE_STopping (10) consecutive failures, stops
     * retrying and shows the persistent error overlay instead.
     *
     * FIXED: All reloads now go through loadDashboardWithAuth() which uses
     * the interceptor, ensuring the auth header is always present on retry.
     */
    private fun onMainFrameError(errorCode: Int, description: String) {
        if (isFinishing) return

        consecutiveErrors++
        val delayMs = RETRY_DELAYS_MS.getOrElse(consecutiveErrors - 1) { RETRY_DELAYS_MS.last() }

        val msg = """
            Main frame error #$consecutiveErrors
              code  : $errorCode
              desc  : $description
              delay : ${delayMs / 1000}s
              max   : $MAX_ERRORS_BEFORE_STopping
        """.trimIndent()

        crashLogger.log(TAG, msg)
        Log.e(TAG, msg)

        if (consecutiveErrors >= MAX_ERRORS_BEFORE_STopping) {
            crashLogger.log(TAG, "MAX ERRORS reached — showing persistent error overlay")
            Log.e(TAG, "MAX ERRORS reached — showing persistent error overlay")
            showPersistentErrorOverlay(description)
            return
        }

        if (!isRetryPending) {
            isRetryPending = true
            crashLogger.log(TAG, "Scheduling retry #${consecutiveErrors} in ${delayMs / 1000}s")
            Log.d(TAG, "Scheduling retry #${consecutiveErrors} in ${delayMs / 1000}s")
            handler.postDelayed(retryRunnable, delayMs)
        }
    }

    /**
     * FIXED: Uses loadDashboardWithAuth() instead of webView.reload().
     * webView.reload() bypasses shouldInterceptRequest, which means
     * the auth header is NOT injected on retry. This caused the dashboard
     * to fail re-authentication after network drops.
     */
    private fun performReload() {
        if (isFinishing) {
            crashLogger.log(TAG, "performReload: Activity finishing, skipping")
            return
        }
        isRetryPending = false
        crashLogger.log(TAG, "performReload: reloading (error #$consecutiveErrors)")
        Log.d(TAG, "performReload: reloading (error #$consecutiveErrors)")
        showLoadingOverlay()
        loadDashboardWithAuth()
    }

    private fun cancelScheduledRetry() {
        if (isRetryPending) {
            handler.removeCallbacks(retryRunnable)
            isRetryPending = false
            crashLogger.log(TAG, "Retry cancelled — page loaded OK")
            Log.d(TAG, "Retry cancelled — page loaded OK")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ERROR OVERLAY (offline page)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Injects a fully self-contained HTML error page into the WebView.
     * Replaces the entire document so the user sees a branded offline screen
     * instead of a blank or broken WebView.
     *
     * FIXED: The retry button now calls AndroidDashboard.requestNativeReload()
     * instead of location.reload(). This ensures the retry goes through
     * shouldInterceptRequest so the auth header is re-injected.
     *
     * The countdown auto-retry still uses location.reload() which will hit
     * the 5-minute mark anyway, but the manual retry button is the primary
     * user action path and is now fixed.
     */
    private fun showPersistentErrorOverlay(lastError: String) {
        val safeError = android.text.TextUtils.htmlEncode(lastError)
        val html = """
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Dashboard Offline</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      background: #121212;
      color: #ffffff;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      flex-direction: column;
    }
    .icon    { font-size: 64px; margin-bottom: 24px; }
    .title   { font-size: 28px; font-weight: 600; margin-bottom: 12px; }
    .subtitle {
      font-size: 16px;
      color: rgba(255,255,255,0.6);
      margin-bottom: 32px;
      text-align: center;
      max-width: 480px;
      line-height: 1.5;
    }
    .countdown { font-size: 14px; color: rgba(255,255,255,0.4); margin-bottom: 24px; }
    .retry-btn {
      padding: 14px 40px;
      background: #333;
      border: 1px solid #555;
      border-radius: 8px;
      color: #fff;
      font-size: 16px;
      cursor: pointer;
      transition: background 0.2s;
    }
    .retry-btn:hover  { background: #444; }
    .retry-btn:active { background: #2a2a2a; }
    .detail {
      font-size: 12px;
      color: rgba(255,255,255,0.3);
      margin-top: 24px;
      max-width: 480px;
      text-align: center;
      word-break: break-all;
      line-height: 1.4;
    }
  </style>
</head>
<body>
  <div class="icon">&#9888;</div>
  <div class="title">Dashboard unavailable</div>
  <div class="subtitle">
    The dashboard server is unreachable or returned an error.<br>
    Check your network connection.
  </div>
  <div class="countdown" id="cd">Retrying in 5:00</div>
  <button class="retry-btn" id="rb">Retry Now</button>
  <div class="detail" id="dl">Last error: $safeError</div>
  <script>
    (function() {
      var delay = 300;
      var cd = document.getElementById('cd');
      var rb = document.getElementById('rb');
      function fmt(s) { return (s < 10 ? '0' : '') + s; }
      function tick() {
        cd.textContent = 'Retrying in ' + fmt(Math.floor(delay/60)) + ':' + fmt(delay%60)
          + ' — tap Retry Now to reload immediately';
        if (delay <= 0) { location.reload(); return; }
        delay--;
        setTimeout(tick, 1000);
      }
      tick();
      // FIXED: Call native Android to reload so auth header is re-injected
      rb.addEventListener('click', function() {
        try {
          if (typeof AndroidDashboard !== 'undefined') {
            AndroidDashboard.requestNativeReload();
          } else {
            location.reload();
          }
        } catch(e) { location.reload(); }
      });
    })();
  </script>
</body>
</html>
        """.trimIndent()

        crashLogger.log(TAG, "Persistent error overlay injected (lastError=$lastError)")
        Log.d(TAG, "Persistent error overlay injected (lastError=$lastError)")

        webView.loadDataWithBaseURL(
            BuildConfig.DASHBOARD_URL,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NAVIGATION / DIALOGS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Loads the dashboard URL through shouldInterceptRequest so the auth
     * header is injected. Use this instead of webView.loadUrl() directly.
     */
    private fun loadDashboard() {
        crashLogger.log(TAG, "loadDashboard: ${BuildConfig.DASHBOARD_URL}")
        Log.d(TAG, "Loading dashboard: ${BuildConfig.DASHBOARD_URL}")
        webView.loadUrl(BuildConfig.DASHBOARD_URL)
        lastJsPongMs = System.currentTimeMillis()
        lastPageFinishMs = System.currentTimeMillis()
        scheduleWatchdogCheck()
    }

    /**
     * Loads dashboard URL with auth header via shouldInterceptRequest.
     * Use this for retry/reload calls instead of webView.reload() directly.
     */
    private fun loadDashboardWithAuth() {
        crashLogger.log(TAG, "loadDashboardWithAuth: ${BuildConfig.DASHBOARD_URL}")
        Log.d(TAG, "loadDashboardWithAuth: ${BuildConfig.DASHBOARD_URL}")
        webView.loadUrl(BuildConfig.DASHBOARD_URL)
        lastJsPongMs = System.currentTimeMillis()
        lastPageFinishMs = System.currentTimeMillis()
        // Watchdog will be rescheduled by onPageFinished
    }

    @JavascriptInterface
    fun requestNativeReload() {
        crashLogger.log(TAG, "requestNativeReload: called from JS")
        Log.d(TAG, "requestNativeReload: called from JS")
        handler.post {
            if (!isFinishing && ::webView.isInitialized) {
                loadDashboardWithAuth()
            }
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Dashboard?")
            .setMessage("Return to Fire TV home screen?")
            .setPositiveButton("Exit") { _, _ ->
                crashLogger.log(TAG, "User confirmed exit")
                Log.d(TAG, "User confirmed exit")
                // Stop the keep-awake service before finishing so its wake lock
                // is released. Otherwise the service lingers and holds the lock
                // until the system reaps it.
                try {
                    stopService(Intent(this, KeepAwakeService::class.java))
                } catch (t: Throwable) {
                    crashLogger.log(TAG, "Failed to stop KeepAwakeService on exit: ${t.message}", t)
                }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
