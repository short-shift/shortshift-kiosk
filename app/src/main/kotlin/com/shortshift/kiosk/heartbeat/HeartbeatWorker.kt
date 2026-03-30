package com.shortshift.kiosk.heartbeat

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shortshift.kiosk.BuildConfig
import com.shortshift.kiosk.api.HeartbeatData
import com.shortshift.kiosk.api.ProvisionApiClient
import com.shortshift.kiosk.util.SecureStorage
import java.util.concurrent.TimeUnit

class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "heartbeat"
        private const val FULLY_PACKAGE = "de.ozerov.fully"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.i(TAG, "Heartbeat planlagt (hver 15. minutt)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Heartbeat avbrutt")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val storage = SecureStorage(applicationContext)
        val token = storage.getApiToken()
        if (token == null) {
            Log.w(TAG, "Ingen API-token funnet, avbryter heartbeat")
            return Result.failure()
        }

        val hardwareId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val data = HeartbeatData(
            hardwareId = hardwareId,
            wifiSsid = getCurrentWifiSsid(),
            wifiSignalDbm = getCurrentWifiSignal(),
            screenOn = isScreenOn(),
            appVersion = BuildConfig.VERSION_NAME,
            fullyVersion = getFullyVersion(),
            androidVersion = Build.VERSION.RELEASE,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
            freeMemoryMb = getFreeMemory(),
            currentUrl = null
        )

        val client = ProvisionApiClient()

        return try {
            val result = client.sendHeartbeat(token, hardwareId, data)
            Log.d(TAG, "Heartbeat sendt OK (config_changed=${result.configChanged})")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat feilet: ${e.message}", e)
            Result.retry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentWifiSsid(): String? {
        return try {
            val wifiManager = applicationContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            val ssid = info.ssid
            if (ssid == "<unknown ssid>" || ssid == "0x") null
            else ssid?.removeSurrounding("\"")
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentWifiSignal(): Int? {
        return try {
            val wifiManager = applicationContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            if (info.rssi == -127) null else info.rssi
        } catch (_: Exception) {
            null
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isInteractive
        } catch (_: Exception) {
            true
        }
    }

    private fun getFullyVersion(): String? {
        return try {
            @Suppress("DEPRECATION")
            val info = applicationContext.packageManager.getPackageInfo(FULLY_PACKAGE, 0)
            info.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getFreeMemory(): Long {
        return try {
            val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.availMem / (1024 * 1024)
        } catch (_: Exception) {
            0
        }
    }
}
