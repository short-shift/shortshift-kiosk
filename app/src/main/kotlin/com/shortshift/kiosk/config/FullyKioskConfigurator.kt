package com.shortshift.kiosk.config

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shortshift.kiosk.DeviceOwnerReceiver
import com.shortshift.kiosk.api.ProvisionConfig
import org.json.JSONObject
import java.io.File

@SuppressLint("MissingPermission")
class FullyKioskConfigurator(private val context: Context) {

    companion object {
        private const val TAG = "FullyKioskConfigurator"
        private const val FULLY_PACKAGE = "de.ozerov.fully"
    }

    fun configureAndLaunch(config: ProvisionConfig) {
        setupLockTaskPackages()
        writeConfigFile(config)
        launchFully(config)
    }

    private fun setupLockTaskPackages() {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, DeviceOwnerReceiver::class.java)
            val ourPackage = context.packageName

            dpm.setLockTaskPackages(componentName, arrayOf(ourPackage, FULLY_PACKAGE))
            Log.i(TAG, "Lock task-pakker konfigurert: $ourPackage, $FULLY_PACKAGE")
        } catch (e: Exception) {
            Log.e(TAG, "Kunne ikke sette lock task-pakker: ${e.message}", e)
        }
    }

    private fun writeConfigFile(config: ProvisionConfig) {
        try {
            val configJson = JSONObject().apply {
                put("startURL", config.fullyStartUrl)
                put("kioskMode", true)
                put("showNavigationBar", false)
                put("screenOrientation", 0) // landscape
                put("enableScreensaver", false)
                put("remoteAdminEnabled", true)

                // Apply any additional settings from provisioning API
                for ((key, value) in config.fullySettings) {
                    put(key, value)
                }
            }

            val configFile = File(context.getExternalFilesDir(null), "fully-config.json")
            configFile.writeText(configJson.toString(2))
            Log.i(TAG, "Fully Kiosk-konfigurasjon skrevet til ${configFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Feil ved skriving av konfigurasjonsfil: ${e.message}", e)
        }
    }

    private fun launchFully(config: ProvisionConfig) {
        val intent = context.packageManager.getLaunchIntentForPackage(FULLY_PACKAGE)

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("url", config.fullyStartUrl)
            context.startActivity(intent)
            Log.i(TAG, "Fully Kiosk startet med URL: ${config.fullyStartUrl}")
        } else {
            Log.e(TAG, "Fully Kiosk ($FULLY_PACKAGE) er ikke installert!")
        }
    }
}
