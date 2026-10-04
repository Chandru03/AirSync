package com.example.airsync.data.bluetooth

import com.example.airsync.domain.model.AirPodsModel
import com.example.airsync.domain.model.BatteryInfo
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState

/** Decoded Apple "proximity pairing" advertisement broadcast by AirPods. */
data class ProximityMessage(
    val model: AirPodsModel,
    val modelId: Int,
    val battery: BatteryInfo,
    val ear: EarStatus,
    val bothInCase: Boolean,
    val rssi: Int
)

/**
 * Parses Apple's manufacturer-specific BLE payload (company 0x004C, type 0x07).
 *
 * The format is reverse-engineered (OpenPods / furiousMAC continuity research); unknown bits are
 * ignored. Layout of the payload *after* the 2-byte company id, as returned by
 * `ScanRecord.getManufacturerSpecificData(0x004C)`:
 *
 * ```
 *  [0]    0x07  message type: proximity pairing
 *  [1]    0x19  length (25)
 *  [2]          prefix
 *  [3..4]       model id, e.g. 0x1B20 = AirPods 4 (ANC)
 *  [5]          status: 0x20 = left pod is the "primary"; 0x02 primary in ear; 0x08 secondary in ear
 *  [6]          pod batteries, one nibble each (0-10 → ×10 %, 15 = unknown)
 *  [7]          high nibble: charging flags (bit0/bit1 pods, bit2 case); low nibble: case battery
 *  [8..]        lid counter, colour, encrypted payload (unused)
 * ```
 */
object ProximityPairingParser {

    const val APPLE_COMPANY_ID = 0x004C
    const val TYPE_PROXIMITY_PAIRING: Byte = 0x07
    const val PAYLOAD_LENGTH: Byte = 0x19

    fun parse(data: ByteArray?, rssi: Int, nowMillis: Long = System.currentTimeMillis()): ProximityMessage? {
        if (data == null || data.size < 9) return null
        // Length byte varies by generation/firmware (0x19 on AirPods 2–Pro; newer may differ), so
        // only the message type is checked and the fixed-offset fields are read defensively.
        if (data[0] != TYPE_PROXIMITY_PAIRING || data.u(1) < MIN_PAYLOAD_LENGTH) return null

        val modelId = (data.u(3) shl 8) or data.u(4)
        val status = data.u(5)
        val pods = data.u(6)
        val flagsAndCase = data.u(7)

        // When bit 0x20 is clear the left/right nibbles are swapped ("flipped").
        val flipped = (status and 0x20) == 0
        val leftRaw = if (flipped) pods shr 4 else pods and 0x0F
        val rightRaw = if (flipped) pods and 0x0F else pods shr 4
        val caseRaw = flagsAndCase and 0x0F

        val chargeFlags = flagsAndCase shr 4
        val leftCharging = (chargeFlags and (if (flipped) 0b0010 else 0b0001)) != 0
        val rightCharging = (chargeFlags and (if (flipped) 0b0001 else 0b0010)) != 0
        val caseCharging = (chargeFlags and 0b0100) != 0

        // "Primary" is the pod doing the talking; 0x20 says whether that is the left one.
        val leftIsPrimary = !flipped
        val primaryInEar = (status and 0x02) != 0
        val secondaryInEar = (status and 0x08) != 0
        val bothInCase = (status and 0x04) != 0

        fun wear(inEar: Boolean, charging: Boolean, raw: Int): WearState = when {
            charging || bothInCase -> WearState.IN_CASE
            inEar -> WearState.IN_EAR
            raw == UNKNOWN_NIBBLE -> WearState.UNKNOWN
            else -> WearState.OUT_OF_EAR
        }

        val leftInEar = if (leftIsPrimary) primaryInEar else secondaryInEar
        val rightInEar = if (leftIsPrimary) secondaryInEar else primaryInEar

        return ProximityMessage(
            model = AirPodsModel.fromId(modelId),
            modelId = modelId,
            battery = BatteryInfo(
                left = PodBattery(level(leftRaw), leftCharging),
                right = PodBattery(level(rightRaw), rightCharging),
                case = PodBattery(level(caseRaw), caseCharging),
                source = BatterySource.BLE_BROADCAST,
                updatedAtMillis = nowMillis
            ),
            ear = EarStatus(
                left = wear(leftInEar, leftCharging, leftRaw),
                right = wear(rightInEar, rightCharging, rightRaw)
            ),
            bothInCase = bothInCase,
            rssi = rssi
        )
    }

    /** Adverts carry 10 % steps; 15 means "not reporting" (e.g. pod in a closed case). */
    private fun level(nibble: Int): Int? = when (nibble) {
        in 0..10 -> nibble * 10
        else -> null
    }

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private const val UNKNOWN_NIBBLE = 0x0F
    private const val MIN_PAYLOAD_LENGTH = 7
}
