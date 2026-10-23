package com.nardech.vdoplayer.playback

import android.media.MediaCodecList
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Whether anything on this device can decode a file's audio track. */
enum class AudioOutlook {
    /**
     * Nothing is known: the file has no audio track, its header could not be read, or a
     * decoder list could not be asked. Silence here is not a finding, so it says nothing.
     */
    UNKNOWN,

    /** The platform or the FFmpeg extension names a decoder for the codec. */
    PLAYABLE,

    /** No decoder anywhere names the codec, so the picture will run without sound. */
    UNDECODABLE,
}

/** A file whose audio no decoder on this device can read. */
data class UnsupportedAudio(
    /** Short codec name for the message, e.g. `AC3`; empty when the file is shy. */
    val codec: String,
)

/**
 * Answers one question about a file before the player opens it: is there a decoder on
 * this device for its audio at all?
 *
 * The no-sound card reports silence after the fact — the viewer has already watched a
 * stretch of picture without sound by then. A header read names the codec before the
 * first frame, and that is early enough to say it up front: this file has no sound
 * because nothing here can decode it, not because something went wrong.
 *
 * Two lists decide it, because the app ships both kinds of decoder. The platform's own
 * audio decoders come from [MediaCodecList]; everything else comes from NextLib's
 * FFmpeg build, which answers for a MIME type directly and is what every decoder
 * preference registers as the fallback. A codec in neither list is reported; a list
 * that cannot be read produces [AudioOutlook.UNKNOWN] rather than a guess, because a
 * wrong "no sound" warning on a perfectly playable file is worse than saying nothing.
 * A file with several audio tracks is only reported when none of them can be decoded:
 * one playable track is sound, and the tracks are all in one header read anyway.
 *
 * Both answers are asked once per process and remembered: the codec list cannot change
 * while the app runs, and the decoder lookup is the same work on every play.
 */
object AudioPreflight {

    private val platformDecoders: Set<String>? by lazy { readPlatformDecoders() }

    /** MIME type to what FFmpeg said about it; a codec is met many times per session. */
    private val ffmpegAnswers = ConcurrentHashMap<String, Boolean>()

    /**
     * The rule, with Android left out of it, so it is tested in every build rather than
     * only on a device. [mimes] is every audio track the file names, because one track
     * that can be decoded is sound: a file is only undecodable when *none* of them can be.
     *
     * A missing decoder list ([platformDecoders] `null`) or an unanswerable FFmpeg
     * ([ffmpegSupports] returning `null`) is not evidence of anything and yields
     * [AudioOutlook.UNKNOWN], as does any entry that is not an audio MIME type — a name
     * with no slash in it is a codec from an older cache, not something to look up.
     */
    fun outlook(
        mimes: List<String>,
        platformDecoders: Set<String>?,
        ffmpegSupports: (String) -> Boolean?,
    ): AudioOutlook {
        // No audio track is a silent film, not an unsupported one.
        if (mimes.isEmpty() || platformDecoders == null) return AudioOutlook.UNKNOWN
        var playable = false
        for (mime in mimes) {
            if (!mime.startsWith("audio/", ignoreCase = true)) return AudioOutlook.UNKNOWN
            if (platformDecoders.any { it.equals(mime, ignoreCase = true) }) {
                playable = true
                continue
            }
            when (ffmpegSupports(mime)) {
                true -> playable = true
                false -> Unit
                null -> return AudioOutlook.UNKNOWN
            }
        }
        return if (playable) AudioOutlook.PLAYABLE else AudioOutlook.UNDECODABLE
    }

    /** The same rule over this device's real decoders. Safe to call off the main thread. */
    fun outlook(mimes: List<String>): AudioOutlook = outlook(
        mimes = mimes,
        platformDecoders = platformDecoders,
        ffmpegSupports = ::ffmpegAnswer,
    )

    /**
     * Whether FFmpeg names a decoder for [mime], or `null` when it cannot be asked.
     *
     * A missing native library is a real "no": the extension renderers are registered by
     * this same library, so without it there is nothing extra to decode with.
     */
    private fun ffmpegAnswer(mime: String): Boolean? {
        ffmpegAnswers[mime]?.let { return it }
        val answer = runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime) }
            .onFailure {
                LogBus.w(LogTag.DECODER, "Could not ask FFmpeg about the '$mime' decoder", it)
            }
            .getOrNull()
        // Only a real answer is remembered, so a library that failed to load once is
        // asked again rather than turned into a permanent "cannot decode" for the codec.
        if (answer != null) ffmpegAnswers[mime] = answer
        return answer
    }

    /**
     * Every audio MIME type the platform has a decoder for, or `null` when the codec
     * list cannot be read — a device that will not answer must not be reported as one
     * that cannot decode.
     */
    private fun readPlatformDecoders(): Set<String>? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { info -> !info.isEncoder }
            .flatMap { info -> info.supportedTypes.asSequence() }
            .map { mime -> mime.lowercase(Locale.ROOT) }
            .toSet()
    }.onFailure {
        LogBus.w(LogTag.DECODER, "Could not read the platform's decoder list", it)
    }.getOrNull()
}
