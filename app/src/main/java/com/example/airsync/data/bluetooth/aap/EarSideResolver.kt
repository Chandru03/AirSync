package com.example.airsync.data.bluetooth.aap

import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.PodBattery
import com.example.airsync.domain.model.WearState

/**
 * AAP ear frames are `[primary][secondary]`, not `[left][right]`. The primary is whichever bud
 * currently owns the link (usually the right one); when the primary is taken out the roles swap
 * and the pods re-send the frame. This resolver maps roles to physical sides using:
 *
 *  1. Battery hints — a bud reported as charging/disconnected is the one "in case"/"unknown".
 *  2. Role-switch tracking — a `[OUT, IN]` frame means the primary was removed, so the *other*
 *     side is primary from the next frame on.
 *  3. A sticky last-known primary side, defaulting to RIGHT (Apple's usual choice).
 */
class EarSideResolver {

    enum class Side { LEFT, RIGHT }

    private var primarySide: Side = Side.RIGHT
    private var lastPrimary: WearState? = null
    private var lastSecondary: WearState? = null
    private var leftHint: PodBattery? = null
    private var rightHint: PodBattery? = null

    fun onEar(primary: WearState, secondary: WearState): EarStatus {
        lastPrimary = primary
        lastSecondary = secondary
        return resolve()
    }

    /** Battery frames usually arrive right after an ear frame; re-resolve with the fresh hint. */
    fun onBattery(left: PodBattery?, right: PodBattery?): EarStatus? {
        leftHint = left
        rightHint = right
        return if (lastPrimary != null) resolve() else null
    }

    fun reset() {
        primarySide = Side.RIGHT
        lastPrimary = null
        lastSecondary = null
        leftHint = null
        rightHint = null
    }

    private fun resolve(): EarStatus {
        val p = lastPrimary ?: return EarStatus()
        val s = lastSecondary ?: return EarStatus()
        if (p == s) return EarStatus(left = p, right = p)

        // 1. Battery hint: exactly one side docked ⇒ that side is whichever role is IN_CASE/UNKNOWN.
        val leftDocked = leftHint.isDocked()
        val rightDocked = rightHint.isDocked()
        if (leftDocked != rightDocked) {
            val dockedRole = when {
                p.isDockedState() && !s.isDockedState() -> Role.PRIMARY
                s.isDockedState() && !p.isDockedState() -> Role.SECONDARY
                else -> null
            }
            if (dockedRole != null) {
                primarySide = when {
                    dockedRole == Role.PRIMARY && leftDocked -> Side.LEFT
                    dockedRole == Role.PRIMARY -> Side.RIGHT
                    leftDocked -> Side.RIGHT
                    else -> Side.LEFT
                }
            }
        }

        val status = assign(p, s)
        // 2. Primary removed ⇒ the pods promote the other bud; subsequent frames use swapped roles.
        if (p == WearState.OUT_OF_EAR && s == WearState.IN_EAR) {
            primarySide = if (primarySide == Side.LEFT) Side.RIGHT else Side.LEFT
        }
        return status
    }

    private fun assign(p: WearState, s: WearState) =
        if (primarySide == Side.LEFT) EarStatus(left = p, right = s) else EarStatus(left = s, right = p)

    /** Unknown level means the pod is unreachable (closed case); charging means it's docked. */
    private fun PodBattery?.isDocked() = this == null || this.charging || this.level == null
    private fun WearState.isDockedState() = this == WearState.IN_CASE || this == WearState.UNKNOWN

    private enum class Role { PRIMARY, SECONDARY }
}
