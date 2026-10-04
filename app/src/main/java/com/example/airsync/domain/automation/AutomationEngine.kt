package com.example.airsync.domain.automation

import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.UserActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the rules look at. Pure data so the engine is trivially unit-testable. */
data class AutomationContext(
    val enabled: Boolean,
    val connected: Boolean,
    val wearing: Boolean,
    val currentMode: AncMode,
    val activity: UserActivity,
    /** Estimated ambient level in dB SPL, or null if the mic could not be sampled. */
    val ambientDb: Float?,
    val walkingRule: Boolean,
    val noisyRule: Boolean,
    val stationaryRule: Boolean,
    val nowMillis: Long,
    /** Automation stays hands-off until this time after a manual mode change. */
    val manualOverrideUntilMillis: Long
)

enum class AutomationReason { WALKING, NOISY, STATIONARY }

data class AutomationDecision(val mode: AncMode, val reason: AutomationReason)

/**
 * Small, deterministic rule engine (deliberately not ML).
 *
 * Priority, highest first:
 *  1. Walking/running/cycling → Transparency (safety: hear traffic).
 *  2. Noisy surroundings      → Noise Cancellation.
 *  3. Stationary              → Adaptive.
 *
 * Returns null when nothing should change, so callers never fight the user or flap modes.
 */
@Singleton
class AutomationEngine @Inject constructor() {

    fun evaluate(ctx: AutomationContext): AutomationDecision? {
        if (!ctx.enabled || !ctx.connected || !ctx.wearing) return null
        if (ctx.nowMillis < ctx.manualOverrideUntilMillis) return null

        val decision = when {
            ctx.walkingRule && ctx.activity in MOVING ->
                AutomationDecision(AncMode.TRANSPARENCY, AutomationReason.WALKING)
            ctx.noisyRule && ctx.ambientDb != null && ctx.ambientDb >= NOISY_THRESHOLD_DB ->
                AutomationDecision(AncMode.ANC, AutomationReason.NOISY)
            ctx.stationaryRule && ctx.activity in STATIONARY && isQuietOrUnknown(ctx.ambientDb) ->
                AutomationDecision(AncMode.ADAPTIVE, AutomationReason.STATIONARY)
            else -> null
        }
        return decision?.takeIf { it.mode != ctx.currentMode }
    }

    // Hysteresis: once noisy, the room must get clearly quieter before we leave ANC.
    private fun isQuietOrUnknown(db: Float?) = db == null || db < NOISY_THRESHOLD_DB - HYSTERESIS_DB

    companion object {
        const val NOISY_THRESHOLD_DB = 70f
        const val HYSTERESIS_DB = 6f
        const val MANUAL_OVERRIDE_MILLIS = 30 * 60 * 1000L

        private val MOVING = setOf(UserActivity.WALKING, UserActivity.RUNNING, UserActivity.ON_BICYCLE)
        private val STATIONARY = setOf(UserActivity.STILL, UserActivity.IN_VEHICLE)
    }
}
