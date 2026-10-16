package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which subtitle file belongs to which video. They are the
 * heart of subtitle auto-discovery, so they are pinned down here rather than
 * discovered on a phone: a wrong match would load the wrong translation.
 */
class SubtitleMatchingTest {

    @Test
    fun `a file named exactly like the video is the best match`() {
        assertEquals(0, SubtitleMatching.score("Movie.mp4", "Movie.srt", "th"))
        assertEquals(0, SubtitleMatching.score("Movie.mp4", "MOVIE.SRT", "th"))
    }

    @Test
    fun `the language of the device beats another language`() {
        assertEquals(1, SubtitleMatching.score("Movie.mp4", "Movie.th.srt", "th"))
        assertEquals(2, SubtitleMatching.score("Movie.mp4", "Movie.en.srt", "th"))
    }

    @Test
    fun `a regional tag still counts as its language`() {
        assertEquals(1, SubtitleMatching.score("Movie.mp4", "Movie.zh-Hans.srt", "zh"))
        assertEquals(2, SubtitleMatching.score("Movie.mp4", "Movie.en-GB.srt", "th"))
    }

    @Test
    fun `files that are not subtitles of this video never match`() {
        assertNull("another video", SubtitleMatching.score("Movie.mp4", "Movie2.srt", "en"))
        assertNull("a different title", SubtitleMatching.score("Movie.mp4", "Other.srt", "en"))
        assertNull("not a subtitle at all", SubtitleMatching.score("Movie.mp4", "Movie.txt", "en"))
        assertNull("a video", SubtitleMatching.score("Movie.mp4", "Movie.mp4", "en"))
        assertNull("a resolution, not a language", SubtitleMatching.score("Movie.mp4", "Movie.1080p.srt", "en"))
        assertNull("a year, not a language", SubtitleMatching.score("Movie.mp4", "Movie.2021.srt", "en"))
        assertNull("no base name to compare", SubtitleMatching.score(".mp4", ".srt", "en"))
    }

    @Test
    fun `only the language tag is read as a language`() {
        assertNull(SubtitleMatching.languageTag("Movie.mp4", "Movie.srt"))
        assertEquals("th", SubtitleMatching.languageTag("Movie.mp4", "Movie.th.srt"))
        assertEquals("zh-hans", SubtitleMatching.languageTag("Movie.mp4", "Movie.zh-Hans.srt"))
        assertNull(SubtitleMatching.languageTag("Movie.mp4", "Other.th.srt"))
    }

    @Test
    fun `ranking puts the exact name first, then the device language`() {
        val ranked = SubtitleMatching.rank(
            videoDisplayName = "Movie.mp4",
            candidates = listOf("Movie.en.srt", "Movie.zz.srt", "Movie.srt", "Movie.th.srt", "Other.th.srt"),
            preferredLanguage = "th",
        )

        assertEquals(listOf("Movie.srt", "Movie.th.srt", "Movie.en.srt", "Movie.zz.srt"), ranked)
    }

    @Test
    fun `equal candidates keep a stable order`() {
        val ranked = SubtitleMatching.rank(
            videoDisplayName = "Movie.mp4",
            candidates = listOf("Movie.ab-th.srt", "Movie.aa-th.srt", "Movie.ac-th.srt"),
            preferredLanguage = "th",
        )

        assertEquals(listOf("Movie.aa-th.srt", "Movie.ab-th.srt", "Movie.ac-th.srt"), ranked)
    }

    @Test
    fun `thai file names are matched like any other`() {
        assertEquals(0, SubtitleMatching.score("หนังบ้าน.mp4", "หนังบ้าน.srt", "th"))
        assertEquals(1, SubtitleMatching.score("หนังบ้าน.mp4", "หนังบ้าน.th.srt", "th"))
    }

    @Test
    fun `extensions decide what counts as a subtitle`() {
        assertTrue(SubtitleMatching.isSubtitle("a.srt"))
        assertTrue(SubtitleMatching.isSubtitle("a.VTT"))
        assertTrue(SubtitleMatching.isSubtitle("a.ass"))
        assertTrue(SubtitleMatching.isSubtitle("a.ssa"))
        assertFalse(SubtitleMatching.isSubtitle("a.mp4"))
        assertFalse(SubtitleMatching.isSubtitle("a"))
        assertFalse(SubtitleMatching.isSubtitle("a.srt.bak"))
    }

    @Test
    fun `a track label keeps the language in the name`() {
        assertEquals("Movie.th", SubtitleMatching.label("Movie.th.srt"))
        assertEquals("Movie", SubtitleMatching.label("Movie.srt"))
    }
}
