package com.poted.pilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test vectors taken from the official DJI docs / test files. */
class FrameTest {

    // docs/protocol_data_segment.md, "Mode Switch (1D04)" example (Hyperlapse, seq 5).
    private val modeSwitchHyperlapse =
        "AA 1B 00 01 00 00 00 00 05 00 57 EE 1D 04 00 00 33 FF 0A 01 47 39 36 F4 FA E1 D0"

    // test/connect_cmd_frame_builder/connect_cmd_frame.txt, first line.
    private val connectRequest =
        "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000FE1F00000000796C667C"

    @Test
    fun encodesModeSwitchExample() {
        val f = Frame(CmdType.CMD_RESPONSE_OR_NOT, 5, 0x1D, 0x04, Commands.modeSwitch(0x0A))
        assertEquals(modeSwitchHyperlapse.replace(" ", ""), f.encode().toHex(""))
    }

    @Test
    fun encodesConnectionRequestExample() {
        val payload = Commands.connectionRequest(
            deviceId = 0x12345678L,
            mac = hexToBytes("38 34 56 78 9A BC"),
            verifyMode = 0,
            verifyData = 0x1FFE,
        )
        assertEquals(33, payload.size)
        val f = Frame(CmdType.CMD_WAIT_RESULT, 1, 0x00, 0x19, payload)
        assertEquals(connectRequest, f.encode().toHex(""))
    }

    @Test
    fun allConnectVectorsDecode() {
        val lines = listOf(
            "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000191A0000000033C8C7BF",
            "AA330002000000000100982F00197856341206383456789ABC000000000000000000000000000000008F1200000000AC768844",
            "AA330002000000000100982F00197856341206383456789ABC00000000000000000000000000000000E10200000000048F9616",
        )
        for (l in lines) {
            val r = Frame.decode(hexToBytes(l))
            assertTrue(r.toString(), r is Frame.Companion.DecodeResult.Ok)
            val f = (r as Frame.Companion.DecodeResult.Ok).frame
            val req = Commands.parseConnectionRequest(f.payload)!!
            assertEquals(0x12345678L, req.deviceId)
            assertEquals(0, req.verifyMode)
        }
    }

    @Test
    fun decodeRoundTrip() {
        val f = Frame(CmdType.ACK_NO_RESPONSE, 0xBEEF, 0x00, 0x19, Commands.connectionResponse(0x1234L))
        val r = Frame.decode(f.encode()) as Frame.Companion.DecodeResult.Ok
        assertEquals(0xBEEF, r.frame.seq)
        assertTrue(r.frame.isResponse)
        assertArrayEquals(f.payload, r.frame.payload)
    }

    @Test
    fun decodeRejectsBadCrc() {
        val b = hexToBytes(modeSwitchHyperlapse)
        b[b.size - 1] = 0
        assertTrue(Frame.decode(b) is Frame.Companion.DecodeResult.Error)
    }

    @Test
    fun reassemblerHandlesSplitAndConcatenatedFrames() {
        val a = hexToBytes(modeSwitchHyperlapse)
        val b = hexToBytes(connectRequest)
        val got = mutableListOf<Frame>()
        val errors = mutableListOf<String>()
        val r = FrameReassembler({ f, _ -> got += f }, { e, _ -> errors += e })
        val stream = byteArrayOf(0x01, 0x02) + a + b
        // Feed in awkward 7-byte chunks.
        stream.toList().chunked(7).forEach { r.feed(it.toByteArray()) }
        assertEquals(2, got.size)
        assertEquals(0x04, got[0].cmdId)
        assertEquals(0x19, got[1].cmdId)
        assertEquals(1, errors.size) // the two leading junk bytes
    }

    @Test
    fun parsesStatusPush() {
        val p = ByteArray(38)
        p[0] = 0x01; p[1] = 0x03
        p[5] = 0x3C; p[6] = 0x00          // 60 s
        p[15] = 0x00; p[16] = 0x10        // 4096 MB
        p[23] = 0x10; p[24] = 0x0E        // 3600 s
        p[37] = 77
        val s = CameraStatus.parse(p)!!
        assertTrue(s.isRecording)
        assertEquals(60, s.recordTimeSec)
        assertEquals(4096L, s.remainCapacityMb)
        assertEquals(3600L, s.remainTimeSec)
        assertEquals(77, s.batteryPercent)
    }

    @Test
    fun parsesNewStatusPush() {
        val p = ByteArray(46)
        p[0] = 1; p[1] = 5; "Video".toByteArray().copyInto(p, 2)
        p[23] = 2; p[24] = 9; "4K|60fps|".toByteArray().copyInto(p, 25)
        val m = Commands.parseNewStatus(p)!!
        assertEquals("Video", m.name)
        assertEquals("4K|60fps|", m.param)
    }
}
