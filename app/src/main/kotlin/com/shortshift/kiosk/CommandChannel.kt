package com.shortshift.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Realtime-kanal til Supabase for fjernkontroll av skjermen.
 * Lytter på INSERT i device_commands-tabellen filtrert på screen_id.
 * Når backoffice sender en kommando, utføres den umiddelbart.
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
        private const val SUPABASE_URL = "mllutwgdiddbskbtiukv.supabase.co"
        private const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1sbHV0d2dkaWRkYnNrYnRpdWt2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEyMzg4NTcsImV4cCI6MjA4NjU5ODg1N30.F2rY0Oi6TmyDrZ2atRys8yGxUjZw5enf-r1BUtF0fcI"
        private const val PREFS_NAME = "shortshift_config"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Ingen timeout for WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun connect() {
        val screenId = prefs.getString("screen_id", null)
        if (screenId.isNullOrBlank()) {
            Log.w(TAG, "Ingen screen_id, kan ikke koble til")
            return
        }

        val url = "wss://$SUPABASE_URL/realtime/v1/websocket?apikey=$SUPABASE_ANON_KEY&vsn=1.0.0"

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket tilkoblet")

                // Join channel: device_commands filtrert på screen_id
                val joinMsg = JSONObject().apply {
                    put("topic", "realtime:public:device_commands:screen_id=eq.$screenId")
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("broadcast", JSONObject().apply {
                                put("self", false)
                            })
                            put("postgres_changes", org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "INSERT")
                                    put("schema", "public")
                                    put("table", "device_commands")
                                    put("filter", "screen_id=eq.$screenId")
                                })
                            })
                        })
                    })
                    put("ref", "1")
                }
                webSocket.send(joinMsg.toString())
                Log.i(TAG, "Abonnerer på kommandoer for screen: $screenId")

                // Start heartbeat (Supabase Realtime krever phoenix heartbeat)
                startPhoenixHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val event = msg.optString("event", "")

                    when (event) {
                        "postgres_changes" -> {
                            val payload = msg.getJSONObject("payload")
                            val data = payload.optJSONObject("data")
                            val record = data?.optJSONObject("record")
                                ?: payload.optJSONObject("record")
                            if (record != null) {
                                handleCommand(record)
                            } else {
                                Log.w(TAG, "Ingen record i payload: $payload")
                            }
                        }
                        "phx_reply" -> {
                            val payload = msg.optJSONObject("payload")
                            val status = payload?.optString("status", "")
                            Log.i(TAG, "Reply: $status")
                        }
                        "phx_error" -> {
                            Log.e(TAG, "Channel error: $text")
                        }
                        else -> {
                            if (event != "phx_reply" && event != "heartbeat") {
                                Log.i(TAG, "Event '$event': ${text.take(200)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Feil ved parsing: ${e.message} — rå: ${text.take(300)}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket feilet: ${t.message}")
                // Reconnect etter 5 sekunder
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.i(TAG, "Forsøker reconnect...")
                    connect()
                }, 5000)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket lukket: $reason")
                // Reconnect
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    connect()
                }, 5000)
            }
        })
    }

    private fun startPhoenixHeartbeat(ws: WebSocket) {
        val heartbeat = JSONObject().apply {
            put("topic", "phoenix")
            put("event", "heartbeat")
            put("payload", JSONObject())
            put("ref", null as Any?)
        }
        Thread {
            while (true) {
                try {
                    Thread.sleep(30_000)
                    ws.send(heartbeat.toString())
                } catch (_: Exception) {
                    break
                }
            }
        }.start()
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
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onSetUrl(payload)
                    }
                    markCommandDone(commandId)
                }
            }
            "reboot" -> {
                markCommandDone(commandId)
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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRefresh()
                }
                markCommandDone(commandId)
            }
            "heartbeat" -> {
                sendStatusToSupabase()
                markCommandDone(commandId)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendStatusToSupabase() {
        val screenId = prefs.getString("screen_id", null) ?: return

        Thread {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                val location = getLocation()
                val currentUrl = getCurrentUrl()

                val body = JSONObject().apply {
                    put("last_seen_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()))
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

                val url = "https://$SUPABASE_URL/rest/v1/screens?id=eq.$screenId"
                val request = Request.Builder()
                    .url(url)
                    .patch(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                client.newCall(request).execute().close()
                Log.i(TAG, "Status sendt til Supabase")
            } catch (e: Exception) {
                Log.e(TAG, "Kunne ikke sende status: ${e.message}")
            }
        }.start()
    }

    private fun markCommandDone(commandId: String) {
        if (commandId.isBlank()) return
        // Oppdater status via Supabase REST API
        Thread {
            try {
                val url = "https://$SUPABASE_URL/rest/v1/device_commands?id=eq.$commandId"
                val body = JSONObject().apply {
                    put("status", "done")
                    put("executed_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date()))
                }
                val request = Request.Builder()
                    .url(url)
                    .patch(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $SUPABASE_ANON_KEY")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                client.newCall(request).execute().close()
                Log.i(TAG, "Kommando $commandId markert som done")
            } catch (e: Exception) {
                Log.e(TAG, "Kunne ikke markere kommando: ${e.message}")
            }
        }.start()
    }

    fun disconnect() {
        webSocket?.close(1000, "App lukkes")
        webSocket = null
    }
}
