package com.nardech.vdoplayer.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes

object IntentHelpers {

    /** Best-effort MIME type for a subtitle file picked from storage. */
    fun subtitleMimeType(displayName: String): String = when (displayName.substringAfterLast('.').lowercase()) {
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** Human readable name for a content URI, falling back to the last path segment. */
    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        return cursor.getString(index) ?: uri.lastPathSegment ?: "file"
                    }
                }
        }
        return uri.lastPathSegment ?: "file"
    }
}
