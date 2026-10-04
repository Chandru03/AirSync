package com.example.airsync.domain.repository

import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.AppSettings
import com.example.airsync.domain.model.BleKeyPair
import com.example.airsync.domain.model.CaseReading
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.LastSeenLocation
import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val settings: StateFlow<AppSettings>

    /** Suspends until DataStore has been read once. Use before acting in a cold process (tile/widget). */
    suspend fun awaitLoaded(): AppSettings

    suspend fun setNoiseMode(mode: AncMode)
    suspend fun setTransparencyLevel(level: Float)
    suspend fun setConversationalAwareness(enabled: Boolean)
    suspend fun setRemoveToPause(enabled: Boolean)
    suspend fun setHeadGestures(enabled: Boolean)
    suspend fun setBackgroundEnabled(enabled: Boolean)
    suspend fun setAutomationEnabled(enabled: Boolean)
    suspend fun setAutomationRules(walking: Boolean, noisy: Boolean, stationary: Boolean)
    suspend fun setEqPreset(preset: EqPreset)
    suspend fun setAppEqPreset(packageName: String, preset: EqPreset?)
    suspend fun setPreferredDevice(address: String?)
    suspend fun setLastSeen(location: LastSeenLocation)
    suspend fun setBleKeys(address: String, keys: BleKeyPair)
    suspend fun setLastCase(address: String, reading: CaseReading)
}
