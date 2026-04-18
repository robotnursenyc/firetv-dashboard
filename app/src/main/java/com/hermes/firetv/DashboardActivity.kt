package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hermes.firetv.R

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var exitButtonContainer: FrameLayout
    private lateinit var exitBottomRight: View

    private val DASHBOARD_URL = "http://2.24.198.162:8080"
    private val TAG = "FireTVDashboard"

    // Track if we can go back in WebView history
    private var canGoBack = false

    // Triple-tap state
    private var tapCount = 0
    private var lastTapTime = 0L

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        setContentView(R.layout.activity_main)

        // Get views from layout
        webView = findViewById(R.id.webView)
        exitButtonContainer = findViewById(R.id.exitButtonContainer)
        exitBottomRight = findViewById(R.id.exitBottomRight)

        // Exit Button 1: Visible X in top-right corner
        exitButtonContainer.setOnClickListener {
            showExitConfirmation()
        }

        // Exit Button 2: Triple-tap bottom-right zone
        exitBottomRight.setOnTouchListener { _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 500) {
                    tapCount++
                } else {
                    tapCount = 1
                }
                lastTapTime = now
                if (tapCount >= 3) {
                    tapCount = 0
                    showExitConfirmation()
                    true
                } else {
                    true
                }
            } else {
                false
            }
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

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                canGoBack = view?.canGoBack() == true
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    errorCode == WebViewClient.ERROR_CONNECT) {
                    Toast.makeText(
                        this@DashboardActivity,
                        "Cannot reach dashboard at $DASHBOARD_URL — check connection",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (errorResponse?.statusCode == 404 || errorResponse?.statusCode == 500) {
                    Toast.makeText(
                        this@DashboardActivity,
                        "Dashboard page not found (HTTP ${errorResponse.statusCode})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        webView.loadUrl(DASHBOARD_URL)

        startService(Intent(this, KeepAwakeService::class.java))
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (canGoBack) {
                    webView.goBack()
                    true
                } else {
                    showExitConfirmation()
                    true
                }
            }
            KeyEvent.KEYCODE_HOME -> {
                finish()
                true
            }
            else -> {
                enterImmersiveMode()
                super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun showExitConfirmation() {
        exitButtonContainer.alpha = 0.3f
        exitButtonContainer.animate().alpha(1f).setDuration(200).start()

        AlertDialog.Builder(this)
            .setTitle("Exit Dashboard?")
            .setMessage("Return to Fire Stick home screen?")
            .setPositiveButton("Exit") { _, _ -> finish() }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                enterImmersiveMode()
            }
            .setOnDismissListener { enterImmersiveMode() }
            .show()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
