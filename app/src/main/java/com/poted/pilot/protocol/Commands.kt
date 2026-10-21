package com.poted.pilot.protocol

/** CmdType values used by the DJI demo (logic/enums_logic.h). */
object CmdType {
    const val CMD_NO_RESPONSE = 0x00
    const val CMD_RESPONSE_OR_NOT = 0x01
    const val CMD_WAIT_RESULT = 0x02
    const val ACK_NO_RESPONSE = 0x20
}

/** Payload builders and parsers for the commands in docs/protocol_data_segment.md. */
object Commands {
    // (CmdSet, CmdID)
    val VERSION = 0x00 to 0x00
    val KEY_REPORT = 0x00 to 0x11
    val CONNECTION = 0x00 to 0x19
    val POWER_MODE = 0x00 to 0x1A
    val STATUS_PUSH = 0x1D to 0x02
    val RECORD = 0x1D to 0x03
    val MODE_SWITCH = 0x1D to 0x04
    val STATUS_SUBSCRIBE = 0x1D to 0x05
    val NEW_STATUS_PUSH = 0x1D to 0x06

    const val VERIFY_MODE_NONE = 0
    const val VERIFY_MODE_REQUIRED = 1
    const val VERIFY_MODE_RESULT = 2

    /** Connection request (0019) command frame, 33 bytes. */
    fun connectionRequest(deviceId: Long, mac: ByteArray, verifyMode: Int, verifyData: Int): ByteArray {
        require(mac.size <= 16)
        return Bytes()
            .u32(deviceId)
            .u8(mac.size)
            .bytes(mac).zeros(16 - mac.size)
            .u32(0)          // fw_version: 0 => camera won't push FW updates
            .u8(0)           // conidx
            .u8(verifyMode)
            .u16(verifyData)
            .zeros(4)
            .build()
    }

    /** Connection response (0019) we send back after the camera's verify_mode=2 request. */
    fun connectionResponse(deviceId: Long, retCode: Int = 0, cameraIndex: Int = 0): ByteArray =
        Bytes().u32(deviceId).u8(retCode).u8(cameraIndex).zeros(3).build()

    // The demo hard-codes these device_id/reserved values for 1D03/1D04; mirrored verbatim.
    fun recordControl(start: Boolean): ByteArray =
        Bytes().u32(0x33FF0000L).u8(if (start) 0 else 1).zeros(4).build()

    fun modeSwitch(mode: Int): ByteArray =
        Bytes().u32(0xFF330000L).u8(mode).bytes(byteArrayOf(0x01, 0x47, 0x39, 0x36)).build()

    /** push_mode 3 = periodic + on change; push_freq 20 (=2 Hz) is the only accepted value. */
    fun statusSubscribe(pushMode: Int = 3, pushFreq: Int = 20): ByteArray =
        Bytes().u8(pushMode).u8(pushFreq).zeros(4).build()

    /** key_code 1 = record, 2 = QS, 3 = snapshot; mode 1 = key event; value 0 = short press. */
    fun keyReport(keyCode: Int, mode: Int = 1, value: Int = 0): ByteArray =
        Bytes().u8(keyCode).u8(mode).u16(value).build()

    /** 0 = normal, 3 = sleep. */
    fun powerMode(mode: Int): ByteArray = Bytes().u8(mode).build()

    data class ConnectionRequest(
        val deviceId: Long,
        val mac: ByteArray,
        val verifyMode: Int,
        val verifyData: Int,
    )

    fun parseConnectionRequest(p: ByteArray): ConnectionRequest? {
        if (p.size < 29) return null
        val macLen = p.u8(4).coerceAtMost(16)
        return ConnectionRequest(
            deviceId = p.u32(0),
            mac = p.copyOfRange(5, 5 + macLen),
            verifyMode = p.u8(26),
            verifyData = p.u16(27),
        )
    }

    data class ConnectionResponse(val deviceId: Long, val retCode: Int)

    fun parseConnectionResponse(p: ByteArray): ConnectionResponse? =
        if (p.size < 5) null else ConnectionResponse(p.u32(0), p.u8(4))

    /** First byte of most simple ACKs is ret_code. */
    fun retCode(p: ByteArray): Int? = if (p.isEmpty()) null else p.u8(0)

    data class Version(val ack: Int, val productId: String, val sdkVersion: String)

    fun parseVersion(p: ByteArray): Version? {
        if (p.size < 18) return null
        return Version(
            ack = p.u16(0),
            productId = p.asciiz(2, 16),
            sdkVersion = String(p, 18, p.size - 18, Charsets.ISO_8859_1)
                .map { if (it.code in 0x20..0x7E) it else '|' }.joinToString(""),
        )
    }

    data class ModeName(val name: String, val param: String)

    fun parseNewStatus(p: ByteArray): ModeName? {
        if (p.size < 2) return null
        var name = ""
        var param = ""
        // TLV-ish: [type][len][21 bytes]; walk defensively.
        var i = 0
        while (i + 2 <= p.size) {
            val type = p.u8(i)
            val len = p.u8(i + 1)
            val start = i + 2
            val avail = (p.size - start).coerceAtLeast(0)
            val s = p.asciiz(start, minOf(len, avail))
            when (type) {
                1 -> name = s
                2 -> param = s
            }
            i = start + 21
        }
        return ModeName(name, param)
    }

    fun modeName(mode: Int): String = when (mode) {
        0x00 -> "Slow Motion"
        0x01 -> "Video"
        0x02 -> "Timelapse"
        0x05 -> "Photo"
        0x0A -> "Hyperlapse"
        0x1A -> "Live"
        0x23 -> "UVC Live"
        0x28 -> "SuperNight"
        0x34 -> "Subject Tracking"
        else -> "0x%02X".format(mode)
    }

    /** Modes offered in the UI for 1D04. */
    val switchableModes = listOf(0x01, 0x00, 0x28, 0x02, 0x0A, 0x05)
}

/** Camera Status Push (1D02). Fields past the payload end are null. */
data class CameraStatus(
    val cameraMode: Int,
    val cameraStatus: Int,
    val videoResolution: Int?,
    val fpsIdx: Int?,
    val eisMode: Int?,
    val recordTimeSec: Int?,
    val remainCapacityMb: Long?,
    val remainPhotoNum: Long?,
    val remainTimeSec: Long?,
    val userMode: Int?,
    val powerMode: Int?,
    val modeNextFlag: Int?,
    val tempOver: Int?,
    val loopRecordSec: Int?,
    val batteryPercent: Int?,
) {
    /** 0x03 = photo or recording, 0x05 = pre-recording (same rule as is_camera_recording()). */
    val isRecording: Boolean get() = cameraStatus == 0x03 || cameraStatus == 0x05

    val statusName: String
        get() = when (cameraStatus) {
            0x00 -> "ekran wyłączony"
            0x01 -> "gotowa"
            0x02 -> "odtwarzanie"
            0x03 -> "nagrywa"
            0x05 -> "pre-recording"
            else -> "0x%02X".format(cameraStatus)
        }

    companion object {
        fun parse(p: ByteArray): CameraStatus? {
            if (p.size < 2) return null
            fun u8(i: Int) = if (p.size > i) p.u8(i) else null
            fun u16(i: Int) = if (p.size >= i + 2) p.u16(i) else null
            fun u32(i: Int) = if (p.size >= i + 4) p.u32(i) else null
            return CameraStatus(
                cameraMode = p.u8(0),
                cameraStatus = p.u8(1),
                videoResolution = u8(2),
                fpsIdx = u8(3),
                eisMode = u8(4),
                recordTimeSec = u16(5),
                remainCapacityMb = u32(15),
                remainPhotoNum = u32(19),
                remainTimeSec = u32(23),
                userMode = u8(27),
                powerMode = u8(28),
                modeNextFlag = u8(29),
                tempOver = u8(30),
                loopRecordSec = u16(35),
                batteryPercent = u8(37),
            )
        }

        fun resolutionName(v: Int?): String? = when (v) {
            null -> null
            10 -> "1080p"
            16 -> "4K 16:9"
            45 -> "2.7K 16:9"
            66 -> "1080p 9:16"
            67 -> "2.7K 9:16"
            95 -> "2.7K 4:3"
            103 -> "4K 4:3"
            109 -> "4K 9:16"
            else -> "res#$v"
        }

        fun fpsName(v: Int?): String? = when (v) {
            null -> null
            1 -> "24"; 2 -> "25"; 3 -> "30"; 4 -> "48"; 5 -> "50"; 6 -> "60"
            10 -> "100"; 7 -> "120"; 19 -> "200"; 8 -> "240"
            else -> "fps#$v"
        }
    }
}

private fun ByteArray.asciiz(start: Int, len: Int): String {
    if (start >= size || len <= 0) return ""
    val end = minOf(size, start + len)
    var e = start
    while (e < end && this[e].toInt() != 0) e++
    return String(this, start, e - start, Charsets.ISO_8859_1).trim()
}
