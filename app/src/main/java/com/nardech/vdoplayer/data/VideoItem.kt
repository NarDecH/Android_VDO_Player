package com.nardech.vdoplayer.data

import android.net.Uri

/** One playable video found on the device (or handed to us by another app). */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val width: Int,
    val height: Int,
    val mimeType: String?,
    val folder: String,
    val relativePath: String?,
) {
    val title: String
        get() = displayName.substringBeforeLast('.').ifBlank { displayName }

    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}x$height" else "?"

    val isPortrait: Boolean
        get() = height > width

    companion object {
        /**
         * Folder key for URIs that did not come from MediaStore, e.g. "open with"
         * intents. The UI translates this key through `folderLabel()`.
         */
        const val EXTERNAL_FOLDER = "External"

        /** Used for URIs that did not come from MediaStore, e.g. "open with" intents. */
        fun fromUri(uri: Uri, title: String? = null): VideoItem = VideoItem(
            id = uri.hashCode().toLong(),
            uri = uri,
            displayName = title ?: uri.lastPathSegment ?: "video",
            durationMs = 0L,
            sizeBytes = 0L,
            dateAddedSeconds = System.currentTimeMillis() / 1000,
            width = 0,
            height = 0,
            mimeType = null,
            folder = EXTERNAL_FOLDER,
            relativePath = null,
        )
    }
}

/**
 * Sorting options for the library grid. Display names live in string resources
 * (`sort_*`) and are resolved by `com.nardech.vdoplayer.ui.sortOrderLabel`.
 */
enum class SortOrder {
    DATE_NEWEST,
    DATE_OLDEST,
    NAME_ASC,
    NAME_DESC,
    DURATION_DESC,
    DURATION_ASC,
    SIZE_DESC,
    ;

    fun apply(items: List<VideoItem>): List<VideoItem> = when (this) {
        DATE_NEWEST -> items.sortedByDescending { it.dateAddedSeconds }
        DATE_OLDEST -> items.sortedBy { it.dateAddedSeconds }
        NAME_ASC -> items.sortedBy { it.title.lowercase() }
        NAME_DESC -> items.sortedByDescending { it.title.lowercase() }
        DURATION_DESC -> items.sortedByDescending { it.durationMs }
        DURATION_ASC -> items.sortedBy { it.durationMs }
        SIZE_DESC -> items.sortedByDescending { it.sizeBytes }
    }
}
