package com.example.airsync.ui.main

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.airsync.R
import com.example.airsync.data.bluetooth.AirPodsConnector
import com.example.airsync.data.context.AutomationController
import com.example.airsync.data.context.AutomationEvent
import com.example.airsync.data.context.ContextSignals
import com.example.airsync.data.location.LocationRepository
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.domain.model.AirPodsDevice
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.AppSettings
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.SpatialAudioState
import com.example.airsync.domain.model.UserActivity
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.AudioRepository
import com.example.airsync.domain.repository.FindSoundTarget
import com.example.airsync.domain.repository.SettingsRepository
import com.example.airsync.service.AirSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppEqEntry(val packageName: String, val label: String, val preset: EqPreset?, val active: Boolean)

data class AutomationUi(
    val activity: UserActivity = UserActivity.UNKNOWN,
    val ambientDb: Float? = null,
    val lastEvent: AutomationEvent? = null
)

data class DashboardUiState(
    val status: AirPodsStatus = AirPodsStatus(),
    val settings: AppSettings = AppSettings(),
    val automation: AutomationUi = AutomationUi(),
    val eqApps: List<AppEqEntry> = emptyList(),
    val findSoundPlaying: Boolean = false,
    val spatialAudio: SpatialAudioState = SpatialAudioState(),
    val connecting: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val airPods: AirPodsRepository,
    private val settingsRepository: SettingsRepository,
    private val audio: AudioRepository,
    private val location: LocationRepository,
    private val signals: ContextSignals,
    private val automationController: AutomationController,
    private val connector: AirPodsConnector
) : ViewModel() {

    private val labelCache = mutableMapOf<String, String>()
    private val _messages = Channel<Int>(Channel.BUFFERED)
    /** One-shot snackbar messages (string resource ids). */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    private val automationUi = combine(signals.activity, signals.ambientDb, signals.lastAutomation, ::AutomationUi)

    private val baseUi = combine(
        airPods.status,
        settingsRepository.settings,
        automationUi,
        audio.eqSessionApps,
        audio.isFindSoundPlaying
    ) { status, settings, automation, activeApps, finding ->
        DashboardUiState(
            status = status,
            settings = settings,
            automation = automation,
            eqApps = (activeApps + settings.appEqPresets.keys)
                .map { AppEqEntry(it, appLabel(it), settings.appEqPresets[it], it in activeApps) }
                .sortedWith(compareByDescending<AppEqEntry> { it.active }.thenBy { it.label.lowercase() }),
            findSoundPlaying = finding
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(baseUi, audio.spatialAudio, connector.connecting) { ui, spatial, connecting ->
        ui.copy(spatialAudio = spatial, connecting = connecting)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState(status = airPods.status.value))

    /** Called from the Activity's onStart/onStop so BLE scanning runs only while visible. */
    fun onVisibilityChanged(visible: Boolean) {
        airPods.setScanDemand(AirPodsRepositoryImpl.UI_OWNER, visible)
        if (visible) {
            airPods.refresh()
            viewModelScope.launch {
                val settings = settingsRepository.awaitLoaded()
                // Re-promote from the foreground so the service gains location/mic types.
                if (settings.backgroundEnabled) AirSyncService.start(context)
                if (airPods.status.value.isConnected) location.recordLastSeen()
            }
        }
    }

    fun onPermissionsChanged() {
        airPods.refresh()
        viewModelScope.launch {
            if (settingsRepository.awaitLoaded().backgroundEnabled) AirSyncService.start(context)
        }
    }

    fun setNoiseMode(mode: AncMode) = launch { airPods.setNoiseMode(mode, fromUser = true) }
    fun setTransparencyLevel(level: Float) = launch { airPods.setTransparencyLevel(level) }
    fun setRemoveToPause(enabled: Boolean) = launch { settingsRepository.setRemoveToPause(enabled) }

    fun setHeadGestures(enabled: Boolean) = launch {
        settingsRepository.setHeadGestures(enabled)
        if (enabled && !settingsRepository.settings.value.backgroundEnabled) settingsRepository.setBackgroundEnabled(true)
        AirSyncService.start(context)
    }
    fun setEqPreset(preset: EqPreset) = launch { settingsRepository.setEqPreset(preset) }
    fun setAppEqPreset(pkg: String, preset: EqPreset?) = launch { settingsRepository.setAppEqPreset(pkg, preset) }
    fun selectDevice(address: String?) = launch { settingsRepository.setPreferredDevice(address) }

    /** One-tap connect for the paired AirPods. Bluetooth-off is handled by the caller (system prompt). */
    fun connect(onBluetoothOff: () -> Unit, onNotPaired: () -> Unit) = launch {
        when (connector.connect()) {
            AirPodsConnector.Result.CONNECTED, AirPodsConnector.Result.ALREADY_CONNECTED -> Unit
            AirPodsConnector.Result.BLUETOOTH_OFF -> onBluetoothOff()
            AirPodsConnector.Result.NOT_PAIRED -> { _messages.send(R.string.msg_connect_not_paired); onNotPaired() }
            AirPodsConnector.Result.FAILED -> _messages.send(R.string.msg_connect_failed)
        }
    }
    fun bondedDevices(): List<AirPodsDevice> = airPods.bondedAudioDevices()

    fun setConversationalAwareness(enabled: Boolean) = launch {
        airPods.setConversationalAwareness(enabled)
        if (settingsRepository.settings.value.backgroundEnabled) AirSyncService.start(context)
    }

    fun setBackgroundEnabled(enabled: Boolean) = launch {
        settingsRepository.setBackgroundEnabled(enabled)
        if (enabled) AirSyncService.start(context) else AirSyncService.stop(context)
    }

    fun setAutomationEnabled(enabled: Boolean) = launch {
        settingsRepository.setAutomationEnabled(enabled)
        if (enabled && !settingsRepository.settings.value.backgroundEnabled) {
            settingsRepository.setBackgroundEnabled(true)
            _messages.send(R.string.msg_background_enabled_for_automation)
        }
        AirSyncService.start(context)
        if (enabled) {
            signals.manualOverrideUntilMillis.value = 0L
            automationController.evaluate()
        }
    }

    fun setAutomationRules(walking: Boolean, noisy: Boolean, stationary: Boolean) = launch {
        settingsRepository.setAutomationRules(walking, noisy, stationary)
    }

    fun refreshLocation() = launch {
        if (!location.recordLastSeen()) _messages.send(R.string.msg_location_unavailable)
    }

    fun playFindSound(target: FindSoundTarget) {
        if (!airPods.status.value.isConnected || !audio.startFindSound(target)) {
            launch { _messages.send(R.string.msg_find_needs_connection) }
        }
    }

    fun stopFindSound() = audio.stopFindSound()

    override fun onCleared() {
        airPods.setScanDemand(AirPodsRepositoryImpl.UI_OWNER, false)
        audio.stopFindSound()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }
}
