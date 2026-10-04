package com.example.airsync.service

import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.WearState

/**
 * Pure state machine behind "Remove to pause", mirroring iOS behaviour:
 *  - Taking a bud out while music plays → pause.
 *  - Putting it back within [RESUME_WINDOW_MS] → resume, but only if *we* paused it.
 *  - Buds go into the case, or the user resumes manually → forget the pending resume.
 */
class EarDetectionPolicy {

    enum class Action { NONE, PAUSE, PLAY }

    private var lastInEar: Int? = null
    private var pausedByUs = false
    private var resumeTarget = 0
    private var pausedAtMillis = 0L

    fun onEarChanged(ear: EarStatus, musicActive: Boolean, enabled: Boolean, nowMillis: Long): Action {
        if (!ear.isKnown) {
            lastInEar = null
            return Action.NONE
        }
        val inEar = ear.budsInEar
        val previous = lastInEar
        lastInEar = inEar

        if (!enabled) {
            pausedByUs = false
            return Action.NONE
        }
        // User resumed by hand (ignore the brief window where the player is still winding down).
        if (pausedByUs && musicActive && nowMillis - pausedAtMillis > PAUSE_SETTLE_MS) pausedByUs = false
        if (ear.left == WearState.IN_CASE && ear.right == WearState.IN_CASE) pausedByUs = false
        if (previous == null) return Action.NONE

        return when {
            inEar < previous && previous > 0 && musicActive -> {
                pausedByUs = true
                resumeTarget = previous
                pausedAtMillis = nowMillis
                Action.PAUSE
            }
            inEar > previous && pausedByUs && inEar >= resumeTarget && !musicActive &&
                nowMillis - pausedAtMillis <= RESUME_WINDOW_MS -> {
                pausedByUs = false
                Action.PLAY
            }
            else -> Action.NONE
        }
    }

    fun reset() {
        lastInEar = null
        pausedByUs = false
    }

    companion object {
        const val RESUME_WINDOW_MS = 10 * 60 * 1000L
        const val PAUSE_SETTLE_MS = 2_000L
    }
}
