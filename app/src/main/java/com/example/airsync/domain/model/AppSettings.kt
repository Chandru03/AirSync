package com.example.airsync.domain.model

data class BleKeyPair(val irkHex: String, val encKeyHex: String?)

/** Last case battery reading, kept across disconnects so the case is never just "—". */
data class CaseReading(val level: Int, val charging: Boolean, val atMillis: Long)

/** User preferences persisted in DataStore. */
data class AppSettings(
    /** Mode last chosen by the user/automation; mirrors the hardware when a control channel exists. */
    val noiseMode: AncMode = AncMode.ANC,
    val transparencyLevel: Float = 0.5f,
    val conversationalAwareness: Boolean = false,
    val removeToPause: Boolean = true,
    /** Nod to answer / shake to decline incoming calls (AirPods motion sensors). */
    val headGestures: Boolean = false,
    val backgroundEnabled: Boolean = true,
    val automationEnabled: Boolean = false,
    val walkingRule: Boolean = true,
    val noisyRule: Boolean = true,
    val stationaryRule: Boolean = true,
    val eqPreset: EqPreset = EqPreset.BALANCED,
    /** Per-app EQ overrides keyed by package name. */
    val appEqPresets: Map<String, EqPreset> = emptyMap(),
    /** Bluetooth address the user explicitly picked as their AirPods (for renamed devices). */
    val preferredDeviceAddress: String? = null,
    val lastSeen: LastSeenLocation? = null,
    /** BLE keys handed over by the AirPods, keyed by their Bluetooth address (hex strings). */
    val bleKeys: Map<String, BleKeyPair> = emptyMap(),
    /** Keyed by AirPods address. */
    val lastCase: Map<String, CaseReading> = emptyMap(),
    val loaded: Boolean = false
)
