package com.example.airsync.domain.repository

import com.example.airsync.domain.model.AirPodsDevice
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AncMode
import kotlinx.coroutines.flow.StateFlow

/** Single source of truth for live AirPods state and hardware commands. */
interface AirPodsRepository {
    val status: StateFlow<AirPodsStatus>

    /** Bonded audio devices the user can pick from when auto-detection misses renamed AirPods. */
    fun bondedAudioDevices(): List<AirPodsDevice>

    /** Re-reads adapter + profile state. Cheap; safe to call from onResume. */
    fun refresh()

    /**
     * BLE scanning is the only expensive part of detection, so it runs only while someone needs it
     * (visible UI or the background service) *and* AirPods are connected.
     */
    fun setScanDemand(owner: String, active: Boolean)

    /** @param fromUser manual changes pause automation for a while; automation passes false. */
    suspend fun setNoiseMode(mode: AncMode, fromUser: Boolean = true)
    suspend fun cycleNoiseMode()
    suspend fun setTransparencyLevel(level: Float)
    suspend fun setConversationalAwareness(enabled: Boolean)

    /** Starts/stops the AirPods' head-tracking sensor stream. Returns false without a control channel. */
    suspend fun setHeadTracking(enabled: Boolean): Boolean
}
