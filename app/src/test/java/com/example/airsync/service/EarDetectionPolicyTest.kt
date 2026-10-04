package com.example.airsync.service

import com.example.airsync.domain.model.EarStatus
import com.example.airsync.domain.model.WearState.IN_CASE
import com.example.airsync.domain.model.WearState.IN_EAR
import com.example.airsync.domain.model.WearState.OUT_OF_EAR
import com.example.airsync.domain.model.WearState.UNKNOWN
import com.example.airsync.service.EarDetectionPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class EarDetectionPolicyTest {

    private val policy = EarDetectionPolicy()
    private val both = EarStatus(IN_EAR, IN_EAR)
    private val leftOut = EarStatus(OUT_OF_EAR, IN_EAR)
    private val noneIn = EarStatus(OUT_OF_EAR, OUT_OF_EAR)

    private fun on(ear: EarStatus, music: Boolean, t: Long = 0L, enabled: Boolean = true) =
        policy.onEarChanged(ear, music, enabled, t)

    @Test
    fun `first reading never acts`() {
        assertEquals(Action.NONE, on(leftOut, music = true))
    }

    @Test
    fun `removing a bud pauses and reinserting resumes`() {
        on(both, music = true)
        assertEquals(Action.PAUSE, on(leftOut, music = true, t = 1_000))
        assertEquals(Action.PLAY, on(both, music = false, t = 5_000))
    }

    @Test
    fun `removing both then reinserting one does not resume until both are back`() {
        on(both, music = true)
        assertEquals(Action.PAUSE, on(noneIn, music = true, t = 1_000))
        assertEquals(Action.NONE, on(leftOut, music = false, t = 3_000))
        assertEquals(Action.PLAY, on(both, music = false, t = 4_000))
    }

    @Test
    fun `no pause when nothing is playing, and no surprise resume later`() {
        on(both, music = false)
        assertEquals(Action.NONE, on(leftOut, music = false, t = 1_000))
        assertEquals(Action.NONE, on(both, music = false, t = 2_000))
    }

    @Test
    fun `does not resume after the resume window`() {
        on(both, music = true)
        on(leftOut, music = true, t = 0)
        assertEquals(Action.NONE, on(both, music = false, t = EarDetectionPolicy.RESUME_WINDOW_MS + 1))
    }

    @Test
    fun `manual resume cancels pending auto resume`() {
        on(both, music = true)
        on(leftOut, music = true, t = 0)
        on(EarStatus(OUT_OF_EAR, IN_EAR), music = true, t = 10_000) // user pressed play
        assertEquals(Action.NONE, on(both, music = true, t = 11_000))
    }

    @Test
    fun `putting buds in the case cancels pending resume`() {
        on(both, music = true)
        on(noneIn, music = true, t = 0)
        on(EarStatus(IN_CASE, IN_CASE), music = false, t = 1_000)
        assertEquals(Action.NONE, on(both, music = false, t = 2_000))
    }

    @Test
    fun `disabled or unknown data never acts`() {
        on(both, music = true)
        assertEquals(Action.NONE, on(leftOut, music = true, enabled = false))
        assertEquals(Action.NONE, on(EarStatus(UNKNOWN, UNKNOWN), music = true))
    }
}
