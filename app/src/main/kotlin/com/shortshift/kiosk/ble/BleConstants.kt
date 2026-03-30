package com.shortshift.kiosk.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000aa01-0000-1000-8000-00805f9b34fb")
    val DEVICE_STATUS_UUID: UUID = UUID.fromString("0000aa02-0000-1000-8000-00805f9b34fb")
    val WIFI_CONFIG_UUID: UUID = UUID.fromString("0000aa03-0000-1000-8000-00805f9b34fb")
    val SETUP_CODE_UUID: UUID = UUID.fromString("0000aa04-0000-1000-8000-00805f9b34fb")
    val PROVISION_RESULT_UUID: UUID = UUID.fromString("0000aa05-0000-1000-8000-00805f9b34fb")

    // Client Characteristic Configuration Descriptor (for notifications)
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
