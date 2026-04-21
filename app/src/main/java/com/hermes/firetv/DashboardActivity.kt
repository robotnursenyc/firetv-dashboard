package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingView: ViewGroup
    private var isError = false
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    private val DASHBOARD_URL = BuildConfig.DASHBOARD_URL
    private val AUTH_TOKEN = BuildConfig.APP_AUTH_TOKEN

    // Trust all SSL certs — FireTV devices don't have Let's Encrypt CA installed
    private val sslTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslSocketFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(sslTrustManager), SecureRandom())
        ctx.socketFactory
    }

    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    // Track pending intercepted requests to detect when page is fully loaded
    private var pendingRequests = 0
    private val pendingLock = Any()
    private val loadingHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loadingHideRunnable = Runnable {
        synchronized(pendingLock) {
            pendingRequests = 0
        }
        loadingView.visibility = View.GONE
        Log.d("DashboardWebView", "Loading hidden by timeout")
    }

    // Wraps an InputStream to call back when fully consumed
    private inner class CountingInputStream(val inner: InputStream, val url: String) : InputStream() {
        private var reachedEOF = false

        override fun read(): Int {
            val b = inner.read()
            if (b == -1 && !reachedEOF) {
                reachedEOF = true
                onRequestDone()
            }
            return b
        }
        override fun read(buf: ByteArray): Int {
            val n = inner.read(buf)
            if (n == -1 && !reachedEOF) {
                reachedEOF = true
                onRequestDone()
            }
            return n
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(buf, off, len)
            if (n == -1 && !reachedEOF) {
                reachedEOF = true
                onRequestDone()
            }
            return n
        }
        override fun skip(n: Long): Long = inner.skip(n)
        override fun available(): Int = inner.available()
        override fun close() {
            try { inner.close() } catch (e: IOException) {}
            if (!reachedEOF) {
                reachedEOF = true
                onRequestDone()
            }
        }
        override fun markSupported(): Boolean = false
        private fun onRequestDone() {
            val remaining: Int
            synchronized(pendingLock) {
                pendingRequests--
                remaining = pendingRequests
            }
            Log.d("DashboardWebView", "Request done for $url [pending=$remaining]")
            if (remaining <= 0) {
                loadingHideHandler.removeCallbacks(loadingHideRunnable)
                loadingHideHandler.post(loadingHideRunnable)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("DashboardWebView", "=== APK v${BuildConfig.VERSION_NAME} STARTING ===")
        Log.d("DashboardWebView", "DASHBOARD_URL: $DASHBOARD_URL")
        Log.d("DashboardWebView", "AUTH_TOKEN: $AUTH_TOKEN")

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Root container
        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0f172a.toInt())
        }

        // Loading indicator — visible until page loads
        loadingView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF0f172a.toInt())
        }

        val spinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            indeterminateDrawable?.setTint(0xFFFFFFFF.toInt())
        }

        val loadingText = TextView(this).apply {
            text = "Loading dashboard..."
            setTextColor(0xFF94a3b8.toInt())
            textSize = 16f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = 24
            }
        }

        val loadingContent = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER
            }
            addView(spinner)
            addView(loadingText)
        }
        loadingView.addView(loadingContent)

        // WebView
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Exit button
        val exitBtn = android.widget.TextView(this).apply {
            text = "×"
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(72, 72).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, 16, 16, 0)
            }
            setBackgroundColor(0x66000000.toInt())
            isClickable = true
            isFocusable = true
        }

        exitBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Exit Dashboard?")
                .setMessage("Return to Fire Stick home?")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        root.addView(webView)
        root.addView(loadingView)
        root.addView(exitBtn)
        setContentView(root)

        exitBtn.bringToFront()

        configureWebView()
        setUpWebViewClient(loadingView)
        setUpWebChromeClient()

        // Load dashboard URL — auth header injection is handled by shouldInterceptRequest
        webView.loadUrl(DASHBOARD_URL)
        startService(Intent(this, KeepAwakeService::class.java))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // Desktop-ish UA for proper Next.js layout
        settings.userAgentString = (
            "Mozilla/5.0 (Linux; Android 9; AFTS Build/NS6225) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/79.0.3945.136 Mobile Safari/537.36"
        )

        // Allow geolocation
        settings.setGeolocationEnabled(true)
        settings.allowContentAccess = true

        // Mixed content
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        WebView.setWebContentsDebuggingEnabled(true)
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
    }

    private fun setUpWebViewClient(loadingView: ViewGroup) {
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isError = false
                pendingRequests = 0
                Log.d("DashboardWebView", "Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("DashboardWebView", "Page finished: $url (pendingRequests=$pendingRequests)")
                // If we intercepted the main frame, onPageFinished fires but we need to
                // wait for all subresources. Schedule hide after a short delay to allow
                // intercepted subresource requests to complete.
                loadingHideHandler.removeCallbacks(loadingHideRunnable)
                loadingHideHandler.postDelayed(loadingHideRunnable, 500)
            }

            /**
             * CRITICAL: Intercept EVERY request and inject the X-App-Auth header.
             * This is the only reliable way to auth JS fetch() calls — FireTV WebView's
             * CookieManager does NOT persist cookies across fetch() calls from within JS.
             * By handling shouldInterceptRequest ourselves, every resource request
             * (HTML, JS bundles, CSS, API calls, images) carries the auth token.
             *
             * Runs on a background thread via ExecutorService to avoid ANR on slow connections.
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                // Only intercept dashboard-origin requests
                if (!url.startsWith(DASHBOARD_URL.removeSuffix("/")) &&
                    !url.startsWith("https://dashboard.cashlabnyc.com") &&
                    !url.startsWith("http://2.24.198.162")) {
                    return super.shouldInterceptRequest(view, request)
                }

                synchronized(pendingLock) {
                    pendingRequests++
                }
                Log.d("DashboardWebView", "shouldIntercept [pending+1=$pendingRequests]: ${request.method} $url")

                return try {
                    // Run network request on background thread to avoid ANR
                    var responseCode = 0
                    var mimeType = "text/plain"
                    var contentEncoding = "utf-8"
                    var inputStream: InputStream? = null
                    val responseHeaders = java.util.HashMap<String, String>()

                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = request.method
                    conn.setRequestProperty("X-App-Auth", AUTH_TOKEN)
                    conn.setRequestProperty("Host", "dashboard.cashlabnyc.com")
                    conn.connectTimeout = 30000   // 30s connect timeout
                    conn.readTimeout = 30000      // 30s read timeout
                    conn.instanceFollowRedirects = true

                    // Trust all SSL certs — FireTV doesn't have Let's Encrypt CA
                    if (conn is HttpsURLConnection) {
                        conn.sslSocketFactory = sslSocketFactory
                        conn.hostnameVerifier = hostnameVerifier
                    }

                    // Retry up to 2 times on 5xx errors
                    var attempt = 0
                    var success = false
                    while (attempt < 3 && !success) {
                        try {
                            responseCode = conn.responseCode
                            mimeType = (conn.contentType ?: "text/plain").split(";")[0].trim()
                            contentEncoding = conn.contentEncoding ?: "utf-8"
                            inputStream = if (responseCode >= 400) conn.errorStream else conn.inputStream
                            success = true
                        } catch (e: java.net.ProtocolException) {
                            // Non-retryable
                            throw e
                        } catch (e: java.net.SocketTimeoutException) {
                            attempt++
                            if (attempt >= 3) throw e
                            Log.w("DashboardWebView", "Timeout on attempt $attempt for $url")
                            conn.disconnect()
                            val reconnect = URL(url).openConnection() as HttpURLConnection
                            // Re-apply settings
                            if (reconnect is HttpsURLConnection) {
                                reconnect.sslSocketFactory = sslSocketFactory
                                reconnect.hostnameVerifier = hostnameVerifier
                            }
                        }
                    }

                    conn.headerFields?.forEach { (key, values) ->
                        if (!key.isNullOrBlank() &&
                            !key.equals("transfer-encoding", ignoreCase = true) &&
                            !key.equals("content-encoding", ignoreCase = true)) {
                            responseHeaders[key] = values.joinToString(", ")
                        }
                    }

                    Log.d("DashboardWebView", "shouldIntercept: ${request.method} $url → $responseCode")

                    val wrappedStream = CountingInputStream(inputStream!!, url)
                    WebResourceResponse(mimeType, contentEncoding, wrappedStream).apply {
                        setResponseHeaders(responseHeaders)
                    }
                } catch (e: Exception) {
                    Log.e("DashboardWebView", "shouldIntercept EXCEPTION for $url: ${e.javaClass.simpleName}: ${e.message}")
                    synchronized(pendingLock) { pendingRequests-- }
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d("DashboardWebView", "shouldOverride: $url")
                // Keep all dashboard URLs in WebView
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    isError = true
                    Log.e("DashboardWebView", "Main frame error ${error?.errorCode}: ${error?.description} — URL: ${request?.url}")
                    showErrorView(loadingView, "Page failed to load (${error?.description})")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val statusCode = response?.statusCode ?: 0
                    Log.e("DashboardWebView", "HTTP error $statusCode for ${request?.url}")
                    if (statusCode >= 400 && !isError) {
                        isError = true
                        showErrorView(loadingView, "HTTP error $statusCode")
                    }
                }
            }
        }
    }

    private fun setUpWebChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d("DashboardWebView", "CONSOLE [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()}")
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    Log.d("DashboardWebView", "Page fully loaded")
                }
            }
        }
    }

    private fun showErrorView(container: ViewGroup, message: String) {
        container.removeAllViews()
        container.setBackgroundColor(0xFF0f172a.toInt())

        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER
        }
        val errorText = TextView(this).apply {
            text = "⚠️ $message\n\n" +
                   "URL: $DASHBOARD_URL\n" +
                   "Token: $AUTH_TOKEN\n" +
                   "Version: ${BuildConfig.VERSION_NAME}\n\n" +
                   "Install latest APK from Telegram."
            setTextColor(0xFF94a3b8.toInt())
            textSize = 14f
            layoutParams = lp
        }
        container.addView(errorText)
        container.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // Clear the keep-screen-on flag so display behaves normally after app exits
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor.shutdown()
        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            destroy()
        }
        super.onDestroy()
    }
}
