package com.nardech.vdoplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag

/**
 * What the details dialog can say about a file before it is played: the codecs it
 * carries, how much data it spends per second, how fast the picture runs and how many
 * tracks of each kind it holds.
 */
data class MediaInfo(
    val videoCodec: String?,
    val audioCodecs: List<String>,
    val videoTracks: Int,
    val audioTracks: Int,
    val subtitleTracks: Int,
    val bitrateBps: Long,
    val frameRate: Float,
    val audioChannels: Int,
    val sampleRate: Int,
)

/**
 * Reads the header of a file and describes it.
 *
 * The same read the audio probe already makes — Media3's own extractors, so the answer
 * matches what playback will see — but keeping the whole formats instead of only their
 * MIME types. Call [read] off the main thread: it opens the container.
 *
 * Anything unreadable comes back as `null`, and the dialog then shows "unknown" rather
 * than a guess: a wrong codec on a bug report is worse than no line at all.
 */
object MediaInfoProbe {

    fun read(context: Context, uri: Uri): MediaInfo? = runCatching {
        val formats = Media3Tracks.readFormats(context, uri)
        if (formats.isEmpty()) {
            LogBus.d(LogTag.MEDIA, "Media info: no track readable in '$uri'")
            return null
        }
        describe(formats)
    }.onFailure { error ->
        LogBus.w(LogTag.MEDIA, "Could not read the media info of '$uri'", error)
    }.getOrNull()

    /** The rule [read] applies, split out so it can be checked without a file. */
    internal fun describe(formats: List<Format>): MediaInfo {
        val video = formats.filter { trackTypeOf(it) == C.TRACK_TYPE_VIDEO }
        val audio = formats.filter { trackTypeOf(it) == C.TRACK_TYPE_AUDIO }
        val subtitles = formats.count { trackTypeOf(it) == C.TRACK_TYPE_TEXT }
        return MediaInfo(
            videoCodec = video.firstOrNull()?.let { videoCodecLabel(it) },
            audioCodecs = audio.map { format ->
                AudioSilence.codecKey(format.sampleMimeType)
                    ?: format.sampleMimeType
                    ?: "?"
            },
            videoTracks = video.size,
            audioTracks = audio.size,
            subtitleTracks = subtitles,
            bitrateBps = formats.map { it.bitrate.toLong() }.filter { it > 0L }.sum(),
            frameRate = video.map { it.frameRate }.filter { it > 0f }.maxOrNull() ?: 0f,
            audioChannels = audio.maxOfOrNull { it.channelCount } ?: 0,
            sampleRate = audio.maxOfOrNull { it.sampleRate } ?: 0,
        )
    }

    /**
     * The spoken name of a video codec: the RFC 6381 string when the container has one
     * (`avc1.64001F` says more than `video/avc`), otherwise the MIME type itself.
     */
    internal fun videoCodecLabel(format: Format): String =
        format.codecs?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType
            ?: "?"

    private fun trackTypeOf(format: Format): Int =
        format.sampleMimeType?.let { mime -> MimeTypes.getTrackType(mime) }
            ?: C.TRACK_TYPE_UNKNOWN
}
