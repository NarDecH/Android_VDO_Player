package com.nardech.vdoplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.nardech.vdoplayer.data.DecoderPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-sound hint is only useful when it fires for the one real failure — an audio
 * track nobody decodes — and stays away from the innocent look-alikes: a silent film,
 * a player that has not prepared yet, a preference that already is FFmpeg, and a hint
 * the viewer has dismissed. Each of those is a test here, because a warning that
 * appears for the wrong reason teaches people to ignore it.
 */
class AudioSilenceTest {

    private fun audioGroup(
        vararg selected: Boolean,
        mime: String = MimeTypes.AUDIO_AC3,
        label: String? = null,
    ) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).setLabel(label).build()),
        false,
        IntArray(selected.size) { C.FORMAT_HANDLED },
        selected,
    )

    private fun videoGroup() = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
        false,
        intArrayOf(C.FORMAT_HANDLED),
        booleanArrayOf(true),
    )

    @Test
    fun `an audio track nothing selected is reported as silent`() {
        val tracks = Tracks(listOf(videoGroup(), audioGroup(false)))

        val silent = AudioSilence.detect(tracks)

        assertEquals(SilentAudio(codec = "AC3"), silent)
    }

    @Test
    fun `the track label is preferred over the raw mime type`() {
        val tracks = Tracks(listOf(audioGroup(false, label = "Thai 5.1")))

        assertEquals(SilentAudio(codec = "Thai 5.1"), AudioSilence.detect(tracks))
    }

    @Test
    fun `a group whose type Media3 cannot tell is not treated as audio`() {
        // Media3 derives a group's type from its MIME type, so a format without one is
        // of unknown type. Guessing here would fire the hint on data or subtitle tracks.
        val untagged = Tracks.Group(
            TrackGroup(Format.Builder().build()),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )

        assertNull(AudioSilence.detect(Tracks(listOf(untagged))))
    }

    @Test
    fun `a selected audio track means the sound is fine`() {
        val tracks = Tracks(listOf(videoGroup(), audioGroup(true)))

        assertNull(AudioSilence.detect(tracks))
    }

    @Test
    fun `one selected group is enough when the file has several`() {
        val tracks = Tracks(
            listOf(
                audioGroup(false, mime = MimeTypes.AUDIO_AC3),
                audioGroup(true, mime = MimeTypes.AUDIO_AAC),
            ),
        )

        assertNull(AudioSilence.detect(tracks))
    }

    @Test
    fun `a video without any audio track is not a failure`() {
        assertNull(AudioSilence.detect(Tracks(listOf(videoGroup()))))
    }

    @Test
    fun `the hint is offered while FFmpeg is still a different choice`() {
        val silent = SilentAudio(codec = "AC3")

        listOf(DecoderPreference.PLATFORM_FIRST, DecoderPreference.PLATFORM_ONLY)
            .forEach { preference ->
                assertTrue(
                    "the hint is missing for $preference",
                    AudioSilence.shouldOfferFfmpeg(
                        silent = silent,
                        preference = preference,
                        playbackState = Player.STATE_READY,
                        dismissed = false,
                    ),
                )
            }
    }

    @Test
    fun `the hint is pointless once FFmpeg is already the choice`() {
        assertFalse(
            AudioSilence.shouldOfferFfmpeg(
                silent = SilentAudio(codec = "AC3"),
                preference = DecoderPreference.FFMPEG_FIRST,
                playbackState = Player.STATE_READY,
                dismissed = false,
            ),
        )
    }

    @Test
    fun `the hint waits until the player is ready`() {
        listOf(Player.STATE_IDLE, Player.STATE_BUFFERING, Player.STATE_ENDED).forEach { state ->
            assertFalse(
                "the hint appears in state $state",
                AudioSilence.shouldOfferFfmpeg(
                    silent = SilentAudio(codec = "AC3"),
                    preference = DecoderPreference.PLATFORM_FIRST,
                    playbackState = state,
                    dismissed = false,
                ),
            )
        }
    }

    @Test
    fun `a dismissed hint does not come back`() {
        assertFalse(
            AudioSilence.shouldOfferFfmpeg(
                silent = SilentAudio(codec = "AC3"),
                preference = DecoderPreference.PLATFORM_FIRST,
                playbackState = Player.STATE_READY,
                dismissed = true,
            ),
        )
    }

    @Test
    fun `a remembered codec switches without asking`() {
        assertEquals(
            "AC3",
            AudioSilence.autoRescueCodec(
                silent = SilentAudio(codec = "AC3"),
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3", "EAC3"),
            ),
        )
    }

    @Test
    fun `only the codec that was rescued before is remembered`() {
        assertNull(
            AudioSilence.autoRescueCodec(
                silent = SilentAudio(codec = "DTS"),
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3"),
            ),
        )
    }

    @Test
    fun `there is nothing to rescue when FFmpeg already is the choice`() {
        assertNull(
            AudioSilence.autoRescueCodec(
                silent = SilentAudio(codec = "AC3"),
                preference = DecoderPreference.FFMPEG_FIRST,
                remembered = setOf("AC3"),
            ),
        )
    }

    @Test
    fun `a remembered codec is chosen before the file starts playing`() {
        assertEquals(
            "the codec read from the file should be the one that switches early",
            "AC3",
            AudioSilence.autoRescueCodec(
                codec = "AC3",
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3", "EAC3"),
            ),
        )
        assertNull(
            "a codec the viewer never rescued must not be switched early",
            AudioSilence.autoRescueCodec(
                codec = "AAC",
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3"),
            ),
        )
        assertNull(
            "there is nothing early to do when FFmpeg already is the choice",
            AudioSilence.autoRescueCodec(
                codec = "AC3",
                preference = DecoderPreference.FFMPEG_FIRST,
                remembered = setOf("AC3"),
            ),
        )
        assertNull(
            "a file whose audio could not be read must not switch anything",
            AudioSilence.autoRescueCodec(
                codec = null,
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3", ""),
            ),
        )
    }

    @Test
    fun `a codec is named the same way from a MIME type as from a track`() {
        assertEquals("AC3", AudioSilence.codecKey("audio/ac3"))
        assertEquals("EAC3", AudioSilence.codecKey("audio/eac3"))
        assertEquals("VND.DTS", AudioSilence.codecKey("audio/vnd.dts"))
        assertNull("a file with no MIME type must not produce a name", AudioSilence.codecKey(null))
        assertNull("an empty MIME type must not produce a name", AudioSilence.codecKey(""))
    }

    @Test
    fun `an unnamed track is never remembered into an automatic switch`() {
        assertNull(
            AudioSilence.autoRescueCodec(
                silent = SilentAudio(codec = ""),
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3", ""),
            ),
        )
        assertNull(
            AudioSilence.autoRescueCodec(
                silent = null,
                preference = DecoderPreference.PLATFORM_FIRST,
                remembered = setOf("AC3"),
            ),
        )
    }

    @Test
    fun `nothing is offered when there is nothing silent`() {
        assertFalse(
            AudioSilence.shouldOfferFfmpeg(
                silent = null,
                preference = DecoderPreference.PLATFORM_FIRST,
                playbackState = Player.STATE_READY,
                dismissed = false,
            ),
        )
    }
}
