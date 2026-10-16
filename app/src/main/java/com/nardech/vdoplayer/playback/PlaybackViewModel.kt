package com.nardech.vdoplayer.playback

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.Diagnostics
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the player UI renders, refreshed from one place. */
data class PlayerUiState(
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val title: String = "",
    val mediaId: String? = null,
    val errorMessage: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val textCodec: String? = null,
    val subtitleCount: Int = 0,
    val audioTrackCount: Int = 0,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val isBuffering: Boolean get() = playbackState == Player.STATE_BUFFERING
    val isEnded: Boolean get() = playbackState == Player.STATE_ENDED
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * Bridges the UI to the [PlaybackService] through a [MediaController].
 *
 * The controller is asynchronous, so state is pulled on a 500 ms ticker plus on
 * every player event. Pulling beats pushing here: one code path updates the UI,
 * and there is no chance of a callback arriving for a released player.
 */
@UnstableApi
class PlaybackViewModel(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var videoSize: VideoSize? = null

    var controller: MediaController? = null
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }

        override fun onVideoSizeChanged(newVideoSize: VideoSize) {
            videoSize = newVideoSize
            LogBus.i(
                LogTag.DECODER,
                "Video size ${newVideoSize.width}x${newVideoSize.height} " +
                    "pixelRatio=${newVideoSize.pixelWidthHeightRatio}",
            )
            refresh()
        }

        override fun onPlayerError(error: PlaybackException) {
            LogBus.e(
                LogTag.PLAYER,
                "Playback error ${error.errorCodeName} on '${error.message}'",
                error,
            )
            _state.value = _state.value.copy(errorMessage = error.message ?: error.errorCodeName)
        }

        override fun onTracksChanged(tracks: Tracks) {
            LogBus.d(LogTag.DECODER, "Tracks changed\n${Diagnostics.playerTracks(tracks)}")
            refresh()
        }
    }

    fun connect() {
        if (controller != null || controllerFuture != null) return
        LogBus.i(LogTag.PLAYER, "Connecting MediaController to PlaybackService")
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connected ->
                        controller = connected
                        connected.addListener(listener)
                        LogBus.i(LogTag.PLAYER, "MediaController connected")
                        startTicker()
                        refresh()
                    }
                    .onFailure {
                        LogBus.e(LogTag.PLAYER, "MediaController could not connect", it)
                    }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    fun releaseController() {
        ticker?.cancel()
        ticker = null
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        videoSize = null
        LogBus.d(LogTag.PLAYER, "MediaController released")
    }

    /** Replaces the queue and starts playing [startIndex]. */
    fun play(items: List<VideoItem>, startIndex: Int, resumePositions: Map<String, Long>) {
        val player = controller ?: return
        if (items.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        val startItem = items[safeIndex]
        val resumeAt = resumePositions[startItem.uri.toString()] ?: 0L

        LogBus.i(
            LogTag.PLAYER,
            "Playing '${startItem.title}' (index $safeIndex of ${items.size}) " +
                "resumeAt=${resumeAt}ms uri=${startItem.uri}",
        )
        _state.value = _state.value.copy(errorMessage = null)

        player.setMediaItems(items.map { it.toMediaItem() }, safeIndex, resumeAt)
        player.prepare()
        player.playWhenReady = true
        refresh()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
        }
        LogBus.d(LogTag.PLAYER, "Toggled playback, now playing=${player.isPlaying}")
        refresh()
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        refresh()
    }

    fun seekBy(deltaMs: Long) {
        val player = controller ?: return
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(target)
        LogBus.d(LogTag.PLAYER, "Seek ${if (deltaMs >= 0) "+" else ""}${deltaMs}ms -> ${target}ms")
        refresh()
    }

    fun setSpeed(speed: Float) {
        val player = controller ?: return
        player.setPlaybackSpeed(speed)
        LogBus.i(LogTag.PLAYER, "Playback speed set to ${speed}x")
        refresh()
    }

    /**
     * Cycles repeat off → the whole queue → this video.
     *
     * A [MediaController] may refuse the command; when it does, the change is logged and
     * the UI keeps showing whatever mode the player really has. Returns the mode now in
     * effect, so the caller can tell the user what happened.
     */
    fun cycleRepeatMode(): Int {
        val player = controller ?: return Player.REPEAT_MODE_OFF
        val next = PlaybackPreferences.nextRepeatMode(player.repeatMode)
        runCatching {
            player.setRepeatMode(next)
            PlaybackPreferences.setRepeatMode(app, next)
            LogBus.i(LogTag.PLAYER, "Repeat mode changed to ${repeatModeName(next)}")
        }.onFailure {
            LogBus.w(LogTag.PLAYER, "The session refused a repeat-mode change", it)
        }
        refresh()
        return player.repeatMode
    }

    fun skipToNext() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
        refresh()
    }

    fun skipToPrevious() {
        controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem()
        refresh()
    }

    fun stop() {
        controller?.stop()
        refresh()
    }

    /** Attaches a subtitle file the user picked from storage. */
    fun attachSubtitle(configuration: MediaItem.SubtitleConfiguration) {
        val player = controller ?: return
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val existing = item.localConfiguration?.subtitleConfigurations.orEmpty()
        val updated = item.buildUpon()
            .setSubtitleConfigurations(existing + configuration)
            .build()
        player.replaceMediaItem(index, updated)
        LogBus.i(
            LogTag.PLAYER,
            "Subtitle attached: ${configuration.uri} (${existing.size + 1} total)",
        )
        refresh()
    }

    /**
     * Forces one specific track (audio or subtitle) to be used. The session may
     * refuse the command, in which case the request is logged and ignored rather
     * than crashing playback.
     */
    fun selectTrack(groupIndex: Int, trackIndex: Int) {
        val player = controller ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (trackIndex !in 0 until group.length) return
        val label = Diagnostics.describe(group.getTrackFormat(trackIndex))
        runCatching {
            val parameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        group.mediaTrackGroup,
                        trackIndex,
                    ),
                )
                .build()
            player.setTrackSelectionParameters(parameters)
            LogBus.i(LogTag.PLAYER, "Track override applied: $label")
        }.onFailure {
            LogBus.w(
                LogTag.PLAYER,
                "Track override rejected by the session, keeping automatic selection: $label",
                it,
            )
        }
        refresh()
    }

    /** Turns the current file's subtitles on or off without touching other tracks. */
    fun setSubtitlesEnabled(enabled: Boolean) {
        val player = controller ?: return
        runCatching {
            val parameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
                .build()
            player.setTrackSelectionParameters(parameters)
            LogBus.i(LogTag.PLAYER, "Subtitles ${if (enabled) "enabled" else "disabled"}")
        }.onFailure {
            LogBus.w(LogTag.PLAYER, "The session refused a subtitle toggle", it)
        }
        refresh()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                refresh()
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun refresh() {
        val player = controller ?: return
        val tracks = player.currentTracks
        val selected = selectedFormats(tracks)

        _state.value = PlayerUiState(
            isPlaying = player.isPlaying,
            playbackState = player.playbackState,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            videoWidth = videoSize?.width ?: 0,
            videoHeight = videoSize?.height ?: 0,
            title = player.mediaMetadata.title?.toString()
                ?: player.currentMediaItem?.mediaId.orEmpty(),
            mediaId = player.currentMediaItem?.mediaId,
            errorMessage = _state.value.errorMessage,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            videoCodec = selected.video,
            audioCodec = selected.audio,
            textCodec = selected.text,
            subtitleCount = player.currentMediaItem?.localConfiguration
                ?.subtitleConfigurations?.size ?: 0,
            audioTrackCount = countSelectedGroup(tracks, C.TRACK_TYPE_AUDIO),
            queueIndex = player.currentMediaItemIndex,
            queueSize = player.mediaItemCount,
            repeatMode = player.repeatMode,
        )
    }

    private fun repeatModeName(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_OFF -> "off"
        Player.REPEAT_MODE_ALL -> "whole queue"
        Player.REPEAT_MODE_ONE -> "this video"
        else -> "unknown($mode)"
    }

    private data class SelectedFormats(val video: String?, val audio: String?, val text: String?)

    private fun selectedFormats(tracks: Tracks): SelectedFormats {
        var video: String? = null
        var audio: String? = null
        var text: String? = null
        tracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                val description = Diagnostics.describe(group.getTrackFormat(index))
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> video = description
                    C.TRACK_TYPE_AUDIO -> audio = description
                    C.TRACK_TYPE_TEXT -> text = description
                }
            }
        }
        return SelectedFormats(video, audio, text)
    }

    private fun countSelectedGroup(tracks: Tracks, type: Int): Int =
        tracks.groups.count { group ->
            group.type == type && (0 until group.length).any { group.isTrackSelected(it) }
        }

    private fun VideoItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(folder)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    private companion object {
        const val POSITION_TICK_MS = 500L
    }
}
