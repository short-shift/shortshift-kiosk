package com.shortshift.kiosk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Emulerer Fully Kiosk Browser sin JavaScript-API (window.fully).
 *
 * finn-bruktbil / all.bruktbil.shop forventer at window.fully finnes med
 * metoder som getScreenOn(), setStartUrl(), getDeviceId() osv.
 * Denne klassen gir WebView et kompatibelt grensesnitt slik at
 * eksisterende kode fungerer uten endringer.
 */
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
    }

    private val configPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val fullyPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FULLY, Context.MODE_PRIVATE)

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

    // ==================== Statistikk & lokasjon ====================

    @JavascriptInterface
    fun loadStatsCSV(): String {
        Log.i(TAG, "loadStatsCSV() → tom (ikke implementert)")
        return ""
    }

    @JavascriptInterface
    fun getLocation(): String {
        Log.i(TAG, "getLocation() → {}")
        return "{}"
    }
}
