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
}
