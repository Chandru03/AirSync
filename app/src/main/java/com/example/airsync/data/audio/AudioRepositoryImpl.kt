package com.example.airsync.data.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.Spatializer
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.airsync.domain.model.SpatialAudioState
import com.example.airsync.domain.model.SpatialAudioStatus
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.repository.AudioRepository
import com.example.airsync.domain.repository.FindSoundTarget
import com.example.airsync.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AudioRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val equalizer: EqualizerController,
    @ApplicationScope scope: CoroutineScope
) : AudioRepository {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val findSound = FindSoundPlayer(audioManager)
    private val ducker = VolumeDucker(audioManager, scope)

    override val isMusicActive: Boolean get() = audioManager.isMusicActive

    private val _musicPlaying = MutableStateFlow(audioManager.isMusicActive)
    override val musicPlaying: StateFlow<Boolean> = _musicPlaying.asStateFlow()

    init {
        audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                _musicPlaying.value = configs.any {
                    it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA ||
                        it.audioAttributes.usage == AudioAttributes.USAGE_GAME
                }
            }
        }, Handler(Looper.getMainLooper()))
    }
    private val _spatialAudio = MutableStateFlow(readSpatialAudio())
    override val spatialAudio: StateFlow<SpatialAudioState> = _spatialAudio.asStateFlow()

    init {
        // The spatializer reports enable/availability changes itself (availability follows routing:
        // AirPods media = available, a call over SCO = not). Head-tracker changes come separately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            val spatializer = audioManager.spatializer
            val executor = ContextCompat.getMainExecutor(context)
            spatializer.addOnSpatializerStateChangedListener(executor, object : Spatializer.OnSpatializerStateChangedListener {
                override fun onSpatializerEnabledChanged(spat: Spatializer, enabled: Boolean) { _spatialAudio.value = readSpatialAudio() }
                override fun onSpatializerAvailableChanged(spat: Spatializer, available: Boolean) { _spatialAudio.value = readSpatialAudio() }
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                spatializer.addOnHeadTrackerAvailableListener(executor) { _, _ -> _spatialAudio.value = readSpatialAudio() }
            }
        }
    }

    private fun readSpatialAudio(): SpatialAudioState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return SpatialAudioState()
        val s = audioManager.spatializer
        if (s.immersiveAudioLevel == Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) return SpatialAudioState()
        val status = when {
            !s.isEnabled -> SpatialAudioStatus.OFF
            !s.isAvailable -> SpatialAudioStatus.ON_INACTIVE
            else -> SpatialAudioStatus.ACTIVE
        }
        val tracking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s.isHeadTrackerAvailable
        return SpatialAudioState(status, tracking)
    }

    override val eqSessionApps: StateFlow<Set<String>> = equalizer.apps
    override val isFindSoundPlaying: StateFlow<Boolean> = findSound.playing

    // Media keys reach whichever MediaSession is currently active (Spotify, YouTube Music, ...).
    override fun pause() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    override fun play() = dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

    override suspend fun duckConversation(targetFraction: Float) = ducker.duckTo(targetFraction)
    override suspend fun endConversationDuck() = ducker.release()
    override fun stopDuckingNow() = ducker.stop()

    override fun applyEqPreset(default: EqPreset, perApp: Map<String, EqPreset>) = equalizer.update(default, perApp)
    override fun onAudioSessionOpened(sessionId: Int, packageName: String?) = equalizer.onSessionOpened(sessionId, packageName)
    override fun onAudioSessionClosed(sessionId: Int) = equalizer.onSessionClosed(sessionId)
    override fun releaseEqualizers() = equalizer.releaseAll()

    override fun startFindSound(target: FindSoundTarget): Boolean {
        val hasBluetoothOutput = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (!hasBluetoothOutput) return false
        if (audioManager.isMusicActive) pause()
        findSound.start(target)
        return true
    }

    override fun stopFindSound() = findSound.stop()

    private fun dispatchMediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

}
