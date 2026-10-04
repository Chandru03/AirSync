package com.example.airsync.data.audio

import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Smoothly ramps media volume toward a moving target, the way iOS fades music for Conversation
 * Awareness. The AirPods stream a "duck depth" as the wearer speaks and stops; we translate the
 * latest value into a target fraction of the volume that was playing when the conversation began,
 * and a single ramp loop walks the system volume there one step at a time.
 *
 * Key properties (the previous implementation got these wrong):
 *  - The pre-conversation volume (baseline) is captured once and restored exactly, so 50 % always
 *    comes back to 50 %, never to a half-ducked level.
 *  - Changes are gradual in both directions.
 */
class VolumeDucker(
    private val audioManager: AudioManager,
    private val scope: CoroutineScope
) {
    private val lock = Mutex()
    private var baseline: Int? = null
    private var targetFraction = 1f
    private var lastApplied: Int? = null
    private var rampJob: Job? = null

    /** Called for each AirPods duck level while a conversation is ongoing. */
    suspend fun duckTo(fraction: Float) = lock.withLock {
        if (baseline == null) {
            baseline = audioManager.getStreamVolume(STREAM)
            android.util.Log.i("VolumeDucker", "conversation start, baseline=${baseline}/${audioManager.getStreamMaxVolume(STREAM)}")
        }
        targetFraction = fraction.coerceIn(0f, 1f)
        ensureRamp()
    }

    /** Conversation ended: ramp back to the captured baseline, then release control. */
    suspend fun release() = lock.withLock {
        if (baseline == null) return@withLock
        targetFraction = 1f
        ensureRamp()
    }

    /** Hard stop (service shutdown): snap back to baseline and forget everything. */
    fun stop() {
        rampJob?.cancel()
        rampJob = null
        baseline?.let { if (audioManager.getStreamVolume(STREAM) == lastApplied) setVolume(it) }
        baseline = null
        lastApplied = null
        targetFraction = 1f
    }

    private fun ensureRamp() {
        if (rampJob?.isActive == true) return // the live loop already reads the updated target
        rampJob = scope.launch {
            while (isActive) {
                val base = baseline ?: break
                val target = (base * targetFraction).roundToInt().coerceIn(1, base)
                val current = audioManager.getStreamVolume(STREAM)

                // The user grabbed the volume keys mid-conversation: hand control back to them.
                if (lastApplied != null && current != lastApplied) {
                    baseline = null
                    lastApplied = null
                    break
                }
                if (current == target) {
                    if (targetFraction >= 1f) { // fully restored
                        android.util.Log.i("VolumeDucker", "restored to baseline=$base")
                        baseline = null
                        lastApplied = null
                        break
                    }
                    delay(HOLD_MS) // hold the duck until the next level arrives
                    continue
                }
                setVolume(current + (target - current).sign)
                delay(STEP_MS)
            }
            rampJob = null
        }
    }

    private fun setVolume(index: Int) {
        val clamped = index.coerceIn(0, audioManager.getStreamMaxVolume(STREAM))
        audioManager.setStreamVolume(STREAM, clamped, 0)
        lastApplied = clamped
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val STEP_MS = 45L   // one volume notch per step → a few hundred ms fade
        const val HOLD_MS = 120L
    }
}
