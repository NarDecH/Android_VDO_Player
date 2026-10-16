package com.nardech.vdoplayer.playback

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player

/**
 * The playback choices that have to be readable before any coroutine can run: the
 * service applies them while it builds the player, so a repeat setting survives the
 * app being closed and reopened.
 *
 * The decoder preference lives in the same `SharedPreferences` file for the same
 * reason (see [PlaybackService]); it stays there instead of here because its value
 * is an enum from the data layer.
 */
object PlaybackPreferences {

    private const val KEY_REPEAT_MODE = "repeat_mode"

    /** Repeat modes in the order the UI cycles through them. */
    val REPEAT_CYCLE = listOf(
        Player.REPEAT_MODE_OFF,
        Player.REPEAT_MODE_ALL,
        Player.REPEAT_MODE_ONE,
    )

    /**
     * The next mode in the cycle: off → the whole queue → this video → off. Repeating
     * the queue is the more useful default after "off", which is why one-video repeat
     * comes last.
     */
    fun nextRepeatMode(current: Int): Int {
        val index = REPEAT_CYCLE.indexOf(current)
        return REPEAT_CYCLE[(index + 1) % REPEAT_CYCLE.size]
    }

    fun isRepeatMode(mode: Int): Boolean = mode in REPEAT_CYCLE

    fun repeatMode(context: Context): Int {
        val stored = context.getSharedPreferences(PlaybackService.PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        return if (isRepeatMode(stored)) stored else Player.REPEAT_MODE_OFF
    }

    fun setRepeatMode(context: Context, mode: Int) {
        context.getSharedPreferences(PlaybackService.PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_REPEAT_MODE, if (isRepeatMode(mode)) mode else Player.REPEAT_MODE_OFF)
        }
    }
}
