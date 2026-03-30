package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("SetJavaScriptEnabled")
class KioskActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KioskActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_DEALER_NAME = "dealer_name"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "Ingen URL mottatt")
            finish()
            return
        }

        // Layout først (hideSystemUI trenger window)
        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF000000.toInt())

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = kioskWebViewClient
            webChromeClient = WebChromeClient()
        }

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        errorText = TextView(this).apply {
            setTextColor(0xFFFF4444.toInt())
            textSize = 18f
            visibility = View.GONE
            setPadding(48, 48, 48, 48)
        }

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val progressParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = android.view.Gravity.CENTER }
        root.addView(progressBar, progressParams)
        root.addView(errorText, progressParams)

        setContentView(root)

        // Lockdown og UI-hiding ETTER setContentView
        enableKioskMode()
        hideSystemUI()

        Log.i(TAG, "Laster URL: $url")
        webView.loadUrl(url)
    }

    // ==================== Kiosk Lockdown ====================

    @SuppressLint("MissingPermission")
    private fun enableKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, DeviceOwnerReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            // Legg til vår app i lock task packages
            dpm.setLockTaskPackages(componentName, arrayOf(packageName))

            // Deaktiver keyguard og statusbar
            try { dpm.setKeyguardDisabled(componentName, true) } catch (_: Exception) {}
            try { dpm.setStatusBarDisabled(componentName, true) } catch (_: Exception) {}

            Log.i(TAG, "Device owner lockdown aktivert")
        }

        // Start lock task mode
        try {
            startLockTask()
            Log.i(TAG, "Lock task mode startet")
        } catch (e: Exception) {
            Log.e(TAG, "Kunne ikke starte lock task: ${e.message}")
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    // ==================== WebView ====================

    private val kioskWebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            progressBar.visibility = View.VISIBLE
            errorText.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            progressBar.visibility = View.GONE
        }

        @SuppressLint("SetTextI18n")
        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                progressBar.visibility = View.GONE
                errorText.text = "Ingen internettilkobling.\nPrøver igjen om 10 sekunder..."
                errorText.visibility = View.VISIBLE

                // Retry etter 10 sek
                view?.postDelayed({
                    errorText.visibility = View.GONE
                    view.reload()
                }, 10_000)
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            // Hold all navigasjon innenfor WebView
            return false
        }
    }

    // ==================== Blokkér escape ====================

    override fun onBackPressed() {
        // Blokkert — kan ikke gå tilbake
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        // Blokkér home, recent apps, etc.
        val blocked = listOf(
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU
        )
        if (event?.keyCode in blocked) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
