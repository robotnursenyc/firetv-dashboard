package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val DASHBOARD_URL = BuildConfig.DASHBOARD_URL

    // SSL context that trusts all certs (needed for FireTV + Let's Encrypt)
    private val sslContext: SSLContext by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        ctx
    }

    private val hostnameVerifier = HostnameVerifier { _, _ -> true }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Exit button: top-right "×"
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
        }

        root.addView(webView)
        root.addView(exitBtn)
        setContentView(root)

        exitBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Exit Dashboard?")
                .setMessage("Return to Fire Stick home?")
                .setPositiveButton("Exit") { _, _ -> finish() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = WebChromeClient()

        // Intercept ALL requests to the dashboard domain, add auth header, and return with correct MIME type.
        // Static assets (JS chunks, CSS, images) also need auth — server returns 401 without it.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val requestUrl = URL(url)

                // Only intercept requests destined for our dashboard server
                if (requestUrl.host != "dashboard.cashlabnyc.com") {
                    return null
                }

                val path = requestUrl.path ?: "/"
                val method = request.method

                return try {
                    val conn = requestUrl.openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.setRequestProperty("X-App-Auth", "f9711c62b88042dca5266d44ddfb6d14")
                    conn.setRequestProperty("User-Agent", "FireTV/1.0")
                    conn.connectTimeout = 20000
                    conn.readTimeout = 20000
                    if (conn is HttpsURLConnection) {
                        conn.sslSocketFactory = this@DashboardActivity.sslContext.socketFactory
                        conn.hostnameVerifier = this@DashboardActivity.hostnameVerifier
                    }

                    // Pass through request headers the WebView sent (Accept, etc.)
                    request.requestHeaders?.forEach { (key, value) ->
                        if (key.isNotEmpty()) conn.setRequestProperty(key, value)
                    }

                    val statusCode = conn.responseCode
                    val reasonPhrase = conn.responseMessage

                    // Build response headers, skipping hop-by-hop headers
                    val responseHeaders = mutableMapOf<String, String>()
                    val skipHeaders = setOf("transfer-encoding", "connection", "keep-alive", "upgrade", "proxy-authenticate", "proxy-authorization", "te", "trailers", "host")
                    conn.headerFields?.forEach { (key, values) ->
                        if (key != null && key.lowercase() !in skipHeaders && values.isNotEmpty()) {
                            responseHeaders[key] = values.joinToString(", ")
                        }
                    }

                    // Determine MIME type from URL path (more reliable than Content-Type for static assets)
                    val mimeType = when {
                        path.endsWith(".js") -> "application/javascript"
                        path.endsWith(".mjs") -> "application/javascript"
                        path.endsWith(".css") -> "text/css"
                        path.endsWith(".png") -> "image/png"
                        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                        path.endsWith(".webp") -> "image/webp"
                        path.endsWith(".svg") -> "image/svg+xml"
                        path.endsWith(".ico") -> "image/x-icon"
                        path.endsWith(".woff") -> "font/woff"
                        path.endsWith(".woff2") -> "font/woff2"
                        path.endsWith(".ttf") -> "font/ttf"
                        path.endsWith(".json") -> "application/json"
                        path.endsWith(".xml") -> "application/xml"
                        path.endsWith(".html") || path == "/" -> "text/html"
                        conn.contentType?.contains("text/html") == true -> "text/html"
                        conn.contentType?.contains("application/json") == true -> "application/json"
                        else -> conn.contentType ?: "application/octet-stream"
                    }

                    val inputStream: InputStream = if (statusCode >= 400) {
                        conn.errorStream ?: conn.inputStream
                    } else {
                        conn.inputStream
                    }

                    WebResourceResponse(mimeType, null, statusCode, reasonPhrase, responseHeaders, inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    """(function(){if('wakeLock' in navigator){navigator.wakeLock.request('screen').then(function(wl){console.log('Wake Lock acquired');wl.addEventListener('release',function(){console.log('Wake Lock released');});}).catch(function(err){console.error('Wake Lock error:',err);});}})();""",
                    null
                )
            }
        }

        webView.loadUrl(DASHBOARD_URL)
        startService(Intent(this, KeepAwakeService::class.java))
    }

    override fun onDestroy() {
        webView.apply {
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            destroy()
        }
        super.onDestroy()
    }
}
