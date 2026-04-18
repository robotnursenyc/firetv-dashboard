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

class DashboardActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var exitButtonContainer: FrameLayout

    private val DASHBOARD_URL = "http://2.24.198.162:8080"

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

        // Build exit overlay programmatically — no XML needed
        exitButtonContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(24, 24, 24, 24)
            }
            setPadding(16, 16, 16, 16)
            isClickable = true
            isFocusable = true
        }

        val xBtn = android.widget.TextView(this).apply {
            text = "×"
            textSize = 32f
            setTextColor(0xFFFFFFFF.toInt())
            textStyle = android.graphics.Typeface.BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(64, 64)
            setBackgroundResource(R.drawable.exit_button_bg)
        }

        exitButtonContainer.addView(xBtn)
        exitButtonContainer.setOnClickListener { showExitConfirmation() }

        // Triple-tap zone in bottom-right
        val tripleTapZone = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                setMargins(24, 24, 24, 24)
            }
            isClickable = true
            isFocusable = true
        }

        tripleTapZone.setOnTouchListener { _, event ->
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
        root.addView(webView)
        root.addView(exitButtonContainer)
        root.addView(tripleTapZone)
        setContentView(root)

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
                enterImmersiveMode()
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
