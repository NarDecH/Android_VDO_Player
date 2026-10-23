package com.nardech.vdoplayer.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * The app's own Media3 session commands, on top of the standard player commands.
 *
 * Decoder selection belongs to whoever owns the player — [PlaybackService] — because
 * the decoder manager has to be driven on the player's application thread, and only
 * the service itself is allowed to touch it. The UI therefore asks for the change
 * over the session instead of reaching for the player.
 */
object PlaybackCommands {

    const val ACTION_SET_DECODER = "com.nardech.vdoplayer.SET_DECODER_PREFERENCE"

    /** Name of a `DecoderPreference` in the command arguments. */
    const val KEY_DECODER = "decoder_preference"

    val setDecoderPreference = SessionCommand(ACTION_SET_DECODER, Bundle.EMPTY)

    /**
     * Switches only the audio half of the decoder, for the no-sound rescue.
     *
     * Kept apart from [setDecoderPreference] on purpose: a file whose audio nothing
     * decodes needs FFmpeg for its sound, not a software video decoder, and the
     * viewer never asked for the picture to lose hardware decoding. This command also
     * leaves the saved priority alone, so one AC-3 file cannot change what the next
     * launch starts with.
     */
    const val ACTION_SET_AUDIO_DECODER = "com.nardech.vdoplayer.SET_AUDIO_DECODER"

    /** Name of a `DecoderMode` in the command arguments. */
    const val KEY_AUDIO_DECODER = "audio_decoder_mode"

    val setAudioDecoder = SessionCommand(ACTION_SET_AUDIO_DECODER, Bundle.EMPTY)
}
