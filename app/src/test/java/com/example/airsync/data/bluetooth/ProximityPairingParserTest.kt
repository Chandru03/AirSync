package com.example.airsync.data.bluetooth

import com.example.airsync.domain.model.AirPodsModel
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.domain.model.WearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityPairingParserTest {

    private fun payload(status: Int, pods: Int, flagsAndCase: Int, model: Int = 0x1B20): ByteArray =
        ByteArray(27).also {
            it[0] = 0x07; it[1] = 0x19; it[2] = 0x01
            it[3] = (model shr 8).toByte(); it[4] = model.toByte()
            it[5] = status.toByte(); it[6] = pods.toByte(); it[7] = flagsAndCase.toByte()
        }

    @Test
    fun `decodes AirPods 4 ANC with left as primary`() {
        // 0x20 = left primary, 0x02 = primary in ear. Pods 0x86: right=8, left=6. Case charging at 50 %.
        val msg = ProximityPairingParser.parse(payload(status = 0x22, pods = 0x86, flagsAndCase = 0x45), rssi = -50)!!

        assertEquals(AirPodsModel.AIRPODS_4_ANC, msg.model)
        assertEquals(60, msg.battery.left.level)
        assertEquals(80, msg.battery.right.level)
        assertEquals(50, msg.battery.case.level)
        assertTrue(msg.battery.case.charging)
        assertFalse(msg.battery.left.charging)
        assertEquals(BatterySource.BLE_BROADCAST, msg.battery.source)
        assertEquals(WearState.IN_EAR, msg.ear.left)
        assertEquals(WearState.OUT_OF_EAR, msg.ear.right)
        assertEquals(-50, msg.rssi)
    }

    @Test
    fun `flipped payload swaps nibbles and charging bits`() {
        // 0x20 clear → flipped: left = high nibble. Both in ear (0x02 | 0x08).
        val msg = ProximityPairingParser.parse(payload(status = 0x0A, pods = 0x9A, flagsAndCase = 0x2F), rssi = -60)!!

        assertEquals(90, msg.battery.left.level)
        assertEquals(100, msg.battery.right.level)
        assertNull("case nibble 15 means unknown", msg.battery.case.level)
        assertTrue("bit 1 is the left pod when flipped", msg.battery.left.charging)
        assertEquals(WearState.IN_CASE, msg.ear.left) // charging wins over in-ear
        assertEquals(WearState.IN_EAR, msg.ear.right)
    }

    @Test
    fun `unknown pod nibble without ear flag is unknown wear state`() {
        val msg = ProximityPairingParser.parse(payload(status = 0x20, pods = 0xF5, flagsAndCase = 0x0F), rssi = -40)!!
        assertNull(msg.battery.right.level)
        assertEquals(WearState.UNKNOWN, msg.ear.right)
        assertEquals(WearState.OUT_OF_EAR, msg.ear.left)
    }

    @Test
    fun `both in case flag marks both pods in case`() {
        val msg = ProximityPairingParser.parse(payload(status = 0x24, pods = 0x88, flagsAndCase = 0x08), rssi = -40)!!
        assertTrue(msg.bothInCase)
        assertEquals(WearState.IN_CASE, msg.ear.left)
        assertEquals(WearState.IN_CASE, msg.ear.right)
    }

    @Test
    fun `rejects other Apple messages and short payloads`() {
        assertNull(ProximityPairingParser.parse(byteArrayOf(0x10, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07), -40))
        assertNull(ProximityPairingParser.parse(byteArrayOf(0x07, 0x19, 0x01), -40))
        assertNull(ProximityPairingParser.parse(null, -40))
    }

    @Test
    fun `unknown model id is still parsed`() {
        val msg = ProximityPairingParser.parse(payload(0x22, 0x55, 0x05, model = 0x9999), rssi = -40)!!
        assertEquals(AirPodsModel.UNKNOWN, msg.model)
        assertEquals(0x9999, msg.modelId)
    }
}
