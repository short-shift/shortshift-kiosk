package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ShowroomNexus — koblingspunktet mellom fysisk showroom og digital opplevelse.
 *
 * Injiseres som `window.fully` i WebView for bakoverkompatibilitet med
 * finn-bruktbil og eksisterende showroom-nettsider. Leverer lokasjon,
 * touch-statistikk, enhets-ID og konfigurasjon til web-laget.
 */
@SuppressLint("MissingPermission")
class ShowroomNexus(
    private val context: Context,
    private val webView: WebView,
    private val onStartUrlChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "Nexus"
        private const val PREFS_NAME = "shortshift_config"
        private const val KEY_URL = "showroom_url"
        private const val PREFS_NEXUS = "nexus_settings"
        private const val PREFS_STATS = "touch_stats"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val nexusPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NEXUS, Context.MODE_PRIVATE)

    private val statsPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)

    @Volatile
    private var lastLocation: Location? = null

    init {
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lastLocation = location
                    Log.i(TAG, "Lokasjon oppdatert: ${location.latitude}, ${location.longitude}")
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 100f, listener)
                lastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 100f, listener)
                if (lastLocation == null) {
                    lastLocation = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
            }
            Log.i(TAG, "Lokasjon startet, cached: ${lastLocation != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Kunne ikke starte lokasjon: ${e.message}")
        }
    }

    // ==================== Identifikasjon ====================

    @JavascriptInterface
    fun getDeviceId(): String {
        val id = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )
        return id
    }

    // ==================== Start URL ====================

    @JavascriptInterface
    fun setStartUrl(url: String) {
        Log.i(TAG, "setStartUrl($url)")
        configPrefs.edit().putString(KEY_URL, url).apply()
        webView.post { onStartUrlChanged(url) }
    }

    @JavascriptInterface
    fun getStartUrl(): String {
        return configPrefs.getString(KEY_URL, "") ?: ""
    }

    @JavascriptInterface
    fun loadStartUrl() {
        val url = getStartUrl()
        Log.i(TAG, "loadStartUrl() → $url")
        if (url.isNotBlank()) {
            webView.post { webView.loadUrl(url) }
        }
    }

    // ==================== Skjerm-status ====================

    @JavascriptInterface
    fun getScreenOn(): Boolean = true

    @JavascriptInterface
    fun turnScreenOff() {
        Log.i(TAG, "turnScreenOff() — ignorert")
    }

    // ==================== Innstillinger ====================

    @JavascriptInterface
    fun getBooleanSetting(key: String): String {
        return nexusPrefs.getString("bool_$key", "false") ?: "false"
    }

    @JavascriptInterface
    fun getStringSetting(key: String): String {
        return nexusPrefs.getString("str_$key", "") ?: ""
    }

    @JavascriptInterface
    fun setStringSetting(key: String, value: String) {
        nexusPrefs.edit().putString("str_$key", value).apply()
    }

    // ==================== Touch-tracking ====================

    fun recordTouch(isClick: Boolean) {
        val today = DATE_FORMAT.format(Date())
        if (isClick) {
            val clicks = statsPrefs.getInt("clicks_$today", 0) + 1
            statsPrefs.edit().putInt("clicks_$today", clicks).apply()
        } else {
            val moves = statsPrefs.getInt("moves_$today", 0) + 1
            statsPrefs.edit().putInt("moves_$today", moves).apply()
        }
    }

    // ==================== Statistikk ====================

    /**
     * Returnerer touch-statistikk som CSV.
     * Bakoverkompatibelt format: kolonne 0=dato, 8=bevegelser, 11=klikk.
     */
    @JavascriptInterface
    fun loadStatsCSV(): String {
        val sb = StringBuilder()
        sb.appendLine("date;col1;col2;col3;col4;col5;col6;col7;movements;col9;col10;clicks")

        val cal = java.util.Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val date = DATE_FORMAT.format(cal.time)
            val clicks = statsPrefs.getInt("clicks_$date", 0)
            val moves = statsPrefs.getInt("moves_$date", 0)
            sb.appendLine("$date;0;0;0;0;0;0;0;$moves;0;0;$clicks")
        }
        sb.appendLine()
        return sb.toString()
    }

    // ==================== Lokasjon ====================

    @JavascriptInterface
    fun getLocation(): String {
        val loc = lastLocation ?: return "{}"
        return JSONObject().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("altitude", loc.altitude)
            put("accuracy", loc.accuracy.toDouble())
            put("provider", loc.provider ?: "unknown")
        }.toString()
    }
}
