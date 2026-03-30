package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.shortshift.kiosk.api.DeviceInfo
import com.shortshift.kiosk.api.ProvisionApiClient
import com.shortshift.kiosk.api.ProvisionConfig
import com.shortshift.kiosk.api.ProvisionResult
import com.shortshift.kiosk.ble.BleGattServer
import com.shortshift.kiosk.ble.BleProvisioningListener
import com.shortshift.kiosk.config.FullyKioskConfigurator
import com.shortshift.kiosk.heartbeat.HeartbeatWorker
import com.shortshift.kiosk.util.SecureStorage
import com.shortshift.kiosk.wifi.WifiProvisioner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class ProvisioningActivity : AppCompatActivity(), BleProvisioningListener {

    companion object {
        private const val TAG = "ProvisioningActivity"
        private const val LAUNCH_DELAY_MS = 3_000L
        private const val RETRY_DELAY_MS = 10_000L
    }

    private lateinit var statusText: TextView
    private lateinit var deviceSuffixText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var bleServer: BleGattServer
    private lateinit var wifiProvisioner: WifiProvisioner
    private lateinit var apiClient: ProvisionApiClient
    private lateinit var configurator: FullyKioskConfigurator
    private lateinit var storage: SecureStorage

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var pendingWifiSsid: String? = null
    private var pendingWifiPassword: String? = null
    private var pendingSetupCode: String? = null
    private var isProvisioning = false

    private val hardwareId: String
        get() = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fjern eventuelle restriksjoner fra tidligere versjon
        clearLegacyRestrictions()

        storage = SecureStorage(this)
        configurator = FullyKioskConfigurator(this)

        // If already provisioned, launch Fully Kiosk immediately
        if (storage.isProvisioned()) {
            Log.i(TAG, "Allerede provisjonert, starter Fully Kiosk")
            val configJson = storage.getConfig()
            if (configJson != null) {
                try {
                    val config = parseStoredConfig(configJson)
                    configurator.configureAndLaunch(config)
                } catch (e: Exception) {
                    Log.e(TAG, "Feil ved parsing av lagret config: ${e.message}", e)
                }
            }
            finish()
            return
        }

        setContentView(R.layout.activity_provisioning)
        hideSystemUI()

        statusText = findViewById(R.id.statusText)
        deviceSuffixText = findViewById(R.id.deviceSuffixText)
        progressBar = findViewById(R.id.progressBar)

        wifiProvisioner = WifiProvisioner(this)
        apiClient = ProvisionApiClient()

        bleServer = BleGattServer(this)
        bleServer.setListener(this)

        val suffix = bleServer.deviceSuffix
        deviceSuffixText.text = getString(R.string.device_prefix) + suffix

        updateState("ready")
        bleServer.start()

        Log.i(TAG, "Provisioning-aktivitet startet, BLE annonserer som ShortShift-$suffix")
    }

    override fun onDestroy() {
        super.onDestroy()
        bleServer.stop()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    // region BleProvisioningListener

    override fun onDeviceConnected() {
        Log.i(TAG, "Telefon tilkoblet via BLE")
        updateState("connected")
    }

    override fun onBleReady() {
        Log.i(TAG, "BLE advertising OK")
        updateState("ready")
    }

    override fun onBleError(message: String) {
        Log.e(TAG, "BLE-feil: $message")
        handler.post {
            statusText.text = "BLE-feil: $message"
            statusText.setTextColor(getColor(R.color.shortshift_red))
        }
    }

    override fun onDeviceDisconnected() {
        Log.i(TAG, "Telefon frakoblet")
        if (!isProvisioning) {
            updateState("ready")
        }
        // If we have all data, continue provisioning even after disconnect
    }

    override fun onWifiConfigReceived(ssid: String, password: String) {
        Log.i(TAG, "WiFi-konfigurasjon mottatt: $ssid")
        pendingWifiSsid = ssid
        pendingWifiPassword = password

        // If we already have setup code, start provisioning
        if (pendingSetupCode != null) {
            startProvisioning()
        } else {
            updateState("connected")
            bleServer.notifyDeviceStatus("wifi_received")
        }
    }

    override fun onSetupCodeReceived(code: String) {
        Log.i(TAG, "Setup-kode mottatt: $code")
        pendingSetupCode = code

        // If we already have WiFi config, start provisioning
        if (pendingWifiSsid != null && pendingWifiPassword != null) {
            startProvisioning()
        } else {
            updateState("connected")
            bleServer.notifyDeviceStatus("code_received")
        }
    }

    // endregion

    private fun startProvisioning() {
        if (isProvisioning) return
        isProvisioning = true

        val ssid = pendingWifiSsid ?: return
        val password = pendingWifiPassword ?: return
        val setupCode = pendingSetupCode ?: return

        Log.i(TAG, "Starter provisioning-flyt")
        updateState("configuring_wifi")
        bleServer.notifyDeviceStatus("configuring_wifi")

        wifiProvisioner.connectToWifi(ssid, password) { wifiSuccess ->
            if (wifiSuccess) {
                Log.i(TAG, "WiFi tilkoblet, kaller provisioning-API")
                callProvisionApi(setupCode)
            } else {
                Log.e(TAG, "WiFi-tilkobling feilet")
                handleError("WiFi-tilkobling feilet")
            }
        }
    }

    private fun callProvisionApi(setupCode: String) {
        updateState("provisioning")
        bleServer.notifyDeviceStatus("provisioning")

        scope.launch {
            try {
                val deviceInfo = DeviceInfo(
                    model = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = BuildConfig.VERSION_NAME
                )

                val result: ProvisionResult = withContext(Dispatchers.IO) {
                    apiClient.provision(setupCode, hardwareId, deviceInfo)
                }

                Log.i(TAG, "Provisioning OK: screen_id=${result.screenId}, dealer=${result.dealerName}")
                onProvisioningComplete(result)
            } catch (e: Exception) {
                Log.e(TAG, "Provisioning-API feilet: ${e.message}", e)
                handleError(e.message ?: "API-feil")
            }
        }
    }

    private fun onProvisioningComplete(result: ProvisionResult) {
        // Save credentials
        storage.saveApiToken(result.apiToken)
        storage.saveScreenId(result.screenId)
        storage.saveDealerName(result.dealerName)
        storage.saveConfig(configToJson(result.config))
        storage.setProvisioned()

        // Notify phone via BLE
        bleServer.notifyProvisionResult(success = true, dealerName = result.dealerName, error = null)
        bleServer.notifyDeviceStatus("complete")

        // Update UI
        updateState("complete", result.dealerName)

        // Schedule heartbeat
        HeartbeatWorker.schedule(this)

        // Stop BLE (no longer needed)
        bleServer.stopAdvertising()

        // Launch Fully Kiosk after delay
        handler.postDelayed({
            configurator.configureAndLaunch(result.config)
            finish()
        }, LAUNCH_DELAY_MS)
    }

    private fun handleError(message: String) {
        Log.e(TAG, "Provisioning-feil: $message")

        bleServer.notifyProvisionResult(success = false, dealerName = null, error = message)
        bleServer.notifyDeviceStatus("error")
        updateState("error")

        // Reset state and retry
        handler.postDelayed({
            isProvisioning = false
            pendingWifiSsid = null
            pendingWifiPassword = null
            pendingSetupCode = null
            updateState("ready")
        }, RETRY_DELAY_MS)
    }

    private fun updateState(state: String, extra: String? = null) {
        handler.post {
            when (state) {
                "ready" -> {
                    statusText.text = getString(R.string.status_ready)
                    statusText.setTextColor(getColor(R.color.shortshift_white))
                    progressBar.visibility = View.GONE
                }
                "connected" -> {
                    statusText.text = getString(R.string.status_connected)
                    statusText.setTextColor(getColor(R.color.shortshift_white))
                    progressBar.visibility = View.GONE
                }
                "configuring_wifi" -> {
                    statusText.text = getString(R.string.status_wifi)
                    statusText.setTextColor(getColor(R.color.shortshift_white))
                    progressBar.visibility = View.VISIBLE
                }
                "provisioning" -> {
                    statusText.text = getString(R.string.status_provisioning)
                    statusText.setTextColor(getColor(R.color.shortshift_white))
                    progressBar.visibility = View.VISIBLE
                }
                "complete" -> {
                    val displayText = if (extra != null) {
                        "${getString(R.string.status_complete)} $extra"
                    } else {
                        getString(R.string.status_complete)
                    }
                    statusText.text = displayText
                    statusText.setTextColor(getColor(R.color.shortshift_green))
                    progressBar.visibility = View.GONE
                }
                "error" -> {
                    statusText.text = getString(R.string.status_error)
                    statusText.setTextColor(getColor(R.color.shortshift_red))
                    progressBar.visibility = View.GONE
                }
            }
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

    private fun configToJson(config: ProvisionConfig): String {
        val json = org.json.JSONObject().apply {
            put("fully_start_url", config.fullyStartUrl)
            put("heartbeat_interval_sec", config.heartbeatIntervalSec)
            val settingsJson = org.json.JSONObject()
            for ((key, value) in config.fullySettings) {
                settingsJson.put(key, value)
            }
            put("fully_settings", settingsJson)
        }
        return json.toString()
    }

    @SuppressLint("MissingPermission")
    private fun clearLegacyRestrictions() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(this, DeviceOwnerReceiver::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) {
                val restrictions = listOf(
                    android.os.UserManager.DISALLOW_FACTORY_RESET,
                    android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
                    android.os.UserManager.DISALLOW_USB_FILE_TRANSFER,
                    android.os.UserManager.DISALLOW_SAFE_BOOT
                )
                for (restriction in restrictions) {
                    try {
                        dpm.clearUserRestriction(componentName, restriction)
                        Log.i(TAG, "Restriksjon fjernet: $restriction")
                    } catch (e: Exception) {
                        Log.w(TAG, "Kunne ikke fjerne restriksjon $restriction: ${e.message}")
                    }
                }
                // Re-enable status bar
                try { dpm.setStatusBarDisabled(componentName, false) } catch (_: Exception) {}
                Log.i(TAG, "Alle legacy-restriksjoner fjernet")
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearLegacyRestrictions feilet: ${e.message}")
        }
    }

    private fun parseStoredConfig(jsonString: String): ProvisionConfig {
        val json = org.json.JSONObject(jsonString)
        val fullySettings = mutableMapOf<String, Any>()
        val settingsJson = json.optJSONObject("fully_settings")
        if (settingsJson != null) {
            for (key in settingsJson.keys()) {
                fullySettings[key] = settingsJson.get(key)
            }
        }
        return ProvisionConfig(
            fullyStartUrl = json.getString("fully_start_url"),
            fullySettings = fullySettings,
            heartbeatIntervalSec = json.optInt("heartbeat_interval_sec", 900)
        )
    }
}
