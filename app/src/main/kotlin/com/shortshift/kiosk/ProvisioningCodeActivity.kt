package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@SuppressLint("MissingPermission", "SetTextI18n")
class ProvisioningCodeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProvisioningCode"
        private const val API_BASE = "https://shortshift-provisioning.netlify.app/.netlify/functions"
        private const val PREFS_NAME = "shortshift_config"
        private const val KEY_URL = "showroom_url"
        private const val KEY_DEALER = "dealer_name"
        private const val KEY_PROVISIONED = "provisioned"
    }

    private lateinit var codeInput: EditText
    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sjekk om allerede provisionert → gå rett til kiosk
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PROVISIONED, false)) {
            val url = prefs.getString(KEY_URL, null)
            if (url != null) {
                startKiosk(url, prefs.getString(KEY_DEALER, "") ?: "")
                return
            }
        }

        setContentView(R.layout.activity_provisioning_code)
        hideSystemUI()

        codeInput = findViewById(R.id.codeInput)
        submitButton = findViewById(R.id.submitButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        submitButton.setOnClickListener {
            Log.i(TAG, "Submit-knapp trykket!")
            onSubmit()
        }

        // Også submit via IME "Done"-knapp
        codeInput.setOnEditorActionListener { _, _, _ ->
            Log.i(TAG, "IME Done trykket!")
            onSubmit()
            true
        }

        codeInput.requestFocus()
    }

    private fun onSubmit() {
        Log.i(TAG, "onSubmit() kalt, kode: '${codeInput.text}'")

        val code = codeInput.text.toString().trim()
        if (code.isEmpty()) {
            statusText.text = "Tast inn provisioning-kode"
            statusText.setTextColor(0xFFFF4444.toInt())
            statusText.visibility = View.VISIBLE
            return
        }

        submitButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.text = "Henter konfigurasjon..."
        statusText.setTextColor(0xFFFFFFFF.toInt())
        statusText.visibility = View.VISIBLE

        // Skjul tastatur
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(codeInput.windowToken, 0)

        Thread {
            callProvisionApi(code)
        }.start()
    }

    private fun callProvisionApi(code: String) {
        try {
            val hardwareId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )

            val body = JSONObject().apply {
                put("setup_code", code)
                put("hardware_id", hardwareId)
                put("device_info", JSONObject().apply {
                    put("model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("app_version", BuildConfig.VERSION_NAME)
                })
            }

            val url = URL("$API_BASE/provision-pair")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.outputStream.write(body.toString().toByteArray())

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Ukjent feil"
            }
            conn.disconnect()

            if (responseCode == 200) {
                val json = JSONObject(responseBody)
                val config = json.getJSONObject("config")
                val showroomUrl = config.getString("fully_start_url")
                val dealerName = json.optString("dealer_name", "")

                // Lagre config
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_URL, showroomUrl)
                    .putString(KEY_DEALER, dealerName)
                    .putBoolean(KEY_PROVISIONED, true)
                    .putString("api_token", json.optString("api_token", ""))
                    .putString("screen_id", json.optString("screen_id", ""))
                    .apply()

                Log.i(TAG, "Provisioning OK: $dealerName → $showroomUrl")

                handler.post {
                    statusText.text = "Klar! Starter $dealerName..."
                    statusText.setTextColor(0xFF4CAF50.toInt())
                    progressBar.visibility = View.GONE
                }

                handler.postDelayed({
                    startKiosk(showroomUrl, dealerName)
                }, 1500)

            } else {
                val errorJson = try { JSONObject(responseBody) } catch (_: Exception) { null }
                val errorMsg = errorJson?.optString("error") ?: "Feil ($responseCode)"
                Log.e(TAG, "API feil: $responseCode — $errorMsg")

                handler.post {
                    statusText.text = errorMsg
                    statusText.setTextColor(0xFFFF4444.toInt())
                    progressBar.visibility = View.GONE
                    submitButton.isEnabled = true
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Nettverksfeil: ${e.message}")
            handler.post {
                statusText.text = "Nettverksfeil: ${e.message}"
                statusText.setTextColor(0xFFFF4444.toInt())
                progressBar.visibility = View.GONE
                submitButton.isEnabled = true
            }
        }
    }

    private fun startKiosk(url: String, dealerName: String) {
        val intent = Intent(this, KioskActivity::class.java).apply {
            putExtra(KioskActivity.EXTRA_URL, url)
            putExtra(KioskActivity.EXTRA_DEALER_NAME, dealerName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }
}
