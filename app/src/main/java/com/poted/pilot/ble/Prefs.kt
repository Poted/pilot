package com.poted.pilot.ble

import android.content.Context
import kotlin.random.Random

/**
 * Persistent identity of this "remote" and the last camera. Android hides the phone's own
 * Bluetooth MAC, so the MAC sent in the connection request is a random, stable value; the
 * camera uses it (with device_id) to remember the pairing.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pilot", Context.MODE_PRIVATE)

    val deviceId: Long
        get() {
            var v = sp.getLong("device_id", -1L)
            if (v < 0) {
                v = Random.nextLong(0x10000000L, 0x7FFFFFFFL)
                sp.edit().putLong("device_id", v).apply()
            }
            return v
        }

    val mac: ByteArray
        get() {
            var s = sp.getString("mac", null)
            if (s == null) {
                val b = Random.nextBytes(6)
                b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte() // locally administered, unicast
                s = b.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
                sp.edit().putString("mac", s).apply()
            }
            return s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        }

    var lastAddress: String?
        get() = sp.getString("last_address", null)
        set(v) = sp.edit().putString("last_address", v).apply()

    var lastName: String?
        get() = sp.getString("last_name", null)
        set(v) = sp.edit().putString("last_name", v).apply()

    var gpsEnabled: Boolean
        get() = sp.getBoolean("gps_enabled", false)
        set(v) = sp.edit().putBoolean("gps_enabled", v).apply()

    fun isPaired(address: String): Boolean = sp.getStringSet("paired", emptySet())!!.contains(address)

    fun setPaired(address: String, paired: Boolean) {
        val set = sp.getStringSet("paired", emptySet())!!.toMutableSet()
        if (paired) set.add(address) else set.remove(address)
        sp.edit().putStringSet("paired", set).apply()
    }

    fun forgetAll() {
        sp.edit().remove("paired").remove("last_address").remove("last_name").apply()
    }
}
