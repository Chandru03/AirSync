package com.example.airsync.data.context

import com.example.airsync.data.audio.AmbientSoundMeter
import com.example.airsync.domain.automation.AutomationContext
import com.example.airsync.domain.automation.AutomationEngine
import com.example.airsync.domain.repository.AirPodsRepository
import com.example.airsync.domain.repository.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between context signals and the pure [AutomationEngine]. Invoked on activity transitions,
 * on AirPods connect, and by the periodic WorkManager job — never in a tight loop.
 */
@Singleton
class AutomationController @Inject constructor(
    private val engine: AutomationEngine,
    private val airPods: AirPodsRepository,
    private val settingsRepository: SettingsRepository,
    private val signals: ContextSignals,
    private val soundMeter: AmbientSoundMeter
) {
    private val mutex = Mutex()

    suspend fun evaluate(sampleNoise: Boolean = true) = mutex.withLock {
        val settings = settingsRepository.awaitLoaded()
        val status = airPods.status.value
        if (!settings.automationEnabled || !status.isConnected) return@withLock

        // Unknown ear state (no BLE data yet) while audio-connected: assume worn.
        val wearing = !status.ear.isKnown || status.ear.budsInEar > 0
        if (!wearing) return@withLock

        if (sampleNoise && settings.noisyRule && soundMeter.hasPermission) {
            signals.ambientDb.value = soundMeter.sampleDb()
        }

        val now = System.currentTimeMillis()
        val decision = engine.evaluate(
            AutomationContext(
                enabled = true,
                connected = true,
                wearing = true,
                currentMode = status.noiseMode,
                activity = signals.activity.value,
                ambientDb = signals.ambientDb.value,
                walkingRule = settings.walkingRule,
                noisyRule = settings.noisyRule,
                stationaryRule = settings.stationaryRule,
                nowMillis = now,
                manualOverrideUntilMillis = signals.manualOverrideUntilMillis.value
            )
        ) ?: return@withLock

        airPods.setNoiseMode(decision.mode, fromUser = false)
        signals.lastAutomation.value = AutomationEvent(decision, now)
    }
}
