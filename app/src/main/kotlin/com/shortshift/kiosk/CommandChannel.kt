package com.shortshift.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Kommunikasjonskanal mellom skjermen og ShortShift sin provisioning-API.
 * Erstatter direkte Supabase-tilkobling med sikker token-basert polling.
 *
 * - Poller commands-pending hvert 30s for ventende kommandoer
 * - Sender heartbeat med full device-status hvert 5. minutt
 * - ACK-er kommandoer via commands-ack
 * - Ingen Supabase-nøkler i appen — alt via Bearer token
 */
class CommandChannel(
    private val context: Context,
    private val onSetUrl: (String) -> Unit,
    private val onRefresh: () -> Unit,
    private val getLocation: () -> Pair<Double, Double>? = { null },
    private val getCurrentUrl: () -> String? = { null }
) {
    companion object {
        private const val TAG = "CommandChannel"
        private const val API_BASE = "https://shortshift-provisioning.netlify.app/.netlify/functions"
        private const val POLL_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 300_000L // 5 minutter
        private const val PREFS_NAME = "shortshift_config"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            Thread { pollCommands() }.start()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            Thread { sendHeartbeat() }.start()
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun connect() {
        val screenId = prefs.getString("screen_id", null)
        val apiToken = prefs.getString("api_token", null)

        if (screenId.isNullOrBlank() || apiToken.isNullOrBlank()) {
            Log.w(TAG, "Mangler screen_id eller api_token, kan ikke starte")
            return
        }

        isRunning = true
        Log.i(TAG, "Starter kommando-polling (${POLL_INTERVAL_MS / 1000}s) og heartbeat (${HEARTBEAT_INTERVAL_MS / 1000}s)")

        // Første heartbeat umiddelbart, deretter periodisk
        Thread { sendHeartbeat() }.start()
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)

        // Første poll etter kort forsinkelse, deretter periodisk
        handler.postDelayed(pollRunnable, 3000)
    }

    fun disconnect() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        Log.i(TAG, "Stoppet polling og heartbeat")
    }

    private fun getApiToken(): String? = prefs.getString("api_token", null)

    private fun pollCommands() {
        val token = getApiToken() ?: return

        try {
            val request = Request.Builder()
                .url("$API_BASE/commands-pending")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (!response.isSuccessful) {
                Log.w(TAG, "Poll feilet: ${response.code}")
                return
            }

            val json = JSONObject(body ?: "{}")
            val commands = json.optJSONArray("commands") ?: return

            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                handleCommand(cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Poll-feil: ${e.message}")
        }
    }

    private fun handleCommand(record: JSONObject) {
        val commandType = record.optString("command_type", "")
        val payload = record.optString("payload", "")
        val commandId = record.optString("id", "")

        Log.i(TAG, "Kommando mottatt: $commandType ($payload)")

        when (commandType) {
            "set_url" -> {
                if (payload.startsWith("http")) {
                    prefs.edit().putString("showroom_url", payload).apply()
                    handler.post { onSetUrl(payload) }
                    acknowledgeCommand(commandId)
                }
            }
            "reboot" -> {
                acknowledgeCommand(commandId)
                try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as DevicePolicyManager
                    val componentName = ComponentName(context, DeviceOwnerReceiver::class.java)
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.reboot(componentName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Reboot feilet: ${e.message}")
                }
            }
            "refresh" -> {
                handler.post { onRefresh() }
                acknowledgeCommand(commandId)
            }
            "heartbeat" -> {
                sendHeartbeat()
                acknowledgeCommand(commandId)
            }
        }
    }

    private fun acknowledgeCommand(commandId: String) {
        if (commandId.isBlank()) return

        val token = getApiToken() ?: return

        Thread {
            try {
                val body = JSONObject().apply {
                    put("command_id", commandId)
                }

                val request = Request.Builder()
                    .url("$API_BASE/commands-ack")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                response.close()

                if (response.isSuccessful) {
                    Log.i(TAG, "Kommando $commandId ACK-et")
                } else {
                    Log.w(TAG, "ACK feilet: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "ACK-feil: ${e.message}")
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sendHeartbeat() {
        val token = getApiToken() ?: return
        val hardwareId = prefs.getString("hardware_id", null)
            ?: android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val location = getLocation()
            val currentUrl = getCurrentUrl()

            val body = JSONObject().apply {
                put("hardware_id", hardwareId)
                put("device_type", "${Build.MODEL} (${Build.BOARD})")
                put("android_version", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                put("app_version", BuildConfig.VERSION_NAME)
                put("wifi_ssid", wifiInfo?.ssid?.replace("\"", "") ?: "")
                put("wifi_signal_dbm", wifiInfo?.rssi ?: 0)
                put("ip_address", android.text.format.Formatter.formatIpAddress(wifiInfo?.ipAddress ?: 0))
                put("mac_address", wifiInfo?.macAddress ?: "")
                put("screen_on", true)
                put("uptime_seconds", SystemClock.elapsedRealtime() / 1000)
                put("free_memory_mb", memInfo.availMem / (1024 * 1024))
                if (currentUrl != null) put("current_url", currentUrl)
                if (location != null) {
                    put("latitude", location.first)
                    put("longitude", location.second)
                }
            }

            val request = Request.Builder()
                .url("$API_BASE/provision-heartbeat")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            response.close()

            if (response.isSuccessful) {
                Log.i(TAG, "Heartbeat sendt via API")

                // Sjekk om heartbeat-respons inneholder ventende kommandoer (piggyback)
                val json = JSONObject(responseBody ?: "{}")
                val commands = json.optJSONArray("commands")
                if (commands != null && commands.length() > 0) {
                    Log.i(TAG, "Piggyback: ${commands.length()} ventende kommandoer i heartbeat-respons")
                    for (i in 0 until commands.length()) {
                        handleCommand(commands.getJSONObject(i))
                    }
                }
            } else {
                Log.w(TAG, "Heartbeat feilet: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat-feil: ${e.message}")
        }
    }
}
