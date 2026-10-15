package com.nardech.vdoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.data.SortOrder
import com.nardech.vdoplayer.data.ThemeMode
import com.nardech.vdoplayer.data.VideoItem

/**
 * Enum to string-resource mapping lives in the UI layer on purpose: the data
 * layer stays free of Android resources, and translations stay in `values-th`
 * instead of being baked into enum constants.
 */
@Composable
fun sortOrderLabel(order: SortOrder): String = stringResource(
    when (order) {
        SortOrder.DATE_NEWEST -> R.string.sort_date_newest
        SortOrder.DATE_OLDEST -> R.string.sort_date_oldest
        SortOrder.NAME_ASC -> R.string.sort_name_asc
        SortOrder.NAME_DESC -> R.string.sort_name_desc
        SortOrder.DURATION_DESC -> R.string.sort_duration_desc
        SortOrder.DURATION_ASC -> R.string.sort_duration_asc
        SortOrder.SIZE_DESC -> R.string.sort_size_desc
    },
)

@Composable
fun decoderPreferenceLabel(preference: DecoderPreference): String = stringResource(
    when (preference) {
        DecoderPreference.PLATFORM_FIRST -> R.string.decoder_platform_first
        DecoderPreference.FFMPEG_FIRST -> R.string.decoder_ffmpeg_first
        DecoderPreference.PLATFORM_ONLY -> R.string.decoder_platform_only
    },
)

@Composable
fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/**
 * Folder names come from MediaStore and are shown as they are; only the synthetic
 * key used for files opened from another app needs a translation.
 */
@Composable
fun folderLabel(folder: String): String =
    if (folder == VideoItem.EXTERNAL_FOLDER) stringResource(R.string.folder_external) else folder
