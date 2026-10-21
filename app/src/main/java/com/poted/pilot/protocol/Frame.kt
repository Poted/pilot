package com.poted.pilot.protocol

/**
 * DJI R SDK frame:
 *
 * | SOF 0xAA | Ver/Len (2, LE) | CmdType | ENC | RES (3) | SEQ (2, LE) | CRC16 (2) | CmdSet | CmdID | payload | CRC32 (4) |
 *
 * CRC16 covers SOF..SEQ, CRC32 covers SOF..end of DATA. All integers are little-endian.
 */
class Frame(
    val cmdType: Int,
    val seq: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
    val enc: Int = 0,
) {
    /** Bit 5 of CmdType: 0 = command frame, 1 = response (ACK) frame. */
    val isResponse: Boolean get() = cmdType and 0x20 != 0

    /** Bits 4..0 of CmdType: 0 = no reply wanted. */
    val wantsReply: Boolean get() = cmdType and 0x1F != 0

    fun encode(): ByteArray {
        val total = HEADER_LEN + 2 + payload.size + 4
        require(total <= 0x3FF) { "frame too long: $total" }
        val b = ByteArray(total)
        b[0] = SOF.toByte()
        b[1] = (total and 0xFF).toByte()
        b[2] = ((total ushr 8) and 0x03).toByte() // version 0
        b[3] = cmdType.toByte()
        b[4] = enc.toByte()
        // b[5..7] reserved = 0
        b[8] = (seq and 0xFF).toByte()
        b[9] = ((seq ushr 8) and 0xFF).toByte()
        val c16 = Crc.crc16(b, 0, 10)
        b[10] = (c16 and 0xFF).toByte()
        b[11] = ((c16 ushr 8) and 0xFF).toByte()
        b[12] = cmdSet.toByte()
        b[13] = cmdId.toByte()
        payload.copyInto(b, 14)
        val c32 = Crc.crc32(b, 0, total - 4)
        for (i in 0 until 4) b[total - 4 + i] = ((c32 ushr (8 * i)) and 0xFF).toByte()
        return b
    }

    val key: String get() = "%02X%02X".format(cmdSet, cmdId)

    override fun toString(): String =
        "Frame(${if (isResponse) "ACK" else "CMD"} $key type=0x%02X seq=%d len=%d)".format(cmdType, seq, payload.size)

    companion object {
        const val SOF = 0xAA
        /** SOF..CRC16, i.e. everything before CmdSet. */
        const val HEADER_LEN = 12
        const val MIN_LEN = HEADER_LEN + 4

        sealed class DecodeResult {
            class Ok(val frame: Frame) : DecodeResult()
            class Error(val reason: String) : DecodeResult()
        }

        /** Decodes exactly one complete frame. */
        fun decode(b: ByteArray): DecodeResult {
            if (b.size < MIN_LEN) return DecodeResult.Error("too short (${b.size})")
            if (b.u8(0) != SOF) return DecodeResult.Error("bad SOF 0x%02X".format(b.u8(0)))
            val len = b.u16(1) and 0x3FF
            if (len != b.size) return DecodeResult.Error("length field $len != ${b.size}")
            val c16 = b.u16(10)
            val c16calc = Crc.crc16(b, 0, 10)
            if (c16 != c16calc) return DecodeResult.Error("CRC16 0x%04X != 0x%04X".format(c16, c16calc))
            val c32 = b.u32(len - 4)
            val c32calc = Crc.crc32(b, 0, len - 4)
            if (c32 != c32calc) return DecodeResult.Error("CRC32 0x%08X != 0x%08X".format(c32, c32calc))
            if (len < MIN_LEN + 2) return DecodeResult.Error("empty DATA segment")
            return DecodeResult.Ok(
                Frame(
                    cmdType = b.u8(3),
                    enc = b.u8(4),
                    seq = b.u16(8),
                    cmdSet = b.u8(12),
                    cmdId = b.u8(13),
                    payload = b.copyOfRange(14, len - 4),
                )
            )
        }
    }
}

/**
 * Splits a byte stream from BLE notifications into frames. The ESP32 demo assumes one
 * notification == one frame (MTU 500); this also copes with frames split across or
 * concatenated within notifications, and resynchronises on garbage.
 *
 * The camera also pushes DJI DUML frames (SOF 0x55, ~1 Hz) on the same characteristic.
 * Per the DJI FAQ they can be ignored, but they must be skipped by their length: they
 * may contain 0xAA bytes that would otherwise be mistaken for an R SDK frame start.
 */
class FrameReassembler(
    private val onFrame: (Frame, ByteArray) -> Unit,
    private val onError: (String, ByteArray) -> Unit,
    private val onForeign: (ByteArray) -> Unit = {},
) {
    private var buf = ByteArray(0)

    fun reset() {
        buf = ByteArray(0)
    }

    private fun drop(n: Int) {
        buf = buf.copyOfRange(n, buf.size)
    }

    fun feed(chunk: ByteArray) {
        buf += chunk
        while (buf.isNotEmpty()) {
            val start = buf.indexOfFirst { val b = it.toInt() and 0xFF; b == Frame.SOF || b == DUML_SOF }
            if (start < 0) {
                onError("dropping ${buf.size} bytes without SOF", buf)
                buf = ByteArray(0)
                return
            }
            if (start > 0) {
                onError("dropping $start bytes before SOF", buf.copyOfRange(0, start))
                drop(start)
            }
            if (buf.u8(0) == DUML_SOF) {
                if (buf.size < 4) return
                val len = buf.u16(1) and 0x3FF
                if (Crc.crc8(buf, 0, 3) != buf.u8(3) || len < DUML_MIN_LEN) {
                    onError("bad DUML header, resync", buf.copyOfRange(0, 4))
                    drop(1)
                    continue
                }
                if (buf.size < len) return
                onForeign(buf.copyOfRange(0, len))
                drop(len)
                continue
            }
            if (buf.size < 3) return
            val len = buf.u16(1) and 0x3FF
            if (len < Frame.MIN_LEN) {
                onError("implausible length $len, resync", buf.copyOfRange(0, 3))
                drop(1)
                continue
            }
            if (buf.size < 12) return
            // Validate header CRC early so a stray 0xAA inside garbage doesn't stall us.
            if (buf.u16(10) != Crc.crc16(buf, 0, 10)) {
                onError("header CRC16 mismatch, resync", buf.copyOfRange(0, 12))
                drop(1)
                continue
            }
            if (buf.size < len) return
            val raw = buf.copyOfRange(0, len)
            drop(len)
            when (val r = Frame.decode(raw)) {
                is Frame.Companion.DecodeResult.Ok -> onFrame(r.frame, raw)
                is Frame.Companion.DecodeResult.Error -> onError(r.reason, raw)
            }
        }
    }

    companion object {
        const val DUML_SOF = 0x55
        const val DUML_MIN_LEN = 13
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u32(i: Int): Long =
    (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

fun ByteArray.toHex(sep: String = " "): String = joinToString(sep) { "%02X".format(it.toInt() and 0xFF) }

fun hexToBytes(s: String): ByteArray {
    val clean = s.filter { it.isLetterOrDigit() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Little-endian payload builder. */
class Bytes {
    private val out = java.io.ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Long) = apply { for (i in 0 until 4) u8((v ushr (8 * i)).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }
    fun build(): ByteArray = out.toByteArray()
}
