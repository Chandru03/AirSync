package com.example.airsync.domain

import com.example.airsync.domain.gesture.HeadGesture
import com.example.airsync.domain.gesture.HeadGestureDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HeadGestureDetectorTest {

    private data class Row(val t: Long, val g: IntArray, val a: IntArray)

    /** Real AirPods 4 recording from the Fold: nod ×3 (0–3.5 s), still, shake ×3 (6–10 s), still. */
    private fun recording(): List<Row> =
        javaClass.classLoader!!.getResourceAsStream("airpods4_nod_then_shake.csv")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val v = line.split(",").map { it.trim().toInt() }
                Row(v[0].toLong(), intArrayOf(v[1], v[2], v[3]), intArrayOf(v[4], v[5], v[6]))
            }

    private fun replay(rows: List<Row>): List<Pair<Long, HeadGesture>> {
        val d = HeadGestureDetector()
        return rows.mapNotNull { r ->
            d.onSample(r.t, r.g[0], r.g[1], r.g[2], r.a[0], r.a[1], r.a[2])?.let { r.t to it }
        }
    }

    @Test
    fun `real recording - nods are recognised as NOD`() {
        val events = replay(recording())
        val firstNod = events.firstOrNull { it.second == HeadGesture.NOD }
        assertNotNull("no nod detected; events=$events", firstNod)
        assertTrue("nod detected too late: $events", firstNod!!.first < 4_000)
        assertTrue("shake reported during the nod phase: $events",
            events.none { it.second == HeadGesture.SHAKE && it.first < 5_500 })
    }

    @Test
    fun `real recording - shakes are recognised as SHAKE`() {
        val events = replay(recording())
        val firstShake = events.firstOrNull { it.second == HeadGesture.SHAKE }
        assertNotNull("no shake detected; events=$events", firstShake)
        assertTrue("shake outside the shake phase: $events", firstShake!!.first in 6_000..10_800)
        assertTrue("nod reported during the shake phase: $events",
            events.none { it.second == HeadGesture.NOD && it.first > 5_500 })
    }

    @Test
    fun `real recording - holding still never triggers`() {
        val still = recording().filter { it.t in 10_800..14_600 }
        assertEquals(emptyList<Pair<Long, HeadGesture>>(), replay(still))
    }

    @Test
    fun `a single head turn is not a shake`() {
        // One smooth yaw turn (no back-and-forth) around a gravity-aligned axis.
        val d = HeadGestureDetector()
        val g = intArrayOf(0, 1000, 0)
        val hits = (0 until 100).mapNotNull { i ->
            val w = if (i in 20 until 45) (900 * sin(PI * (i - 20) / 25)).toInt() else 0
            d.onSample(i * 20L, 0, w, 0, g[0], g[1], g[2])
        }
        assertEquals(emptyList<HeadGesture>(), hits)
    }

    @Test
    fun `synthetic shake around gravity is a SHAKE, rotation across it is a NOD`() {
        fun run(axis: IntArray): HeadGesture? {
            val d = HeadGestureDetector()
            var out: HeadGesture? = null
            for (i in 0 until 100) {
                val w = 800 * sin(2 * PI * i / 25) // 2 Hz back-and-forth at 50 Hz
                val hit = d.onSample(i * 20L, (axis[0] * w).toInt(), (axis[1] * w).toInt(), (axis[2] * w).toInt(), 0, 1000, 0)
                if (hit != null && out == null) out = hit
            }
            return out
        }
        assertEquals(HeadGesture.SHAKE, run(intArrayOf(0, 1, 0)))
        assertEquals(HeadGesture.NOD, run(intArrayOf(1, 0, 0)))
    }
}
