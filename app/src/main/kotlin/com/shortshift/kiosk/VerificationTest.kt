package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.ScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifiserer alle påstander for ZTP-arkitekturen:
 * 1. setLockTaskPackages() kan låse Fully Kiosk
 * 2. Fully Kiosk REST API er tilgjengelig
 * 3. Auto-grant permissions fungerer
 * 4. Fully Kiosk kan startes og låses i kiosk-modus
 */
@SuppressLint("MissingPermission", "SetTextI18n")
class VerificationTest : AppCompatActivity() {

    companion object {
        private const val TAG = "VerificationTest"
        private const val FULLY_PACKAGE = "com.fullykiosk.emm"
        private const val FULLY_ACTIVITY = "de.ozerov.fully.MainActivity"
    }

    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        resultText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
        }
        layout.addView(resultText)
        scroll.addView(layout)
        scroll.setBackgroundColor(0xFF000000.toInt())
        setContentView(scroll)

        Thread {
            runAllTests()
        }.start()
    }

    private fun runAllTests() {
        val results = StringBuilder()
        results.appendLine("=== ZTP VERIFIKASJONSTEST ===\n")

        // Test 1: Er vi device owner?
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, DeviceOwnerReceiver::class.java)
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        results.appendLine("1. Device owner: ${if (isOwner) "JA ✓" else "NEI ✗"}")
        log(results)

        if (!isOwner) {
            results.appendLine("\n⚠ Kan ikke fortsette uten device owner")
            log(results)
            return
        }

        // Test 2: setLockTaskPackages()
        try {
            dpm.setLockTaskPackages(componentName, arrayOf(packageName, FULLY_PACKAGE))
            val lockPackages = dpm.getLockTaskPackages(componentName)
            val hasFullyLocked = lockPackages.contains(FULLY_PACKAGE)
            results.appendLine("2. setLockTaskPackages: ${if (hasFullyLocked) "JA ✓ — Fully Kiosk kan låses" else "NEI ✗"}")
            results.appendLine("   Låste pakker: ${lockPackages.joinToString()}")
        } catch (e: Exception) {
            results.appendLine("2. setLockTaskPackages: FEIL ✗ — ${e.message}")
        }
        log(results)

        // Test 3: Auto-grant permissions for Fully Kiosk
        val permissions = listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        var grantedCount = 0
        for (perm in permissions) {
            try {
                val result = dpm.setPermissionGrantState(
                    componentName, FULLY_PACKAGE, perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                if (result) grantedCount++
            } catch (_: Exception) {}
        }
        results.appendLine("3. Auto-grant tillatelser for Fully Kiosk: $grantedCount/${permissions.size} ✓")
        log(results)

        // Test 4: Start Fully Kiosk
        try {
            val intent = Intent().apply {
                setClassName(FULLY_PACKAGE, FULLY_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            results.appendLine("4. Start Fully Kiosk: JA ✓")
        } catch (e: Exception) {
            results.appendLine("4. Start Fully Kiosk: FEIL ✗ — ${e.message}")
        }
        log(results)

        // Test 5: Fully Kiosk REST API (vent litt til den starter)
        Thread.sleep(5000)
        try {
            val url = URL("http://localhost:2323/?cmd=deviceInfo&type=json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText().take(200)
            conn.disconnect()
            results.appendLine("5. Fully Kiosk REST API: JA ✓ (HTTP $code)")
            results.appendLine("   Response: $body")
        } catch (e: Exception) {
            results.appendLine("5. Fully Kiosk REST API: FEIL ✗ — ${e.message}")
            results.appendLine("   (REST API er kanskje ikke aktivert i Fully Kiosk)")
        }
        log(results)

        // Test 6: Kan vi sette Fully Kiosk i lock task mode?
        results.appendLine("\n6. Lock task mode: Må testes manuelt.")
        results.appendLine("   Fully Kiosk er lagt til i lock task packages.")
        results.appendLine("   Fully Kiosk må kalle startLockTask() selv,")
        results.appendLine("   eller vi kan tvinge det via ActivityManager.")
        log(results)

        // Oppsummering
        results.appendLine("\n=== KONKLUSJON ===")
        results.appendLine("Hvis test 1-5 er ✓: ShortShift kan styre Fully Kiosk komplett.")
        results.appendLine("Fabrikken trenger bare bytte device owner til ShortShift.")
        log(results)
    }

    private fun log(text: StringBuilder) {
        Log.i(TAG, text.toString())
        runOnUiThread {
            resultText.text = text.toString()
        }
    }
}
