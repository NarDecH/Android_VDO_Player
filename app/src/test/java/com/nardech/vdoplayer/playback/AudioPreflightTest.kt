package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pre-playback warning has one job: say "this file has no sound because nothing here
 * can decode it" only when that is true. Both halves of the answer come from lists this
 * class cannot read on the JVM, so they are passed in and the rule is pinned here — the
 * two lists themselves are checked on a device, in [AudioCodecProbeDeviceTest].
 *
 * The failures matter more than the hits: a warning on a playable file tells the viewer
 * their video is broken when it is not, so every way of not knowing ends as
 * [AudioOutlook.UNKNOWN] rather than as a verdict.
 */
class AudioPreflightTest {

    /** The platform decoder list of a phone born without AC-3 support. */
    private val noAc3Platform = setOf("audio/mp4a-latm", "audio/mpeg", "audio/opus")

    @Test
    fun `a decoder the platform names is enough`() {
        assertEquals(
            AudioOutlook.PLAYABLE,
            AudioPreflight.outlook(listOf("audio/mp4a-latm"), noAc3Platform) { false },
        )
    }

    @Test
    fun `the ffmpeg extension is a decoder too`() {
        assertEquals(
            "the platform refusing a codec is not the end of it: every preference registers FFmpeg",
            AudioOutlook.PLAYABLE,
            AudioPreflight.outlook(listOf("audio/ac3"), noAc3Platform) { mime -> mime == "audio/ac3" },
        )
    }

    @Test
    fun `one playable track is sound`() {
        assertEquals(
            "a file with a second, playable track is not silent, so it must not be warned about",
            AudioOutlook.PLAYABLE,
            AudioPreflight.outlook(listOf("audio/vnd.dts.hd", "audio/mp4a-latm"), noAc3Platform) {
                false
            },
        )
    }

    @Test
    fun `a codec no track names is undecodable`() {
        assertEquals(
            AudioOutlook.UNDECODABLE,
            AudioPreflight.outlook(listOf("audio/vnd.dts.hd"), noAc3Platform) { false },
        )
        assertEquals(
            AudioOutlook.UNDECODABLE,
            AudioPreflight.outlook(
                listOf("audio/vnd.dts.hd", "audio/vnd.dts"),
                noAc3Platform,
            ) { false },
        )
    }

    @Test
    fun `a file without an audio track is not a warning`() {
        assertEquals(
            "a silent film, or a header nobody could read, must not be called undecodable",
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(emptyList(), noAc3Platform) { false },
        )
        assertEquals(
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(listOf("video/avc"), noAc3Platform) { false },
        )
    }

    @Test
    fun `a value that is not a MIME type is not a warning`() {
        assertEquals(
            "a codec name from an older cache cannot be looked up, so it can only say nothing",
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(listOf("AC3"), noAc3Platform) { false },
        )
    }

    @Test
    fun `a platform that cannot be asked makes no claim`() {
        assertEquals(
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(listOf("audio/ac3"), platformDecoders = null) { false },
        )
    }

    @Test
    fun `an ffmpeg that cannot be asked makes no claim`() {
        assertEquals(
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(listOf("audio/ac3"), noAc3Platform) { null },
        )
        assertEquals(
            "one codec that cannot be asked about is enough to withhold the verdict",
            AudioOutlook.UNKNOWN,
            AudioPreflight.outlook(listOf("audio/mp4a-latm", "audio/ac3"), noAc3Platform) { null },
        )
    }

    @Test
    fun `the MIME type is compared without case`() {
        assertEquals(
            AudioOutlook.PLAYABLE,
            AudioPreflight.outlook(listOf("audio/MP4A-LATM"), noAc3Platform) { false },
        )
    }
}
