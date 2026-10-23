package com.nardech.vdoplayer.playback

import com.nardech.vdoplayer.data.DecoderPreference
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The probe exists to answer two questions before playback starts: whether a file's audio
 * codec is one this viewer already sent to FFmpeg, and whether anything on the device can
 * decode that audio at all. These tests pin the rules those decisions rest on — which
 * track of a container counts, that the name read from a container is spelled exactly
 * like the name the player reports later, that both questions read one remembered MIME
 * type, and that a file already read is not read a second time.
 *
 * Nothing here opens a container: the read itself is the one part that needs
 * a device ([AudioCodecProbeDeviceTest] covers it), while the cache rule is plain
 * Kotlin and is checked in every build.
 */
class AudioCodecProbeTest {

    /**
     * The probe remembers results for the whole process, so each test starts empty —
     * otherwise the previous test's file would be a hit for this one.
     */
    @Before
    fun forgetEarlierProbes() {
        AudioCodecProbe.forgetRemembered()
    }

    @Test
    fun `the first audio track decides`() {
        assertEquals(
            "video tracks come first in every container, so the first audio one has to be picked",
            "audio/ac3",
            AudioCodecProbe.firstAudioMime(listOf("video/avc", "audio/ac3", "audio/aac")),
        )
    }

    @Test
    fun `a file without audio has no codec`() {
        assertNull(
            "a video-only file must not switch the audio decoder",
            AudioCodecProbe.firstAudioMime(listOf("video/avc", null)),
        )
        assertNull(AudioCodecProbe.firstAudioMime(emptyList()))
        assertNull(
            "a file with no audio track must come out as no codec at all",
            AudioSilence.codecKey(AudioCodecProbe.firstAudioMime(listOf("video/avc"))),
        )
    }

    @Test
    fun `the early switch and the live rescue agree on the name`() {
        val remembered = setOf("AC3")
        val fromFile = AudioSilence.codecKey(
            AudioCodecProbe.firstAudioMime(listOf("video/avc", "audio/ac3")),
        )
        assertEquals(
            "a codec remembered from a silent track must be recognised from the file too",
            AudioSilence.autoRescueCodec(
                silent = SilentAudio(codec = "AC3"),
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = remembered,
            ),
            AudioSilence.autoRescueCodec(
                codec = fromFile,
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = remembered,
            ),
        )
        assertEquals("AC3", fromFile)
    }

    @Test
    fun `a file already read is not read a second time`() {
        val opens = AtomicInteger()

        assertEquals("AC3", probe(KEY, opens))
        assertEquals("AC3", probe(KEY, opens))

        assertEquals(
            "the second play of an unchanged file must be answered from memory",
            1,
            opens.get(),
        )
    }

    @Test
    fun `every audio track is remembered and the first one names the rescue`() {
        val opens = AtomicInteger()
        val mimes = AudioCodecProbe.resolveAudioMimes(KEY, CLIP) {
            opens.incrementAndGet()
            listOf("video/avc", "audio/ac3", "audio/aac")
        }

        assertEquals(
            "a second track can still be playable, so the file has to keep both answers",
            listOf("audio/ac3", "audio/aac"),
            mimes,
        )
        assertEquals(
            "and the whole list is what the next play reads, not just its first line",
            mimes,
            AudioCodecProbe.resolveAudioMimes(KEY, CLIP) {
                opens.incrementAndGet()
                listOf("audio/opus")
            },
        )
        assertEquals(
            "the rescue still names the first track, the one Media3 plays by default",
            "AC3",
            AudioCodecProbe.resolve(KEY, CLIP) {
                opens.incrementAndGet()
                listOf("audio/opus")
            },
        )
        assertEquals("and asking three times must not open the file once more", 1, opens.get())
    }

    @Test
    fun `a file whose bytes changed is read again`() {
        val opens = AtomicInteger()
        probe(KEY, opens)
        probe("$KEY|replaced", opens)

        assertEquals(
            "a re-downloaded file must not inherit the answer for the old one",
            2,
            opens.get(),
        )
    }

    @Test
    fun `a file without audio is not remembered`() {
        val opens = AtomicInteger()
        val videoOnly = { ->
            opens.incrementAndGet()
            listOf("video/avc")
        }

        assertNull(AudioCodecProbe.resolve(KEY, CLIP, videoOnly))
        assertNull(AudioCodecProbe.resolve(KEY, CLIP, videoOnly))

        assertEquals(
            "silence is not worth remembering — the track list may not be final yet",
            2,
            opens.get(),
        )
    }

    @Test
    fun `a read that failed is tried again`() {
        val opens = AtomicInteger()
        val stillWriting = { ->
            if (opens.incrementAndGet() == 1) throw IOException("still being written")
            listOf("audio/ac3")
        }

        assertNull(
            "an unreadable file must not fail playback",
            AudioCodecProbe.resolve(KEY, CLIP, stillWriting),
        )
        assertEquals(
            "and the next attempt must reach the file, not a remembered failure",
            "AC3",
            AudioCodecProbe.resolve(KEY, CLIP, stillWriting),
        )
        assertEquals(2, opens.get())
    }

    @Test
    fun `a file with no trusted identity is never cached`() {
        val opens = AtomicInteger()

        assertNull(AudioCodecProbe.cacheKey("content://video/1", sizeBytes = 0, changedAt = 0))
        assertEquals("AC3", probe(key = null, opens = opens))
        assertEquals("AC3", probe(key = null, opens = opens))

        assertEquals(
            "without a key there is nothing to look the answer up by, so it is not kept",
            2,
            opens.get(),
        )
    }

    private fun probe(key: String?, opens: AtomicInteger): String? =
        AudioCodecProbe.resolve(key, CLIP) {
            opens.incrementAndGet()
            listOf("video/avc", "audio/ac3")
        }

    private companion object {
        const val KEY = "content://media/external/video/media/42|41000|1700000000"
        const val CLIP = "'clip.mkv'"
    }
}
