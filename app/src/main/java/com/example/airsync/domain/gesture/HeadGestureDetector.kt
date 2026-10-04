package com.example.airsync.domain.gesture

import kotlin.math.abs
import kotlin.math.sqrt

enum class HeadGesture { NOD, SHAKE }

/**
 * Detects a nod (yes) or head shake (no) from the AirPods' 50 Hz motion stream.
 *
 * The AirPod sits tilted in the ear, so its sensor axes don't line up with the head. Instead of
 * relying on a fixed axis we build a head-aligned frame from gravity:
 *  - a shake rotates around the vertical axis, so the gyro vector is parallel to gravity (yaw);
 *  - a nod rotates around the ear-to-ear axis, so the gyro vector is perpendicular to it (pitch).
 * On a real recording this separates the two by ~6–10× (see HeadGestureDetectorTest).
 *
 * A gesture fires when, within [WINDOW_MS], one component clearly dominates the other and swings
 * back and forth at least [MIN_SWINGS] times — so a single glance or a turn doesn't trigger it.
 */
class HeadGestureDetector {

    private data class Sample(val t: Long, val yaw: Float, val hx: Float, val hy: Float, val hz: Float)

    private val window = ArrayDeque<Sample>()
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var hasGravity = false
    private var cooldownUntil = 0L

    /**
     * Feeds one sample. [wx]/[wy]/[wz] = gyro rate, [ax]/[ay]/[az] = acceleration (milli-g).
     * Returns a gesture when one is recognised, otherwise null.
     */
    fun onSample(tMillis: Long, wx: Int, wy: Int, wz: Int, ax: Int, ay: Int, az: Int): HeadGesture? {
        // Slow low-pass of acceleration = gravity direction (head motion averages out).
        if (!hasGravity) {
            gx = ax.toFloat(); gy = ay.toFloat(); gz = az.toFloat(); hasGravity = true
        } else {
            gx += (ax - gx) * GRAVITY_ALPHA
            gy += (ay - gy) * GRAVITY_ALPHA
            gz += (az - gz) * GRAVITY_ALPHA
        }
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1f) return null
        val ux = gx / n; val uy = gy / n; val uz = gz / n

        val yaw = wx * ux + wy * uy + wz * uz
        window.addLast(Sample(tMillis, yaw, wx - yaw * ux, wy - yaw * uy, wz - yaw * uz))
        while (window.isNotEmpty() && tMillis - window.first().t > WINDOW_MS) window.removeFirst()

        if (tMillis < cooldownUntil || window.size < MIN_SAMPLES) return null

        var yawSq = 0f
        var horSq = 0f
        for (s in window) {
            yawSq += s.yaw * s.yaw
            horSq += s.hx * s.hx + s.hy * s.hy + s.hz * s.hz
        }
        val yawRms = sqrt(yawSq / window.size)
        val horRms = sqrt(horSq / window.size)
        if (maxOf(yawRms, horRms) < MIN_RMS) return null

        val gesture = when {
            yawRms > DOMINANCE * horRms -> HeadGesture.SHAKE
            horRms > DOMINANCE * yawRms -> HeadGesture.NOD
            else -> return null
        }
        val signal = if (gesture == HeadGesture.SHAKE) window.map { it.yaw } else nodSignal()
        if (alternatingSwings(signal) < MIN_SWINGS) return null

        window.clear()
        cooldownUntil = tMillis + COOLDOWN_MS
        return gesture
    }

    fun reset() {
        window.clear()
        hasGravity = false
        cooldownUntil = 0L
    }

    /** Signed pitch: horizontal rotation projected on its own strongest direction in the window. */
    private fun nodSignal(): List<Float> {
        val ref = window.maxBy { it.hx * it.hx + it.hy * it.hy + it.hz * it.hz }
        val rn = sqrt(ref.hx * ref.hx + ref.hy * ref.hy + ref.hz * ref.hz).takeIf { it > 0f } ?: return emptyList()
        return window.map { (it.hx * ref.hx + it.hy * ref.hy + it.hz * ref.hz) / rn }
    }

    /** Number of excursions beyond ±[PEAK] whose sign alternates (back-and-forth motion). */
    private fun alternatingSwings(signal: List<Float>): Int {
        var swings = 0
        var lastSign = 0
        for (v in signal) {
            if (abs(v) < PEAK) continue
            val sign = if (v > 0) 1 else -1
            if (sign != lastSign) {
                swings++
                lastSign = sign
            }
        }
        return swings
    }

    companion object {
        const val WINDOW_MS = 1_500L
        const val COOLDOWN_MS = 1_500L
        private const val MIN_SAMPLES = 20
        private const val GRAVITY_ALPHA = 0.02f
        private const val MIN_RMS = 180f
        private const val DOMINANCE = 2.5f
        private const val PEAK = 300f
        private const val MIN_SWINGS = 3
    }
}
