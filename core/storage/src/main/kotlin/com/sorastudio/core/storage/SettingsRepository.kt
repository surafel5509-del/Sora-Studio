package com.sorastudio.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sora_settings")

enum class ThemeMode { DARK, LIGHT, SYSTEM }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val defaultBpm: Float = 120f,
    val sampleRate: Int = 44100,
    val bufferSizeFrames: Int = 512,
    val metronomeDefault: Boolean = false,
    val countInBars: Int = 1,
    val monitorWhileRecording: Boolean = false,
    val masterVolumePercent: Int = 80,
    val lastProjectId: String? = null,
    val snapDefault: Boolean = true,
    val exportSampleRate: Int = 44100
)

/** DataStore-backed user preferences. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DEFAULT_BPM = floatPreferencesKey("default_bpm")
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val BUFFER_FRAMES = intPreferencesKey("buffer_frames")
        val METRONOME = booleanPreferencesKey("metronome_default")
        val COUNT_IN = intPreferencesKey("count_in_bars")
        val MONITOR = booleanPreferencesKey("monitor_recording")
        val MASTER_VOLUME = intPreferencesKey("master_volume")
        val LAST_PROJECT = stringPreferencesKey("last_project_id")
        val SNAP = booleanPreferencesKey("snap_default")
        val EXPORT_RATE = intPreferencesKey("export_sample_rate")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: "DARK") }.getOrDefault(ThemeMode.DARK),
            defaultBpm = prefs[Keys.DEFAULT_BPM] ?: 120f,
            sampleRate = prefs[Keys.SAMPLE_RATE] ?: 44100,
            bufferSizeFrames = prefs[Keys.BUFFER_FRAMES] ?: 512,
            metronomeDefault = prefs[Keys.METRONOME] ?: false,
            countInBars = prefs[Keys.COUNT_IN] ?: 1,
            monitorWhileRecording = prefs[Keys.MONITOR] ?: false,
            masterVolumePercent = prefs[Keys.MASTER_VOLUME] ?: 80,
            lastProjectId = prefs[Keys.LAST_PROJECT],
            snapDefault = prefs[Keys.SNAP] ?: true,
            exportSampleRate = prefs[Keys.EXPORT_RATE] ?: 44100
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME] = mode.name }
    suspend fun setDefaultBpm(bpm: Float) = edit { it[Keys.DEFAULT_BPM] = bpm.coerceIn(40f, 240f) }
    suspend fun setSampleRate(rate: Int) = edit { it[Keys.SAMPLE_RATE] = rate }
    suspend fun setBufferSize(frames: Int) = edit { it[Keys.BUFFER_FRAMES] = frames }
    suspend fun setMetronomeDefault(enabled: Boolean) = edit { it[Keys.METRONOME] = enabled }
    suspend fun setCountInBars(bars: Int) = edit { it[Keys.COUNT_IN] = bars.coerceIn(0, 4) }
    suspend fun setMonitor(enabled: Boolean) = edit { it[Keys.MONITOR] = enabled }
    suspend fun setMasterVolume(percent: Int) = edit { it[Keys.MASTER_VOLUME] = percent.coerceIn(0, 100) }
    suspend fun setLastProject(id: String?) = edit { prefs ->
        if (id == null) prefs.remove(Keys.LAST_PROJECT) else prefs[Keys.LAST_PROJECT] = id
    }
    suspend fun setSnapDefault(enabled: Boolean) = edit { it[Keys.SNAP] = enabled }
    suspend fun setExportSampleRate(rate: Int) = edit { it[Keys.EXPORT_RATE] = rate }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
