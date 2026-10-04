package com.example.airsync.domain.repository

import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.SpatialAudioState
import kotlinx.coroutines.flow.StateFlow

enum class FindSoundTarget { BOTH, LEFT, RIGHT }

interface AudioRepository {
    val isMusicActive: Boolean

    /** Event-driven "media is playing" signal (AudioPlaybackCallback), no polling. */
    val musicPlaying: StateFlow<Boolean>

    /** Package names of media apps that opened an audio-effect session (eligible for per-app EQ). */
    val eqSessionApps: StateFlow<Set<String>>

    /** Android's spatializer state for the current output, updated as routing changes. */
    val spatialAudio: StateFlow<SpatialAudioState>

    /** True while the find-my tone is playing. */
    val isFindSoundPlaying: StateFlow<Boolean>

    fun pause()
    fun play()

    /**
     * Conversation Awareness ducking. [targetFraction] is the AirPods' requested share of the
     * volume that was playing when the conversation began; the implementation ramps there smoothly.
     */
    suspend fun duckConversation(targetFraction: Float)
    /** Conversation ended: ramp back to the pre-conversation volume. */
    suspend fun endConversationDuck()
    /** Immediately releases any duck (service shutdown). */
    fun stopDuckingNow()

    fun applyEqPreset(default: EqPreset, perApp: Map<String, EqPreset>)
    fun onAudioSessionOpened(sessionId: Int, packageName: String?)
    fun onAudioSessionClosed(sessionId: Int)
    fun releaseEqualizers()

    /** Plays a loud chirp through the active (AirPods) route. Returns false if no route. */
    fun startFindSound(target: FindSoundTarget): Boolean
    fun stopFindSound()
}
