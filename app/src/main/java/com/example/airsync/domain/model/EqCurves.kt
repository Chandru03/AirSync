package com.example.airsync.domain.model

import kotlin.math.ln

/**
 * Target response of each preset as (frequency Hz → gain dB) control points. Shared by the
 * equalizer engine (to set device bands) and the UI (to draw the curve), so they never disagree.
 */
object EqCurves {

    private val curves: Map<EqPreset, List<Pair<Float, Float>>> = mapOf(
        EqPreset.BALANCED to listOf(20f to 0f, 20_000f to 0f),
        EqPreset.BASS_BOOST to listOf(
            20f to 6f, 60f to 6f, 150f to 4.5f, 400f to 1.5f, 1_000f to 0f, 8_000f to 0f, 20_000f to 0.5f
        ),
        EqPreset.VOCAL_CLARITY to listOf(
            20f to -2f, 60f to -2f, 150f to -1.5f, 400f to 0f, 1_000f to 2f, 2_500f to 3.5f,
            5_000f to 2.5f, 10_000f to 0.5f, 20_000f to 0f
        )
    )

    const val MIN_HZ = 20f
    const val MAX_HZ = 20_000f
    const val MAX_ABS_DB = 8f

    /** Gain in dB at [hz], linearly interpolated on a log-frequency axis. */
    fun gainAt(preset: EqPreset, hz: Float): Float {
        val points = curves.getValue(preset)
        val f = hz.coerceIn(MIN_HZ, MAX_HZ)
        val upper = points.indexOfFirst { it.first >= f }.let { if (it <= 0) 1 else it }
        val (f0, g0) = points[upper - 1]
        val (f1, g1) = points[upper]
        val t = ((ln(f) - ln(f0)) / (ln(f1) - ln(f0))).coerceIn(0f, 1f)
        return g0 + (g1 - g0) * t
    }
}
