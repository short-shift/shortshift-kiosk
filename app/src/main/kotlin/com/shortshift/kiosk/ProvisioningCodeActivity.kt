package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission", "SetTextI18n")
class ProvisioningCodeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProvisioningCode"
        private const val API_BASE = "https://shortshift-provisioning.netlify.app/.netlify/functions"
        private const val PREFS_NAME = "shortshift_config"
        private const val KEY_URL = "showroom_url"
        private const val KEY_DEALER = "dealer_name"
        private const val KEY_PROVISIONED = "provisioned"
        private const val MAX_CODE_LENGTH = 4
    }

    private lateinit var codeDisplay: TextView
    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val adminEscape by lazy { AdminEscape(this) }
    private var enteredCode = StringBuilder()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (adminEscape.handleTouchEvent(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

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

        codeDisplay = findViewById(R.id.codeDisplay)
        submitButton = findViewById(R.id.submitButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        setupNumpad()
        updateCodeDisplay()

        submitButton.setOnClickListener {
            Log.i(TAG, "Submit-knapp trykket!")
            onSubmit()
        }
    }

    private fun setupNumpad() {
        val numpad = findViewById<LinearLayout>(R.id.numpad)
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "OK")
        )
        val dp = resources.displayMetrics.density

        for (row in keys) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (key in row) {
                val btn = Button(this).apply {
                    text = key
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setBackgroundColor(when (key) {
                        "OK" -> 0xFF4CAF50.toInt()
                        "⌫" -> 0xFF555555.toInt()
                        else -> 0xFF333333.toInt()
                    })
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        when (key) {
                            "⌫" -> {
                                if (enteredCode.isNotEmpty()) {
                                    enteredCode.deleteCharAt(enteredCode.length - 1)
                                    updateCodeDisplay()
                                }
                            }
                            "OK" -> onSubmit()
                            else -> {
                                if (enteredCode.length < MAX_CODE_LENGTH) {
                                    enteredCode.append(key)
                                    updateCodeDisplay()
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn, LinearLayout.LayoutParams(
                    0, (64 * dp).toInt(), 1f
                ).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) })
            }
            numpad.addView(rowLayout)
        }
    }

    private fun updateCodeDisplay() {
        codeDisplay.text = if (enteredCode.isEmpty()) "_ _ _ _"
        else enteredCode.toString().toList().joinToString(" ")
    }

    private fun onSubmit() {
        val code = enteredCode.toString()
        Log.i(TAG, "onSubmit() kalt, kode: '$code'")

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

        Thread {
            callProvisionApi(code)
        }.start()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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

            val request = Request.Builder()
                .url("$API_BASE/provision-pair")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseCode = response.code
            val responseBody = response.body?.string() ?: "Ukjent feil"
            response.close()

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
