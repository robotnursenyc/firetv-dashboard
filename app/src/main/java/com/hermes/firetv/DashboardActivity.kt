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

        // Intercept all WebView requests and re-fetch with X-App-Auth header
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = request.method
                    conn.setRequestProperty("X-App-Auth", "f9711c62b88042dca5266d44ddfb6d14")
                    conn.setRequestProperty("User-Agent", "FireTV/1.0")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    if (conn is HttpsURLConnection) {
                        conn.sslSocketFactory = this@DashboardActivity.sslContext.socketFactory
                        conn.hostnameVerifier = this@DashboardActivity.hostnameVerifier
                    }
                    val mimeType = conn.contentType ?: "text/html"
                    val inputStream: InputStream = conn.inputStream
                    val statusCode = conn.responseCode
                    val reasonPhrase = conn.responseMessage
                    val responseHeaders = mutableMapOf<String, String>()
                    conn.headerFields.forEach { (key, values) ->
                        if (key != null) responseHeaders[key] = values.joinToString(",")
                    }
                    WebResourceResponse(mimeType, null, statusCode, reasonPhrase, responseHeaders, inputStream)
                } catch (e: Exception) {
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
