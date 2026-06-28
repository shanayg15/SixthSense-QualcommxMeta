package com.sixthsense.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * BLE client skeleton for the SixthSense haptic belt (ESP32, Nordic UART
 * service). Scans for the service UUID, connects, discovers the write
 * characteristic, and sends 4-byte packets with write-no-response where
 * supported. Caller is responsible for runtime Bluetooth permissions.
 */
@SuppressLint("MissingPermission")
class BeltClient(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Fired (on a binder thread) when the belt connects / disconnects, so the
     *  app can auto-start/stop driving the belt from the live scene. */
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /** Start scanning for the belt and connect to the first match. */
    fun connect() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Bluetooth unavailable or off; cannot scan.")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        Log.i(TAG, "Scanning for belt service $SERVICE_UUID")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.close()
        gatt = null
        writeChar = null
        isConnected = false
        Log.i(TAG, "Disconnected.")
    }

    /** Send a raw belt packet [left, center, right, pattern]. */
    fun send(packet: ByteArray) {
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null || !isConnected) {
            Log.w(TAG, "send() ignored — belt not connected (packet=${packet.joinToString()})")
            return
        }
        Log.i(TAG, "send packet=${packet.joinToString()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            ch.value = packet
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "Found belt ${device.address}; stopping scan and connecting.")
            adapter?.bluetoothLeScanner?.stopScan(this)
            gatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected; discovering services.")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected (status=$status).")
                isConnected = false
                onDisconnected?.invoke()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            val ch = service?.getCharacteristic(CHAR_UUID)
            if (ch == null) {
                Log.w(TAG, "Belt characteristic not found; check firmware UUIDs.")
                return
            }
            writeChar = ch
            isConnected = true
            Log.i(TAG, "Belt ready: characteristic $CHAR_UUID")
            onConnected?.invoke()
        }
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        // Nordic UART Service (NUS) UUIDs, matched by the ESP32 firmware.
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    }
}
