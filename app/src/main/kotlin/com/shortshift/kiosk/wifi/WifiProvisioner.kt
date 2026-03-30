@file:Suppress("DEPRECATION")

package com.shortshift.kiosk.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

@SuppressLint("MissingPermission")
class WifiProvisioner(private val context: Context) {

    companion object {
        private const val TAG = "WifiProvisioner"
        private const val CONNECTIVITY_TIMEOUT_MS = 30_000L
    }

    fun connectToWifi(ssid: String, password: String, callback: (Boolean) -> Unit) {
        Log.i(TAG, "Kobler til WiFi: $ssid")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Ensure WiFi is enabled (device owner can toggle this)
        if (!wifiManager.isWifiEnabled) {
            wifiManager.isWifiEnabled = true
            Log.i(TAG, "WiFi aktivert")
        }

        // Build WifiConfiguration
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        // Remove any existing config for this SSID
        wifiManager.configuredNetworks?.forEach { existing ->
            if (existing.SSID == "\"$ssid\"") {
                wifiManager.removeNetwork(existing.networkId)
                Log.d(TAG, "Fjernet eksisterende konfigurasjon for $ssid")
            }
        }

        val netId = wifiManager.addNetwork(wifiConfig)
        if (netId == -1) {
            Log.e(TAG, "Kunne ikke legge til nettverk: $ssid")
            callback(false)
            return
        }

        wifiManager.disconnect()
        val enabled = wifiManager.enableNetwork(netId, true)
        if (!enabled) {
            Log.e(TAG, "Kunne ikke aktivere nettverk: $ssid")
            callback(false)
            return
        }
        wifiManager.reconnect()

        Log.i(TAG, "Nettverk lagt til og aktivert, venter på tilkobling...")
        waitForConnectivity(CONNECTIVITY_TIMEOUT_MS, callback)
    }

    private fun waitForConnectivity(timeoutMs: Long, callback: (Boolean) -> Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val handler = Handler(Looper.getMainLooper())
        var responded = false

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!responded) {
                    responded = true
                    connectivityManager.unregisterNetworkCallback(this)
                    handler.removeCallbacksAndMessages(null)
                    Log.i(TAG, "WiFi-tilkobling etablert")
                    handler.post { callback(true) }
                }
            }

            override fun onUnavailable() {
                if (!responded) {
                    responded = true
                    connectivityManager.unregisterNetworkCallback(this)
                    handler.removeCallbacksAndMessages(null)
                    Log.e(TAG, "WiFi-tilkobling utilgjengelig")
                    handler.post { callback(false) }
                }
            }
        }

        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Timeout fallback
        handler.postDelayed({
            if (!responded) {
                responded = true
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (_: IllegalArgumentException) {
                    // Already unregistered
                }
                Log.e(TAG, "WiFi-tilkobling tidsavbrutt etter ${timeoutMs}ms")
                callback(false)
            }
        }, timeoutMs)
    }
}
