package com.shortshift.kiosk.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.nio.charset.StandardCharsets

interface BleProvisioningListener {
    fun onWifiConfigReceived(ssid: String, password: String)
    fun onSetupCodeReceived(code: String)
    fun onDeviceConnected()
    fun onDeviceDisconnected()
    fun onBleError(message: String)
    fun onBleReady()
}

@SuppressLint("MissingPermission")
class BleGattServer(private val context: Context) {

    companion object {
        private const val TAG = "BleGattServer"
        private const val RECONNECT_DELAY_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var listener: BleProvisioningListener? = null

    // Chunk reassembly buffers per characteristic UUID
    private val reassemblyBuffers = mutableMapOf<String, ByteArray>()

    // Characteristics
    private var deviceStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var provisionResultCharacteristic: BluetoothGattCharacteristic? = null

    // Subscribed devices for notifications
    private var statusNotificationsEnabled = false
    private var resultNotificationsEnabled = false

    private val hardwareId: String
        get() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    val deviceSuffix: String
        get() {
            val id = hardwareId
            return if (id.length >= 4) id.takeLast(4).uppercase() else id.uppercase()
        }

    fun setListener(listener: BleProvisioningListener) {
        this.listener = listener
    }

    fun start() {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth ikke tilgjengelig på denne enheten")
            handler.post { listener?.onBleError("Bluetooth ikke tilgjengelig") }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.i(TAG, "Slår på Bluetooth...")
            bluetoothAdapter.enable()
            // Vent litt på at Bluetooth starter
            handler.postDelayed({ continueStart() }, 2000)
        } else {
            continueStart()
        }
    }

    private fun continueStart() {
        if (bluetoothAdapter == null) return

        // Set the local name before advertising
        val bleName = "ShortShift-$deviceSuffix"
        bluetoothAdapter.name = bleName
        Log.i(TAG, "BLE-navn satt til: $bleName (adapter name: ${bluetoothAdapter.name})")

        if (bluetoothAdapter.bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BLE Advertiser ikke tilgjengelig - enheten støtter kanskje ikke BLE peripheral mode")
            handler.post { listener?.onBleError("BLE peripheral ikke støttet") }
            return
        }

        openGattServer()
        startAdvertising()
    }

    fun stop() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        connectedDevice = null
    }

    private fun openGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // DEVICE_STATUS: READ + NOTIFY
        deviceStatusCharacteristic = BluetoothGattCharacteristic(
            BleConstants.DEVICE_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also {
            val cccd = BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            it.addDescriptor(cccd)
            service.addCharacteristic(it)
        }

        // WIFI_CONFIG: WRITE
        val wifiConfigChar = BluetoothGattCharacteristic(
            BleConstants.WIFI_CONFIG_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(wifiConfigChar)

        // SETUP_CODE: WRITE
        val setupCodeChar = BluetoothGattCharacteristic(
            BleConstants.SETUP_CODE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(setupCodeChar)

        // PROVISION_RESULT: READ + NOTIFY
        provisionResultCharacteristic = BluetoothGattCharacteristic(
            BleConstants.PROVISION_RESULT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).also {
            val cccd = BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            it.addDescriptor(cccd)
            service.addCharacteristic(it)
        }

        gattServer?.addService(service)
        Log.i(TAG, "GATT-server startet med ShortShift-service")
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE advertising ikke tilgjengelig")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        // Service UUID i advertise data for discovery
        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .setIncludeDeviceName(false) // Navn i scan response i stedet (plass)
            .build()

        // Navn i scan response
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        Log.i(TAG, "Starter BLE advertising som '${bluetoothAdapter?.name}' med service ${BleConstants.SERVICE_UUID}")
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        Log.i(TAG, "BLE advertising startet som ShortShift-$deviceSuffix")
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        Log.i(TAG, "BLE advertising stoppet")
    }

    fun notifyDeviceStatus(state: String) {
        val device = connectedDevice ?: return
        val characteristic = deviceStatusCharacteristic ?: return

        if (!statusNotificationsEnabled) return

        val data = state.toByteArray(StandardCharsets.UTF_8)
        characteristic.value = data
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        Log.d(TAG, "Sendt status-notifikasjon: $state")
    }

    fun notifyProvisionResult(success: Boolean, dealerName: String?, error: String?) {
        val device = connectedDevice ?: return
        val characteristic = provisionResultCharacteristic ?: return

        if (!resultNotificationsEnabled) return

        val json = JSONObject().apply {
            put("success", success)
            if (dealerName != null) put("dealer_name", dealerName)
            if (error != null) put("error", error)
        }

        val data = json.toString().toByteArray(StandardCharsets.UTF_8)
        characteristic.value = data
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        Log.d(TAG, "Sendt provision-resultat: success=$success")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising startet OK")
            handler.post { listener?.onBleReady() }
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data for stor"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "For mange advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Allerede startet"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Intern feil"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE advertising ikke støttet"
                else -> "Ukjent feil ($errorCode)"
            }
            Log.e(TAG, "Advertising feilet: $errorMsg")
            handler.post { listener?.onBleError("BLE: $errorMsg") }
            // Retry after cooldown
            handler.postDelayed({ startAdvertising() }, RECONNECT_DELAY_MS)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                if (connectedDevice != null) {
                    // Already have a connection — reject this one
                    gattServer?.cancelConnection(device)
                    return
                }
                connectedDevice = device
                stopAdvertising()
                Log.i(TAG, "Enhet tilkoblet: ${device.address}")
                handler.post { listener?.onDeviceConnected() }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (device.address == connectedDevice?.address) {
                    connectedDevice = null
                    statusNotificationsEnabled = false
                    resultNotificationsEnabled = false
                    reassemblyBuffers.clear()
                    Log.i(TAG, "Enhet frakoblet: ${device.address}")
                    handler.post { listener?.onDeviceDisconnected() }
                    // Restart advertising after cooldown
                    handler.postDelayed({ startAdvertising() }, RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: byteArrayOf()
            val responseData = if (offset >= value.size) {
                byteArrayOf()
            } else {
                value.copyOfRange(offset, value.size)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (value.isEmpty()) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            // Chunk reassembly: byte 0 has flags, bytes 1-N are payload
            val header = value[0].toInt() and 0xFF
            val isLastChunk = (header and 0x80) != 0
            val payload = if (value.size > 1) value.copyOfRange(1, value.size) else byteArrayOf()

            val key = characteristic.uuid.toString()
            val existing = reassemblyBuffers[key] ?: byteArrayOf()
            reassemblyBuffers[key] = existing + payload

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            if (isLastChunk) {
                val completeData = reassemblyBuffers.remove(key) ?: return
                val jsonString = String(completeData, StandardCharsets.UTF_8)
                Log.d(TAG, "Mottok komplett data på ${characteristic.uuid}: $jsonString")

                handler.post {
                    handleCompleteWrite(characteristic.uuid.toString(), jsonString)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                val enabled = when (descriptor.characteristic.uuid) {
                    BleConstants.DEVICE_STATUS_UUID -> statusNotificationsEnabled
                    BleConstants.PROVISION_RESULT_UUID -> resultNotificationsEnabled
                    else -> false
                }
                val value = if (enabled) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                when (descriptor.characteristic.uuid) {
                    BleConstants.DEVICE_STATUS_UUID -> statusNotificationsEnabled = enabled
                    BleConstants.PROVISION_RESULT_UUID -> resultNotificationsEnabled = enabled
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            Log.d(TAG, "MTU endret til $mtu")
        }
    }

    private fun handleCompleteWrite(characteristicUuid: String, jsonString: String) {
        try {
            val json = JSONObject(jsonString)

            when (characteristicUuid) {
                BleConstants.WIFI_CONFIG_UUID.toString() -> {
                    val ssid = json.getString("ssid")
                    val password = json.getString("password")
                    listener?.onWifiConfigReceived(ssid, password)
                }
                BleConstants.SETUP_CODE_UUID.toString() -> {
                    val code = json.getString("code")
                    listener?.onSetupCodeReceived(code)
                }
                else -> {
                    Log.w(TAG, "Ukjent karakteristikk for skriving: $characteristicUuid")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feil ved parsing av mottatt data: ${e.message}", e)
        }
    }
}
