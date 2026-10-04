package com.example.airsync.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.airsync.data.audio.AmbientSoundMeter
import com.example.airsync.data.context.ActivityRecognitionSource
import com.example.airsync.data.context.AutomationController
import com.example.airsync.data.location.LocationRepository
import com.example.airsync.data.bluetooth.aap.AapProtocol
import com.example.airsync.data.repository.AirPodsRepositoryImpl
import com.example.airsync.domain.model.AirPodsStatus
import com.example.airsync.domain.model.AppSettings
import com.example.airsync.domain.model.ControlChannel
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.AudioRepository
import com.example.airsync.domain.repository.SettingsRepository
import com.example.airsync.worker.AirPodsAutomationWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Long-running companion service. Battery strategy:
 *  - Connection tracking is broadcast-driven (free).
 *  - BLE scanning only while AirPods are connected, hardware-filtered, LOW_POWER mode.
 *  - Activity changes come from Play services' low-power transition API.
 *  - Microphone is opened only for an enabled feature (and only while music plays for CA).
 *  - Periodic work goes through WorkManager (15 min), never a wake-lock loop.
 */
@AndroidEntryPoint
class AirSyncService : Service() {

    @Inject lateinit var airPods: AirPodsRepository
    @Inject lateinit var audio: AudioRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var location: LocationRepository
    @Inject lateinit var automation: AutomationController
    @Inject lateinit var activitySource: ActivityRecognitionSource
    @Inject lateinit var soundMeter: AmbientSoundMeter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val earPolicy = EarDetectionPolicy()
    private var started = false
    private var lastNotified: String? = null

    /** Media apps announce their audio sessions here; we attach equalizers to them. */
    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    audio.onAudioSessionOpened(session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> audio.onAudioSessionClosed(session)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            // null intent = sticky restart by the system; treat it as a start.
            else -> if (!promote()) return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startMonitoring()
        }
        return START_STICKY
    }

    /**
     * Enters the foreground with every service type we are currently eligible for. While-in-use
     * types (location, microphone) are only allowed when started from the foreground (e.g. the app
     * is open); from boot we fall back to connectedDevice only, and retry when the UI opens.
     */
    private fun promote(): Boolean {
        val notification = ServiceNotification.build(this, airPods.status.value)
        val full = desiredTypes(settingsRepository.settings.value)
        for (types in listOf(full, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE).distinct()) {
            try {
                ServiceCompat.startForeground(this, ServiceNotification.NOTIFICATION_ID, notification, types)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground(types=$types) rejected: ${e.javaClass.simpleName}")
            }
        }
        stopSelf()
        return false
    }

    private fun desiredTypes(settings: AppSettings): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return types
        if (location.hasPermission) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // Only the "noisy → ANC" automation rule samples the phone mic; CA uses the AirPods' mics.
        val needsMic = settings.automationEnabled && settings.noisyRule
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && needsMic && soundMeter.hasPermission) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    @OptIn(FlowPreview::class)
    private fun startMonitoring() {
        airPods.setScanDemand(AirPodsRepositoryImpl.SERVICE_OWNER, true)
        ContextCompat.registerReceiver(
            this, audioSessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED // sent by third-party media apps
        )

        val status = airPods.status
        val settings = settingsRepository.settings

        // Notification mirrors live state; only re-post when the visible text changes.
        status.onEach(::updateNotification).launchIn(scope)

        // Remove to pause / insert to resume. Debounce smooths flickering adverts.
        status.map { it.ear }.distinctUntilChanged().debounce(EAR_DEBOUNCE_MS).onEach { ear ->
            val action = earPolicy.onEarChanged(
                ear, audio.isMusicActive, settings.value.removeToPause, System.currentTimeMillis()
            )
            if (action != EarDetectionPolicy.Action.NONE) Log.i(TAG, "ear ${ear.left}/${ear.right} music=${audio.isMusicActive} -> $action")
            when (action) {
                EarDetectionPolicy.Action.PAUSE -> audio.pause()
                EarDetectionPolicy.Action.PLAY -> audio.play()
                EarDetectionPolicy.Action.NONE -> Unit
            }
        }.launchIn(scope)

        // Connect / disconnect: remember where the AirPods were, kick automation.
        status.map { it.device?.address }.distinctUntilChanged().drop(1).onEach { address ->
            earPolicy.reset()
            scope.launch { location.recordLastSeen() }
            if (address != null) scope.launch { delay(3_000); automation.evaluate() }
        }.launchIn(scope)

        // Automation: activity transitions + periodic re-evaluation only while enabled.
        settings.map { it.automationEnabled }.distinctUntilChanged().onEach { enabled ->
            val work = WorkManager.getInstance(this)
            if (enabled) {
                activitySource.start()
                work.enqueueUniquePeriodicWork(
                    AirPodsAutomationWorker.NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<AirPodsAutomationWorker>(15, TimeUnit.MINUTES).build()
                )
                automation.evaluate()
            } else {
                activitySource.stop()
                work.cancelUniqueWork(AirPodsAutomationWorker.NAME)
            }
        }.launchIn(scope)

        // EQ presets follow settings.
        settings.map { it.eqPreset to it.appEqPresets }.distinctUntilChanged()
            .onEach { (preset, perApp) -> audio.applyEqPreset(preset, perApp) }
            .launchIn(scope)

        // Conversation Awareness — driven entirely by the AirPods' own microphones (the AAP level
        // stream, exactly as on iPhone). The phone microphone is never used for this.
        (airPods as? AirPodsRepositoryImpl)?.conversation
            ?.onEach { onConversationLevel(it, settings.value.conversationalAwareness) }
            ?.launchIn(scope)

        // Releasing the duck the moment the feature is turned off.
        settings.map { it.conversationalAwareness }.distinctUntilChanged().onEach { enabled ->
            if (!enabled) { conversationIdleJob?.cancel(); audio.endConversationDuck() }
        }.launchIn(scope)

        // Head gestures for calls: armed only while the user has the feature on.
        (airPods as? AirPodsRepositoryImpl)?.let { repo ->
            val gestures = CallGestureController(this, repo, scope).also { callGestures = it }
            settings.map { it.headGestures }.distinctUntilChanged().onEach { enabled ->
                if (enabled) gestures.start() else gestures.stop()
            }.launchIn(scope)
        }

        settings.map { it.backgroundEnabled }.distinctUntilChanged().onEach { enabled ->
            if (!enabled) stopEverything()
        }.launchIn(scope)
    }

    private var callGestures: CallGestureController? = null

    private var conversationIdleJob: Job? = null

    private suspend fun onConversationLevel(level: AapProtocol.Event.ConversationLevel?, enabled: Boolean) {
        if (level == null || !enabled) return
        conversationIdleJob?.cancel()
        Log.i(TAG, "CA level ${level.level} -> target x${level.volumeFraction}")
        if (level.volumeFraction < 1f) {
            audio.duckConversation(level.volumeFraction)
            // Safety net: if the AirPods never send the "back to normal" level, restore after a lull
            // so the volume can't get stuck part-way down.
            conversationIdleJob = scope.launch {
                delay(CONVERSATION_IDLE_MS)
                audio.endConversationDuck()
            }
        } else {
            audio.endConversationDuck()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(status: AirPodsStatus) {
        val key = "${status.device?.name}|${ServiceNotification.batterySummary(this, status)}|${status.noiseMode}"
        if (key == lastNotified) return
        lastNotified = key
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            getSystemService(NotificationManager::class.java)
                .notify(ServiceNotification.NOTIFICATION_ID, ServiceNotification.build(this, status))
        }
    }

    private fun stopEverything() {
        if (started) {
            airPods.setScanDemand(AirPodsRepositoryImpl.SERVICE_OWNER, false)
            runCatching { unregisterReceiver(audioSessionReceiver) }
            audio.stopDuckingNow()
            audio.releaseEqualizers()
            callGestures?.stop()
            WorkManager.getInstance(this).cancelUniqueWork(AirPodsAutomationWorker.NAME)
            started = false
        }
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (started) {
            airPods.setScanDemand(AirPodsRepositoryImpl.SERVICE_OWNER, false)
            runCatching { unregisterReceiver(audioSessionReceiver) }
            audio.stopDuckingNow()
            audio.releaseEqualizers()
            callGestures?.stop()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirSyncService"
        private const val ACTION_START = "com.example.airsync.action.START"
        private const val ACTION_STOP = "com.example.airsync.action.STOP"
        private const val EAR_DEBOUNCE_MS = 350L
        private const val CONVERSATION_IDLE_MS = 1_200L

        /** Bluetooth permission is a hard prerequisite for a connectedDevice FGS on Android 14+. */
        fun canStart(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        /** Starts (or re-promotes with new FGS types). Safe to call repeatedly. */
        fun start(context: Context) {
            if (!canStart(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context, Intent(context, AirSyncService::class.java).setAction(ACTION_START)
                )
            }.onFailure { Log.w(TAG, "Could not start service: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, AirSyncService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
