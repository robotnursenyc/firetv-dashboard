package com.hermes.firetv

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

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
//   • ACRA crash reporting (release builds only)
//   • Remote WebView debugging (debug builds only)
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
    private lateinit var windowInsetsController: WindowInsetsControllerCompat

    // ── Watchdog state ────────────────────────────────────────────────────────
    // Tracks last JS pong — reloaded if no pong for STALE_THRESHOLD_MS.
    private var lastJsPongMs: Long = 0L

    // Tracks last page-finish timestamp — used to detect initial load stalls.
    private var lastPageFinishMs: Long = 0L

    // If JS hasn't ponged within this window, consider the page stale and reload.
    private val STALE_THRESHOLD_MS = 90_000L   // 90 s  (3 × 30-s JS ping interval)

    // Check JS health every this many ms.
    private val WATCHDOG_INTERVAL_MS = 30_000L

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

    // ═════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logStartupBanner()
        setupFullscreenWindow()
        setupViews()
        setupKeepAwake()
        Log.d(TAG, "=== DashboardActivity onCreate END ===")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        lastJsPongMs = System.currentTimeMillis()
        scheduleWatchdogCheck()
        Log.d(TAG, "onResume — WebView resumed, watchdog armed")
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(retryRunnable)
        Log.d(TAG, "onPause — watchdog disarmed")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(retryRunnable)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.destroy()
        }
        super.onDestroy()
    }

    // Low-memory signal from the system. Reload the WebView to release memory.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.w(TAG, "onTrimMemory level=$level")
        when {
            level >= TRIM_MEMORY_MODERATE -> {
                Log.w(TAG, "Memory pressure ≥ MODERATE — reloading WebView to release memory")
                webView.reload()
            }
            level >= TRIM_MEMORY_BACKGROUND -> {
                Log.w(TAG, "Memory pressure ≥ BACKGROUND — proactive reload")
                webView.reload()
            }
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
            // Future extension: allow volume keys for debug overlays, etc.
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STARTUP HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun logStartupBanner() {
        val wvPkg = WebView.getCurrentWebViewPackage()
        Log.d(TAG, """
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
        """.trimIndent())
    }

    @Suppress("DEPRECATION")
    private fun setupFullscreenWindow() {
        // Keep screen on — prevents the device CPU from sleeping.
        // The Fire TV screensaver / ambient mode is a separate concern that
        // must be disabled in Settings → Display & Sounds → Screen Saver → Never.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // BLOCK screenshots and screen recording — essential for kiosk security.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

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

        // WebView — fills entire screen.
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Exit button — top-right "×".
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
            isFocusable = true
            isClickable = true
        }
        exitBtn.setOnClickListener { showExitDialog() }

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

        // Hardware layer for GPU-accelerated rendering.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Remote debugging — debug builds only.
        // Enables Chrome DevTools: chrome://inspect/#devices on desktop (same network).
        if (BuildConfig.ENABLE_WEBVIEW_DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            Log.d(TAG, "WebView remote debugging ENABLED (debug build)")
        }

        rootView.addView(webView)
        rootView.addView(exitBtn)
        setContentView(rootView)

        // ── WebView clients ────────────────────────────────────────────────
        setupWebViewClients()

        // ── Load the dashboard ─────────────────────────────────────────────
        loadDashboard()
    }

    private fun setupKeepAwake() {
        try {
            startService(Intent(this, KeepAwakeService::class.java))
            Log.d(TAG, "KeepAwakeService started")
        } catch (e: Exception) {
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
                when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, "CONSOLE ERROR: $src ${msg.message()}")
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, "CONSOLE WARN:  $src ${msg.message()}")
                    else                                  -> Log.v(TAG, "CONSOLE:       $src ${msg.message()}")
                }
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                Log.d(TAG, "Page title: $title")
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
                // POST/PUT/DELETE cannot be faithfully proxied because
                // WebResourceRequest does not expose the request body.
                // Let the WebView handle those natively — they go direct
                // over HTTPS without the X-App-Auth header (the dashboard
                // session is already established via the initial HTML).
                if (host != "dashboard.cashlabnyc.com") return null
                if (request.method != "GET") return null

                val path = url.path ?: "/"
                Log.v(TAG, "INTERCEPT ${request.method} $path")
                return proxyDashboardRequest(url.toString(), request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "MAINFRAME ERROR ${error?.errorCode} — ${error?.description}")
                    onMainFrameError(
                        error?.errorCode?.toInt() ?: -1,
                        error?.description?.toString() ?: "WebView error"
                    )
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
                cancelScheduledRetry()
                isRetryPending = false
                Log.d(TAG, "Page finished: $url")
                injectJsHealthCheck()
            }

            /**
             * Called when the WebView's render process terminates.
             * This happens under memory pressure or after a JS crash — not the same
             * as onReceivedError. Returning true tells the WebView to attempt a reload
             * via the default error page; we additionally trigger a full reload.
             */
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                val crashed = detail?.didCrash() ?: false
                val renderer = detail?.rendererTerminatedReason() ?: "unknown"
                Log.e(TAG, "RENDER PROCESS GONE — crashed=$crashed reason=$renderer")
                if (crashed) {
                    Log.w(TAG, "Renderer crashed — reloading WebView")
                    // Reload without full Activity recreation; WebView is recreated by the system.
                    handler.postDelayed({ webView.reload() }, 1_000)
                }
                return true  // Let the system handle its own error page; we handle recovery.
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // REQUEST PROXY (shouldInterceptRequest)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Proxies HTTP/S requests to dashboard.cashlabnyc.com.
     *
     * Adds the X-App-Auth header (from BuildConfig, injected by CI),
     * copies relevant request headers, buffers the full response body
     * (prevents truncated-chunk JS parser crashes), and returns a
     * fully-formed WebResourceResponse.
     *
     * Standard HTTPS is used throughout. No custom TrustManager —
     * Let's Encrypt IS4 is in the Android system trust store on all
     * supported Fire OS versions.
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
            conn.setRequestProperty("User-Agent", "FireTV/1.0")

            // Forward applicable headers from the WebView's original request.
            request.requestHeaders?.forEach { (key, value) ->
                if (key.isNotEmpty() && key.lowercase() !in HOP_BY_HOP) {
                    conn.setRequestProperty(key, value)
                }
            }

            val status = conn.responseCode
            val reason = conn.responseMessage
            Log.v(TAG, "  → $status $reason")

            val responseHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && key.lowercase() !in HOP_BY_HOP && values.isNotEmpty()) {
                    responseHeaders[key] = values.joinToString(", ")
                }
            }

            val mimeType = inferMimeType(url.path, conn.contentType)
            val bytes = bufferFully(conn, status)

            val charset = if (isTextMime(mimeType)) "UTF-8" else null
            WebResourceResponse(
                mimeType, charset, status, reason, responseHeaders,
                ByteArrayInputStream(bytes)
            )
        } catch (e: Exception) {
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
            contentType?.isNotBlank() == true             -> contentType!!
            else                                            -> "application/octet-stream"
        }
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
        mime == "application/javascript" ||
        mime == "application/json"

    companion object {
        private val HOP_BY_HOP = setOf(
            "transfer-encoding", "connection", "keep-alive", "upgrade",
            "proxy-authenticate", "proxy-authorization", "te", "trailers", "host"
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
        webView.evaluateJavascript(script, null)
    }

    private fun scheduleWatchdogCheck() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    @JavascriptInterface
    fun pong() {
        lastJsPongMs = System.currentTimeMillis()
        Log.v(TAG, "JS pong")
    }

    @JavascriptInterface
    fun logFromJs(level: String, message: String) {
        when (level.uppercase()) {
            "ERROR"   -> Log.e(TAG, "[JS] $message")
            "WARN"    -> Log.w(TAG, "[JS] $message")
            else      -> Log.d(TAG, "[JS] $message")
        }
    }

    private fun checkJsHealth() {
        if (!::webView.isInitialized || webView.url == null) {
            scheduleWatchdogCheck()
            return
        }

        val elapsed = System.currentTimeMillis() - lastJsPongMs
        if (elapsed > STALE_THRESHOLD_MS) {
            Log.w(TAG, "JS watchdog TRIGGERED — no pong for ${elapsed / 1000}s (threshold=${STALE_THRESHOLD_MS / 1000}s)")
            consecutiveErrors++
            onMainFrameError(-1, "JS watchdog: page unresponsive for ${elapsed / 1000}s")
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
     */
    private fun onMainFrameError(errorCode: Int, description: String) {
        consecutiveErrors++
        val delayMs = RETRY_DELAYS_MS.getOrElse(consecutiveErrors - 1) { RETRY_DELAYS_MS.last() }

        Log.e(TAG, """
            Main frame error #$consecutiveErrors
              code  : $errorCode
              desc  : $description
              delay : ${delayMs / 1000}s
              max   : $MAX_ERRORS_BEFORE_STopping
        """.trimIndent())

        if (consecutiveErrors >= MAX_ERRORS_BEFORE_STopping) {
            Log.e(TAG, "MAX ERRORS reached — showing persistent error overlay")
            showPersistentErrorOverlay(description)
            return
        }

        if (!isRetryPending) {
            isRetryPending = true
            Log.d(TAG, "Scheduling retry #${consecutiveErrors} in ${delayMs / 1000}s")
            handler.postDelayed(retryRunnable, delayMs)
        }
    }

    private fun performReload() {
        isRetryPending = false
        Log.d(TAG, "performReload: reloading (error #$consecutiveErrors)")
        webView.reload()
    }

    private fun cancelScheduledRetry() {
        if (isRetryPending) {
            handler.removeCallbacks(retryRunnable)
            isRetryPending = false
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
     * Uses loadDataWithBaseURL so we avoid all JS-string-escaping complexity.
     * The page includes:
     *   • Countdown auto-retry (5 minutes)
     *   • "Retry Now" button (immediate reload)
     *   • Last error description displayed for diagnostics
     *   • No external dependencies (all CSS/JS inline)
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
                  rb.addEventListener('click', function() { location.reload(); });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(
            BuildConfig.DASHBOARD_URL,
            html,
            "text/html",
            "UTF-8",
            null
        )
        Log.d(TAG, "Persistent error overlay injected (lastError=$lastError)")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // NAVIGATION / DIALOGS
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadDashboard() {
        val url = BuildConfig.DASHBOARD_URL
        Log.d(TAG, "Loading dashboard: $url")
        webView.loadUrl(url)
        lastJsPongMs = System.currentTimeMillis()
        lastPageFinishMs = System.currentTimeMillis()
        scheduleWatchdogCheck()
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Dashboard?")
            .setMessage("Return to Fire TV home screen?")
            .setPositiveButton("Exit") { _, _ ->
                Log.d(TAG, "User confirmed exit")
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
