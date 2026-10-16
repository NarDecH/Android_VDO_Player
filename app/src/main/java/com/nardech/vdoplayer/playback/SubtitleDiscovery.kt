package com.nardech.vdoplayer.playback

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.util.IntentHelpers
import java.util.Locale

/**
 * Finds the subtitle files that sit next to a video and turns them into Media3
 * subtitle tracks, so a video whose `.srt` is already in the same folder starts
 * with subtitles instead of asking the user to pick a file.
 *
 * Only MediaStore is consulted, which is what the device scope allows: the app
 * can see its own files anywhere, and other apps' non-media files only on
 * Android 12 and older (from Android 13 the media permissions cover video,
 * audio and images, not `.srt`). Everything else — no folder, unreadable file,
 * blocked query — is logged and simply means "no subtitles found", never a
 * failed playback.
 */
class SubtitleResolver(private val context: Context) {

    /** Returns the subtitle tracks for [item], best match first, or an empty list. */
    fun discover(
        item: VideoItem,
        preferredLanguage: String? = Locale.getDefault().language,
    ): List<MediaItem.SubtitleConfiguration> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            LogBus.d(
                LogTag.SUBTITLE,
                "Subtitle discovery needs Android 10 for a folder to look in; " +
                    "'${item.displayName}' was not searched",
            )
            return emptyList()
        }
        val folder = item.relativePath?.takeIf { it.isNotBlank() } ?: return emptyList()

        val started = System.currentTimeMillis()
        val found = queryFolder(folder)
        val ranked = SubtitleMatching.rank(
            videoDisplayName = item.displayName,
            candidates = found.map { it.second },
            preferredLanguage = preferredLanguage,
        )

        val configurations = ranked.mapNotNull { name ->
            val match = found.firstOrNull { it.second == name } ?: return@mapNotNull null
            val uri = match.first
            if (!isReadable(uri)) {
                LogBus.w(
                    LogTag.SUBTITLE,
                    "'$name' matches '${item.displayName}' but cannot be read, ignoring it",
                )
                return@mapNotNull null
            }
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(IntentHelpers.subtitleMimeType(name))
                .setLabel(SubtitleMatching.label(name))
                .apply {
                    SubtitleMatching.languageTag(item.displayName, name)?.let { setLanguage(it) }
                }
                // Only the best match is forced on: the others stay available for
                // the track picker without overriding the device language.
                .setSelectionFlags(
                    if (name == ranked.first()) C.SELECTION_FLAG_DEFAULT else 0,
                )
                .build()
        }

        LogBus.i(
            LogTag.SUBTITLE,
            "Subtitle discovery for '${item.displayName}' in '$folder': " +
                "${found.size} files, ${configurations.size} matched in " +
                "${System.currentTimeMillis() - started} ms" +
                if (configurations.isEmpty()) "" else " (${configurations.joinToString { it.label.toString() }})",
        )
        return configurations
    }

    /**
     * Every file MediaStore knows in [relativePath]. The folder is queried rather
     * than the file name, because SQL matching of mixed-case extensions and
     * language tags is more reliable in Kotlin than in a selection string.
     */
    private fun queryFolder(relativePath: String): List<Pair<Uri, String>> {
        // "external" rather than MediaStore.VOLUME_EXTERNAL: the constant only exists
        // from Android 10, while the volume name works on every release.
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?"
        val files = mutableListOf<Pair<Uri, String>>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf(relativePath),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                while (cursor.moveToNext() && files.size < MAX_CANDIDATES) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!SubtitleMatching.isSubtitle(name)) continue
                    files += ContentUris.withAppendedId(collection, cursor.getLong(idColumn)) to name
                }
            }
        }.onFailure {
            LogBus.w(LogTag.SUBTITLE, "Could not list '$relativePath' for subtitles", it)
        }
        return files
    }

    /** A subtitle the app cannot open would break the text track later, so check first. */
    private fun isReadable(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { } != null
    }.getOrDefault(false)

    private companion object {
        const val MAX_CANDIDATES = 40
    }
}
