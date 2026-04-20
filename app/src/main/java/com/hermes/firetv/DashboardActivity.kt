package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isError = false

    private val DASHBOARD_URL = BuildConfig.DASHBOARD_URL

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            setBackgroundColor(0xFF0f172a.toInt()) // dark slate background
        }

        // Loading indicator — visible until page loads
        val loadingView = FrameLayout(this).apply {
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

        webView.loadUrl(DASHBOARD_URL)
        startService(Intent(this, KeepAwakeService::class.java))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        // CRITICAL: Enable JavaScript
        settings.javaScriptEnabled = true

        // DOM storage — required for many React/Next.js features
        settings.domStorageEnabled = true

        // Database — enables WebSQL/IndexedDB
        settings.databaseEnabled = true

        // Media playback — auto-play audio/video
        settings.mediaPlaybackRequiresUserGesture = false

        // Cache
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Images
        settings.loadsImagesAutomatically = true

        // Disable file/content access (security)
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // CRITICAL for Fire TV: Set a desktop-ish user agent
        // Fire TV WebView default UA may cause Next.js to serve broken layout
        settings.userAgentString = (
            "Mozilla/5.0 (Linux; Android 9; AFTS Build/NS6225) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/79.0.3945.136 Mobile Safari/537.36"
        )

        // Allow geolocation
        settings.setGeolocationEnabled(true)

        // Allow content URL loading (for about:blank etc)
        settings.allowContentAccess = true

        // CRITICAL: Mixed content mode — Next.js loads HTTPS assets from HTTP page
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Viewport
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL

        // Enable WebView debugging (for adb logcat)
        WebView.setWebContentsDebuggingEnabled(true)

        // CRITICAL: Hardware acceleration can cause blank screen on some FireOS versions
        // Set to SOFTWARE for the WebView to avoid GPU compositing issues
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        // Zoom
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
    }

    private fun setUpWebViewClient(loadingView: View) {
        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isError = false
                android.util.Log.d("DashboardWebView", "Page started: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("DashboardWebView", "Page finished: $url")

                if (!isError) {
                    // Hide loading indicator
                    loadingView.visibility = View.GONE

                    // Inject diagnostic JS
                    view?.evaluateJavascript(
                        """
                        (function() {
                            console.log('Dashboard loaded, URL: ' + window.location.href);
                            console.log('User Agent: ' + navigator.userAgent);
                            console.log('Document ready state: ' + document.readyState);
                            console.log('Body innerHTML length: ' + document.body.innerHTML.length);
                            if (document.body) {
                                var bg = window.getComputedStyle(document.body).backgroundColor;
                                console.log('Body background: ' + bg);
                            }
                            if ('wakeLock' in navigator) {
                                navigator.wakeLock.request('screen').then(function(wl) {
                                    console.log('Wake Lock acquired');
                                }).catch(function(e) {
                                    console.error('Wake Lock error:', e);
                                });
                            }
                        })();
                        """,
                        null
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                android.util.Log.d("DashboardWebView", "shouldOverride: $url")

                // Allow all HTTP/HTTPS URLs from the dashboard
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    // If it's not our dashboard, open externally
                    if (!url.startsWith(DASHBOARD_URL.removeSuffix("/")) &&
                        !url.startsWith("http://2.24.198.162")) {
                        // Not our dashboard domain — let the system handle it
                    }
                    return false // Let WebView handle it
                }
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only catch main frame errors
                if (request?.isForMainFrame == true) {
                    isError = true
                    android.util.Log.e(
                        "DashboardWebView",
                        "Main frame error ${error?.errorCode}: ${error?.description} — URL: ${request?.url}"
                    )
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
                    android.util.Log.e(
                        "DashboardWebView",
                        "HTTP error $statusCode for ${request?.url}"
                    )
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
                android.util.Log.d(
                    "DashboardWebView",
                    "CONSOLE [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()}"
                )
                return true
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                android.util.Log.d("DashboardWebView", "Progress: $newProgress%")
            }
        }
    }

    private fun showErrorView(container: View, message: String) {
        if (container is FrameLayout) {
            container.removeAllViews()
            container.setBackgroundColor(0xFF0f172a.toInt())

            val errorText = TextView(this).apply {
                text = "⚠️ $message\n\nTap × to exit"
                setTextColor(0xFF94a3b8.toInt())
                textSize = 16f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            container.addView(errorText)
            container.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
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
