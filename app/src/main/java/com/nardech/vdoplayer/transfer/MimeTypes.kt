package com.nardech.vdoplayer.transfer

/**
 * Extension based MIME lookup. Receiving decides the MediaStore collection from it:
 * videos land in `Movies/VDO Player` so they appear in the app's own library, other
 * files land in `Download/VDO Player`.
 */
object MimeTypes {

    private val BY_EXTENSION = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "ogg" to "audio/ogg",
        "wav" to "audio/wav",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt",
        "txt" to "text/plain",
        "zip" to "application/zip",
        "pdf" to "application/pdf",
    )

    fun of(fileName: String): String =
        BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun isVideo(fileName: String): Boolean = of(fileName).startsWith("video/")
}
