package com.example.airsync.domain

import com.example.airsync.domain.automation.AutomationContext
import com.example.airsync.domain.automation.AutomationEngine
import com.example.airsync.domain.automation.AutomationReason
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.UserActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomationEngineTest {

    private val engine = AutomationEngine()

    private fun ctx(
        activity: UserActivity = UserActivity.STILL,
        db: Float? = null,
        mode: AncMode = AncMode.OFF,
        enabled: Boolean = true,
        connected: Boolean = true,
        wearing: Boolean = true,
        overrideUntil: Long = 0L,
        walking: Boolean = true,
        noisy: Boolean = true,
        stationary: Boolean = true
    ) = AutomationContext(
        enabled, connected, wearing, mode, activity, db, walking, noisy, stationary,
        nowMillis = 1_000_000L, manualOverrideUntilMillis = overrideUntil
    )

    @Test
    fun `walking enables transparency`() {
        val d = engine.evaluate(ctx(activity = UserActivity.WALKING))!!
        assertEquals(AncMode.TRANSPARENCY, d.mode)
        assertEquals(AutomationReason.WALKING, d.reason)
    }

    @Test
    fun `walking beats noise for safety`() {
        assertEquals(AncMode.TRANSPARENCY, engine.evaluate(ctx(activity = UserActivity.RUNNING, db = 85f))!!.mode)
    }

    @Test
    fun `noisy environment enables ANC`() {
        val d = engine.evaluate(ctx(activity = UserActivity.IN_VEHICLE, db = 78f))!!
        assertEquals(AncMode.ANC, d.mode)
        assertEquals(AutomationReason.NOISY, d.reason)
    }

    @Test
    fun `stationary and quiet enables adaptive`() {
        assertEquals(AncMode.ADAPTIVE, engine.evaluate(ctx(db = 40f))!!.mode)
        assertEquals("unknown noise counts as quiet", AncMode.ADAPTIVE, engine.evaluate(ctx(db = null))!!.mode)
    }

    @Test
    fun `hysteresis band keeps current mode`() {
        assertNull(engine.evaluate(ctx(db = AutomationEngine.NOISY_THRESHOLD_DB - 3f, mode = AncMode.ANC)))
    }

    @Test
    fun `no change when already in target mode`() {
        assertNull(engine.evaluate(ctx(activity = UserActivity.WALKING, mode = AncMode.TRANSPARENCY)))
    }

    @Test
    fun `respects manual override window`() {
        assertNull(engine.evaluate(ctx(activity = UserActivity.WALKING, overrideUntil = 2_000_000L)))
    }

    @Test
    fun `does nothing when disabled, disconnected or not worn`() {
        assertNull(engine.evaluate(ctx(activity = UserActivity.WALKING, enabled = false)))
        assertNull(engine.evaluate(ctx(activity = UserActivity.WALKING, connected = false)))
        assertNull(engine.evaluate(ctx(activity = UserActivity.WALKING, wearing = false)))
    }

    @Test
    fun `disabled rules are skipped`() {
        assertEquals(AncMode.ANC, engine.evaluate(ctx(activity = UserActivity.WALKING, db = 80f, walking = false))!!.mode)
        assertNull(engine.evaluate(ctx(activity = UserActivity.UNKNOWN)))
        assertNull(engine.evaluate(ctx(stationary = false)))
    }

    @Test
    fun `cycle order matches quick settings spec`() {
        assertEquals(AncMode.TRANSPARENCY, AncMode.ANC.next())
        assertEquals(AncMode.ADAPTIVE, AncMode.TRANSPARENCY.next())
        assertEquals(AncMode.ANC, AncMode.ADAPTIVE.next())
        assertEquals(AncMode.ANC, AncMode.OFF.next())
    }
}
