package com.shortshift.kiosk.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val appVersion: String
)

data class ProvisionConfig(
    val fullyStartUrl: String,
    val fullySettings: Map<String, Any>,
    val heartbeatIntervalSec: Int
)

data class ProvisionResult(
    val screenId: String,
    val apiToken: String,
    val dealerName: String,
    val config: ProvisionConfig
)

data class HeartbeatData(
    val hardwareId: String,
    val wifiSsid: String?,
    val wifiSignalDbm: Int?,
    val screenOn: Boolean,
    val appVersion: String,
    val fullyVersion: String?,
    val androidVersion: String,
    val uptimeSeconds: Long,
    val freeMemoryMb: Long,
    val currentUrl: String?
)

data class HeartbeatResult(
    val success: Boolean,
    val configChanged: Boolean
)

data class ConfigResult(
    val config: ProvisionConfig
)

class ProvisionApiClient {

    companion object {
        private const val TAG = "ProvisionApiClient"
        private const val BASE_URL = "https://shortshift-provisioning.netlify.app/.netlify/functions"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun provision(setupCode: String, hardwareId: String, deviceInfo: DeviceInfo): ProvisionResult {
        val body = JSONObject().apply {
            put("setup_code", setupCode)
            put("hardware_id", hardwareId)
            put("device_info", JSONObject().apply {
                put("model", deviceInfo.model)
                put("android_version", deviceInfo.androidVersion)
                put("app_version", deviceInfo.appVersion)
            })
        }

        val request = Request.Builder()
            .url("$BASE_URL/provision-pair")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        Log.d(TAG, "Sender provisioning-forespørsel for kode: $setupCode")

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("Tom response fra server")

            if (!response.isSuccessful) {
                val errorMsg = try {
                    JSONObject(responseBody).optString("error", "Ukjent feil")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                throw IOException("Provisioning feilet: $errorMsg")
            }

            val json = JSONObject(responseBody)
            val configJson = json.getJSONObject("config")

            val fullySettings = mutableMapOf<String, Any>()
            val settingsJson = configJson.optJSONObject("fully_settings")
            if (settingsJson != null) {
                for (key in settingsJson.keys()) {
                    fullySettings[key] = settingsJson.get(key)
                }
            }

            val config = ProvisionConfig(
                fullyStartUrl = configJson.getString("fully_start_url"),
                fullySettings = fullySettings,
                heartbeatIntervalSec = configJson.optInt("heartbeat_interval_sec", 900)
            )

            return ProvisionResult(
                screenId = json.getString("screen_id"),
                apiToken = json.getString("api_token"),
                dealerName = json.getString("dealer_name"),
                config = config
            )
        }
    }

    @Throws(IOException::class)
    fun sendHeartbeat(token: String, hardwareId: String, heartbeatData: HeartbeatData): HeartbeatResult {
        val body = JSONObject().apply {
            put("hardware_id", hardwareId)
            put("wifi_ssid", heartbeatData.wifiSsid)
            put("wifi_signal_dbm", heartbeatData.wifiSignalDbm)
            put("screen_on", heartbeatData.screenOn)
            put("app_version", heartbeatData.appVersion)
            put("fully_version", heartbeatData.fullyVersion)
            put("android_version", heartbeatData.androidVersion)
            put("uptime_seconds", heartbeatData.uptimeSeconds)
            put("free_memory_mb", heartbeatData.freeMemoryMb)
            put("current_url", heartbeatData.currentUrl)
        }

        val request = Request.Builder()
            .url("$BASE_URL/provision-heartbeat")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("Tom response fra server")

            if (!response.isSuccessful) {
                throw IOException("Heartbeat feilet: HTTP ${response.code}")
            }

            val json = JSONObject(responseBody)
            return HeartbeatResult(
                success = json.optBoolean("success", true),
                configChanged = json.optBoolean("config_changed", false)
            )
        }
    }

    @Throws(IOException::class)
    fun getConfig(token: String): ConfigResult {
        val request = Request.Builder()
            .url("$BASE_URL/provision-config")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw IOException("Tom response fra server")

            if (!response.isSuccessful) {
                throw IOException("Config-henting feilet: HTTP ${response.code}")
            }

            val json = JSONObject(responseBody)
            val configJson = json.getJSONObject("config")

            val fullySettings = mutableMapOf<String, Any>()
            val settingsJson = configJson.optJSONObject("fully_settings")
            if (settingsJson != null) {
                for (key in settingsJson.keys()) {
                    fullySettings[key] = settingsJson.get(key)
                }
            }

            val config = ProvisionConfig(
                fullyStartUrl = configJson.getString("fully_start_url"),
                fullySettings = fullySettings,
                heartbeatIntervalSec = configJson.optInt("heartbeat_interval_sec", 900)
            )

            return ConfigResult(config = config)
        }
    }
}
