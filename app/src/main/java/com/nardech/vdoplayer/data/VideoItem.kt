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
            folder = "External",
            relativePath = null,
        )
    }
}

/** Sorting options for the library grid. */
enum class SortOrder(val label: String) {
    DATE_NEWEST("Newest first"),
    DATE_OLDEST("Oldest first"),
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    DURATION_DESC("Longest first"),
    DURATION_ASC("Shortest first"),
    SIZE_DESC("Largest first"),
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
