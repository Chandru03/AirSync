package com.example.airsync.data.audio

import android.media.audiofx.Equalizer
import android.util.Log
import com.example.airsync.domain.model.EqCurves
import com.example.airsync.domain.model.EqPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Per-session equalizer.
 *
 * Android has no reliable *global* EQ for third-party apps (session 0 is deprecated and ignored on
 * most devices). The supported mechanism is: media players broadcast
 * `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` with their session id and package, and an
 * EQ app attaches an [Equalizer] to that session. That also gives us per-app tuning for free.
 * Apps that never broadcast their session (some streaming apps) can't be tuned — a platform limit.
 */
@Singleton
class EqualizerController @Inject constructor() {

    private class Session(val packageName: String?, val equalizer: Equalizer)

    private val sessions = mutableMapOf<Int, Session>()
    private var defaultPreset = EqPreset.BALANCED
    private var perApp: Map<String, EqPreset> = emptyMap()

    private val _apps = MutableStateFlow<Set<String>>(emptySet())
    val apps: StateFlow<Set<String>> = _apps.asStateFlow()

    @Synchronized
    fun onSessionOpened(sessionId: Int, packageName: String?) {
        if (sessionId <= 0 || sessionId in sessions) return
        val eq = try {
            Equalizer(0, sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "Equalizer unavailable for session $sessionId ($packageName)", t)
            return
        }
        sessions[sessionId] = Session(packageName, eq)
        apply(sessions.getValue(sessionId))
        publishApps()
    }

    @Synchronized
    fun onSessionClosed(sessionId: Int) {
        sessions.remove(sessionId)?.let { runCatching { it.equalizer.release() } }
        publishApps()
    }

    @Synchronized
    fun update(default: EqPreset, perApp: Map<String, EqPreset>) {
        defaultPreset = default
        this.perApp = perApp
        sessions.values.forEach(::apply)
    }

    @Synchronized
    fun releaseAll() {
        sessions.values.forEach { runCatching { it.equalizer.release() } }
        sessions.clear()
        publishApps()
    }

    private fun apply(session: Session) {
        val preset = session.packageName?.let { perApp[it] } ?: defaultPreset
        val eq = session.equalizer
        runCatching {
            val (minMb, maxMb) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
            for (band in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(band.toShort()) / 1000f // milli-Hertz → Hz
                val mb = (EqCurves.gainAt(preset, hz) * 100).roundToInt().coerceIn(minMb, maxMb)
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
            // A flat EQ is bypassed entirely so "Balanced" is bit-exact with no processing.
            eq.enabled = preset != EqPreset.BALANCED
        }.onFailure { Log.w(TAG, "Failed to apply $preset", it) }
    }

    private fun publishApps() {
        _apps.value = sessions.values.mapNotNull { it.packageName }.toSet()
    }

    private companion object {
        const val TAG = "EqualizerController"
    }
}
