package com.example.airsync.domain.model

/** Listening modes supported by AirPods 4 (ANC). Order matters: it is the Quick Settings cycle order. */
enum class AncMode {
    ANC, TRANSPARENCY, ADAPTIVE, OFF;

    /** ANC → Transparency → Adaptive → ANC. OFF re-enters the cycle at ANC. */
    fun next(): AncMode = when (this) {
        ANC -> TRANSPARENCY
        TRANSPARENCY -> ADAPTIVE
        ADAPTIVE, OFF -> ANC
    }
}

enum class EqPreset { BALANCED, BASS_BOOST, VOCAL_CLARITY }

enum class BluetoothStatus { UNSUPPORTED, PERMISSION_REQUIRED, OFF, ON }

/**
 * Whether we have a live Apple Accessory Protocol (AAP) channel to the AirPods.
 * Without it, mode changes are tracked locally only (the AirPods themselves are unchanged).
 */
enum class ControlChannel { UNAVAILABLE, CONNECTING, CONNECTED }

/** Where the battery numbers came from, from least to most precise. */
enum class BatterySource { NONE, HEADSET_PROFILE, BLE_BROADCAST, CONTROL_CHANNEL }

enum class WearState { IN_EAR, OUT_OF_EAR, IN_CASE, UNKNOWN }

data class PodBattery(val level: Int? = null, val charging: Boolean = false) {
    val isKnown: Boolean get() = level != null
}

data class BatteryInfo(
    val left: PodBattery = PodBattery(),
    val right: PodBattery = PodBattery(),
    val case: PodBattery = PodBattery(),
    val source: BatterySource = BatterySource.NONE,
    val updatedAtMillis: Long = 0L,
    /** When [case] was last reported by the hardware; the case only reports while a bud is docked. */
    val caseUpdatedAtMillis: Long = 0L
) {
    /** Lowest known bud level, used by the widget/notification summaries. */
    val lowestBud: Int? get() = listOfNotNull(left.level, right.level).minOrNull()
}

data class EarStatus(
    val left: WearState = WearState.UNKNOWN,
    val right: WearState = WearState.UNKNOWN
) {
    val isKnown: Boolean get() = left != WearState.UNKNOWN || right != WearState.UNKNOWN
    val budsInEar: Int get() = listOf(left, right).count { it == WearState.IN_EAR }
}

data class AirPodsDevice(
    val name: String,
    val address: String,
    val model: AirPodsModel = AirPodsModel.UNKNOWN,
    /** Apple part number (e.g. A3053) and firmware build, from the AAP metadata frame. */
    val modelNumber: String? = null,
    val firmware: String? = null
)

/** Apple model identifiers as seen in proximity-pairing adverts (bytes 3..4, big-endian). */
enum class AirPodsModel(val modelId: Int, val displayName: String) {
    AIRPODS_4_ANC(0x1B20, "AirPods 4"),
    AIRPODS_4(0x1920, "AirPods 4"),
    AIRPODS_3(0x1320, "AirPods (3rd gen)"),
    AIRPODS_2(0x0F20, "AirPods (2nd gen)"),
    AIRPODS_1(0x0220, "AirPods"),
    AIRPODS_PRO(0x0E20, "AirPods Pro"),
    AIRPODS_PRO_2(0x1420, "AirPods Pro 2"),
    AIRPODS_PRO_2_USB_C(0x2420, "AirPods Pro 2"),
    AIRPODS_MAX(0x0A20, "AirPods Max"),
    UNKNOWN(-1, "AirPods");

    companion object {
        fun fromId(id: Int): AirPodsModel = entries.firstOrNull { it.modelId == id } ?: UNKNOWN

        /** Apple part numbers as reported in the AAP metadata frame. */
        fun fromPartNumber(part: String?): AirPodsModel = when (part?.uppercase()) {
            "A3053", "A3054", "A3055", "A3056", "A3057", "A3058" -> AIRPODS_4_ANC
            "A2564", "A2565", "A2566" -> AIRPODS_3
            "A2031", "A2032" -> AIRPODS_2
            "A1523", "A1722" -> AIRPODS_1
            "A2083", "A2084" -> AIRPODS_PRO
            "A2698", "A2699", "A2700" -> AIRPODS_PRO_2
            "A3047", "A3048", "A3049" -> AIRPODS_PRO_2_USB_C
            "A2096" -> AIRPODS_MAX
            else -> UNKNOWN
        }
    }
}

/** Live, hardware-derived state. Persisted user preferences live in [AppSettings]. */
data class AirPodsStatus(
    val bluetooth: BluetoothStatus = BluetoothStatus.ON,
    val device: AirPodsDevice? = null,
    val battery: BatteryInfo = BatteryInfo(),
    val ear: EarStatus = EarStatus(),
    val noiseMode: AncMode = AncMode.OFF,
    val controlChannel: ControlChannel = ControlChannel.UNAVAILABLE,
    val isScanning: Boolean = false,
    /** True while the AirPods report the wearer is talking (hardware conversation awareness). */
    val wearerSpeaking: Boolean = false
) {
    val isConnected: Boolean get() = device != null
    val canControlHardware: Boolean get() = controlChannel == ControlChannel.CONNECTED
}

data class LastSeenLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long
)

/** Coarse user activity from Activity Recognition, consumed by the automation engine. */
enum class UserActivity { UNKNOWN, STILL, WALKING, RUNNING, ON_BICYCLE, IN_VEHICLE }
