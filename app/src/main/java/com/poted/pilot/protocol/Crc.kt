package com.poted.pilot.protocol

/**
 * CRCs used by the DJI R SDK frame (see utils/crc in Osmo-GPS-Controller-Demo).
 * Both are reflected table-driven CRCs with a non-standard seed of 0x3AA3 and no final XOR:
 * CRC-16 uses polynomial 0xA001 (reflected 0x8005), CRC-32 uses 0xEDB88320.
 */
object Crc {
    private const val SEED = 0x3AA3

    private val table16 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    private val table32 = LongArray(256) { i ->
        var c = i.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        c
    }

    private val table8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }
        c
    }

    /** DUML header CRC8 (reflected 0x31, seed 0x77); only used to recognise DUML frames. */
    fun crc8(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0x77
        for (i in offset until offset + length) crc = table8[(crc xor data[i].toInt()) and 0xFF]
        return crc
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = SEED
        for (i in offset until offset + length) {
            crc = (table16[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)) and 0xFFFF
        }
        return crc
    }

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        var crc = SEED.toLong()
        for (i in offset until offset + length) {
            crc = (table32[((crc xor data[i].toLong()) and 0xFF).toInt()] xor (crc ushr 8)) and 0xFFFFFFFFL
        }
        return crc
    }
}
