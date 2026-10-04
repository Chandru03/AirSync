package com.example.airsync.data.bluetooth.aap

import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState

/**
 * Apple Accessory Protocol (AAP / "AACP") — the private L2CAP protocol (PSM 0x1001) Apple devices
 * use to control AirPods. Frame layouts follow the community documentation (LibrePods
 * `docs/AAP Definitions.md`, verified against AirPods Pro 2 / AirPods 4 firmware). Every parser
 * is defensive and returns null on anything unexpected, because firmware updates change details.
 *
 * Frame: `04 00 04 00 <opcode LE16> <payload>`; control commands (opcode 0x09) are
 * `04 00 04 00 09 00 <id> <d1> <d2> <d3> <d4>`.
 */
object AapProtocol {

    const val PSM = 0x1001

    /** Required first; AirPods ignore everything until they see it. */
    val HANDSHAKE = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    /** Host capabilities (0x4D): unlocks Adaptive Audio and conversation awareness while audio plays. */
    val SET_FEATURES = bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
    /** Subscribe to battery, ear, listening-mode and conversation notifications. */
    val REQUEST_NOTIFICATIONS = bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)
    /** ListeningModeConfigs bitmask: Off | ANC | Transparency | Adaptive all selectable. */
    val ENABLE_ALL_LISTENING_MODES = control(CONTROL_LISTENING_MODE_CONFIGS, 0x0F)
    val ALLOW_OFF_MODE = control(CONTROL_ALLOW_OFF_OPTION, 0x01)
    /** Asks for the BLE keys (IRK + advert encryption key) so we can read the case's adverts. */
    val REQUEST_BLE_KEYS = bytes(0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00)
    /**
     * Head-tracking sensor control (opcode 0x17), as captured from an iPhone enabling head-tracked
     * Spatial Audio with AirPods 4. Each message carries `<stream> <action> <u32 arg>`:
     * action 0x04 = enable/mode, 0x01 = report interval in microseconds (0 = off).
     * Stream 0x10 is the 50 Hz motion stream; 0x0F/0x0D are 1 Hz companions.
     */
    fun headTrackingStart(seq: () -> Int): List<ByteArray> = listOf(
        sensorCommand(seq(), 0x10, 0x04, 1),
        sensorCommand(seq(), 0x10, 0x01, 20_000),
        sensorCommand(seq(), 0x0F, 0x01, 1_000_000),
        sensorCommand(seq(), 0x0D, 0x01, 1_000_000)
    )

    fun headTrackingStop(seq: () -> Int): List<ByteArray> = listOf(
        sensorCommand(seq(), 0x0D, 0x01, 0),
        sensorCommand(seq(), 0x0F, 0x01, 0),
        sensorCommand(seq(), 0x10, 0x04, 10),
        sensorCommand(seq(), 0x10, 0x01, 0)
    )

    /** `04 00 04 00 17 00 00 00 10 00 <len LE16> 08 <seq> 42 0B 08 <stream> 10 02 1A 05 <action> <u32 LE>` */
    internal fun sensorCommand(seq: Int, stream: Int, action: Int, arg: Int): ByteArray {
        val body = varintField(1, seq) + bytes(
            0x42, 0x0B, 0x08, stream, 0x10, 0x02, 0x1A, 0x05, action,
            arg and 0xFF, (arg shr 8) and 0xFF, (arg shr 16) and 0xFF, (arg ushr 24) and 0xFF
        )
        return bytes(0x04, 0x00, 0x04, 0x00, 0x17, 0x00, 0x00, 0x00, 0x10, 0x00,
            body.size and 0xFF, body.size shr 8) + body
    }

    private fun varintField(field: Int, value: Int): ByteArray {
        val out = ArrayList<Byte>()
        out += ((field shl 3) or 0).toByte()
        var v = value
        while (v >= 0x80) { out += ((v and 0x7F) or 0x80).toByte(); v = v ushr 7 }
        out += v.toByte()
        return out.toByteArray()
    }

    private val HEADER = bytes(0x04, 0x00, 0x04, 0x00)
    private val HANDSHAKE_ACK = bytes(0x01, 0x00, 0x04, 0x00)
    private const val OP_BATTERY = 0x04
    private const val OP_EAR = 0x06
    private const val OP_CONTROL = 0x09
    private const val OP_METADATA = 0x1D
    private const val OP_CONVERSATION = 0x4B
    private const val OP_BLE_KEYS = 0x31
    private const val OP_HEAD_TRACKING = 0x17

    const val CONTROL_LISTENING_MODE = 0x0D
    const val CONTROL_LISTENING_MODE_CONFIGS = 0x1A
    const val CONTROL_CONVERSATION_DETECT = 0x28
    const val CONTROL_ADAPTIVE_STRENGTH = 0x2E
    const val CONTROL_ALLOW_OFF_OPTION = 0x34

    sealed interface Event {
        data object HandshakeAck : Event
        data class NoiseMode(val mode: AncMode) : Event
        data class ConversationalAwareness(val enabled: Boolean) : Event
        /**
         * The AirPods send a stream of these as the wearer talks and stops: a low level means duck
         * hard (just started speaking), rising levels taper back toward normal (8–9 = done). The
         * intermediate values are exactly how iOS produces a smooth fade, so we map the whole range
         * continuously rather than in buckets.
         */
        data class ConversationLevel(val level: Int) : Event {
            val speaking: Boolean get() = level < NORMAL_LEVEL
            /** Fraction of the pre-conversation volume to hold at this level. */
            val volumeFraction: Float get() = when {
                level >= NORMAL_LEVEL -> 1f
                else -> (level / NORMAL_LEVEL.toFloat()).coerceIn(0.1f, 1f)
            }
        }
        data class Battery(val left: PodBattery?, val right: PodBattery?, val case: PodBattery?) : Event
        data class Ear(val primary: WearState, val secondary: WearState) : Event
        /** IRK resolves the AirPods' rotating BLE address; encKey decrypts the advert's battery block. */
        data class BleKeys(val irk: ByteArray, val encKey: ByteArray?) : Event

        /**
         * One 50 Hz motion sample. [gx]/[gy]/[gz] are raw gyroscope rates (zero at rest);
         * [ax]/[ay]/[az] are acceleration in milli-g (|a| ≈ 1000 at rest, i.e. gravity).
         */
        data class HeadPose(
            val gx: Int, val gy: Int, val gz: Int,
            val ax: Int, val ay: Int, val az: Int
        ) : Event

        data class Metadata(
            val name: String,
            val modelNumber: String,
            val serial: String,
            val firmware: String
        ) : Event
    }

    fun noiseModeCommand(mode: AncMode): ByteArray = control(CONTROL_LISTENING_MODE, mode.wireValue())
    fun conversationalAwarenessCommand(enabled: Boolean): ByteArray =
        control(CONTROL_CONVERSATION_DETECT, if (enabled) 0x01 else 0x02)
    /** 0 = let the most sound through, 100 = filter the most (only applies in Adaptive). */
    fun adaptiveStrengthCommand(level0to100: Int): ByteArray =
        control(CONTROL_ADAPTIVE_STRENGTH, level0to100.coerceIn(0, 100))

    fun parse(packet: ByteArray): Event? {
        if (packet.startsWith(HANDSHAKE_ACK)) return Event.HandshakeAck
        if (packet.size < 6 || !packet.startsWith(HEADER)) return null
        return when (packet.u(4) or (packet.u(5) shl 8)) {
            OP_CONTROL -> parseControl(packet)
            OP_BATTERY -> parseBattery(packet)
            OP_EAR -> parseEar(packet)
            OP_CONVERSATION -> parseConversation(packet)
            OP_METADATA -> parseMetadata(packet)
            OP_BLE_KEYS -> parseBleKeys(packet)
            OP_HEAD_TRACKING -> parseHeadPose(packet)
            else -> null
        }
    }

    private fun parseControl(p: ByteArray): Event? {
        if (p.size < 8) return null
        val value = p.u(7)
        return when (p.u(6)) {
            CONTROL_LISTENING_MODE -> modeFromWire(value)?.let { Event.NoiseMode(it) }
            CONTROL_CONVERSATION_DETECT -> when (value) {
                0x01 -> Event.ConversationalAwareness(true)
                0x02 -> Event.ConversationalAwareness(false)
                else -> null
            }
            else -> null
        }
    }

    /** `04 00 04 00 04 00 <count> { <component> 01 <level> <status> 01 }*` */
    private fun parseBattery(p: ByteArray): Event? {
        if (p.size < 7) return null
        val count = p.u(6)
        if (p.size < 7 + count * 5) return null
        var left: PodBattery? = null
        var right: PodBattery? = null
        var case: PodBattery? = null
        repeat(count) { i ->
            val o = 7 + i * 5
            val status = p.u(o + 3)
            if (status == STATUS_DISCONNECTED) return@repeat
            val battery = PodBattery(p.u(o + 2).coerceIn(0, 100), charging = status == STATUS_CHARGING)
            when (p.u(o)) {
                COMPONENT_LEFT -> left = battery
                COMPONENT_RIGHT -> right = battery
                COMPONENT_CASE -> case = battery
            }
        }
        return Event.Battery(left, right, case)
    }

    /** `04 00 04 00 06 00 <primary> <secondary>`; 0 = in ear, 1 = out, 2 = in case. */
    private fun parseEar(p: ByteArray): Event? {
        if (p.size < 8) return null
        fun state(v: Int) = when (v) {
            0x00 -> WearState.IN_EAR
            0x01 -> WearState.OUT_OF_EAR
            0x02 -> WearState.IN_CASE
            else -> WearState.UNKNOWN
        }
        return Event.Ear(state(p.u(6)), state(p.u(7)))
    }

    /** `04 00 04 00 4B 00 02 00 01 <level>` */
    private fun parseConversation(p: ByteArray): Event? {
        if (p.size < 10 || p.u(6) != 0x02 || p.u(8) != 0x01) return null
        return Event.ConversationLevel(p.u(9))
    }

    /**
     * `04 00 04 00 1D <…> <name>\0<model>\0<manufacturer>\0<serial>\0<firmware>\0…`
     * The first string starts after a short, firmware-dependent prefix, so we locate it by
     * scanning for the first printable run that is followed by a NUL.
     */
    private fun parseMetadata(p: ByteArray): Event? {
        val start = (6 until p.size).firstOrNull { i -> p[i].toInt().let { it in 0x20..0x7E } } ?: return null
        val fields = ArrayList<String>()
        var i = start
        while (i < p.size && fields.size < 5) {
            val end = (i until p.size).firstOrNull { p[it] == 0.toByte() } ?: p.size
            fields += String(p, i, end - i, Charsets.UTF_8)
            i = end + 1
        }
        if (fields.size < 5) return null
        return Event.Metadata(name = fields[0], modelNumber = fields[1], serial = fields[3], firmware = fields[4])
    }

    /** `04 00 04 00 31 00 <count> { <type> 00 <len> 00 <key…> }*`; type 0x01 = IRK, 0x04 = enc key. */
    private fun parseBleKeys(p: ByteArray): Event? {
        if (p.size < 7) return null
        var irk: ByteArray? = null
        var enc: ByteArray? = null
        var o = 7
        repeat(p.u(6)) {
            if (o + 4 > p.size) return@repeat
            val type = p.u(o)
            val len = p.u(o + 2)
            o += 4
            if (o + len > p.size) return@repeat
            val key = p.copyOfRange(o, o + len)
            o += len
            if (len == 16) when (type) {
                0x01 -> irk = key
                0x04 -> enc = key
            }
        }
        return irk?.let { Event.BleKeys(it, enc) }
    }

    /**
     * Motion frame: `… 08 <seq varint> 10 <kind> 1A <len> <sample>`. Two shapes have been seen:
     * kind 0x05 / 58 bytes (iPhone host) and kind 0x01 / 60 bytes (Android host). In both, the
     * gyro sits at sample offsets 28/30/32 and the accelerometer at 46/48/50 — identified from
     * recordings (gravity magnitude ≈ 1 g ±1 %, gyro zero-mean and bursty with head motion).
     */
    private fun parseHeadPose(p: ByteArray): Event? {
        var i = 12
        if (p.size <= i || p.u(i) != 0x08) return null
        i++
        while (i < p.size && (p.u(i) and 0x80) != 0) i++
        i++
        if (i + 4 > p.size || p.u(i) != 0x10 || p.u(i + 2) != 0x1A) return null
        val kind = p.u(i + 1)
        val len = p.u(i + 3)
        val o = i + 4
        val known = (kind == 0x05 && len == 58) || (kind == 0x01 && len == 60)
        if (!known || o + len > p.size) return null
        fun s16(k: Int) = ((p.u(o + k + 1) shl 8) or p.u(o + k)).toShort().toInt()
        return Event.HeadPose(
            gx = s16(28), gy = s16(30), gz = s16(32),
            ax = s16(46), ay = s16(48), az = s16(50)
        )
    }

    private fun control(id: Int, value: Int): ByteArray =
        bytes(0x04, 0x00, 0x04, 0x00, OP_CONTROL, 0x00, id, value, 0x00, 0x00, 0x00)

    private fun AncMode.wireValue() = when (this) {
        AncMode.OFF -> 0x01
        AncMode.ANC -> 0x02
        AncMode.TRANSPARENCY -> 0x03
        AncMode.ADAPTIVE -> 0x04
    }

    private fun modeFromWire(v: Int) = when (v) {
        0x01 -> AncMode.OFF
        0x02 -> AncMode.ANC
        0x03 -> AncMode.TRANSPARENCY
        0x04 -> AncMode.ADAPTIVE
        else -> null
    }

    /** Level at or above which the wearer is considered done speaking. */
    private const val NORMAL_LEVEL = 8

    private const val COMPONENT_RIGHT = 0x02
    private const val COMPONENT_LEFT = 0x04
    private const val COMPONENT_CASE = 0x08
    private const val STATUS_CHARGING = 0x01
    private const val STATUS_DISCONNECTED = 0x04

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ByteArray.u(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
