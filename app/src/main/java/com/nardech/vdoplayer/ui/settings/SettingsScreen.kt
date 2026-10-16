package com.nardech.vdoplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.BuildConfig
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.data.SortOrder
import com.nardech.vdoplayer.data.ThemeMode
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogExportFormat
import com.nardech.vdoplayer.logging.LogLevel
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.ui.decoderPreferenceLabel
import com.nardech.vdoplayer.ui.sortOrderLabel
import com.nardech.vdoplayer.ui.themeModeLabel
import com.nardech.vdoplayer.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    val repository = container.settings

    val themeLabels = ThemeMode.entries.map { themeModeLabel(it) }
    val sortLabels = SortOrder.entries.map { sortOrderLabel(it) }
    val decoderLabels = DecoderPreference.entries.map { decoderPreferenceLabel(it) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsCard(stringResource(R.string.settings_appearance)) {
                ChipRow(
                    title = stringResource(R.string.settings_theme),
                    options = themeLabels,
                    selectedIndex = ThemeMode.entries.indexOf(settings.themeMode),
                    onPick = { scope.launch { repository.setThemeMode(ThemeMode.entries[it]) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle = stringResource(R.string.settings_dynamic_color_sub),
                    checked = settings.dynamicColor,
                    onChange = { scope.launch { repository.setDynamicColor(it) } },
                )
                ChipRow(
                    title = stringResource(R.string.settings_columns),
                    options = listOf("1", "2", "3", "4"),
                    selectedIndex = (settings.gridColumns - 1).coerceIn(0, 3),
                    onPick = { scope.launch { repository.setGridColumns(it + 1) } },
                )
                ChipRow(
                    title = stringResource(R.string.settings_default_sort),
                    options = sortLabels,
                    selectedIndex = SortOrder.entries.indexOf(settings.sortOrder),
                    onPick = { scope.launch { repository.setSortOrder(SortOrder.entries[it]) } },
                )
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_playback)) {
                Text(
                    text = stringResource(R.string.settings_decoder),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_decoder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                ChipRow(
                    title = "",
                    options = decoderLabels,
                    selectedIndex = DecoderPreference.entries.indexOf(settings.decoderPreference),
                    onPick = {
                        val picked = DecoderPreference.entries[it]
                        LogBus.i(LogTag.PLAYER, "Decoder preference changed to ${picked.name}")
                        scope.launch { repository.setDecoderPreference(picked) }
                    },
                )
                SliderRow(
                    title = stringResource(R.string.settings_default_speed),
                    value = settings.defaultSpeed,
                    valueRange = 0.5f..2f,
                    steps = 5,
                    label = Formatters.speed(settings.defaultSpeed),
                    onChange = { scope.launch { repository.setDefaultSpeed(it) } },
                )
                ChipRow(
                    title = stringResource(R.string.settings_seek_step),
                    options = listOf("5s", "10s", "15s", "30s"),
                    selectedIndex = when (settings.seekStepMs) {
                        5_000L -> 0
                        10_000L -> 1
                        15_000L -> 2
                        else -> 3
                    },
                    onPick = {
                        val step = listOf(5_000L, 10_000L, 15_000L, 30_000L)[it]
                        scope.launch { repository.setSeekStep(step) }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_resume),
                    subtitle = stringResource(R.string.settings_resume_sub),
                    checked = settings.resumePlayback,
                    onChange = { scope.launch { repository.setResumePlayback(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_gestures),
                    subtitle = stringResource(R.string.settings_gestures_sub),
                    checked = settings.gesturesEnabled,
                    onChange = { scope.launch { repository.setGestures(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_keep_screen_on),
                    checked = settings.keepScreenOn,
                    onChange = { scope.launch { repository.setKeepScreenOn(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_rotate),
                    checked = settings.autoRotate,
                    onChange = { scope.launch { repository.setAutoRotate(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_remember_brightness),
                    subtitle = stringResource(R.string.settings_remember_brightness_sub),
                    checked = settings.rememberBrightness,
                    onChange = { scope.launch { repository.setRememberBrightness(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_seek_preview),
                    subtitle = stringResource(R.string.settings_seek_preview_sub),
                    checked = settings.seekPreviewEnabled,
                    onChange = { scope.launch { repository.setSeekPreview(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_autoplay_next),
                    checked = settings.autoPlayNext,
                    onChange = { scope.launch { repository.setAutoPlayNext(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_background),
                    subtitle = stringResource(R.string.settings_background_sub),
                    checked = settings.backgroundPlayback,
                    onChange = { scope.launch { repository.setBackgroundPlayback(it) } },
                )
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_diagnostics)) {
                ChipRow(
                    title = stringResource(R.string.settings_log_level),
                    options = LogLevel.entries.map { it.name },
                    selectedIndex = LogLevel.entries.indexOf(settings.logLevel),
                    onPick = {
                        val level = LogLevel.entries[it]
                        LogBus.fileLevel = level
                        LogBus.i(LogTag.APP, "File log level changed to ${level.name}")
                        scope.launch { repository.setLogLevel(level) }
                    },
                )
                ChipRow(
                    title = stringResource(R.string.settings_export_format),
                    options = LogExportFormat.entries.map { it.name },
                    selectedIndex = LogExportFormat.entries.indexOf(settings.logExportFormat),
                    onPick = {
                        scope.launch {
                            repository.setLogExportFormat(LogExportFormat.entries[it])
                        }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_include_diagnostics),
                    subtitle = stringResource(R.string.settings_include_diagnostics_sub),
                    checked = settings.includeDiagnosticsInExport,
                    onChange = { scope.launch { repository.setIncludeDiagnostics(it) } },
                )
                Text(
                    text = stringResource(R.string.settings_logs_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_license_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChipRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        if (title.isNotBlank()) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEachIndexed { index, label ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onPick(index) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}
