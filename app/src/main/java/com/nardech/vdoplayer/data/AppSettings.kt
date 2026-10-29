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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.exoplayer.DefaultRenderersFactory
import com.nardech.vdoplayer.logging.LogExportFormat
import com.nardech.vdoplayer.logging.LogLevel
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Decoder priority, in two halves that NextLib keeps apart: which renderers the
 * player is built with ([extensionMode]), and which of those the decoder manager
 * is allowed to select ([videoMode] / [audioMode]).
 *
 * The renderers are fixed when the player is built, so the extension renderers stay
 * enabled even for [PLATFORM_ONLY]: that is what lets the player switch between
 * hardware and FFmpeg while a video is playing. What each entry means:
 *
 * * [PLATFORM_FIRST] — hardware first, FFmpeg when the SoC cannot decode the file.
 * * [FFMPEG_FIRST] — FFmpeg decodes video and audio, for the widest format support.
 * * [PLATFORM_ONLY] — hardware MediaCodec decoders only; no FFmpeg, no software.
 *
 * Display names live in string resources (`decoder_*`) and are resolved by
 * `com.nardech.vdoplayer.ui.decoderPreferenceLabel`.
 */
enum class DecoderPreference(
    val extensionMode: Int,
    val videoMode: DecoderMode,
    val audioMode: DecoderMode,
) {
    PLATFORM_FIRST(
        extensionMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        videoMode = DecoderMode.AUTO,
        audioMode = DecoderMode.AUTO,
    ),
    FFMPEG_FIRST(
        extensionMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
        videoMode = DecoderMode.FFMPEG,
        audioMode = DecoderMode.FFMPEG,
    ),
    PLATFORM_ONLY(
        extensionMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        videoMode = DecoderMode.HARDWARE,
        audioMode = DecoderMode.HARDWARE,
    ),
    ;

    companion object {
        fun fromName(name: String?): DecoderPreference? =
            entries.firstOrNull { it.name == name }
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val decoderPreference: DecoderPreference = DecoderPreference.PLATFORM_FIRST,
    val gesturesEnabled: Boolean = true,
    /**
     * Puts the seek bar and the buttons along the top edge, above the picture, instead
     * of the bottom one. The title bar takes the other edge, so the two never overlap.
     */
    val controlsOnTop: Boolean = false,
    val seekStepMs: Long = 10_000L,
    val defaultSpeed: Float = 1f,
    val resumePlayback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoRotate: Boolean = true,
    val autoPlayNext: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val rememberBrightness: Boolean = true,
    /** Decodes a frame while scrubbing, so the seek bar shows where it is going. */
    val seekPreviewEnabled: Boolean = true,
    /** Looks for a `.srt` / `.vtt` / `.ass` next to the video and loads it automatically. */
    val subtitleAutoDiscovery: Boolean = true,
    /**
     * Audio codecs the viewer already rescued by hand. When one of them cannot be
     * decoded again, the player switches to FFmpeg without asking.
     */
    val autoFfmpegCodecs: Set<String> = emptySet(),
    val gridColumns: Int = 2,
    val sortOrder: SortOrder = SortOrder.DATE_NEWEST,
    /** Draws the library as folder sections instead of one flat list. */
    val groupByFolder: Boolean = false,
    val logLevel: LogLevel = LogLevel.DEBUG,
    val logExportFormat: LogExportFormat = LogExportFormat.ZIP,
    val includeDiagnosticsInExport: Boolean = true,
    /** Name other phones see while receiving; empty means "use the device model". */
    val deviceName: String = "",
)

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "vdo_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val decoder = stringPreferencesKey("decoder_preference")
        val gestures = booleanPreferencesKey("gestures")
        val controlsOnTop = booleanPreferencesKey("controls_on_top")
        val seekStep = longPreferencesKey("seek_step_ms")
        val speed = floatPreferencesKey("default_speed")
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoRotate = booleanPreferencesKey("auto_rotate")
        val autoPlayNext = booleanPreferencesKey("auto_play_next")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val rememberBrightness = booleanPreferencesKey("remember_brightness")
        val seekPreview = booleanPreferencesKey("seek_preview")
        val subtitleAutoDiscovery = booleanPreferencesKey("subtitle_auto_discovery")
        val autoFfmpegCodecs = stringSetPreferencesKey("auto_ffmpeg_audio_codecs")
        val gridColumns = intPreferencesKey("grid_columns")
        val sortOrder = stringPreferencesKey("sort_order")
        val groupByFolder = booleanPreferencesKey("group_by_folder")
        val logLevel = stringPreferencesKey("log_level")
        val logExportFormat = stringPreferencesKey("log_export_format")
        val diagnosticsInExport = booleanPreferencesKey("diagnostics_in_export")
        val deviceName = stringPreferencesKey("device_name")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val defaults = AppSettings()
        AppSettings(
            themeMode = prefs[Keys.themeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: defaults.themeMode,
            dynamicColor = prefs[Keys.dynamicColor] ?: defaults.dynamicColor,
            decoderPreference = DecoderPreference.fromName(prefs[Keys.decoder])
                ?: defaults.decoderPreference,
            gesturesEnabled = prefs[Keys.gestures] ?: defaults.gesturesEnabled,
            controlsOnTop = prefs[Keys.controlsOnTop] ?: defaults.controlsOnTop,
            seekStepMs = prefs[Keys.seekStep] ?: defaults.seekStepMs,
            defaultSpeed = prefs[Keys.speed] ?: defaults.defaultSpeed,
            resumePlayback = prefs[Keys.resumePlayback] ?: defaults.resumePlayback,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            autoRotate = prefs[Keys.autoRotate] ?: defaults.autoRotate,
            autoPlayNext = prefs[Keys.autoPlayNext] ?: defaults.autoPlayNext,
            backgroundPlayback = prefs[Keys.backgroundPlayback] ?: defaults.backgroundPlayback,
            rememberBrightness = prefs[Keys.rememberBrightness] ?: defaults.rememberBrightness,
            seekPreviewEnabled = prefs[Keys.seekPreview] ?: defaults.seekPreviewEnabled,
            subtitleAutoDiscovery = prefs[Keys.subtitleAutoDiscovery]
                ?: defaults.subtitleAutoDiscovery,
            autoFfmpegCodecs = prefs[Keys.autoFfmpegCodecs] ?: defaults.autoFfmpegCodecs,
            gridColumns = prefs[Keys.gridColumns] ?: defaults.gridColumns,
            sortOrder = prefs[Keys.sortOrder]
                ?.let { name -> SortOrder.entries.firstOrNull { it.name == name } }
                ?: defaults.sortOrder,
            groupByFolder = prefs[Keys.groupByFolder] ?: defaults.groupByFolder,
            logLevel = prefs[Keys.logLevel]
                ?.let { LogLevel.fromName(it) }
                ?: defaults.logLevel,
            logExportFormat = prefs[Keys.logExportFormat]
                ?.let { name -> LogExportFormat.entries.firstOrNull { it.name == name } }
                ?: defaults.logExportFormat,
            includeDiagnosticsInExport = prefs[Keys.diagnosticsInExport]
                ?: defaults.includeDiagnosticsInExport,
            deviceName = prefs[Keys.deviceName] ?: defaults.deviceName,
        )
    }

    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.themeMode] = value.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.dynamicColor] = value }
    suspend fun setDecoderPreference(value: DecoderPreference) =
        edit { it[Keys.decoder] = value.name }

    suspend fun setGestures(value: Boolean) = edit { it[Keys.gestures] = value }
    suspend fun setControlsOnTop(value: Boolean) = edit { it[Keys.controlsOnTop] = value }
    suspend fun setSeekStep(value: Long) = edit { it[Keys.seekStep] = value }
    suspend fun setDefaultSpeed(value: Float) = edit { it[Keys.speed] = value }
    suspend fun setResumePlayback(value: Boolean) = edit { it[Keys.resumePlayback] = value }
    suspend fun setKeepScreenOn(value: Boolean) = edit { it[Keys.keepScreenOn] = value }
    suspend fun setAutoRotate(value: Boolean) = edit { it[Keys.autoRotate] = value }
    suspend fun setAutoPlayNext(value: Boolean) = edit { it[Keys.autoPlayNext] = value }
    suspend fun setBackgroundPlayback(value: Boolean) = edit { it[Keys.backgroundPlayback] = value }
    suspend fun setRememberBrightness(value: Boolean) = edit { it[Keys.rememberBrightness] = value }

    suspend fun setSeekPreview(value: Boolean) = edit { it[Keys.seekPreview] = value }
    suspend fun setSubtitleAutoDiscovery(value: Boolean) =
        edit { it[Keys.subtitleAutoDiscovery] = value }

    /** Remembers a codec the viewer fixed by hand, so the next file needs no tap. */
    suspend fun rememberFfmpegCodec(codec: String) = edit { prefs ->
        if (codec.isNotBlank()) {
            prefs[Keys.autoFfmpegCodecs] = prefs[Keys.autoFfmpegCodecs].orEmpty() + codec
        }
    }

    suspend fun forgetFfmpegCodec(codec: String) = edit { prefs ->
        prefs[Keys.autoFfmpegCodecs] = prefs[Keys.autoFfmpegCodecs].orEmpty() - codec
    }
    suspend fun setGridColumns(value: Int) = edit { it[Keys.gridColumns] = value }
    suspend fun setSortOrder(value: SortOrder) = edit { it[Keys.sortOrder] = value.name }
    suspend fun setGroupByFolder(value: Boolean) = edit { it[Keys.groupByFolder] = value }
    suspend fun setLogLevel(value: LogLevel) = edit { it[Keys.logLevel] = value.name }
    suspend fun setLogExportFormat(value: LogExportFormat) =
        edit { it[Keys.logExportFormat] = value.name }

    suspend fun setIncludeDiagnostics(value: Boolean) = edit { it[Keys.diagnosticsInExport] = value }

    suspend fun setDeviceName(value: String) = edit { it[Keys.deviceName] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
