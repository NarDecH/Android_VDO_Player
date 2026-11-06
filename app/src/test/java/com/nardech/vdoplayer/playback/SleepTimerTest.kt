package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sleep timer's whole promise is that it fires exactly once, when it says it will:
 * a run that stops playback early, that never stops it, or that survives the video it
 * was set on, is worse than not offering the button at all.
 */
class SleepTimerTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `a timed stop fires at its deadline and not before`() {
        val timer = SleepTimerPolicy.after(30, start)

        assertFalse(SleepTimerPolicy.isDue(timer, start))
        assertFalse(SleepTimerPolicy.isDue(timer, start + 29 * minute))
        assertTrue(SleepTimerPolicy.isDue(timer, start + 30 * minute))
        assertTrue(SleepTimerPolicy.isDue(timer, start + 31 * minute))
    }

    @Test
    fun `the time left never goes below zero`() {
        val timer = SleepTimerPolicy.after(10, start)

        assertEquals(10 * minute, SleepTimerPolicy.remainingMs(timer, start))
        assertEquals(0L, SleepTimerPolicy.remainingMs(timer, start + 11 * minute))
    }

    @Test
    fun `a timer that is off never fires`() {
        val off = SleepTimerPolicy.OFF

        assertFalse(off.isActive)
        assertFalse(SleepTimerPolicy.shouldStop(off, start + 24 * 60 * minute, videoEnded = true))
        assertNull(SleepTimerPolicy.remainingMs(off, start))
    }

    @Test
    fun `end of video waits for the video to end`() {
        val timer = SleepTimerPolicy.untilEndOfVideo()

        assertTrue(timer.isActive)
        assertFalse(SleepTimerPolicy.shouldStop(timer, start + 5 * minute, videoEnded = false))
        assertTrue(SleepTimerPolicy.shouldStop(timer, start + 5 * minute, videoEnded = true))
        assertNull(SleepTimerPolicy.remainingMs(timer, start))
    }

    @Test
    fun `a timed stop does not need the video to end`() {
        val timer = SleepTimerPolicy.after(1, start)

        assertTrue(SleepTimerPolicy.shouldStop(timer, start + minute, videoEnded = false))
    }

    @Test
    fun `a duration of zero or less still waits a minute instead of firing at once`() {
        val timer = SleepTimerPolicy.after(0, start)

        assertEquals(minute, timer.deadlineMs?.minus(start))
        assertFalse(SleepTimerPolicy.isDue(timer, start))
    }

    @Test
    fun `the dialog offers the documented durations`() {
        assertEquals(listOf(10, 20, 30, 60), SleepTimerPolicy.MINUTES)
        assertEquals(0, SleepTimerPolicy.END_OF_VIDEO)
    }
}
