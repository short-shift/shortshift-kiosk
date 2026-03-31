package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("MissingPermission")
class ProvisioningActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProvisioningActivity"
        private const val WIFI_SCAN_INTERVAL_MS = 10_000L
        private const val WIFI_CONNECT_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adminEscape by lazy { AdminEscape(this) }
    private lateinit var wifiManager: WifiManager

    // Views
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var networkList: LinearLayout
    private lateinit var networkScroll: ScrollView
    private lateinit var passwordLayout: LinearLayout
    private lateinit var selectedNetworkText: TextView
    private lateinit var passwordInput: EditText
    private lateinit var connectButton: Button
    private lateinit var backButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var selectedSsid: String? = null
    private var isConnecting = false

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                displayNetworks()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fjern eventuelle restriksjoner fra tidligere versjon
        clearLegacyRestrictions()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Sjekk om allerede provisionert → gå rett til kiosk
        val prefs = getSharedPreferences("shortshift_config", Context.MODE_PRIVATE)
        if (prefs.getBoolean("provisioned", false)) {
            val url = prefs.getString("showroom_url", null)
            if (url != null) {
                val kioskIntent = Intent(this, KioskActivity::class.java).apply {
                    putExtra(KioskActivity.EXTRA_URL, url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(kioskIntent)
                finish()
                return
            }
        }

        // Sjekk om WiFi allerede er tilkoblet → gå til provisioning-kode
        if (isConnectedToWifi()) {
            launchProvisioningCode()
            return
        }

        setContentView(R.layout.activity_provisioning)
        hideSystemUI()

        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        networkList = findViewById(R.id.networkList)
        networkScroll = findViewById(R.id.networkScroll)
        passwordLayout = findViewById(R.id.passwordLayout)
        selectedNetworkText = findViewById(R.id.selectedNetworkText)
        passwordInput = findViewById(R.id.passwordInput)
        connectButton = findViewById(R.id.connectButton)
        backButton = findViewById(R.id.backButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        connectButton.setOnClickListener { onConnectClicked() }
        backButton.setOnClickListener { showNetworkList() }

        // Slå på WiFi hvis av
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
        }

        showNetworkList()
        startWifiScan()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (adminEscape.handleTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(wifiScanReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ==================== WiFi-skanning ====================

    private fun startWifiScan() {
        wifiManager.startScan()
        handler.postDelayed({ startWifiScan() }, WIFI_SCAN_INTERVAL_MS)
    }

    @SuppressLint("SetTextI18n")
    private fun displayNetworks() {
        val results = wifiManager.scanResults
            .filter { it.SSID.isNotBlank() }
            .distinctBy { it.SSID }
            .sortedByDescending { it.level }

        networkList.removeAllViews()

        if (results.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Søker etter nettverk..."
                setTextColor(0xFF888888.toInt())
                textSize = 20f
                setPadding(0, 48, 0, 48)
            }
            networkList.addView(empty)
            return
        }

        for (result in results) {
            val row = createNetworkRow(result)
            networkList.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createNetworkRow(result: ScanResult): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 28, 32, 28)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onNetworkSelected(result.SSID) }
        }

        // Signal ikon (enkel tekst)
        val signalLevel = WifiManager.calculateSignalLevel(result.level, 4)
        val signalText = TextView(this).apply {
            text = when (signalLevel) {
                3 -> "▂▄▆█"
                2 -> "▂▄▆ "
                1 -> "▂▄  "
                else -> "▂   "
            }
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 24, 0)
        }

        val nameText = TextView(this).apply {
            text = result.SSID
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
        }

        // Lås-ikon for sikrede nettverk
        val secureText = TextView(this).apply {
            text = if (result.capabilities.contains("WPA") || result.capabilities.contains("WEP")) "🔒" else ""
            textSize = 18f
            setPadding(16, 0, 0, 0)
        }

        row.addView(signalText)
        row.addView(nameText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(secureText)

        return row
    }

    // ==================== Nettverksvalg ====================

    private fun onNetworkSelected(ssid: String) {
        selectedSsid = ssid
        showPasswordInput(ssid)
    }

    @SuppressLint("SetTextI18n")
    private fun showPasswordInput(ssid: String) {
        networkScroll.visibility = View.GONE
        passwordLayout.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE

        titleText.text = "Koble til WiFi"
        subtitleText.text = ssid
        selectedNetworkText.text = ssid
        passwordInput.text.clear()
        passwordInput.requestFocus()

        // Vis tastatur
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        handler.postDelayed({
            imm.showSoftInput(passwordInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    @SuppressLint("SetTextI18n")
    private fun showNetworkList() {
        selectedSsid = null
        networkScroll.visibility = View.VISIBLE
        passwordLayout.visibility = View.GONE
        progressBar.visibility = View.GONE
        statusText.visibility = View.GONE

        titleText.text = "Velg WiFi-nettverk"
        subtitleText.text = "Koble skjermen til internett"

        // Skjul tastatur
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(passwordInput.windowToken, 0)

        displayNetworks()
    }

    // ==================== WiFi-tilkobling ====================

    @SuppressLint("SetTextI18n")
    private fun onConnectClicked() {
        val ssid = selectedSsid ?: return
        val password = passwordInput.text.toString()

        if (password.isEmpty()) {
            statusText.text = "Skriv inn passord"
            statusText.setTextColor(0xFFFF4444.toInt())
            statusText.visibility = View.VISIBLE
            return
        }

        isConnecting = true
        connectButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.text = "Kobler til $ssid..."
        statusText.setTextColor(0xFFFFFFFF.toInt())
        statusText.visibility = View.VISIBLE

        // Skjul tastatur
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(passwordInput.windowToken, 0)

        connectToWifi(ssid, password)
    }

    private fun connectToWifi(ssid: String, password: String) {
        @Suppress("DEPRECATION")
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            onWifiFailed("Kunne ikke legge til nettverk")
            return
        }

        wifiManager.disconnect()
        wifiManager.enableNetwork(netId, true)
        wifiManager.reconnect()

        // Vent på tilkobling
        waitForConnectivity()
    }

    private fun waitForConnectivity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var responded = false

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!responded) {
                    responded = true
                    cm.unregisterNetworkCallback(this)
                    handler.post { onWifiConnected() }
                }
            }
        }

        cm.registerNetworkCallback(request, callback)

        // Timeout
        handler.postDelayed({
            if (!responded) {
                responded = true
                try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                onWifiFailed("Tilkobling tidsavbrutt — sjekk passord")
            }
        }, WIFI_CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("SetTextI18n")
    private fun onWifiConnected() {
        Log.i(TAG, "WiFi tilkoblet!")
        statusText.text = "Tilkoblet!"
        statusText.setTextColor(0xFF4CAF50.toInt())
        progressBar.visibility = View.GONE

        // Gå til provisioning-kode
        handler.postDelayed({ launchProvisioningCode() }, 1500)
    }

    @SuppressLint("SetTextI18n")
    private fun onWifiFailed(message: String) {
        Log.e(TAG, "WiFi feilet: $message")
        isConnecting = false
        connectButton.isEnabled = true
        progressBar.visibility = View.GONE
        statusText.text = message
        statusText.setTextColor(0xFFFF4444.toInt())
        statusText.visibility = View.VISIBLE
    }

    // ==================== Fully Kiosk ====================

    private fun launchProvisioningCode() {
        val intent = Intent(this, ProvisioningCodeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    // ==================== Hjelpefunksjoner ====================

    private fun isConnectedToWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun clearLegacyRestrictions() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = ComponentName(this, DeviceOwnerReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                val restrictions = listOf(
                    android.os.UserManager.DISALLOW_FACTORY_RESET,
                    android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
                    android.os.UserManager.DISALLOW_USB_FILE_TRANSFER,
                    android.os.UserManager.DISALLOW_SAFE_BOOT
                )
                for (r in restrictions) {
                    try { dpm.clearUserRestriction(componentName, r) } catch (_: Exception) {}
                }
                try { dpm.setStatusBarDisabled(componentName, false) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun hideSystemUI() {
        // Skjul kun statusbar, IKKE navigasjon — ellers blokkeres tastaturet
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }
}
