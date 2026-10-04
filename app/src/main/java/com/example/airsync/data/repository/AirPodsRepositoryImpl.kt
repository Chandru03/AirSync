package com.example.airsync.data.repository

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.airsync.data.bluetooth.BleCrypto
import com.example.airsync.data.bluetooth.BleCrypto.hexToBytes
import com.example.airsync.data.bluetooth.BleCrypto.toHex
import com.example.airsync.data.bluetooth.BluetoothService
import com.example.airsync.data.bluetooth.ProximityMessage
import com.example.airsync.data.bluetooth.aap.AapClient
import com.example.airsync.data.bluetooth.aap.AapProtocol
import com.example.airsync.data.bluetooth.aap.EarSideResolver
import com.example.airsync.data.context.ContextSignals
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.automation.AutomationEngine
import com.example.airsync.domain.model.AirPodsDevice
import com.example.airsync.domain.model.AirPodsModel
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.BatteryInfo
import com.example.airsync.domain.model.BatterySource
import com.example.airsync.domain.model.BleKeyPair
import com.example.airsync.domain.model.CaseReading
import com.example.airsync.domain.model.BluetoothStatus
import com.example.airsync.domain.model.ControlChannel
import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Merges every source of AirPods truth into one [AirPodsStatus]:
 *
 *  | data            | best source → fallback                                   |
 *  |-----------------|-----------------------------------------------------------|
 *  | connection      | A2DP/HFP profile state (never "bonded")                   |
 *  | battery         | AAP notifications → BLE adverts → HFP single level         |
 *  | ear detection   | AAP notifications → BLE adverts → unknown                  |
 *  | noise mode      | AAP (real hardware) → locally tracked preference           |
 */
@Singleton
class AirPodsRepositoryImpl @Inject constructor(
    private val bluetooth: BluetoothService,
    private val aap: AapClient,
    private val settings: SettingsRepository,
    private val signals: ContextSignals,
    @ApplicationScope private val scope: CoroutineScope
) : AirPodsRepository {

    private val ble = MutableStateFlow<ProximityMessage?>(null)
    private val aapBattery = MutableStateFlow<BatteryInfo?>(null)
    private val aapEar = MutableStateFlow<EarStatus?>(null)
    private val aapMode = MutableStateFlow<AncMode?>(null)
    private val aapMeta = MutableStateFlow<AapProtocol.Event.Metadata?>(null)
    private val earSides = EarSideResolver()
    private val _conversation = MutableStateFlow<AapProtocol.Event.ConversationLevel?>(null)
    /** Latest hardware conversation-awareness level (null until the AirPods report one). */
    val conversation: StateFlow<AapProtocol.Event.ConversationLevel?> = _conversation.asStateFlow()
    private val scanOwners = MutableStateFlow<Set<String>>(emptySet())

    /**
     * BLE adverts use rotating random addresses, so they can't be matched to the bonded device by
     * MAC. We lock onto the first strong advert's model while our AirPods are connected and then
     * only accept that model (with a looser RSSI gate). Strangers' AirPods are usually far weaker.
     */
    @Volatile private var lockedModelId: Int? = null

    /** Last mode we asked for; DataStore writes are async, so rapid tile taps must not read stale state. */
    @Volatile private var lastRequestedMode: AncMode? = null

    private val connectedDevice: StateFlow<BluetoothDevice?> = combine(
        bluetooth.connectedAudio,
        settings.settings.map { it.preferredDeviceAddress }.distinctUntilChanged()
    ) { devices, preferred ->
        devices[preferred] ?: devices.values.firstOrNull { isAirPodsName(bluetooth.nameOf(it)) }
    }.distinctUntilChanged { a, b -> a?.address == b?.address }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val hardware = combine(
        connectedDevice, ble, aapBattery, aapEar, bluetooth.headsetBattery, aapMeta
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val device = values[0] as BluetoothDevice?
        Hardware(
            device = device,
            ble = values[1] as ProximityMessage?,
            aapBattery = values[2] as BatteryInfo?,
            aapEar = values[3] as EarStatus?,
            hfpLevel = device?.let { (values[4] as Map<String, Int>)[it.address] },
            meta = values[5] as AapProtocol.Event.Metadata?
        )
    }

    override val status: StateFlow<AirPodsStatus> = combine(
        bluetooth.status,
        hardware,
        combine(aap.channel, aapMode, settings.settings.map { it.noiseMode }, ::Triple),
        bluetooth.isScanning,
        _conversation
    ) { btStatus, hw, (channel, hwMode, savedMode), scanning, conversation ->
        val device = hw.device?.let {
            val model = hw.ble?.model ?: hw.meta?.let { m -> AirPodsModel.fromPartNumber(m.modelNumber) } ?: AirPodsModel.UNKNOWN
            AirPodsDevice(
                name = bluetooth.nameOf(it) ?: hw.meta?.name ?: model.displayName,
                address = it.address,
                model = model,
                modelNumber = hw.meta?.modelNumber,
                firmware = hw.meta?.firmware
            )
        }
        AirPodsStatus(
            bluetooth = btStatus,
            device = device,
            battery = if (device == null) BatteryInfo() else mergeBattery(hw),
            ear = if (device == null) EarStatus() else hw.aapEar ?: hw.ble?.ear ?: EarStatus(),
            noiseMode = if (channel == ControlChannel.CONNECTED) hwMode ?: savedMode else savedMode,
            controlChannel = if (device == null) ControlChannel.UNAVAILABLE else channel,
            isScanning = scanning,
            wearerSpeaking = device != null && channel == ControlChannel.CONNECTED && conversation?.speaking == true
        )
    }.stateIn(scope, SharingStarted.Eagerly, AirPodsStatus(bluetooth = bluetooth.status.value))

    init {
        // Device (dis)connected: reset per-session state and manage the AAP channel.
        connectedDevice.onEach { device ->
            ble.value = null
            lockedModelId = null
            aapBattery.value = null
            aapEar.value = null
            aapMode.value = null
            aapMeta.value = null
            earSides.reset()
            _conversation.value = null
            if (device != null) aap.connect(device) else aap.disconnect()
        }.launchIn(scope)

        bluetooth.proximity.onEach(::onAdvert).launchIn(scope)
        aap.events.onEach(::onAapEvent).launchIn(scope)
        bluetooth.ownAdverts.onEach(::onOwnAdvert).launchIn(scope)

        // Keep the resolver's key table in sync with persisted keys.
        settings.settings.map { it.bleKeys }.distinctUntilChanged().onEach { keys ->
            bluetooth.identityKeys = keys.mapValues { (_, k) -> k.irkHex.hexToBytes() }
        }.launchIn(scope)

        // Scan only while AirPods are connected AND something needs live data. If Android throttles
        // or kills the scan, retry with a back-off instead of silently losing ear detection.
        scope.launch {
            combine(connectedDevice, scanOwners, bluetooth.status, aap.channel) { device, owners, bt, channel ->
                // AirPods 4 (fw 8B39+) never advertise, so adverts are only a fallback for older
                // models when there is no AAP channel. Keys are still exchanged so own adverts can
                // be recognised and decrypted if a model does send them.
                val needed = device != null && owners.isNotEmpty() && bt == BluetoothStatus.ON &&
                    channel != ControlChannel.CONNECTED
                needed to (UI_OWNER in owners)
            }.distinctUntilChanged().collectLatest { (shouldScan, uiVisible) ->
                bluetooth.stopScan()
                if (!shouldScan) return@collectLatest
                while (true) {
                    if (bluetooth.startScan(lowLatency = uiVisible)) bluetooth.isScanning.first { !it }
                    delay(SCAN_RETRY_MS)
                }
            }
        }
    }

    override fun bondedAudioDevices(): List<AirPodsDevice> =
        bluetooth.bondedAudioDevices().map { AirPodsDevice(bluetooth.nameOf(it) ?: it.address, it.address) }

    override fun refresh() = bluetooth.refresh()

    override fun setScanDemand(owner: String, active: Boolean) =
        scanOwners.update { if (active) it + owner else it - owner }

    override suspend fun setNoiseMode(mode: AncMode, fromUser: Boolean) {
        lastRequestedMode = mode
        if (fromUser) {
            signals.manualOverrideUntilMillis.value =
                System.currentTimeMillis() + AutomationEngine.MANUAL_OVERRIDE_MILLIS
        }
        settings.setNoiseMode(mode)
        if (aap.send(AapProtocol.noiseModeCommand(mode))) aapMode.value = mode
    }

    override suspend fun cycleNoiseMode() {
        val saved = settings.awaitLoaded().noiseMode
        val current = when {
            aap.channel.value == ControlChannel.CONNECTED && aapMode.value != null -> aapMode.value!!
            else -> lastRequestedMode ?: saved
        }
        setNoiseMode(current.next())
    }

    override suspend fun setTransparencyLevel(level: Float) {
        settings.setTransparencyLevel(level)
        aap.send(AapProtocol.adaptiveStrengthCommand((level * 100).toInt()))
    }

    private val _headPose = kotlinx.coroutines.flow.MutableSharedFlow<AapProtocol.Event.HeadPose>(extraBufferCapacity = 64)
    /** Live head-tracking samples while tracking is enabled. */
    val headPose: kotlinx.coroutines.flow.SharedFlow<AapProtocol.Event.HeadPose> = _headPose

    private val sensorSeq = java.util.concurrent.atomic.AtomicInteger(1)

    override suspend fun setHeadTracking(enabled: Boolean): Boolean {
        val next = { sensorSeq.getAndIncrement() and 0x3FFF }
        val packets = if (enabled) AapProtocol.headTrackingStart(next) else AapProtocol.headTrackingStop(next)
        return packets.all { aap.send(it) }
    }

    override suspend fun setConversationalAwareness(enabled: Boolean) {
        settings.setConversationalAwareness(enabled)
        aap.send(AapProtocol.conversationalAwarenessCommand(enabled))
    }

    private var ownLogged = 0

    /** Adverts proven to be from our AirPods: the standalone-case data path. */
    private fun onOwnAdvert(advert: BluetoothService.OwnAdvert) {
        if (ownLogged++ < 40) {
            val key = settings.settings.value.bleKeys[advert.address]?.encKeyHex?.hexToBytes()
            val dec = key?.let { BleCrypto.decryptLastBlock(advert.data, it) }
            Log.i(TAG, "own advert rssi=${advert.rssi} data=${advert.data.toHex()} decrypted=${dec?.toHex()}")
        }
    }

    private fun onAdvert(message: ProximityMessage) {
        if (connectedDevice.value == null) return
        val locked = lockedModelId
        val accept = if (locked == null) message.rssi >= LOCK_RSSI else message.modelId == locked && message.rssi >= TRACK_RSSI
        if (!accept) return
        if (locked == null) {
            lockedModelId = message.modelId
            Log.i(TAG, "locked onto advert model=0x${message.modelId.toString(16)} (${message.model}) rssi=${message.rssi}")
        }

        // The case only reports while open; keep the last known case level instead of blanking it.
        val previousCase = ble.value?.battery?.case
        val battery = message.battery.let { b ->
            if (b.case.level == null && previousCase?.level != null) b.copy(case = previousCase) else b
        }
        ble.value = message.copy(battery = battery)
    }

    private suspend fun onAapEvent(event: AapProtocol.Event) {
        when (event) {
            // The AirPods are the source of truth for their own settings (like iOS): we read their
            // state after connecting and only write when the user changes something.
            AapProtocol.Event.HandshakeAck -> Unit
            is AapProtocol.Event.Metadata -> {
                aapMeta.value = event
                Log.i(TAG, "AirPods ${event.modelNumber} fw ${event.firmware}")
                aap.send(AapProtocol.REQUEST_BLE_KEYS)
            }
            is AapProtocol.Event.BleKeys -> {
                val address = connectedDevice.value?.address ?: return
                Log.i(TAG, "BLE keys received (enc key: ${event.encKey != null})")
                settings.setBleKeys(address, BleKeyPair(event.irk.toHex(), event.encKey?.toHex()))
            }
            is AapProtocol.Event.ConversationLevel -> _conversation.value = event
            is AapProtocol.Event.HeadPose -> _headPose.tryEmit(event)
            is AapProtocol.Event.NoiseMode -> {
                Log.i(TAG, "hardware listening mode: ${event.mode}")
                aapMode.value = event.mode
                lastRequestedMode = event.mode
                settings.setNoiseMode(event.mode) // keep tracked mode in sync with stem presses
            }
            is AapProtocol.Event.ConversationalAwareness -> settings.setConversationalAwareness(event.enabled)
            is AapProtocol.Event.Ear -> aapEar.value = earSides.onEar(event.primary, event.secondary)
            is AapProtocol.Event.Battery -> {
                earSides.onBattery(event.left, event.right)?.let { aapEar.value = it }
                val now = System.currentTimeMillis()
                val address = connectedDevice.value?.address
                // The case only reports while a bud is docked with the lid open; remember it.
                val remembered = address?.let { settings.settings.value.lastCase[it] }
                if (event.case?.level != null && address != null) {
                    settings.setLastCase(address, CaseReading(event.case.level, event.case.charging, now))
                }
                aapBattery.update { prev ->
                    BatteryInfo(
                        left = event.left ?: prev?.left ?: PodBattery(),
                        right = event.right ?: prev?.right ?: PodBattery(),
                        case = event.case ?: prev?.case ?: remembered?.let { PodBattery(it.level, it.charging) } ?: PodBattery(),
                        source = BatterySource.CONTROL_CHANNEL,
                        updatedAtMillis = now,
                        caseUpdatedAtMillis = if (event.case?.level != null) now else prev?.caseUpdatedAtMillis ?: remembered?.atMillis ?: 0L
                    )
                }
            }
        }
    }

    private fun mergeBattery(hw: Hardware): BatteryInfo = when {
        hw.aapBattery != null -> hw.aapBattery
        hw.ble != null -> hw.ble.battery.let { b ->
            if (b.case.level == null) b.withRememberedCase(hw.device?.address) else b
        }
        hw.hfpLevel != null -> BatteryInfo(
            left = PodBattery(hw.hfpLevel),
            right = PodBattery(hw.hfpLevel),
            source = BatterySource.HEADSET_PROFILE,
            updatedAtMillis = System.currentTimeMillis()
        )
        else -> BatteryInfo().withRememberedCase(hw.device?.address)
    }

    private fun BatteryInfo.withRememberedCase(address: String?): BatteryInfo {
        val r = address?.let { settings.settings.value.lastCase[it] } ?: return this
        return copy(case = PodBattery(r.level, r.charging), caseUpdatedAtMillis = r.atMillis)
    }

    private data class Hardware(
        val device: BluetoothDevice?,
        val ble: ProximityMessage?,
        val aapBattery: BatteryInfo?,
        val aapEar: EarStatus?,
        val hfpLevel: Int?,
        val meta: AapProtocol.Event.Metadata?
    )

    companion object {
        private const val TAG = "AirPodsRepository"
        const val UI_OWNER = "ui"
        const val SERVICE_OWNER = "service"
        private const val LOCK_RSSI = -72
        private const val TRACK_RSSI = -88
        private const val SCAN_RETRY_MS = 15_000L

        fun isAirPodsName(name: String?): Boolean = name?.contains("AirPods", ignoreCase = true) == true
    }
}
