package com.example.airsync.data.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Phone-microphone signal processing for the "simulated" features:
 *  - [sampleDb]: short ambient level reading for the "noisy → ANC" automation rule.
 *  - [voiceActivity]: lightweight energy-based speech detector for simulated conversational
 *    awareness (real CA needs the AirPods' own mics, i.e. the AAP channel).
 *
 * Audio is processed in memory only; nothing is stored or transmitted.
 * If Android silences the mic (app not eligible for background capture) we get all-zero buffers,
 * which we report as "unknown" rather than "quiet" so automation never acts on bogus data.
 */
@Singleton
class AmbientSoundMeter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Estimated ambient level in dB SPL, or null if unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun sampleDb(durationMs: Long = 1_500): Float? = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext null
        val record = createRecord() ?: return@withContext null
        try {
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            var sumSquares = 0.0
            var count = 0L
            var nonZero = false
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end && currentCoroutineContext().isActive) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                for (i in 0 until n) {
                    val s = frame[i].toDouble()
                    if (s != 0.0) nonZero = true
                    sumSquares += s * s
                }
                count += n
            }
            if (!nonZero || count == 0L) null else toSpl(sqrt(sumSquares / count))
        } catch (_: Exception) {
            null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Emits true while nearby speech is detected. Adaptive noise floor + 1 s majority vote keeps it
     * from triggering on door slams or steady background noise.
     */
    @SuppressLint("MissingPermission")
    fun voiceActivity(): Flow<Boolean> = flow {
        if (!hasPermission) return@flow
        val record = createRecord() ?: return@flow
        try {
            record.startRecording()
            val frame = ShortArray(FRAME_SAMPLES)
            val window = ArrayDeque<Boolean>()
            var floorDb = 45f
            while (currentCoroutineContext().isActive) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) break
                var sum = 0.0
                for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                val db = toSpl(sqrt(sum / n))
                val speechy = db > floorDb + SPEECH_MARGIN_DB && db > MIN_SPEECH_DB
                // Track the floor slowly, and only from non-speech frames.
                if (!speechy) floorDb = floorDb * 0.97f + db * 0.03f
                window.addLast(speechy)
                if (window.size > WINDOW_FRAMES) window.removeFirst()
                emit(window.count { it } >= WINDOW_FRAMES * 0.6)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        return runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, FRAME_SAMPLES * 4)
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
    }

    /** dBFS → rough dB SPL. Phone mics are uncalibrated; ±5 dB is fine for coarse rules. */
    private fun toSpl(rms: Double): Float =
        (20 * log10(max(rms, 1.0) / Short.MAX_VALUE) + CALIBRATION_OFFSET_DB).toFloat()

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 800 // 50 ms
        const val WINDOW_FRAMES = 20 // 1 s
        const val CALIBRATION_OFFSET_DB = 94.0
        const val SPEECH_MARGIN_DB = 10f
        const val MIN_SPEECH_DB = 52f
    }
}
