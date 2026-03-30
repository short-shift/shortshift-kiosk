package com.shortshift.kiosk

import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class DeviceOwnerReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceOwnerReceiver"
    }

    @SuppressLint("MissingPermission")
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin aktivert")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(context, DeviceOwnerReceiver::class.java)

        // Only configure if we are device owner
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Ikke device owner, hopper over konfigurering")
            return
        }

        try {
            // Disable keyguard (lock screen)
            dpm.setKeyguardDisabled(componentName, true)
            Log.i(TAG, "Keyguard deaktivert")
        } catch (e: Exception) {
            Log.e(TAG, "Kunne ikke deaktivere keyguard: ${e.message}")
        }

        // TODO: Deaktiver statusbar i produksjon
        // try {
        //     dpm.setStatusBarDisabled(componentName, true)
        // } catch (e: Exception) {
        //     Log.e(TAG, "Statusbar feilet: ${e.message}")
        // }
        Log.i(TAG, "Statusbar-blokkering hoppet over (dev-modus)")

        // TODO: Aktiver restriksjoner i produksjon. Deaktivert under utvikling.
        // val restrictions = listOf(
        //     UserManager.DISALLOW_FACTORY_RESET,
        //     UserManager.DISALLOW_DEBUGGING_FEATURES,
        //     UserManager.DISALLOW_USB_FILE_TRANSFER,
        //     UserManager.DISALLOW_SAFE_BOOT
        // )
        // for (restriction in restrictions) {
        //     try {
        //         dpm.addUserRestriction(componentName, restriction)
        //     } catch (e: Exception) {
        //         Log.e(TAG, "Restriksjon feilet: $restriction: ${e.message}")
        //     }
        // }
        Log.i(TAG, "Brukerrestriksjoner hoppet over (dev-modus)")

        // Auto-grant all runtime permissions for our package
        autoGrantPermissions(context, dpm, componentName)
    }

    private fun autoGrantPermissions(
        context: Context,
        dpm: DevicePolicyManager,
        componentName: ComponentName
    ) {
        val packageName = context.packageName
        val permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )

        for (permission in permissions) {
            try {
                dpm.setPermissionGrantState(
                    componentName,
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.i(TAG, "Tillatelse auto-gitt: $permission")
            } catch (e: Exception) {
                Log.e(TAG, "Kunne ikke gi tillatelse $permission: ${e.message}")
            }
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device admin deaktivert")
    }
}
