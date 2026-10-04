package com.example.airsync.data.bluetooth

import com.example.airsync.data.bluetooth.aap.AapProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Byte-exact against an iPhone capture of AirPods 4 head-tracked Spatial Audio. */
class HeadTrackingProtocolTest {

    private fun hex(s: String) = s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `sensor command matches the captured iPhone bytes`() {
        assertArrayEquals(
            hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 58 42 0b 08 10 10 02 1a 05 01 20 4e 00 00"),
            AapProtocol.sensorCommand(seq = 0x58, stream = 0x10, action = 0x01, arg = 20_000)
        )
        assertArrayEquals(
            hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 59 42 0b 08 0f 10 02 1a 05 01 40 42 0f 00"),
            AapProtocol.sensorCommand(seq = 0x59, stream = 0x0F, action = 0x01, arg = 1_000_000)
        )
    }

    @Test
    fun `multi-byte sequence numbers are varint encoded`() {
        val cmd = AapProtocol.sensorCommand(seq = 300, stream = 0x10, action = 0x01, arg = 0)
        // 300 = 0xAC 0x02 as a varint; body grows by one byte, reflected in the length field.
        assertEquals(0x10, cmd[10].toInt())
        assertEquals(0xAC.toByte(), cmd[13])
        assertEquals(0x02.toByte(), cmd[14])
    }

    @Test
    fun `start and stop sequences mirror the iPhone`() {
        var n = 0x57
        val start = AapProtocol.headTrackingStart { n++ }
        assertEquals(4, start.size)
        assertArrayEquals(hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 57 42 0b 08 10 10 02 1a 05 04 01 00 00 00"), start[0])
        var m = 0x5b
        val stop = AapProtocol.headTrackingStop { m++ }
        assertArrayEquals(hex("04 00 04 00 17 00 00 00 10 00 0f 00 08 5e 42 0b 08 10 10 02 1a 05 01 00 00 00 00"), stop[3])
    }

    @Test
    fun `decodes an iPhone-format motion frame`() {
        val pose = AapProtocol.parse(hex("04 00 04 00 17 00 00 00 10 00 43 00 08 8a 17 10 05 1a 3a 10 00 01 b8 2d 2c 5f 54 03 00 00 06 00 42 f1 25 6f 96 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 62 10 03 f5 56 06 78 02 0b 03 1d 03 76 02 0d 03 1e ff 18 3e f0 37 5a 25 a0 00")) as AapProtocol.Event.HeadPose
        assertEquals(listOf(0, 0, 0), listOf(pose.gx, pose.gy, pose.gz))
        assertEquals(listOf(630, 781, -226), listOf(pose.ax, pose.ay, pose.az))
        assertGravity(pose)
    }

    @Test
    fun `decodes an Android-format motion frame from the Fold`() {
        val pose = AapProtocol.parse(hex("04 00 04 00 17 00 00 00 10 00 43 00 08 be 2d 10 01 1a 3c 10 00 01 20 93 e2 47 8d 35 00 00 03 00 82 9d d3 c2 ab 00 00 00 00 00 00 00 00 00 00 44 00 19 00 8b 00 bd 08 03 f5 56 06 34 01 ed 03 c5 03 5d 01 dc 03 a3 ff 02 78 0f 3a 7e 49 00 00")) as AapProtocol.Event.HeadPose
        assertEquals(listOf(68, 25, 139), listOf(pose.gx, pose.gy, pose.gz))
        assertEquals(listOf(349, 988, -93), listOf(pose.ax, pose.ay, pose.az))
        assertGravity(pose)
    }

    /** The accelerometer reads gravity: magnitude ~1 g in milli-g. */
    private fun assertGravity(pose: AapProtocol.Event.HeadPose) {
        val g = sqrt((pose.ax * pose.ax + pose.ay * pose.ay + pose.az * pose.az).toDouble())
        assertTrue("gravity magnitude was $g", g in 900.0..1100.0)
    }

    @Test
    fun `ignores the 1 Hz status frames and the rejection ack`() {
        assertNull(AapProtocol.parse(hex("04 00 04 00 17 00 00 00 10 00 08 00 08 05 10 01 4a 02 08 0e")))
    }
}
