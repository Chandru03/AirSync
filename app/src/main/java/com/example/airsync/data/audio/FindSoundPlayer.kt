package com.example.airsync.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.example.airsync.domain.repository.FindSoundTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * "Play sound" fallback for Find My.
 *
 * Apple's real find-my chirp is triggered through the Find My network with owner keys we can't
 * have. What *does* work: while the AirPods are still connected (e.g. lost in the sofa, out of the
 * case), any media audio is routed to them. We synthesise a piercing two-tone chirp, ramp it up
 * like Apple does, and can pan it to one bud to tell which one is missing.
 */
class FindSoundPlayer(private val audioManager: AudioManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun start(target: FindSoundTarget) {
        stop()
        job = scope.launch {
            val originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val track = buildTrack()
            _playing.value = true
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.85f).roundToInt(), 0)
                track.play()
                val pattern = chirpPattern(target)
                val started = System.currentTimeMillis()
                var cycle = 0
                while (isActive && System.currentTimeMillis() - started < MAX_DURATION_MS) {
                    // Fade in over the first few cycles so nobody gets blasted at full level.
                    val gain = ((cycle + 1) / 4f).coerceAtMost(1f)
                    val scaled = ShortArray(pattern.size) { (pattern[it] * gain).toInt().toShort() }
                    if (track.write(scaled, 0, scaled.size) < 0) break
                    cycle++
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                _playing.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** One cycle: two rising chirps then a pause, interleaved stereo. */
    private fun chirpPattern(target: FindSoundTarget): ShortArray {
        val chirp = (SAMPLE_RATE * 0.18).toInt()
        val gap = (SAMPLE_RATE * 0.07).toInt()
        val pause = (SAMPLE_RATE * 0.45).toInt()
        val frames = chirp * 2 + gap + pause
        val out = ShortArray(frames * 2)
        var phase = 0.0
        for (i in 0 until frames) {
            val inChirp = i < chirp || (i >= chirp + gap && i < chirp * 2 + gap)
            val sample: Short = if (inChirp) {
                val local = if (i < chirp) i else i - chirp - gap
                val t = local.toDouble() / chirp
                val freq = 2_200 + 1_400 * t // rising sweep cuts through ambient noise
                phase += 2 * PI * freq / SAMPLE_RATE
                val envelope = minOf(1.0, local / 200.0, (chirp - local) / 200.0)
                (sin(phase) * envelope * AMPLITUDE).toInt().toShort()
            } else 0
            out[i * 2] = if (target == FindSoundTarget.RIGHT) 0 else sample
            out[i * 2 + 1] = if (target == FindSoundTarget.LEFT) 0 else sample
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val AMPLITUDE = 30_000.0
        const val MAX_DURATION_MS = 60_000L
    }
}
