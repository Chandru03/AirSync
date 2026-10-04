package com.example.airsync.domain

import com.example.airsync.domain.model.EqCurves
import com.example.airsync.domain.model.EqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqCurvesTest {

    @Test
    fun `balanced is flat everywhere`() {
        listOf(20f, 100f, 1_000f, 10_000f, 20_000f).forEach {
            assertEquals(0f, EqCurves.gainAt(EqPreset.BALANCED, it), 0.001f)
        }
    }

    @Test
    fun `bass boost lifts lows only`() {
        assertTrue(EqCurves.gainAt(EqPreset.BASS_BOOST, 60f) >= 5f)
        assertEquals(0f, EqCurves.gainAt(EqPreset.BASS_BOOST, 3_000f), 0.001f)
    }

    @Test
    fun `vocal clarity lifts the presence band`() {
        assertTrue(EqCurves.gainAt(EqPreset.VOCAL_CLARITY, 2_500f) > 3f)
        assertTrue(EqCurves.gainAt(EqPreset.VOCAL_CLARITY, 60f) < 0f)
    }

    @Test
    fun `interpolates and clamps out-of-range frequencies`() {
        val mid = EqCurves.gainAt(EqPreset.BASS_BOOST, 250f)
        assertTrue(mid < 4.5f && mid > 1.5f)
        assertEquals(EqCurves.gainAt(EqPreset.BASS_BOOST, 20f), EqCurves.gainAt(EqPreset.BASS_BOOST, 5f), 0.001f)
        assertEquals(EqCurves.gainAt(EqPreset.BASS_BOOST, 20_000f), EqCurves.gainAt(EqPreset.BASS_BOOST, 40_000f), 0.001f)
    }
}
