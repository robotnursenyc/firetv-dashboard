package com.hermes.firetv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var exitButtonContainer: FrameLayout
    private lateinit var exitBottomRight: View

    private val DASHBOARD_URL = "http://2.24.198.162:8080"
    private val TAG = "FireTVDashboard"

    // Track if we can go back in WebView history
    private var canGoBack = false

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Keep screen always on — this is more reliable than a service on Fire TV
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Lock to landscape for TV display
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Wire up views
        webView = findViewById(R.id.webView)
        exitButtonContainer = findViewById(R.id.exitButtonContainer)
        exitBottomRight = findViewById(R.id.exitBottomRight)

        // Exit Button 1: Visible X in top-right corner
        exitButtonContainer.setOnClickListener {
            showExitConfirmation()
        }

        // Exit Button 2: Invisible bottom-right touch target (triple-tap zone)
        var tapCount = 0
        var lastTapTime = 0L
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

        // Configure WebView
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadsImagesAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                Log.d(TAG, "Page title: $title")
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                canGoBack = view?.canGoBack() == true
                Log.d(TAG, "Page loaded: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                Log.e(TAG, "WebView error $errorCode: $description — $failingUrl")
                if (errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
                    errorCode == WebViewClient.ERROR_CONNECT) {
                    showConnectionError()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only handle main frame errors, not subresource errors
                if (request?.isForMainFrame == true) {
                    Log.e(TAG, "Main frame error: ${error?.description}")
                    showConnectionError()
                }
            }
        }

        webView.loadUrl(DASHBOARD_URL)

        // Start foreground service to keep screen awake across activity lifecycle
        startService(Intent(this, KeepAwakeService::class.java))
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        // Don't release — keep screen awake while app is in foreground
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
            // Back button: if WebView can go back, go back; otherwise show exit
            KeyEvent.KEYCODE_BACK -> {
                if (canGoBack) {
                    webView.goBack()
                    true
                } else {
                    showExitConfirmation()
                    true
                }
            }
            // Home button: exit immediately
            KeyEvent.KEYCODE_HOME -> {
                finish()
                true
            }
            // Remote control: re-enter immersive on any key
            else -> {
                enterImmersiveMode()
                super.onKeyDown(keyCode, event)
            }
        }
    }

    private fun showExitConfirmation() {
        // Quick flash of the X button to give tactile feedback before dialog
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

    private fun showConnectionError() {
        runOnUiThread {
            Toast.makeText(
                this,
                "Cannot reach dashboard at $DASHBOARD_URL — check connection",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
