package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val DASHBOARD_URL = "http://2.24.198.162:8080"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    """(function(){if('wakeLock' in navigator){navigator.wakeLock.request('screen').then(function(wl){console.log('Wake Lock acquired');wl.addEventListener('release',function(){console.log('Wake Lock released');});}).catch(function(err){console.error('Wake Lock error:',err);});}})();
""", null
                )
            }
        }

        webView.loadUrl(DASHBOARD_URL)

        startService(Intent(this, KeepAwakeService::class.java))
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
