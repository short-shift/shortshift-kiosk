package com.shortshift.kiosk.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class SecureStorage(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
        )
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "shortshift_secure",
            masterKey,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("SecureStorage", "EncryptedSharedPreferences feilet, bruker vanlig SharedPreferences", e)
        context.getSharedPreferences("shortshift_fallback", Context.MODE_PRIVATE)
    }

    fun saveApiToken(token: String) {
        prefs.edit().putString(KEY_API_TOKEN, token).apply()
    }

    fun getApiToken(): String? = prefs.getString(KEY_API_TOKEN, null)

    fun saveScreenId(id: String) {
        prefs.edit().putString(KEY_SCREEN_ID, id).apply()
    }

    fun getScreenId(): String? = prefs.getString(KEY_SCREEN_ID, null)

    fun isProvisioned(): Boolean = prefs.getBoolean(KEY_PROVISIONED, false)

    fun setProvisioned() {
        prefs.edit().putBoolean(KEY_PROVISIONED, true).apply()
    }

    fun saveConfig(configJson: String) {
        prefs.edit().putString(KEY_CONFIG, configJson).apply()
    }

    fun getConfig(): String? = prefs.getString(KEY_CONFIG, null)

    fun saveDealerName(name: String) {
        prefs.edit().putString(KEY_DEALER_NAME, name).apply()
    }

    fun getDealerName(): String? = prefs.getString(KEY_DEALER_NAME, null)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_SCREEN_ID = "screen_id"
        private const val KEY_PROVISIONED = "provisioned"
        private const val KEY_CONFIG = "config"
        private const val KEY_DEALER_NAME = "dealer_name"
    }
}
