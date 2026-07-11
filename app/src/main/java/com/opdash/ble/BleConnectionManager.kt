package com.opdash.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.opdash.logging.Logger
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import android.bluetooth.BluetoothDevice

class BleConnectionManager(
    private val context: Context
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private var bleHandler: DashBleHandler? = null

    @SuppressLint("MissingPermission")
    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Logger.d("BLE Scanner not available")
            return
        }

        Logger.d("Starting BLE scan for Tripper Dash...")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: ""
                if (name.startsWith("RE_", ignoreCase = true)) {
                    Logger.d("Found Dash: $name")
                    onDeviceFound(result.device)
                }
            }
        }
        scanner.startScan(callback)
        
        // Stop scan after 10 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            Logger.d("BLE scan stopped")
        }, 10000)
    }

    fun connect(device: BluetoothDevice) {
        bleHandler = DashBleHandler(context)
        bleHandler?.connect(device)
            ?.useAutoConnect(true)
            ?.enqueue()
    }

    private inner class DashBleHandler(context: Context) : BleManager(context) {
        override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
            override fun isRequiredServiceSupported(gatt: android.bluetooth.BluetoothGatt): Boolean {
                val service = gatt.getService(UUIDs.SERVICE)
                return service != null
            }

            override fun onServicesInvalidated() {}
        }

        override fun initialize() {
            Logger.d("BLE Connected to Dash")
            // Here we would normally perform the handshake:
            // 1. Send pairing packet
            // 2. Dash responds with Wi-Fi SSID/Pass
            // 3. We trigger the DashConnectionService
        }
    }
}