package com.example.airsync.data.bluetooth

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.airsync.data.bluetooth.aap.AapClient
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.domain.model.BluetoothStatus
import com.example.airsync.domain.model.ControlChannel
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-tap "connect my AirPods" for an already-paired pair (Quick Settings tile + in-app button).
 *
 * Android has no public API for an app to connect a paired headset. What works, in order:
 *  1. The A2DP profile's `connect()` (hidden; accepted on the Galaxy Z Fold / Android 17). Media
 *     audio connects in ~2 s and the AirPods bring up the call profile themselves.
 *  2. Fallback where (1) is refused: page the AirPods by opening the AAP control channel, then
 *     release it — the AirPods respond by connecting their audio profiles to the phone.
 */
@Singleton
class AirPodsConnector @Inject constructor(
    private val bluetooth: BluetoothService,
    private val aap: AapClient,
    private val airPods: AirPodsRepository,
    private val settings: SettingsRepository
) {
    enum class Result { CONNECTED, ALREADY_CONNECTED, BLUETOOTH_OFF, NOT_PAIRED, FAILED }

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    suspend fun connect(): Result {
        if (airPods.status.value.isConnected) return Result.ALREADY_CONNECTED
        if (bluetooth.status.value != BluetoothStatus.ON) return Result.BLUETOOTH_OFF
        val device = target() ?: return Result.NOT_PAIRED
        if (!_connecting.compareAndSet(false, true)) return Result.FAILED
        try {
            Log.i(TAG, "connecting to ${bluetooth.nameOf(device)}")
            if (bluetooth.tryConnectProfiles(device) && awaitAudio(DIRECT_TIMEOUT_MS)) return done(true)

            // Fallback: knock on the AirPods over the control channel, then step aside.
            Log.i(TAG, "direct connect unavailable; paging via control channel")
            aap.connect(device)
            withTimeoutOrNull(PAGE_TIMEOUT_MS) { aap.channel.first { it == ControlChannel.CONNECTED } }
            delay(RELEASE_DELAY_MS)
            if (!airPods.status.value.isConnected) aap.disconnect()
            return done(awaitAudio(FALLBACK_TIMEOUT_MS))
        } finally {
            _connecting.value = false
        }
    }

    private fun done(ok: Boolean): Result {
        Log.i(TAG, if (ok) "audio connected" else "could not connect")
        return if (ok) Result.CONNECTED else Result.FAILED
    }

    /**
     * Waits for the AirPods' audio to come up. Polls the profile proxies directly as well as
     * listening for broadcasts, so a delayed broadcast can never make a success look like a timeout.
     */
    private suspend fun awaitAudio(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!airPods.status.value.isConnected) {
                bluetooth.refresh()
                delay(POLL_MS)
            }
            true
        } ?: false

    /** The user's chosen AirPods, else the first paired device that looks like AirPods. */
    private suspend fun target(): BluetoothDevice? {
        val preferred = settings.awaitLoaded().preferredDeviceAddress
        val bonded = bluetooth.bondedAudioDevices()
        return bonded.firstOrNull { it.address == preferred }
            ?: bonded.firstOrNull { AirPodsRepositoryImpl.isAirPodsName(bluetooth.nameOf(it)) }
    }

    private companion object {
        const val TAG = "AirPodsConnector"
        const val DIRECT_TIMEOUT_MS = 8_000L
        const val PAGE_TIMEOUT_MS = 6_000L
        const val RELEASE_DELAY_MS = 1_000L
        const val FALLBACK_TIMEOUT_MS = 10_000L
        const val POLL_MS = 400L
    }
}
