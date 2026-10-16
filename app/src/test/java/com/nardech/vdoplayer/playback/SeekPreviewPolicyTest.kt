package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding a frame costs a container seek plus a decode, so the rules that decide when a
 * frame is worth taking are worth pinning: a scrub inside the same bucket must reuse what
 * is on screen, and a preview from another part of the video must never be shown as if it
 * belonged to the current position.
 */
class SeekPreviewPolicyTest {

    @Test
    fun `positions round down to a bucket`() {
        assertEquals(0L, SeekPreviewPolicy.bucket(0L))
        assertEquals(0L, SeekPreviewPolicy.bucket(1_999L))
        assertEquals(2_000L, SeekPreviewPolicy.bucket(2_000L))
        assertEquals(2_000L, SeekPreviewPolicy.bucket(3_999L))
        assertEquals(4_000L, SeekPreviewPolicy.bucket(4_000L))
        assertEquals(120_000L, SeekPreviewPolicy.bucket(121_500L))
    }

    @Test
    fun `a negative position cannot produce a negative bucket`() {
        assertEquals(0L, SeekPreviewPolicy.bucket(-5L))
        assertEquals(0L, SeekPreviewPolicy.bucket(-4_000L))
    }

    @Test
    fun `an explicit bucket size is honoured, and zero keeps the position`() {
        assertEquals(0L, SeekPreviewPolicy.bucket(1_234L, bucketMs = 5_000L))
        assertEquals(5_000L, SeekPreviewPolicy.bucket(6_800L, bucketMs = 5_000L))
        assertEquals(1_234L, SeekPreviewPolicy.bucket(1_234L, bucketMs = 0L))
    }

    @Test
    fun `scrolling inside one bucket does not ask for another frame`() {
        assertTrue(SeekPreviewPolicy.needsNewFrame(currentPositionMs = null, targetMs = 0L))
        assertTrue(SeekPreviewPolicy.needsNewFrame(currentPositionMs = 1_000L, targetMs = 2_100L))
        assertFalse(SeekPreviewPolicy.needsNewFrame(currentPositionMs = 2_100L, targetMs = 3_900L))
    }

    @Test
    fun `a preview only describes its own bucket`() {
        assertFalse(SeekPreviewPolicy.stillApplies(previewPositionMs = null, targetMs = 0L))
        assertTrue(SeekPreviewPolicy.stillApplies(previewPositionMs = 2_000L, targetMs = 3_500L))
        assertFalse(SeekPreviewPolicy.stillApplies(previewPositionMs = 2_000L, targetMs = 4_500L))
    }

    @Test
    fun `the setting turns previews off`() {
        assertTrue(SeekPreviewPolicy.isEnabled(setting = true))
        assertFalse(SeekPreviewPolicy.isEnabled(setting = false))
    }
}
