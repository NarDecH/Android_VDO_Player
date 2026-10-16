package com.nardech.vdoplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio picker hands `TrackSelectionOverride` the group and track indices the
 * player reported, so every option has to point back at the track it came from —
 * picking the wrong one would switch to an unrelated track. The assertions look the
 * index up in the same `Tracks` object instead of assuming an order, because how
 * Media3 stores the groups is its own business.
 */
class TrackOptionsTest {

    private fun video() = TrackGroup(
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
    )

    private fun audio(label: String?, language: String?) = TrackGroup(
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setLabel(label)
            .setLanguage(language)
            .build(),
    )

    private fun group(trackGroup: TrackGroup, vararg selected: Boolean, adaptive: Boolean = false) =
        Tracks.Group(
            trackGroup,
            adaptive,
            IntArray(selected.size) { C.FORMAT_HANDLED },
            selected,
        )

    private val label: (Format) -> String = { format ->
        format.label ?: format.language ?: format.sampleMimeType.orEmpty()
    }

    /** Every option has to address an audio group, and address it correctly. */
    private fun assertOptionsPointAtTheirTracks(tracks: Tracks, options: List<TrackChoice>) {
        options.forEach { choice ->
            val group = tracks.groups[choice.groupIndex]
            assertEquals("option points at a non-audio group", C.TRACK_TYPE_AUDIO, group.type)
            assertTrue(
                "option points past the end of its group",
                choice.trackIndex in 0 until group.length,
            )
            assertEquals(
                "option label belongs to a different track",
                label(group.getTrackFormat(choice.trackIndex)),
                choice.label,
            )
            assertEquals(
                "selection state does not match the player",
                group.isTrackSelected(choice.trackIndex),
                choice.selected,
            )
        }
    }

    @Test
    fun `only audio groups become options`() {
        val tracks = Tracks(
            listOf(
                group(video(), true),
                group(audio("Thai", null), true),
                group(audio(null, "en"), false),
            ),
        )

        val options = TrackOptions.audioTracks(tracks, label)

        assertEquals(setOf("Thai", "en"), options.map { it.label }.toSet())
        assertEquals(
            mapOf("Thai" to true, "en" to false),
            options.associate { it.label to it.selected },
        )
        assertOptionsPointAtTheirTracks(tracks, options)
    }

    @Test
    fun `several tracks inside one group all appear`() {
        val bundled = TrackGroup(
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLabel("Stereo").build(),
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setLabel("5.1").build(),
        )
        val tracks = Tracks(
            listOf(
                group(bundled, false, true, adaptive = true),
            ),
        )

        val options = TrackOptions.audioTracks(tracks, label)

        assertEquals(setOf("Stereo", "5.1"), options.map { it.label }.toSet())
        assertEquals(setOf(0, 1), options.map { it.trackIndex }.toSet())
        assertEquals(1, options.map { it.groupIndex }.distinct().size)
        assertOptionsPointAtTheirTracks(tracks, options)
    }

    @Test
    fun `a video without audio offers nothing to pick`() {
        val tracks = Tracks(listOf(group(video(), true)))

        assertTrue(TrackOptions.audioTracks(tracks, label).isEmpty())
    }
}
