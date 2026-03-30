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
 * Emulerer Fully Kiosk Browser sin JavaScript-API (window.fully).
 *
 * finn-bruktbil / all.bruktbil.shop forventer at window.fully finnes med
 * metoder som getScreenOn(), setStartUrl(), getDeviceId() osv.
 * Denne klassen gir WebView et kompatibelt grensesnitt slik at
 * eksisterende kode fungerer uten endringer.
 */
@SuppressLint("MissingPermission")
class FullyKioskApi(
    private val context: Context,
    private val webView: WebView,
    private val onStartUrlChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "FullyKioskApi"
        private const val PREFS_NAME = "shortshift_config"
        private const val KEY_URL = "showroom_url"
        private const val PREFS_FULLY = "fully_settings"
        private const val PREFS_STATS = "touch_stats"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val fullyPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FULLY, Context.MODE_PRIVATE)

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

            // Prøv GPS først, fall tilbake til network
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
            Log.i(TAG, "Lokasjonsoppdatering startet, cached: ${lastLocation != null}")
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
        Log.i(TAG, "getDeviceId() → $id")
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
        val url = configPrefs.getString(KEY_URL, "") ?: ""
        Log.i(TAG, "getStartUrl() → $url")
        return url
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
    fun getScreenOn(): Boolean {
        return true // Kiosk-skjermen er alltid på
    }

    @JavascriptInterface
    fun turnScreenOff() {
        Log.i(TAG, "turnScreenOff() — ignorert (håndteres av device owner)")
    }

    // ==================== Innstillinger ====================

    @JavascriptInterface
    fun getBooleanSetting(key: String): String {
        val value = fullyPrefs.getString("bool_$key", "false") ?: "false"
        Log.i(TAG, "getBooleanSetting($key) → $value")
        return value
    }

    @JavascriptInterface
    fun getStringSetting(key: String): String {
        val value = fullyPrefs.getString("str_$key", "") ?: ""
        Log.i(TAG, "getStringSetting($key) → $value")
        return value
    }

    @JavascriptInterface
    fun setStringSetting(key: String, value: String) {
        Log.i(TAG, "setStringSetting($key, $value)")
        fullyPrefs.edit().putString("str_$key", value).apply()
    }

    // ==================== Touch-tracking ====================

    /** Kalles fra KioskActivity.dispatchTouchEvent for å tracke klikk og bevegelser */
    fun recordTouch(isClick: Boolean) {
        val today = DATE_FORMAT.format(Date())
        val clickKey = "clicks_$today"
        val moveKey = "moves_$today"

        if (isClick) {
            val clicks = statsPrefs.getInt(clickKey, 0) + 1
            statsPrefs.edit().putInt(clickKey, clicks).apply()
        } else {
            val moves = statsPrefs.getInt(moveKey, 0) + 1
            statsPrefs.edit().putInt(moveKey, moves).apply()
        }
    }

    // ==================== Statistikk & lokasjon ====================

    /**
     * Returnerer CSV i Fully Kiosk-format.
     * finn-bruktbil leser kolonne 0 (dato), 8 (bevegelser), 11 (klikk).
     * Vi fyller resten med 0 for kompatibilitet.
     * Format: dato;0;0;0;0;0;0;0;movements;0;0;clicks
     */
    @JavascriptInterface
    fun loadStatsCSV(): String {
        val sb = StringBuilder()
        // Header (Fully-kompatibel)
        sb.appendLine("date;col1;col2;col3;col4;col5;col6;col7;movements;col9;col10;clicks")

        // Siste 7 dager
        val cal = java.util.Calendar.getInstance()
        for (i in 6 downTo 0) {
            cal.time = Date()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -i)
            val date = DATE_FORMAT.format(cal.time)
            val clicks = statsPrefs.getInt("clicks_$date", 0)
            val moves = statsPrefs.getInt("moves_$date", 0)
            sb.appendLine("$date;0;0;0;0;0;0;0;$moves;0;0;$clicks")
        }
        // Fully-format har en tom linje på slutten
        sb.appendLine()

        val csv = sb.toString()
        Log.i(TAG, "loadStatsCSV() → ${csv.lines().size} linjer")
        return csv
    }

    @JavascriptInterface
    fun getLocation(): String {
        val loc = lastLocation
        if (loc == null) {
            Log.i(TAG, "getLocation() → {} (ingen lokasjon)")
            return "{}"
        }
        val json = JSONObject().apply {
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("altitude", loc.altitude)
            put("accuracy", loc.accuracy.toDouble())
            put("provider", loc.provider ?: "unknown")
        }
        val result = json.toString()
        Log.i(TAG, "getLocation() → $result")
        return result
    }
}
