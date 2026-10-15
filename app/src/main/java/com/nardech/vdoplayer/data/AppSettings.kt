package com.nardech.vdoplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nardech.vdoplayer.logging.LogExportFormat
import com.nardech.vdoplayer.logging.LogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Maps onto the NextLib / Media3 extension renderer priority. */
enum class DecoderPreference(val label: String, val extensionMode: Int) {
    PLATFORM_FIRST("Hardware first, FFmpeg fallback", 1),
    FFMPEG_FIRST("Prefer FFmpeg (most compatible)", 2),
    PLATFORM_ONLY("Hardware only", 0),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val decoderPreference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
    val gesturesEnabled: Boolean = true,
    val seekStepMs: Long = 10_000L,
    val defaultSpeed: Float = 1f,
    val resumePlayback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoRotate: Boolean = true,
    val autoPlayNext: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val rememberBrightness: Boolean = true,
    val gridColumns: Int = 2,
    val sortOrder: SortOrder = SortOrder.DATE_NEWEST,
    val logLevel: LogLevel = LogLevel.DEBUG,
    val logExportFormat: LogExportFormat = LogExportFormat.ZIP,
    val includeDiagnosticsInExport: Boolean = true,
)

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "vdo_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val decoder = stringPreferencesKey("decoder_preference")
        val gestures = booleanPreferencesKey("gestures")
        val seekStep = longPreferencesKey("seek_step_ms")
        val speed = floatPreferencesKey("default_speed")
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoRotate = booleanPreferencesKey("auto_rotate")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val rememberBrightness = booleanPreferencesKey("remember_brightness")
        val gridColumns = intPreferencesKey("grid_columns")
        val sortOrder = stringPreferencesKey("sort_order")
        val logLevel = stringPreferencesKey("log_level")
        val logExportFormat = stringPreferencesKey("log_export_format")
        val diagnosticsInExport = booleanPreferencesKey("diagnostics_in_export")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.themeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.themeMode,
            dynamicColor = prefs[Keys.dynamicColor] ?: defaults.dynamicColor,
            decoderPreference = prefs[Keys.decoder]
                ?.let { name -> DecoderPreference.entries.firstOrNull { it.name == name } }
                ?: defaults.decoderPreference,
            gesturesEnabled = prefs[Keys.gestures] ?: defaults.gesturesEnabled,
            seekStepMs = prefs[Keys.seekStep] ?: defaults.seekStepMs,
            defaultSpeed = prefs[Keys.speed] ?: defaults.defaultSpeed,
            resumePlayback = prefs[Keys.resumePlayback] ?: defaults.resumePlayback,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            autoRotate = prefs[Keys.autoRotate] ?: defaults.autoRotate,
            autoPlayNext = prefs[Keys.autoPlayNext] ?: defaults.autoPlayNext,
            backgroundPlayback = prefs[Keys.backgroundPlayback] ?: defaults.backgroundPlayback,
            rememberBrightness = prefs[Keys.rememberBrightness] ?: defaults.rememberBrightness,
            gridColumns = prefs[Keys.gridColumns] ?: defaults.gridColumns,
            sortOrder = prefs[Keys.sortOrder]
                ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
                ?: defaults.sortOrder,
            logLevel = prefs[Keys.logLevel]
                ?.let { LogLevel.fromName(it) }
                ?: defaults.logLevel,
            logExportFormat = prefs[Keys.logExportFormat]
                ?.let { name -> LogExportFormat.entries.firstOrNull { it.name == name } }
                ?: defaults.logExportFormat,
            includeDiagnosticsInExport = prefs[Keys.diagnosticsInExport]
                ?: defaults.includeDiagnosticsInExport,
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.themeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.dynamicColor] = value }
    suspend fun setDecoderPreference(value: DecoderPreference) =
        edit { it[Keys.decoder] = value.name }

    suspend fun setGestures(value: Boolean) = edit { it[Keys.gestures] = value }
    suspend fun setSeekStep(value: Long) = edit { it[Keys.seekStep] = value }
    suspend fun setDefaultSpeed(value: Float) = edit { it[Keys.speed] = value }
    suspend fun setResumePlayback(value: Boolean) = edit { it[Keys.resumePlayback] = value }
    suspend fun setKeepScreenOn(value: Boolean) = edit { it[Keys.keepScreenOn] = value }
    suspend fun setAutoRotate(value: Boolean) = edit { it[Keys.autoRotate] = value }
    suspend fun setAutoPlayNext(value: Boolean) = edit { it[Keys.autoPlayNext] = value }
    suspend fun setBackgroundPlayback(value: Boolean) = edit { it[Keys.backgroundPlayback] = value }
    suspend fun setRememberBrightness(value: Boolean) = edit { it[Keys.rememberBrightness] = value }
    suspend fun setGridColumns(value: Int) = edit { it[Keys.gridColumns] = value }
    suspend fun setSortOrder(value: SortOrder) = edit { it[Keys.sortOrder] = value.name }
    suspend fun setLogLevel(value: LogLevel) = edit { it[Keys.logLevel] = value.name }
    suspend fun setLogExportFormat(value: LogExportFormat) =
        edit { it[Keys.logExportFormat] = value.name }

    suspend fun setIncludeDiagnostics(value: Boolean) = edit { it[Keys.diagnosticsInExport] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
