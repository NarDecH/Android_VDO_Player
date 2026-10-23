package com.nardech.vdoplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.nardech.vdoplayer.data.DecoderPreference
import java.util.Locale

/**
 * The audio of the current item when nothing is decoding it.
 *
 * A file whose audio codec the device cannot decode does not fail: the picture
 * keeps running, nothing throws, and the audio track is simply never selected. The
 * viewer gets a silent video with no explanation, so this state is a fact the UI has
 * to state itself.
 */
data class SilentAudio(
    /** Short codec name for the message, e.g. `AC3`; empty when the file is shy. */
    val codec: String,
)

/**
 * Reads "this video is playing without sound" off the player's own [Tracks].
 *
 * Everything here is a plain function over Media3 objects, so the rules are tested
 * on the JVM rather than guessed at on a phone: a hint that fires while the player
 * is still preparing, or on a silent film that never had audio, would be worse than
 * no hint at all.
 */
object AudioSilence {

    /**
     * Returns the silent-audio state of [tracks], or `null` when audio is fine.
     *
     * "Fine" covers both harmless cases: the file has no audio track at all, or the
     * player selected one. What is left — audio groups exist and none of them is
     * selected — is the failure this exists for. Candidates that no renderer could
     * take are reported here too, because selection never happens for them.
     */
    fun detect(tracks: Tracks): SilentAudio? {
        val audioGroups = tracks.groups.filter { group -> group.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return null
        val anySelected = audioGroups.any { group ->
            (0 until group.length).any { index -> group.isTrackSelected(index) }
        }
        if (anySelected) return null
        return SilentAudio(codec = codecName(audioGroups.first().getTrackFormat(0)))
    }

    /**
     * Whether offering the FFmpeg decoder is worth the space on screen.
     *
     * The suggestion is pointless when FFmpeg is already the active choice, would
     * flicker while the player is still preparing, and must not come back after the
     * viewer dismissed it for this video.
     */
    fun shouldOfferFfmpeg(
        silent: SilentAudio?,
        preference: DecoderPreference,
        playbackState: Int,
        dismissed: Boolean,
    ): Boolean = silent != null &&
        !dismissed &&
        preference != DecoderPreference.FFMPEG_FIRST &&
        playbackState == Player.STATE_READY

    /**
     * The remembered codec that should switch to FFmpeg by itself, or `null` when the
     * viewer still has to be asked.
     *
     * A codec only reaches the remembered set by being rescued by hand once, so this
     * never changes what the app does on its own initiative. When FFmpeg already is the
     * choice there is nothing left to offer, and a file that does not name its audio
     * (empty codec) is never remembered — an unnamed track must not teach the app to
     * switch everything.
     */
    fun autoRescueCodec(
        silent: SilentAudio?,
        preference: DecoderPreference,
        remembered: Set<String>,
    ): String? = autoRescueCodec(silent?.codec, preference, remembered)

    /**
     * The same rule, for a codec that is known before the file is playing.
     *
     * `AudioCodecProbe` reads a file's audio codec on its own, which is what lets the
     * switch happen before the first item is prepared instead of after the picture has
     * already gone silent. Both paths have to decide with one function and one spelling
     * of the codec name, or a remembered codec would be recognised by the live rescue
     * and missed by the early one.
     */
    fun autoRescueCodec(
        codec: String?,
        preference: DecoderPreference,
        remembered: Set<String>,
    ): String? {
        if (preference == DecoderPreference.FFMPEG_FIRST) return null
        return codec?.takeIf { it.isNotEmpty() && it in remembered }
    }

    /**
     * A name a person can read: the track's own label when it has one, otherwise the
     * MIME subtype (`audio/ac3` → `AC3`). The raw MIME type belongs in the log, not
     * in a one-line message.
     */
    private fun codecName(format: Format): String {
        format.label?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return codecKey(format.sampleMimeType).orEmpty()
    }

    /**
     * The name a codec is remembered under, from its MIME type alone: `audio/ac3` →
     * `AC3`.
     *
     * Shared with [AudioCodecProbe] so the codec a file announces before playback and
     * the codec the player reports while it runs are spelled the same way. A track that
     * carries a label is remembered under that label, which no container probe can
     * predict — those files keep the live rescue, because a mismatched name only costs
     * the waiting, never the sound.
     */
    fun codecKey(mimeType: String?): String? = mimeType
        ?.substringAfter('/', "")
        ?.trim()
        ?.uppercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }
}
