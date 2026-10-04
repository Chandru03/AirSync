package com.example.airsync.data.bluetooth

import com.example.airsync.data.bluetooth.aap.AapProtocol
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `builds noise control commands`() {
        assertArrayEquals(
            bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x02, 0x00, 0x00, 0x00),
            AapProtocol.noiseModeCommand(AncMode.ANC)
        )
        assertArrayEquals(
            bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x04, 0x00, 0x00, 0x00),
            AapProtocol.noiseModeCommand(AncMode.ADAPTIVE)
        )
    }

    @Test
    fun `round-trips every mode`() {
        AncMode.entries.forEach { mode ->
            assertEquals(AapProtocol.Event.NoiseMode(mode), AapProtocol.parse(AapProtocol.noiseModeCommand(mode)))
        }
    }

    @Test
    fun `clamps adaptive strength`() {
        assertEquals(100.toByte(), AapProtocol.adaptiveStrengthCommand(150)[7])
        assertEquals(0.toByte(), AapProtocol.adaptiveStrengthCommand(-3)[7])
    }

    @Test
    fun `parses the documented AirPods Pro 2 battery example`() {
        // From LibrePods' AAP definitions: left 100 % discharging, right 99 % charging, case 17 %.
        val event = AapProtocol.parse(hex("04 00 04 00 04 00 03 02 01 64 02 01 04 01 63 01 01 08 01 11 02 01"))
            as AapProtocol.Event.Battery
        assertEquals(PodBattery(100, charging = false), event.right)
        assertEquals(PodBattery(99, charging = true), event.left)
        assertEquals(PodBattery(17, charging = false), event.case)
    }

    @Test
    fun `battery skips disconnected components`() {
        val packet = bytes(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x02,
            0x04, 0x01, 85, 0x02, 0x01,
            0x08, 0x01, 32, 0x04, 0x01
        )
        val event = AapProtocol.parse(packet) as AapProtocol.Event.Battery
        assertEquals(PodBattery(85), event.left)
        assertNull(event.right)
        assertNull(event.case)
    }

    @Test
    fun `parses ear detection`() {
        val event = AapProtocol.parse(bytes(0x04, 0x00, 0x04, 0x00, 0x06, 0x00, 0x00, 0x02))
        assertEquals(AapProtocol.Event.Ear(WearState.IN_EAR, WearState.IN_CASE), event)
    }

    @Test
    fun `parses conversational awareness state and speech levels`() {
        assertEquals(AapProtocol.Event.ConversationalAwareness(true), AapProtocol.parse(AapProtocol.conversationalAwarenessCommand(true)))
        assertEquals(AapProtocol.Event.ConversationalAwareness(false), AapProtocol.parse(AapProtocol.conversationalAwarenessCommand(false)))

        // Continuous curve: low level = duck hard, rising levels taper back, 8+ = normal.
        val speaking = AapProtocol.parse(hex("04 00 04 00 4B 00 02 00 01 01")) as AapProtocol.Event.ConversationLevel
        assertTrue(speaking.speaking)
        assertEquals(0.125f, speaking.volumeFraction, 0.001f)
        val mid = AapProtocol.parse(hex("04 00 04 00 4B 00 02 00 01 04")) as AapProtocol.Event.ConversationLevel
        assertTrue(mid.speaking)
        assertEquals(0.5f, mid.volumeFraction, 0.001f)
        val normal = AapProtocol.parse(hex("04 00 04 00 4B 00 02 00 01 09")) as AapProtocol.Event.ConversationLevel
        assertFalse(normal.speaking)
        assertEquals(1f, normal.volumeFraction, 0.001f)
    }

    @Test
    fun `parses the documented metadata example`() {
        val packet = hex(
            "040004001d0002d5000400416972506f64732050726f004133303438004170706c6520496e632e00" +
                "51584e5248485958503600" + "36312e313836383034303030323030303030302e3237313300" +
                "36312e313836383034303030323030303030302e3237313300312e302e3000"
        )
        val meta = AapProtocol.parse(packet) as AapProtocol.Event.Metadata
        assertEquals("AirPods Pro", meta.name)
        assertEquals("A3048", meta.modelNumber)
        assertEquals("QXNRHHYXP6", meta.serial)
        assertEquals("61.1868040002000000.2713", meta.firmware)
    }

    @Test
    fun `recognises the handshake ack`() {
        assertEquals(AapProtocol.Event.HandshakeAck, AapProtocol.parse(hex("01 00 04 00 00 00 00 00")))
    }

    @Test
    fun `ignores malformed and unknown packets`() {
        assertNull(AapProtocol.parse(bytes(0x01, 0x02)))
        assertNull(AapProtocol.parse(bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x05, 0x04))) // truncated battery
        assertNull(AapProtocol.parse(bytes(0x04, 0x00, 0x04, 0x00, 0x77, 0x00, 0x01, 0x02)))
        assertNull(AapProtocol.parse(bytes(0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0D, 0x09, 0x00, 0x00, 0x00)))
        assertNull(AapProtocol.parse(hex("04 00 04 00 4B 00 02 00 02 01")))
    }
}
