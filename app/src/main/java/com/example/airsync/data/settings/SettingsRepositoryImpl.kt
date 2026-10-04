package com.example.airsync.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.airsync.di.ApplicationScope
import com.example.airsync.domain.model.AncMode
import com.example.airsync.domain.model.AppSettings
import com.example.airsync.domain.model.BleKeyPair
import com.example.airsync.domain.model.CaseReading
import com.example.airsync.domain.model.EqPreset
import com.example.airsync.domain.model.LastSeenLocation
import com.example.airsync.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "airsync_state")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope scope: CoroutineScope
) : SettingsRepository {

    private val dataStore = context.settingsDataStore

    override val settings: StateFlow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun awaitLoaded(): AppSettings = settings.first { it.loaded }

    override suspend fun setNoiseMode(mode: AncMode) = edit { it[Keys.ANC_MODE] = mode.name }
    override suspend fun setTransparencyLevel(level: Float) = edit { it[Keys.TRANSPARENCY_LEVEL] = level.coerceIn(0f, 1f) }
    override suspend fun setConversationalAwareness(enabled: Boolean) = edit { it[Keys.CONVERSATION] = enabled }
    override suspend fun setRemoveToPause(enabled: Boolean) = edit { it[Keys.REMOVE_TO_PAUSE] = enabled }
    override suspend fun setHeadGestures(enabled: Boolean) = edit { it[Keys.HEAD_GESTURES] = enabled }
    override suspend fun setBackgroundEnabled(enabled: Boolean) = edit { it[Keys.BACKGROUND] = enabled }
    override suspend fun setAutomationEnabled(enabled: Boolean) = edit { it[Keys.AUTOMATION] = enabled }
    override suspend fun setEqPreset(preset: EqPreset) = edit { it[Keys.EQ_PRESET] = preset.name }

    override suspend fun setAutomationRules(walking: Boolean, noisy: Boolean, stationary: Boolean) = edit {
        it[Keys.RULE_WALKING] = walking
        it[Keys.RULE_NOISY] = noisy
        it[Keys.RULE_STATIONARY] = stationary
    }

    override suspend fun setAppEqPreset(packageName: String, preset: EqPreset?) = edit { prefs ->
        val current = decodeAppPresets(prefs[Keys.APP_EQ]).toMutableMap()
        if (preset == null) current.remove(packageName) else current[packageName] = preset
        prefs[Keys.APP_EQ] = current.map { (pkg, p) -> "$pkg$SEP${p.name}" }.toSet()
    }

    override suspend fun setPreferredDevice(address: String?) = edit {
        if (address == null) it.remove(Keys.PREFERRED_DEVICE) else it[Keys.PREFERRED_DEVICE] = address
    }

    override suspend fun setBleKeys(address: String, keys: BleKeyPair) = edit { prefs ->
        val current = decodeKeys(prefs[Keys.BLE_KEYS]).toMutableMap()
        current[address] = keys
        prefs[Keys.BLE_KEYS] = current.map { (a, k) -> "$a$SEP${k.irkHex}$SEP${k.encKeyHex.orEmpty()}" }.toSet()
    }

    override suspend fun setLastCase(address: String, reading: CaseReading) = edit { prefs ->
        val current = decodeCase(prefs[Keys.LAST_CASE]).toMutableMap()
        current[address] = reading
        prefs[Keys.LAST_CASE] = current.map { (a, r) -> "$a$SEP${r.level}$SEP${if (r.charging) 1 else 0}$SEP${r.atMillis}" }.toSet()
    }

    override suspend fun setLastSeen(location: LastSeenLocation) = edit {
        it[Keys.LAST_LAT] = location.latitude
        it[Keys.LAST_LNG] = location.longitude
        it[Keys.LAST_ACCURACY] = location.accuracyMeters
        it[Keys.LAST_TIME] = location.timestampMillis
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        val lat = this[Keys.LAST_LAT]
        val lng = this[Keys.LAST_LNG]
        val time = this[Keys.LAST_TIME]
        return AppSettings(
            noiseMode = enumOrNull<AncMode>(this[Keys.ANC_MODE]) ?: defaults.noiseMode,
            transparencyLevel = this[Keys.TRANSPARENCY_LEVEL] ?: defaults.transparencyLevel,
            conversationalAwareness = this[Keys.CONVERSATION] ?: defaults.conversationalAwareness,
            removeToPause = this[Keys.REMOVE_TO_PAUSE] ?: defaults.removeToPause,
            headGestures = this[Keys.HEAD_GESTURES] ?: defaults.headGestures,
            backgroundEnabled = this[Keys.BACKGROUND] ?: defaults.backgroundEnabled,
            automationEnabled = this[Keys.AUTOMATION] ?: defaults.automationEnabled,
            walkingRule = this[Keys.RULE_WALKING] ?: defaults.walkingRule,
            noisyRule = this[Keys.RULE_NOISY] ?: defaults.noisyRule,
            stationaryRule = this[Keys.RULE_STATIONARY] ?: defaults.stationaryRule,
            eqPreset = enumOrNull<EqPreset>(this[Keys.EQ_PRESET]) ?: defaults.eqPreset,
            appEqPresets = decodeAppPresets(this[Keys.APP_EQ]),
            bleKeys = decodeKeys(this[Keys.BLE_KEYS]),
            lastCase = decodeCase(this[Keys.LAST_CASE]),
            preferredDeviceAddress = this[Keys.PREFERRED_DEVICE],
            lastSeen = if (lat != null && lng != null && time != null) {
                LastSeenLocation(lat, lng, this[Keys.LAST_ACCURACY] ?: 0f, time)
            } else null,
            loaded = true
        )
    }

    private fun decodeCase(raw: Set<String>?): Map<String, CaseReading> =
        raw.orEmpty().mapNotNull { entry ->
            val p = entry.split(SEP)
            if (p.size != 4) null
            else runCatching { p[0] to CaseReading(p[1].toInt(), p[2] == "1", p[3].toLong()) }.getOrNull()
        }.toMap()

    private fun decodeKeys(raw: Set<String>?): Map<String, BleKeyPair> =
        raw.orEmpty().mapNotNull { entry ->
            val parts = entry.split(SEP)
            if (parts.size < 2 || parts[1].length != 32) null
            else parts[0] to BleKeyPair(parts[1], parts.getOrNull(2)?.takeIf { it.length == 32 })
        }.toMap()

    private fun decodeAppPresets(raw: Set<String>?): Map<String, EqPreset> =
        raw.orEmpty().mapNotNull { entry ->
            val pkg = entry.substringBefore(SEP)
            enumOrNull<EqPreset>(entry.substringAfter(SEP, ""))?.let { pkg to it }
        }.toMap()

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? =
        value?.let { v -> enumValues<T>().firstOrNull { it.name == v } }

    private object Keys {
        val ANC_MODE = stringPreferencesKey("anc_mode")
        val TRANSPARENCY_LEVEL = floatPreferencesKey("transparency_level")
        val CONVERSATION = booleanPreferencesKey("conversation")
        val REMOVE_TO_PAUSE = booleanPreferencesKey("remove_to_pause")
        val HEAD_GESTURES = booleanPreferencesKey("head_gestures")
        val BACKGROUND = booleanPreferencesKey("background_enabled")
        val AUTOMATION = booleanPreferencesKey("automation")
        val RULE_WALKING = booleanPreferencesKey("rule_walking")
        val RULE_NOISY = booleanPreferencesKey("rule_noisy")
        val RULE_STATIONARY = booleanPreferencesKey("rule_stationary")
        val EQ_PRESET = stringPreferencesKey("eq_preset")
        val APP_EQ = stringSetPreferencesKey("app_eq_presets")
        val BLE_KEYS = stringSetPreferencesKey("ble_keys")
        val LAST_CASE = stringSetPreferencesKey("last_case")
        val PREFERRED_DEVICE = stringPreferencesKey("preferred_device")
        val LAST_LAT = doublePreferencesKey("last_lat")
        val LAST_LNG = doublePreferencesKey("last_lng")
        val LAST_ACCURACY = floatPreferencesKey("last_accuracy")
        val LAST_TIME = longPreferencesKey("last_time")
    }

    private companion object {
        const val SEP = "|"
    }
}
