package com.poted.pilot.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.poted.pilot.log.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/**
 * Thin coroutine wrapper around BluetoothGatt. Android allows only one outstanding GATT
 * operation, so every operation goes through [opMutex] and waits for its callback.
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val onNotify: (ByteArray) -> Unit,
    private val onDisconnected: (status: Int) -> Unit,
) {
    companion object {
        private const val TAG = "GATT"
        val SERVICE_UUID: UUID = uuid16(0xFFF0)
        val NOTIFY_UUID: UUID = uuid16(0xFFF4)
        val WRITE_UUID: UUID = uuid16(0xFFF5)
        val CCCD_UUID: UUID = uuid16(0x2902)

        fun uuid16(v: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))
    }

    private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Unit>()
    val disconnected = CompletableDeferred<Int>()

    @Volatile var mtu: Int = 23
        private set

    var notifyChar: BluetoothGattCharacteristic? = null
        private set
    var writeChar: BluetoothGattCharacteristic? = null
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            AppLog.i(TAG, "connectionState status=$status newState=${stateName(newState)}")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val err = IOException("disconnected (status=$status)")
                connected.completeExceptionally(err)
                pendingOp?.completeExceptionally(err)
                if (disconnected.complete(status)) onDisconnected(status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            AppLog.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattClient.mtu = mtu
            pendingOp?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onNotify(c.value ?: return)
            }
        }
    }

    /** Suspends until the link is up. With autoConnect=true this waits until the device appears. */
    suspend fun connect(autoConnect: Boolean) {
        AppLog.i(TAG, "connectGatt ${device.address} autoConnect=$autoConnect")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt returned null")
        connected.await()
    }

    private suspend fun op(name: String, timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): Int =
        opMutex.withLock {
            val g = gatt ?: throw IOException("$name: no gatt")
            if (disconnected.isCompleted) throw IOException("$name: disconnected")
            val d = CompletableDeferred<Int>()
            pendingOp = d
            try {
                if (!start(g)) throw IOException("$name: could not start")
                withTimeout(timeoutMs) { d.await() }
            } finally {
                pendingOp = null
            }
        }

    suspend fun requestMtu(size: Int): Int {
        val st = op("requestMtu") { it.requestMtu(size) }
        if (st != BluetoothGatt.GATT_SUCCESS) AppLog.w(TAG, "requestMtu status=$st, keeping mtu=$mtu")
        return mtu
    }

    suspend fun discover() {
        val st = op("discoverServices", 20_000) { it.discoverServices() }
        if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("discoverServices status=$st")
        val g = gatt!!
        for (s in g.services) {
            AppLog.i(TAG, "service ${s.uuid}")
            for (c in s.characteristics) {
                AppLog.i(TAG, "   char ${c.uuid} props=0x%02X".format(c.properties))
            }
        }
        val svc = g.getService(SERVICE_UUID) ?: throw IOException("service FFF0 not found")
        notifyChar = svc.getCharacteristic(NOTIFY_UUID) ?: throw IOException("char FFF4 not found")
        writeChar = svc.getCharacteristic(WRITE_UUID) ?: throw IOException("char FFF5 not found")
    }

    suspend fun enableNotifications() {
        val g = gatt ?: throw IOException("no gatt")
        val c = notifyChar ?: throw IOException("no notify char")
        if (!g.setCharacteristicNotification(c, true)) throw IOException("setCharacteristicNotification failed")
        val d = c.getDescriptor(CCCD_UUID) ?: throw IOException("CCCD not found on FFF4")
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val st = op("writeDescriptor") { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(d)
            }
        }
        if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("CCCD write status=$st")
    }

    /**
     * Writes [data] to FFF5, splitting into MTU-sized chunks if needed. [noResponse] picks
     * write-without-response like the demo does for CMD_NO_RESPONSE frames, falling back to
     * whatever the characteristic actually supports.
     */
    suspend fun write(data: ByteArray, noResponse: Boolean) {
        val c = writeChar ?: throw IOException("no write char")
        val canNoRsp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val canRsp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = when {
            noResponse && canNoRsp -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            !noResponse && canRsp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            canRsp -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(data.size, off + chunk))
            val st = op("writeCharacteristic") { g ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = type
                    @Suppress("DEPRECATION")
                    c.value = part
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(c)
                }
            }
            if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("write status=$st")
            off += part.size
        }
    }

    fun close() {
        val g = gatt ?: return
        gatt = null
        val err = IOException("closed")
        connected.completeExceptionally(err)
        pendingOp?.completeExceptionally(err)
        disconnected.complete(-1)
        try {
            g.disconnect()
        } catch (_: Exception) {
        }
        try {
            g.close()
        } catch (_: Exception) {
        }
    }

    private fun stateName(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> s.toString()
    }
}
