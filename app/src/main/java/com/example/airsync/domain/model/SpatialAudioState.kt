package com.example.airsync.domain.model

/** What Android's built-in spatializer is doing for the current audio output. */
enum class SpatialAudioStatus {
    /** This phone has no spatializer. */
    UNSUPPORTED,
    /** Spatial audio is switched off for the current output. */
    OFF,
    /** Switched on, but the current output can't be spatialized right now (e.g. during a call). */
    ON_INACTIVE,
    /** Rendering spatial audio on the current output. */
    ACTIVE
}

data class SpatialAudioState(
    val status: SpatialAudioStatus = SpatialAudioStatus.UNSUPPORTED,
    /** True only when the system has a head-tracker sensor for this output (AirPods never provide one). */
    val headTracking: Boolean = false
)
