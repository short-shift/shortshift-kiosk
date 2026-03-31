package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("SetJavaScriptEnabled", "MissingPermission")
class KioskActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "KioskActivity"
        const val EXTRA_URL = "url"
        const val EXTRA_DEALER_NAME = "dealer_name"
        private const val PREFS_NAME = "shortshift_config"
        private const val CORNER_SIZE_DP = 100
        private const val GESTURE_TIMEOUT_MS = 3000L
        private const val TAP_INTERVAL_MS = 500L
        private const val DEFAULT_PIN = "3023"
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private var errorOverlay: View? = null
    private var retryCountdown: Runnable? = null
    private var isShowingError = false
    private val handler = Handler(Looper.getMainLooper())

    // Admin gesture state
    private var topLeftTapCount = 0
    private var bottomRightTapCount = 0
    private var gesturePhase = 0 // 0=waiting for top-left, 1=waiting for bottom-right
    private var lastTapTime = 0L
    private var adminOverlay: View? = null
    private lateinit var nexus: ShowroomNexus
    private lateinit var commandChannel: CommandChannel
    private var lastMoveTrackTime = 0L
    @Volatile private var currentWebViewUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Log.e(TAG, "Ingen URL mottatt")
            finish()
            return
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webViewClient = kioskWebViewClient
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage ?: return true
                    val level = when (msg.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        else -> "LOG"
                    }
                    Log.i(TAG, "JS [$level]: ${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]")
                    return true
                }
            }
        }

        nexus = ShowroomNexus(this, webView) { newStartUrl ->
            Log.i(TAG, "Start-URL endret til: $newStartUrl")
        }
        webView.addJavascriptInterface(nexus, "fully")

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
        ).apply { gravity = Gravity.CENTER }
        root.addView(progressBar, progressParams)
        root.addView(errorText, progressParams)

        setContentView(root)

        enableKioskMode()
        hideSystemUI()

        Log.i(TAG, "Laster URL: $url")
        webView.loadUrl(url)

        // Koble til Supabase Realtime for fjernkommandoer
        commandChannel = CommandChannel(
            context = this,
            onSetUrl = { newUrl ->
                Log.i(TAG, "Fjernkommando: sett URL til $newUrl")
                webView.loadUrl(newUrl)
            },
            onRefresh = {
                Log.i(TAG, "Fjernkommando: refresh")
                webView.reload()
            },
            getLocation = {
                nexus.getLocationPair()
            },
            getCurrentUrl = {
                currentWebViewUrl
            }
        )
        commandChannel.connect()
    }

    // ==================== Admin Gesture Detection ====================

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Touch-tracking for statistikk (klikk og bevegelser)
        if (adminOverlay == null && ::nexus.isInitialized) {
            when (ev?.action) {
                MotionEvent.ACTION_DOWN -> nexus.recordTouch(isClick = true)
                MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMoveTrackTime > 200) { // Throttle bevegelser
                        nexus.recordTouch(isClick = false)
                        lastMoveTrackTime = now
                    }
                }
            }
        }

        if (ev?.action == MotionEvent.ACTION_DOWN && adminOverlay == null) {
            val cornerPx = (CORNER_SIZE_DP * resources.displayMetrics.density).toInt()
            val now = System.currentTimeMillis()
            val isTopLeft = ev.x < cornerPx && ev.y < cornerPx
            val isBottomRight = ev.x > (root.width - cornerPx) && ev.y > (root.height - cornerPx)

            when (gesturePhase) {
                0 -> { // Venter på 2x top-left
                    if (isTopLeft) {
                        if (now - lastTapTime < TAP_INTERVAL_MS) {
                            topLeftTapCount++
                        } else {
                            topLeftTapCount = 1
                        }
                        lastTapTime = now
                        if (topLeftTapCount >= 2) {
                            gesturePhase = 1
                            bottomRightTapCount = 0
                            lastTapTime = now
                            // Timeout: reset etter 3 sek
                            handler.postDelayed({ resetGesture() }, GESTURE_TIMEOUT_MS)
                        }
                    } else if (!isBottomRight) {
                        resetGesture()
                    }
                }
                1 -> { // Venter på 2x bottom-right
                    if (isBottomRight) {
                        if (now - lastTapTime < TAP_INTERVAL_MS) {
                            bottomRightTapCount++
                        } else {
                            bottomRightTapCount = 1
                        }
                        lastTapTime = now
                        if (bottomRightTapCount >= 2) {
                            resetGesture()
                            showPinDialog()
                        }
                    } else if (!isTopLeft) {
                        resetGesture()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetGesture() {
        gesturePhase = 0
        topLeftTapCount = 0
        bottomRightTapCount = 0
    }

    // ==================== PIN Dialog ====================

    private fun showPinDialog() {
        Log.i(TAG, "Viser PIN-dialog")
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            isFocusable = true
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(64, 48, 64, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Admin PIN"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })

        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
            textSize = 28f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
        }
        card.addView(pinInput, LinearLayout.LayoutParams(
            400, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val errorLabel = TextView(this).apply {
            setTextColor(0xFFFF4444.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        card.addView(errorLabel)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val cancelBtn = Button(this).apply {
            text = "Avbryt"
            textSize = 18f
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                hideKeyboard()
                root.removeView(overlay)
                adminOverlay = null
                hideSystemUI()
            }
        }
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(
            200, 64
        ).apply { rightMargin = 16 })

        val okBtn = Button(this).apply {
            text = "OK"
            textSize = 18f
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                hideKeyboard()
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val correctPin = prefs.getString("admin_pin", DEFAULT_PIN)
                if (pinInput.text.toString() == correctPin) {
                    root.removeView(overlay)
                    adminOverlay = null
                    showAdminMenu()
                } else {
                    errorLabel.text = "Feil PIN"
                    errorLabel.visibility = View.VISIBLE
                    pinInput.text.clear()
                }
            }
        }
        buttonRow.addView(okBtn, LinearLayout.LayoutParams(200, 64))

        card.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        // Enter-tast submitter PIN
        pinInput.setOnEditorActionListener { _, _, _ ->
            okBtn.performClick()
            true
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        overlay.addView(card, cardParams)

        adminOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // PIN er eneste input — autofokus + tastatur opp
        pinInput.requestFocus()
        handler.postDelayed({
            pinInput.requestFocus()
            pinInput.dispatchWindowFocusChanged(true)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
        }, 400)
    }

    // ==================== Admin Menu ====================

    private fun showAdminMenu(activeTab: String? = null) {
        Log.i(TAG, "Åpner admin-meny")
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 32, 32)
        }

        // Venstre: meny-knapper
        val menuColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 32, 0)
        }

        val menuTitle = TextView(this).apply {
            text = "ShortShift Admin"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        menuColumn.addView(menuTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 32 })

        // Høyre: innholdsområde
        val contentArea = FrameLayout(this).apply {
            setBackgroundColor(0xFF111111.toInt())
            setPadding(32, 32, 32, 32)
        }

        fun addMenuButton(label: String, onClick: () -> Unit) {
            val btn = Button(this).apply {
                text = label
                textSize = 18f
                setBackgroundColor(0xFF333333.toInt())
                setTextColor(Color.WHITE)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(32, 0, 32, 0)
                setOnClickListener { onClick() }
            }
            menuColumn.addView(btn, LinearLayout.LayoutParams(
                320, 64
            ).apply { bottomMargin = 12 })
        }

        addMenuButton("Endre WiFi") { hideKeyboard(); showWifiPicker(contentArea) }
        addMenuButton("Endre URL") { hideKeyboard(); showUrlEditor(contentArea) }
        addMenuButton("Enhetsstatus") { hideKeyboard(); showDeviceStatus(contentArea) }
        addMenuButton("Start på nytt") {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val cn = ComponentName(this, DeviceOwnerReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                Log.i(TAG, "Reboot fra admin-meny")
                dpm.reboot(cn)
            }
        }
        addMenuButton("Gå til Android") {
            Log.i(TAG, "Avslutter kiosk-modus — går til Android")
            hideKeyboard()
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val cn = ComponentName(this, DeviceOwnerReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                try { dpm.setLockTaskPackages(cn, arrayOf(packageName, "com.android.settings")) } catch (_: Exception) {}
                try { dpm.setStatusBarDisabled(cn, false) } catch (_: Exception) {}
            }
            try { stopLockTask() } catch (_: Exception) {}
            // Start system-launcher (startsiden)
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
            finish()
        }
        addMenuButton("Tilbake") {
            hideKeyboard()
            root.removeView(overlay)
            adminOverlay = null
            hideSystemUI()
        }

        mainLayout.addView(menuColumn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        mainLayout.addView(contentArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
        ))

        overlay.addView(mainLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        adminOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Vis aktiv fane
        when (activeTab) {
            "wifi" -> showWifiPicker(contentArea)
            else -> showDeviceStatus(contentArea)
        }
    }

    // ==================== WiFi Picker ====================

    @SuppressLint("SetTextI18n")
    private fun showWifiPicker(contentArea: FrameLayout) {
        contentArea.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Velg WiFi-nettverk"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        layout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        val statusText = TextView(this).apply {
            text = "Søker etter nettverk..."
            textSize = 16f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(statusText)

        val networkList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(this).apply {
            addView(networkList)
        }
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        contentArea.addView(layout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // WiFi-skanning
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val results = wifiManager.scanResults
                    .filter { it.SSID.isNotBlank() }
                    .distinctBy { it.SSID }
                    .sortedByDescending { it.level }

                networkList.removeAllViews()
                statusText.text = "${results.size} nettverk funnet"

                for (result in results) {
                    val row = createNetworkRow(result) {
                        try { unregisterReceiver(this) } catch (_: Exception) {}
                        showWifiPassword(contentArea, result.SSID, wifiManager)
                    }
                    networkList.addView(row)
                }
            }
        }

        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        wifiManager.startScan()
    }

    private fun createNetworkRow(result: ScanResult, onClick: () -> Unit): LinearLayout {
        val signalLevel = WifiManager.calculateSignalLevel(result.level, 4)
        val bars = when (signalLevel) {
            3 -> "▂▄▆█"
            2 -> "▂▄▆ "
            1 -> "▂▄  "
            else -> "▂   "
        }
        val isSecure = result.capabilities.contains("WPA") || result.capabilities.contains("WEP")

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 20, 24, 20)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundColor(0xFF222222.toInt())
            setOnClickListener { onClick() }

            addView(TextView(this@KioskActivity).apply {
                text = bars
                textSize = 16f
                setTextColor(0xFF4CAF50.toInt())
                typeface = Typeface.MONOSPACE
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 16 })

            addView(TextView(this@KioskActivity).apply {
                text = result.SSID
                textSize = 18f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (isSecure) {
                addView(TextView(this@KioskActivity).apply {
                    text = "🔒"
                    textSize = 16f
                })
            }
        }.also {
            // Margin mellom rader
            (it.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 4
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showWifiPassword(contentArea: FrameLayout, ssid: String, wifiManager: WifiManager) {
        contentArea.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "Koble til $ssid"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        layout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val passwordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val passwordInput = EditText(this).apply {
            hint = "Passord"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 20f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(32, 24, 32, 24)
        }
        passwordRow.addView(passwordInput, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))

        val showPassBtn = Button(this).apply {
            text = "Vis"
            textSize = 14f
            setBackgroundColor(0xFF555555.toInt())
            setTextColor(Color.WHITE)
            setPadding(24, 0, 24, 0)
            var showing = false
            setOnClickListener {
                showing = !showing
                if (showing) {
                    passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    text = "Skjul"
                } else {
                    passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    text = "Vis"
                }
                passwordInput.setTextColor(Color.WHITE)
                passwordInput.typeface = Typeface.DEFAULT
                passwordInput.setSelection(passwordInput.text.length)
            }
        }
        passwordRow.addView(showPassBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
        ).apply { leftMargin = 8 })

        layout.addView(passwordRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val statusLabel = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        layout.addView(statusLabel)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val connectBtn = Button(this).apply {
            text = "Koble til"
            textSize = 18f
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(Color.WHITE)
        }

        connectBtn.setOnClickListener {
            val password = passwordInput.text.toString()
            if (password.isEmpty()) {
                statusLabel.text = "Skriv inn passord"
                statusLabel.setTextColor(0xFFFF4444.toInt())
                statusLabel.visibility = View.VISIBLE
                return@setOnClickListener
            }
            connectBtn.isEnabled = false
            statusLabel.text = "Kobler til..."
            statusLabel.setTextColor(Color.WHITE)
            statusLabel.visibility = View.VISIBLE

            // Skjul tastatur
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(passwordInput.windowToken, 0)

            connectToWifi(ssid, password, wifiManager, statusLabel) { success ->
                if (success) {
                    statusLabel.text = "Tilkoblet $ssid!"
                    statusLabel.setTextColor(0xFF4CAF50.toInt())
                    handler.postDelayed({
                        // Gå tilbake til admin-menyen
                        adminOverlay?.let {
                            root.removeView(it)
                            adminOverlay = null
                        }
                        showAdminMenu()
                    }, 1500)
                } else {
                    statusLabel.text = "Tilkobling feilet — sjekk passord"
                    statusLabel.setTextColor(0xFFFF4444.toInt())
                    connectBtn.isEnabled = true
                }
            }
        }

        buttonRow.addView(connectBtn, LinearLayout.LayoutParams(250, 64))
        layout.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        contentArea.addView(layout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        passwordInput.requestFocus()
        handler.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    @Suppress("DEPRECATION")
    private fun connectToWifi(
        ssid: String,
        password: String,
        wifiManager: WifiManager,
        statusLabel: TextView,
        callback: (Boolean) -> Unit
    ) {
        Thread {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            val netId = wifiManager.addNetwork(config)
            if (netId == -1) {
                handler.post { callback(false) }
                return@Thread
            }
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()

            // Vent på tilkobling
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            var connected = false
            val latch = Object()

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connected = true
                    synchronized(latch) { latch.notify() }
                }
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, networkCallback)

            synchronized(latch) {
                try { latch.wait(30_000) } catch (_: InterruptedException) {}
            }
            cm.unregisterNetworkCallback(networkCallback)
            handler.post { callback(connected) }
        }.start()
    }

    // ==================== URL Editor ====================

    @SuppressLint("SetTextI18n")
    private fun showUrlEditor(contentArea: FrameLayout) {
        contentArea.removeAllViews()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val title = TextView(this).apply {
            text = "Endre start-URL"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        layout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val currentUrl = prefs.getString("showroom_url", "") ?: ""

        val urlInput = EditText(this).apply {
            setText(currentUrl)
            hint = "https://..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF666666.toInt())
            setBackgroundColor(0xFF333333.toInt())
            setPadding(32, 24, 32, 24)
            setSelectAllOnFocus(true)
        }
        layout.addView(urlInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val statusLabel = TextView(this).apply {
            textSize = 16f
            visibility = View.GONE
        }
        layout.addView(statusLabel)

        val saveBtn = Button(this).apply {
            text = "Lagre og last inn"
            textSize = 18f
            setBackgroundColor(0xFF4CAF50.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener {
                val newUrl = urlInput.text.toString().trim()
                if (newUrl.isBlank() || !newUrl.startsWith("http")) {
                    statusLabel.text = "Ugyldig URL"
                    statusLabel.setTextColor(0xFFFF4444.toInt())
                    statusLabel.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                // Gjem tastatur og lagre ny URL
                hideKeyboard()
                prefs.edit().putString("showroom_url", newUrl).apply()
                statusLabel.text = "Lagret! Laster $newUrl..."
                statusLabel.setTextColor(0xFF4CAF50.toInt())
                statusLabel.visibility = View.VISIBLE

                // Lukk admin og last ny URL
                handler.postDelayed({
                    adminOverlay?.let {
                        root.removeView(it)
                        adminOverlay = null
                    }
                    hideSystemUI()
                    webView.loadUrl(newUrl)
                }, 1000)
            }
        }
        layout.addView(saveBtn, LinearLayout.LayoutParams(
            300, 64
        ).apply { topMargin = 8 })

        contentArea.addView(layout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Ikke auto-fokuser — tastatur vises kun når bruker trykker i feltet
    }

    // ==================== Device Status ====================

    @SuppressLint("SetTextI18n")
    private fun showDeviceStatus(contentArea: FrameLayout) {
        contentArea.removeAllViews()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo

        val status = buildString {
            appendLine("Enhetsstatus")
            appendLine("─────────────────────────")
            appendLine()
            appendLine("Device ID:    ${android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)}")
            appendLine("Screen ID:    ${prefs.getString("screen_id", "—")}")
            appendLine("Forhandler:   ${prefs.getString("dealer_name", "—")}")
            appendLine()
            appendLine("Nåværende URL:")
            appendLine("  ${webView.url ?: "—"}")
            appendLine()
            appendLine("Start-URL:")
            appendLine("  ${prefs.getString("showroom_url", "—")}")
            appendLine()
            appendLine("WiFi SSID:    ${wifiInfo?.ssid?.replace("\"", "") ?: "—"}")
            appendLine("WiFi signal:  ${wifiInfo?.rssi ?: "—"} dBm")
            appendLine("IP:           ${android.text.format.Formatter.formatIpAddress(wifiInfo?.ipAddress ?: 0)}")
            appendLine()
            appendLine("App-versjon:  ${BuildConfig.VERSION_NAME}")
            appendLine("Android:      ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Modell:       ${Build.MODEL}")
        }

        val textView = TextView(this).apply {
            text = status
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setPadding(16, 16, 16, 16)
        }

        contentArea.addView(textView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    // ==================== Branded Error Overlay ====================

    @SuppressLint("SetTextI18n")
    private fun showErrorOverlay() {
        if (errorOverlay != null) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0A0A.toInt())
            isClickable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Logo/tittel
        val logo = TextView(this).apply {
            text = "ShortShift"
            textSize = 36f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        content.addView(logo, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 64 })

        val statusText = TextView(this).apply {
            text = "Kobler til nettverk..."
            textSize = 22f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        val countdownText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(countdownText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 48 })

        val wifiBtn = Button(this).apply {
            text = "Koble til WiFi"
            textSize = 18f
            setBackgroundColor(0xFF2196F3.toInt())
            setTextColor(Color.WHITE)
            setPadding(48, 24, 48, 24)
            setOnClickListener {
                dismissErrorOverlay()
                showAdminMenu("wifi")
            }
        }
        content.addView(wifiBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        overlay.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })

        errorOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Nedtelling og auto-retry
        startRetryCountdown(countdownText, 10)
    }

    private fun startRetryCountdown(countdownText: TextView, seconds: Int) {
        var remaining = seconds
        retryCountdown?.let { handler.removeCallbacks(it) }

        val tick = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    // Behold overlayen mens vi prøver — fjernes i onPageFinished ved suksess
                    isShowingError = false
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val url = prefs.getString("showroom_url", null)
                    if (url != null) webView.loadUrl(url)
                    // Start ny nedtelling i tilfelle retry også feiler
                    remaining = 10
                    handler.postDelayed(this, 1000)
                    return
                }
                countdownText.text = "Prøver igjen om $remaining sekunder..."
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
        retryCountdown = tick
        tick.run()
    }

    private fun dismissErrorOverlay() {
        isShowingError = false
        errorOverlay?.let {
            root.removeView(it)
            errorOverlay = null
        }
        retryCountdown?.let { handler.removeCallbacks(it) }
        retryCountdown = null
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
            ?: imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ==================== Kiosk Lockdown ====================

    private fun enableKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, DeviceOwnerReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(componentName, arrayOf(packageName))

            try { dpm.setKeyguardDisabled(componentName, true) } catch (_: Exception) {}
            try { dpm.setStatusBarDisabled(componentName, true) } catch (_: Exception) {}


            DeviceOwnerReceiver.enforceInputMethod(this, dpm, componentName)

            Log.i(TAG, "Device owner lockdown aktivert")
        }

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
            currentWebViewUrl = url
            if (!isShowingError && url != null && url != "about:blank") dismissErrorOverlay()
            Log.i(TAG, "Page finished: $url")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                progressBar.visibility = View.GONE
                isShowingError = true
                view?.loadUrl("about:blank")
                showErrorOverlay()
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            return false
        }
    }

    // ==================== Key Blocking ====================

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (adminOverlay != null) {
            root.removeView(adminOverlay)
            adminOverlay = null
            hideSystemUI()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
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
        if (hasFocus && adminOverlay == null) hideSystemUI()
    }

    override fun onDestroy() {
        if (::commandChannel.isInitialized) commandChannel.disconnect()
        webView.destroy()
        super.onDestroy()
    }
}
