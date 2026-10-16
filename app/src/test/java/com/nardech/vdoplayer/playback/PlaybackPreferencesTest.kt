package com.nardech.vdoplayer.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repeat button is a single tap that walks a fixed cycle, and the value is restored
 * at startup, so both the cycle and the "is this a mode we know" check matter.
 */
class PlaybackPreferencesTest {

    @Test
    fun `repeat cycles off, queue, one video and back`() {
        var mode = Player.REPEAT_MODE_OFF
        mode = PlaybackPreferences.nextRepeatMode(mode)
        assertEquals(Player.REPEAT_MODE_ALL, mode)
        mode = PlaybackPreferences.nextRepeatMode(mode)
        assertEquals(Player.REPEAT_MODE_ONE, mode)
        mode = PlaybackPreferences.nextRepeatMode(mode)
        assertEquals(Player.REPEAT_MODE_OFF, mode)
    }

    @Test
    fun `an unknown stored value falls back to off`() {
        assertEquals(Player.REPEAT_MODE_OFF, PlaybackPreferences.nextRepeatMode(99))
        assertFalse(PlaybackPreferences.isRepeatMode(99))
        assertFalse(PlaybackPreferences.isRepeatMode(-1))
    }

    @Test
    fun `every mode in the cycle is accepted`() {
        PlaybackPreferences.REPEAT_CYCLE.forEach { mode ->
            assertTrue("$mode should be a valid repeat mode", PlaybackPreferences.isRepeatMode(mode))
        }
        assertEquals(3, PlaybackPreferences.REPEAT_CYCLE.size)
    }
}
