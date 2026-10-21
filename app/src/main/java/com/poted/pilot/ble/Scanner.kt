package com.poted.pilot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.poted.pilot.log.AppLog
import com.poted.pilot.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isDjiCamera: Boolean,
    val lastSeen: Long,
)

@SuppressLint("MissingPermission")
class Scanner(private val context: Context) {
    companion object {
        private const val TAG = "Scan"

        /**
         * Same test as bsp_link_is_dji_camera_adv() in the demo: a manufacturer-specific AD
         * structure whose data (company id included) starts with AA 08 and has 0xFA at index 4.
         */
        fun isDjiCameraAdv(raw: ByteArray?): Boolean {
            if (raw == null) return false
            var i = 0
            while (i < raw.size) {
                val len = raw[i].toInt() and 0xFF
                if (len == 0 || i + len + 1 > raw.size) break
                val type = raw[i + 1].toInt() and 0xFF
                if (type == 0xFF && len - 1 >= 5) {
                    val d = i + 2
                    if ((raw[d].toInt() and 0xFF) == 0xAA &&
                        (raw[d + 1].toInt() and 0xFF) == 0x08 &&
                        (raw[d + 4].toInt() and 0xFF) == 0xFA
                    ) return true
                }
                i += len + 1
            }
            return false
        }
    }

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val logged = HashSet<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            AppLog.e(TAG, "scan failed, errorCode=$errorCode")
            _scanning.value = false
        }
    }

    private fun handle(r: ScanResult) {
        val raw = r.scanRecord?.bytes
        val dji = isDjiCameraAdv(raw)
        val name = r.scanRecord?.deviceName ?: r.device.name
        if (!dji && name == null) return
        val addr = r.device.address
        if (dji && logged.add(addr)) {
            AppLog.i(TAG, "DJI camera: $name $addr rssi=${r.rssi} adv=${raw?.let { trimAdv(it).toHex("") }}")
        }
        val d = FoundDevice(addr, name, r.rssi, dji, System.currentTimeMillis())
        val list = _devices.value.filter { it.address != addr } + d
        _devices.value = list.sortedWith(compareByDescending<FoundDevice> { it.isDjiCamera }.thenByDescending { it.rssi })
    }

    private fun trimAdv(raw: ByteArray): ByteArray {
        var end = raw.size
        while (end > 0 && raw[end - 1].toInt() == 0) end--
        return raw.copyOf(end)
    }

    fun start(durationMs: Long = 15_000) {
        val a = adapter
        if (a == null || !a.isEnabled) {
            AppLog.e(TAG, "Bluetooth is off")
            return
        }
        val s = a.bluetoothLeScanner ?: return
        stop()
        _devices.value = emptyList()
        logged.clear()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        AppLog.i(TAG, "scan start")
        s.startScan(null, settings, callback)
        _scanning.value = true
        main.postDelayed({ stop() }, durationMs)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (!_scanning.value) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            AppLog.w(TAG, "stopScan: ${e.message}")
        }
        _scanning.value = false
        AppLog.i(TAG, "scan stop, found ${_devices.value.count { it.isDjiCamera }} DJI camera(s)")
    }
}
