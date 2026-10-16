package com.nardech.vdoplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/**
 * One entry of the audio track picker: which track of the current media item it
 * is, what to show for it, and whether the player uses it right now.
 *
 * The group and track indices are exactly what `TrackSelectionOverride` needs, so
 * the UI never has to touch Media3 types itself.
 */
data class TrackChoice(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
)

/**
 * Turns the player's track list into the options the picker shows. Pure on
 * purpose (the label formatter is injected), so the ordering and index maths can
 * be tested without a player.
 */
object TrackOptions {

    fun audioTracks(tracks: Tracks, describe: (Format) -> String): List<TrackChoice> =
        buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    add(
                        TrackChoice(
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            label = describe(group.getTrackFormat(trackIndex)),
                            selected = group.isTrackSelected(trackIndex),
                        ),
                    )
                }
            }
        }
}
