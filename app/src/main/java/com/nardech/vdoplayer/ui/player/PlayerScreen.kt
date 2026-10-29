package com.nardech.vdoplayer.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.Diagnostics
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.playback.AudioCodecProbe
import com.nardech.vdoplayer.playback.AudioOutlook
import com.nardech.vdoplayer.playback.AudioPreflight
import com.nardech.vdoplayer.playback.AudioSilence
import com.nardech.vdoplayer.playback.PlaybackViewModel
import com.nardech.vdoplayer.playback.PlayerUiState
import com.nardech.vdoplayer.playback.SeekPreview
import com.nardech.vdoplayer.playback.SeekPreviewPolicy
import com.nardech.vdoplayer.playback.SeekPreviewSource
import com.nardech.vdoplayer.playback.UnsupportedAudio
import com.nardech.vdoplayer.playback.probeKey
import com.nardech.vdoplayer.ui.decoderChipLabel
import com.nardech.vdoplayer.ui.decoderPreferenceLabel
import com.nardech.vdoplayer.ui.folderLabel
import com.nardech.vdoplayer.util.Formatters
import com.nardech.vdoplayer.util.IntentHelpers
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AspectMode(@StringRes val labelRes: Int, val resizeMode: Int) {
    FIT(R.string.aspect_fit, AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM(R.string.aspect_zoom, AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH(R.string.aspect_stretch, AspectRatioFrameLayout.RESIZE_MODE_FILL),
    FIXED_WIDTH(R.string.aspect_width, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
}

private enum class HudKind { BRIGHTNESS, VOLUME, SEEK }

private data class HudState(
    val kind: HudKind,
    val primary: String,
    val secondary: String,
    /** Frame grabbed for this seek position; only the seek HUD ever has one. */
    val preview: Bitmap? = null,
)

private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

/**
 * How far above the bottom edge a snackbar has to sit: the control bar is there, and
 * the stream panel is taller still. Both cover the video, and the player has no
 * Scaffold to lay the snackbar out against, so the offset is stated once here.
 */
private val CONTROL_BAR_CLEARANCE = 140.dp
/** The title bar's height plus a little: the clearance left when the bar moved to the top. */
private val HEADER_CLEARANCE = 88.dp
private val STREAM_PANEL_CLEARANCE = 300.dp

/**
 * What a file's own header says about its audio, read before the player opens it.
 *
 * The track's MIME types are kept rather than the codec name because both questions are
 * asked of them: which name to remember a rescue under — the first track, the one Media3
 * plays by default — and whether anything on this device can decode any track at all.
 */
private data class AudioAudit(
    val mimes: List<String>,
    val outlook: AudioOutlook,
) {
    val codec: String? get() = AudioSilence.codecKey(mimes.firstOrNull())

    /** The file nothing here can decode, or `null` when there is nothing to warn about. */
    val unsupported: UnsupportedAudio?
        get() = if (outlook == AudioOutlook.UNDECODABLE) {
            UnsupportedAudio(codec = codec.orEmpty())
        } else {
            null
        }
}

/**
 * Reads [this] file's audio header off the main thread — the one probe both decisions use.
 *
 * A file no decoder here can read is logged where it is found, which is the only place
 * that knows both the file and the verdict; the screen only decides when to say it.
 */
private suspend fun VideoItem.auditAudio(context: Context): AudioAudit =
    withContext(Dispatchers.IO) {
        val mimes = AudioCodecProbe.probeAudioMimes(context, uri, probeKey())
        AudioAudit(mimes = mimes, outlook = AudioPreflight.outlook(mimes)).also { audit ->
            audit.unsupported?.let { unsupported ->
                LogBus.w(
                    LogTag.DECODER,
                    "No decoder on this device reads the '${unsupported.codec}' audio of " +
                        "'$title'; it will play silently",
                )
            }
        }
    }

@Composable
fun PlayerScreen(
    playback: PlaybackViewModel,
    queue: List<VideoItem>,
    startIndex: Int,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    resumePositions: Map<String, Long>,
    onSaveProgress: (VideoItem, Long, Long) -> Unit,
    onSetDecoderPreference: (DecoderPreference) -> Unit,
    onRememberAudioCodec: (String) -> Unit,
    onForgetAudioCodec: (String) -> Unit,
    onSetControlsOnTop: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val state by playback.state.collectAsStateWithLifecycle()
    val controlsOnTop = settings.controlsOnTop

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<HudState?>(null) }
    // Media id whose no-sound hint was dismissed or acted on; a new video gets it again.
    var silentHintClosedFor by remember { mutableStateOf<String?>(null) }
    // Media id whose remembered codec already switched by itself, so the effect below
    // cannot fire twice, and the id whose automatic switch the session refused.
    var autoRescuedFor by remember { mutableStateOf<String?>(null) }
    var autoRescueRefusedFor by remember { mutableStateOf<String?>(null) }
    // Whether this screen already moved the audio decoder to FFmpeg. That choice lives in
    // the playback service and outlives an item, so every later file in the queue is
    // covered by it already: re-sending the command would only repeat the message.
    var audioSwitchedByApp by remember { mutableStateOf(false) }
    // The item whose audio no decoder on this device can read, decided from its own header
    // before the player opened the file, and the item whose warning the viewer closed.
    var unsupportedAudio by remember { mutableStateOf<UnsupportedAudio?>(null) }
    var unsupportedDismissedFor by remember { mutableStateOf<String?>(null) }
    // The item the player last moved to, which is how an arrival is told from the start.
    var arrivedMediaId by remember { mutableStateOf<String?>(null) }
    var scrubProgress by remember { mutableFloatStateOf(-1f) }
    var previewTarget by remember { mutableStateOf<Long?>(null) }
    // Held in a state object rather than a local, because the gesture and slider
    // callbacks are created once and must read the newest frame when they run.
    val previewFrame = remember { mutableStateOf<SeekPreview?>(null) }
    val previewSource = remember { SeekPreviewSource() }
    val previewMaxWidthPx = with(LocalDensity.current) { 220.dp.toPx() }.toInt()

    // Gesture callbacks run outside composition, so the HUD wording is resolved here.
    val brightnessLabel = stringResource(R.string.hud_brightness)
    val volumeLabel = stringResource(R.string.hud_volume)
    val doubleTapSeekLabel = stringResource(R.string.hud_double_tap_seek)
    val holdToSpeedLabel = stringResource(R.string.hud_hold_speed)
    val seekSeconds = (settings.seekStepMs / 1000L).toInt()
    val seekForwardLabel = stringResource(R.string.hud_seek_forward, seekSeconds)
    val seekBackwardLabel = stringResource(R.string.hud_seek_backward, seekSeconds)
    val speedBoostLabel = Formatters.speed(3f)
    val repeatOffLabel = stringResource(R.string.player_repeat_off)
    val repeatAllLabel = stringResource(R.string.player_repeat_all)
    val repeatOneLabel = stringResource(R.string.player_repeat_one)
    val repeatAnnounceFormat = stringResource(R.string.player_repeat_announce)
    // Resolved here, because the decoder chips are built inside a callback.
    val decoderChoices = DecoderPreference.entries
    val decoderChips = decoderChoices.map { decoderChipLabel(it) }
    val decoderAnnounceFormat = stringResource(R.string.player_decoder_switched)
    val decoderPendingFormat = stringResource(R.string.player_decoder_switched_pending)
    val autoRememberedFormat = stringResource(R.string.player_auto_ffmpeg_remembered)
    val autoRescuedFormat = stringResource(R.string.player_auto_ffmpeg_applied)
    val autoRescuedForget = stringResource(R.string.player_auto_ffmpeg_forget)
    val autoForgottenFormat = stringResource(R.string.player_auto_ffmpeg_forgotten)
    val audioSwitchedLabel = stringResource(R.string.player_audio_decoder_switched)
    val audioRefusedLabel = stringResource(R.string.player_audio_decoder_refused)
    // The stream panel's chips change the whole decoder pair, because "FFmpeg first"
    // in Settings means exactly that and is stored. Choosing on purpose is not the
    // same as rescuing one silent track, so those chips keep the full switch.
    val applyDecoderChoice: (DecoderPreference) -> Unit = { preference ->
        onSetDecoderPreference(preference)
        // The chips move both halves, so what the rescue knew about the audio decoder no
        // longer holds: a later file is allowed to switch again.
        audioSwitchedByApp = false
        val appliedLive = playback.setDecoderPreference(preference)
        val label = decoderChips[decoderChoices.indexOf(preference)]
        val message = if (appliedLive) {
            decoderAnnounceFormat.format(label)
        } else {
            decoderPendingFormat.format(label)
        }
        scope.launch { snackbarHost.showSnackbar(message) }
    }
    // The rescue the no-sound card and the remembered-codec effect share. It moves
    // the audio decoder only: the picture keeps its hardware decoder, so a file with
    // unsupported sound does not also lose smooth 4K playback. A codec handed over
    // here is remembered for the next file with the same sound.
    val rescueAudio: (String?) -> Boolean = { rememberedCodec ->
        val appliedLive = playback.rescueAudioWithFfmpeg()
        if (appliedLive) audioSwitchedByApp = true
        if (!appliedLive) {
            LogBus.w(LogTag.DECODER, "The session refused the audio decoder switch")
        }
        val message = when {
            rememberedCodec != null && appliedLive -> autoRememberedFormat.format(rememberedCodec)
            appliedLive -> audioSwitchedLabel
            else -> audioRefusedLabel
        }
        scope.launch { snackbarHost.showSnackbar(message) }
        if (rememberedCodec != null) onRememberAudioCodec(rememberedCodec)
        appliedLive
    }
    // Every automatic switch says so, with the same undo. The message and its action are
    // built in one place because three paths now switch by themselves — before the first
    // item, on auto-advance, and after a file has already gone silent — and they must not
    // drift apart in what they claim or in what Forget undoes. Announced on the screen's
    // scope, not an effect's: the switch removes the very state that restarted the effect,
    // which would cancel a snackbar shown from inside it.
    val announceAutoRescue: (String) -> Unit = { codec ->
        scope.launch {
            val result = snackbarHost.showSnackbar(
                message = autoRescuedFormat.format(codec),
                actionLabel = autoRescuedForget,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onForgetAudioCodec(codec)
                LogBus.i(
                    LogTag.DECODER,
                    "Viewer forgot codec '$codec': no automatic switch next time",
                )
                snackbarHost.showSnackbar(autoForgottenFormat.format(codec))
            }
        }
    }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableFloatStateOf(1f) }
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.5f,
        )
    }

    val currentItem = queue.getOrNull(state.queueIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)))

    // Back belongs to the overlay, not to the Activity: one press closes one thing the
    // player opened, and the last one returns to the list the video came from.
    PlayerBackHandler(
        onDismiss = {
            when {
                showInfo -> {
                    showInfo = false
                    true
                }

                showSpeedDialog -> {
                    showSpeedDialog = false
                    true
                }

                showAspectDialog -> {
                    showAspectDialog = false
                    true
                }

                showAudioDialog -> {
                    showAudioDialog = false
                    true
                }

                else -> false
            }
        },
        onClose = {
            LogBus.i(LogTag.UI, "Back pressed in the player: returning to the list")
            onClose()
        },
    )

    // Set when this file's audio codec was rescued by hand before: the card is then not
    // shown and the switch below happens by itself. A refusal puts the card back.
    val autoRescueCodec = AudioSilence.autoRescueCodec(
        silent = state.silentAudio,
        preference = settings.decoderPreference,
        remembered = settings.autoFfmpegCodecs,
    )?.takeIf { state.mediaId != autoRescueRefusedFor }

    // Start (or restart) playback whenever a new queue/index arrives.
    LaunchedEffect(queue, startIndex, settings.subtitleAutoDiscovery) {
        if (queue.isEmpty()) return@LaunchedEffect
        val startItem = queue.getOrNull(startIndex.coerceIn(0, queue.lastIndex))

        // The file's header is read once, before the player is handed the queue, and that
        // one read answers both questions asked of a first item: is this a codec the viewer
        // already sent to FFmpeg — so the switch lands before the first frame instead of
        // after a stretch of silence — and is there a decoder here for its audio at all, so
        // a file that can only ever be silent says so before it starts rather than once it
        // has. Nothing is decided for most launches; the read is cached by the file's own
        // identity, so returning to a file this process already read costs nothing at all.
        val audit = startItem?.auditAudio(context)
        // Set before playback starts, so the warning is already on screen for the first
        // frame instead of arriving after the viewer has wondered why it is quiet.
        unsupportedAudio = audit?.unsupported
        val earlyRescueCodec = AudioSilence.autoRescueCodec(
            codec = audit?.codec,
            preference = settings.decoderPreference,
            remembered = settings.autoFfmpegCodecs,
        )
        if (startItem != null && earlyRescueCodec != null && !audioSwitchedByApp) {
            val startMediaId = startItem.id.toString()
            val alreadyAnnounced = autoRescuedFor == startMediaId
            if (playback.rescueAudioWithFfmpeg()) {
                autoRescuedFor = startMediaId
                audioSwitchedByApp = true
                LogBus.i(
                    LogTag.DECODER,
                    "Remembered audio codec '$earlyRescueCodec': choosing FFmpeg audio for " +
                        "'${startItem.title}' before it is prepared",
                )
                if (!alreadyAnnounced) announceAutoRescue(earlyRescueCodec)
            } else {
                LogBus.w(
                    LogTag.DECODER,
                    "The session refused the early switch for '$earlyRescueCodec'; " +
                        "the no-sound rescue still covers this file",
                )
            }
        }

        playback.play(
            items = queue,
            startIndex = startIndex,
            resumePositions = resumePositions,
            autoSubtitles = settings.subtitleAutoDiscovery,
        )
    }

    // A frame grabbed while scrubbing, decoded off the main thread. The effect restarts
    // whenever the target bucket changes, which also cancels a decode nobody wants.
    LaunchedEffect(previewTarget, state.mediaId, settings.seekPreviewEnabled) {
        val target = previewTarget
        val uri = playback.controller?.currentMediaItem?.localConfiguration?.uri
        if (target == null || uri == null || !SeekPreviewPolicy.isEnabled(settings.seekPreviewEnabled)) {
            previewFrame.value = null
            return@LaunchedEffect
        }
        val frame = withContext(Dispatchers.IO) {
            previewSource.frame(context, uri, target, previewMaxWidthPx)
        }
        previewFrame.value = frame?.let { SeekPreview(target, it) }
    }

    // A new video means new frames: drop the retriever so it does not hold the old file.
    DisposableEffect(state.mediaId) {
        onDispose {
            previewSource.release()
            previewFrame.value = null
            previewTarget = null
        }
    }

    // Auto rotation behaviour on entry.
    DisposableEffect(settings.autoRotate) {
        if (settings.autoRotate) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (!settings.rememberBrightness) {
                activity?.window?.attributes = activity.window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    // Watch positions are checkpointed every five seconds, not on every tick:
    // the position updates twice a second and each save is a DataStore write.
    LaunchedEffect(queue) {
        if (queue.isEmpty()) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(5_000)
            val snapshot = playback.state.value
            val item = queue.getOrNull(
                snapshot.queueIndex.coerceIn(0, queue.lastIndex),
            ) ?: continue
            if (snapshot.positionMs > 0L) {
                onSaveProgress(item, snapshot.positionMs, snapshot.durationMs)
            }
        }
    }
    DisposableEffect(currentItem?.id) {
        onDispose {
            val item = currentItem ?: return@onDispose
            val snapshot = playback.state.value
            if (snapshot.positionMs > 0L) {
                onSaveProgress(item, snapshot.positionMs, snapshot.durationMs)
            }
        }
    }

    // Controls fade out while playing.
    LaunchedEffect(controlsVisible, state.isPlaying) {
        if (controlsVisible && state.isPlaying && !locked && hud == null) {
            kotlinx.coroutines.delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(hud) {
        if (hud != null) {
            kotlinx.coroutines.delay(900)
            hud = null
        }
    }

    // A codec the viewer rescued by hand switches without a second tap. The switch is
    // live only: the saved decoder priority is left alone, so the next launch still
    // starts from the choice in Settings instead of on the software decoder because of
    // one AC-3 file.
    LaunchedEffect(state.mediaId, autoRescueCodec, state.playbackState) {
        val codec = autoRescueCodec ?: return@LaunchedEffect
        if (state.playbackState != Player.STATE_READY) return@LaunchedEffect
        if (autoRescuedFor == state.mediaId) return@LaunchedEffect
        autoRescuedFor = state.mediaId
        LogBus.i(
            LogTag.DECODER,
            "Remembered audio codec '$codec': switching the audio decoder without asking",
        )
        if (playback.rescueAudioWithFfmpeg()) {
            announceAutoRescue(codec)
        } else {
            autoRescueRefusedFor = state.mediaId
            LogBus.w(LogTag.DECODER, "The session refused the automatic switch for '$codec'")
        }
    }

    // The audio header of the item that comes next, read while this one is still playing.
    //
    // Auto-advance is the one path both earlier decisions cannot reach: the start item is
    // probed once, and then the player moves on by itself, so the next file arrives before
    // anyone has asked what its sound is. Reading it here costs one header read per
    // arrival — none for a file this process already answered for — and it is what lets
    // the switch and the warning below land on the first frames instead of after a stretch
    // of silence.
    LaunchedEffect(queue, state.queueIndex, state.repeatMode) {
        val next = when {
            state.queueIndex + 1 < queue.size -> queue[state.queueIndex + 1]
            state.repeatMode == Player.REPEAT_MODE_ALL -> queue.firstOrNull()
            else -> null
        } ?: return@LaunchedEffect
        // The answer is kept in the probe's own cache, so the arrival below is a hit and
        // opens nothing; nothing here has to hand it over.
        withContext(Dispatchers.IO) {
            AudioCodecProbe.probeMime(context, next.uri, next.probeKey())
        }
    }

    // Arrival at another item: the same decisions as the start, taken from what was read
    // ahead instead of from a header read at the last second. The first media id is
    // skipped on purpose — the early path already decided for it before it was prepared,
    // and a second decision would only announce again what the viewer has just seen.
    LaunchedEffect(state.mediaId, state.queueIndex) {
        val mediaId = state.mediaId
        val previousMediaId = arrivedMediaId
        arrivedMediaId = mediaId
        if (mediaId == null || previousMediaId == null || previousMediaId == mediaId) {
            return@LaunchedEffect
        }
        val item = queue.firstOrNull { it.id.toString() == mediaId } ?: return@LaunchedEffect
        val audit = item.auditAudio(context)
        // The warning belongs to the item that just started and to nothing else: every
        // arrival decides again, so a warning never outlives the file that earned it.
        unsupportedAudio = audit.unsupported

        if (autoRescuedFor == mediaId || autoRescueRefusedFor == mediaId) return@LaunchedEffect
        if (audioSwitchedByApp || settings.autoFfmpegCodecs.isEmpty()) return@LaunchedEffect
        val rescue = AudioSilence.autoRescueCodec(
            codec = audit.codec,
            preference = settings.decoderPreference,
            remembered = settings.autoFfmpegCodecs,
        ) ?: return@LaunchedEffect
        autoRescuedFor = mediaId
        LogBus.i(
            LogTag.DECODER,
            "Remembered audio codec '$rescue': switching the audio decoder as '${item.title}' " +
                "arrives",
        )
        if (playback.rescueAudioWithFfmpeg()) {
            audioSwitchedByApp = true
            announceAutoRescue(rescue)
        } else {
            autoRescueRefusedFor = mediaId
            LogBus.w(LogTag.DECODER, "The session refused the arrival switch for '$rescue'")
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = IntentHelpers.displayName(context, uri)
            val mime = IntentHelpers.subtitleMimeType(name)
            LogBus.i(LogTag.PLAYER, "Subtitle file picked: $name ($mime)")
            playback.attachSubtitle(
                MediaItem.SubtitleConfiguration.Builder(uri)
                    .setMimeType(mime)
                    .setLabel(name)
                    .setLanguage(java.util.Locale.getDefault().language)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build(),
            )
        }
    }

    Surface(color = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings.gesturesEnabled, locked) {
                    if (!settings.gesturesEnabled || locked) return@pointerInput
                    var dragging = false
                    var mode: HudKind? = null
                    var startX = 0f
                    var startY = 0f
                    var width = size.width.toFloat()
                    var height = size.height.toFloat()
                    var dragSum = 0f
                    var seekTarget = 0L

                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = false
                            mode = null
                            startX = offset.x
                            startY = offset.y
                            dragSum = 0f
                            seekTarget = playback.state.value.positionMs
                        },
                        onDragEnd = {
                            if (mode == HudKind.SEEK) {
                                playback.seekTo(seekTarget)
                            }
                            mode = null
                            dragging = false
                            previewTarget = null
                            hud = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragSum += dragAmount.x + dragAmount.y
                            if (!dragging && abs(dragSum) < 18f) return@detectDragGestures
                            if (!dragging) {
                                dragging = true
                                mode = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                    HudKind.SEEK
                                } else if (startX < width / 2f) {
                                    HudKind.BRIGHTNESS
                                } else {
                                    HudKind.VOLUME
                                }
                            }
                            when (mode) {
                                HudKind.SEEK -> {
                                    width = size.width.toFloat()
                                    val duration = playback.state.value.durationMs
                                    val delta = (dragAmount.x / width) * 90_000f
                                    seekTarget = (seekTarget + delta.toLong())
                                        .coerceIn(0L, duration.coerceAtLeast(0L))
                                    previewTarget = seekTarget
                                    hud = HudState(
                                        HudKind.SEEK,
                                        Formatters.duration(seekTarget),
                                        Formatters.duration(duration),
                                        preview = previewFrame.value
                                            ?.takeIf {
                                                SeekPreviewPolicy.stillApplies(it.positionMs, seekTarget)
                                            }
                                            ?.bitmap,
                                    )
                                }

                                HudKind.BRIGHTNESS -> {
                                    height = size.height.toFloat()
                                    brightness = (brightness - dragAmount.y / height)
                                        .coerceIn(0.02f, 1f)
                                    applyBrightness(activity, brightness)
                                    hud = HudState(
                                        HudKind.BRIGHTNESS,
                                        "${(brightness * 100).toInt()}%",
                                        brightnessLabel,
                                    )
                                }

                                HudKind.VOLUME -> {
                                    height = size.height.toFloat()
                                    val delta = -dragAmount.y / height * 1.5f
                                    val current = volume + delta
                                    volume = current.coerceIn(0f, 1f)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        (volume * maxVolume).toInt(),
                                        0,
                                    )
                                    hud = HudState(
                                        HudKind.VOLUME,
                                        "${(volume * 100).toInt()}%",
                                        volumeLabel,
                                    )
                                }

                                null -> Unit
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { if (!locked) controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                val forward = offset.x > size.width / 2f
                                val step = settings.seekStepMs
                                playback.seekBy(if (forward) step else -step)
                                hud = HudState(
                                    HudKind.SEEK,
                                    if (forward) seekForwardLabel else seekBackwardLabel,
                                    doubleTapSeekLabel,
                                )
                            }
                        },
                        onLongPress = {
                            if (!locked) {
                                playback.setSpeed(3f)
                                hud = HudState(HudKind.SEEK, speedBoostLabel, holdToSpeedLabel)
                            }
                        },
                    )
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = playback.controller
                    view.resizeMode = aspectMode.resizeMode
                    view.keepScreenOn = settings.keepScreenOn
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (state.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(52.dp),
                    color = Color.White,
                )
            }

            state.errorMessage?.let { message ->
                PlaybackErrorCard(
                    message = message,
                    onDismiss = { playback.togglePlayPause() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }

            val silentAudio = state.silentAudio
            // A file nothing can decode is already explained by the card below, and
            // offering FFmpeg for it would promise a rescue that cannot exist.
            val silentHintVisible = autoRescueCodec == null &&
                unsupportedAudio == null &&
                AudioSilence.shouldOfferFfmpeg(
                silent = silentAudio,
                preference = settings.decoderPreference,
                playbackState = state.playbackState,
                dismissed = silentHintClosedFor != null && silentHintClosedFor == state.mediaId,
            )
            if (silentHintVisible && silentAudio != null) {
                SilentAudioCard(
                    codec = silentAudio.codec.ifEmpty { stringResource(R.string.player_unknown) },
                    onUseFfmpeg = {
                        LogBus.i(
                            LogTag.DECODER,
                            "Silent audio hint accepted: switching the audio decoder to FFmpeg",
                        )
                        // Only a switch that was taken closes the card: a refusal has
                        // to leave the card (and the second chance) on screen.
                        if (rescueAudio(silentAudio.codec.ifEmpty { null })) {
                            silentHintClosedFor = state.mediaId
                            // The switch remembers this video too, so the automatic
                            // path must not run again once the codec is remembered.
                            autoRescuedFor = state.mediaId
                        }
                    },
                    onDismiss = {
                        silentHintClosedFor = state.mediaId
                        LogBus.i(LogTag.DECODER, "Silent audio hint dismissed by the viewer")
                    },
                    modifier = Modifier
                        // Opposite the control bar, so a hint never lands under it.
                        .align(if (controlsOnTop) Alignment.BottomCenter else Alignment.TopCenter)
                        .padding(horizontal = 16.dp)
                        .padding(
                            top = if (controlsOnTop) 0.dp else 72.dp,
                            bottom = if (controlsOnTop) 72.dp else 0.dp,
                        ),
                )
            }

            val unsupported = unsupportedAudio
            if (unsupported != null && unsupportedDismissedFor != state.mediaId) {
                UnsupportedAudioCard(
                    codec = unsupported.codec.ifEmpty { stringResource(R.string.player_unknown) },
                    // A file that will never have sound is a dead end on its own; when
                    // the queue holds another video, the card is also the way out of it.
                    // A one-item queue counts: skipping would only restart this item.
                    onPlayNext = if (state.hasNext && queue.size > 1) {
                        {
                            LogBus.i(
                                LogTag.DECODER,
                                "Skipping the video no decoder here can give sound to",
                            )
                            playback.skipToNext()
                        }
                    } else {
                        null
                    },
                    onDismiss = {
                        unsupportedDismissedFor = state.mediaId
                        LogBus.i(
                            LogTag.DECODER,
                            "Unsupported-audio warning dismissed by the viewer",
                        )
                    },
                    modifier = Modifier
                        // Opposite the control bar, so a hint never lands under it.
                        .align(if (controlsOnTop) Alignment.BottomCenter else Alignment.TopCenter)
                        .padding(horizontal = 16.dp)
                        .padding(
                            top = if (controlsOnTop) 0.dp else 72.dp,
                            bottom = if (controlsOnTop) 72.dp else 0.dp,
                        ),
                )
            }

            hud?.let { current ->
                HudOverlay(current, modifier = Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                PlayerControls(
                    state = state,
                    title = currentItem?.title ?: state.title,
                    folder = currentItem?.let { folderLabel(it.folder) }.orEmpty(),
                    controlsOnTop = controlsOnTop,
                    locked = locked,
                    scrubProgress = scrubProgress,
                    onScrub = { fraction ->
                        scrubProgress = fraction
                        val duration = playback.state.value.durationMs
                        if (settings.seekPreviewEnabled && duration > 0L) {
                            val target = (fraction * duration).toLong()
                            previewTarget = target
                            hud = HudState(
                                HudKind.SEEK,
                                Formatters.duration(target),
                                Formatters.duration(duration),
                                preview = previewFrame.value
                                    ?.takeIf { SeekPreviewPolicy.stillApplies(it.positionMs, target) }
                                    ?.bitmap,
                            )
                        }
                    },
                    onScrubFinished = {
                        if (scrubProgress >= 0f && state.durationMs > 0L) {
                            playback.seekTo((scrubProgress * state.durationMs).toLong())
                        }
                        scrubProgress = -1f
                        previewTarget = null
                        previewFrame.value = null
                        hud = null
                    },
                    onRepeat = {
                        val mode = playback.cycleRepeatMode()
                        val label = when (mode) {
                            Player.REPEAT_MODE_ALL -> repeatAllLabel
                            Player.REPEAT_MODE_ONE -> repeatOneLabel
                            else -> repeatOffLabel
                        }
                        scope.launch {
                            snackbarHost.showSnackbar(repeatAnnounceFormat.format(label))
                        }
                    },
                    onToggleLock = { locked = !locked },
                    onToggleControlsPosition = { onSetControlsOnTop(!controlsOnTop) },
                    onClose = onClose,
                    onPlayPause = { playback.togglePlayPause() },
                    onPrevious = {
                        if (state.hasPrevious) {
                            playback.skipToPrevious()
                        } else {
                            playback.seekTo(0L)
                        }
                    },
                    onNext = {
                        if (state.hasNext) {
                            playback.skipToNext()
                        }
                    },
                    onSpeed = { showSpeedDialog = true },
                    onAspect = { showAspectDialog = true },
                    onInfo = { showInfo = !showInfo },
                    onAudio = { showAudioDialog = true },
                    onSubtitles = {
                        if (state.subtitleCount > 0) {
                            playback.setSubtitlesEnabled(true)
                        } else {
                            subtitlePicker.launch(arrayOf("*/*"))
                        }
                    },
                    onRotate = {
                        activity?.requestedOrientation =
                            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                    },
                    onPip = { enterPip(activity, state) },
                )
            }

            if (showInfo) {
                TrackInfoPanel(
                    state = state,
                    decoderPreference = settings.decoderPreference,
                    decoderChips = decoderChips,
                    onPickDecoder = { preference -> applyDecoderChoice(preference) },
                    onCopyDiagnostics = { copyDiagnostics(context, playback) },
                    modifier = Modifier
                        // Same edge as the control bar: this panel is what replaces it
                        // while it is open, and the title bar on the other edge stays
                        // reachable, so the panel can always be closed again.
                        .align(if (controlsOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }

            PlayerSnackbarHost(
                hostState = snackbarHost,
                showInfo = showInfo,
                controlsOnTop = controlsOnTop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showSpeedDialog) {
        ChoiceDialog(
            title = stringResource(R.string.dialog_speed_title),
            options = SPEED_PRESETS.map { Formatters.speed(it) },
            selected = Formatters.speed(state.speed),
            onPick = { index ->
                playback.setSpeed(SPEED_PRESETS[index])
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false },
        )
    }

    if (showAspectDialog) {
        ChoiceDialog(
            title = stringResource(R.string.dialog_aspect_title),
            options = AspectMode.entries.map { stringResource(it.labelRes) },
            selected = stringResource(aspectMode.labelRes),
            onPick = { index ->
                aspectMode = AspectMode.entries[index]
                showAspectDialog = false
            },
            onDismiss = { showAspectDialog = false },
        )
    }

    if (showAudioDialog) {
        val audioTracks = state.audioTracks
        val emptyLabel = stringResource(R.string.player_waiting_tracks)
        ChoiceDialog(
            title = stringResource(R.string.dialog_audio_title),
            options = if (audioTracks.isEmpty()) listOf(emptyLabel) else audioTracks.map { it.label },
            selected = audioTracks.firstOrNull { it.selected }?.label.orEmpty(),
            onPick = { index ->
                playback.selectAudioTrack(index)
                showAudioDialog = false
            },
            onDismiss = { showAudioDialog = false },
        )
    }
}

/**
 * Back while the player overlay is up.
 *
 * The press used to reach the Activity, which then finished: leaving a video also left
 * the app. This takes the press instead, asks [onDismiss] to close one thing at a time —
 * the stream panel, a picker — and calls [onClose] only when nothing is left to close, so
 * the overlay really leaves for the list it came from. [onDismiss] answers whether it
 * closed something.
 *
 * Hoisted out of [PlayerScreen] so a device test can press the button the system presses
 * and watch a host activity survive it.
 */
@Composable
internal fun PlayerBackHandler(onDismiss: () -> Boolean, onClose: () -> Unit) {
    BackHandler {
        if (!onDismiss()) onClose()
    }
}

/**
 * The overlay above the picture: the title bar on one edge, the seek bar and the
 * buttons on the other. [controlsOnTop] swaps the two places — it is what the button
 * in the title bar changes — so the bar can move out of the way of a hand or a stand.
 *
 * Not private only so a device test can measure which edge each half lands on; there
 * is no other caller.
 */
@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    title: String,
    folder: String,
    controlsOnTop: Boolean,
    locked: Boolean,
    scrubProgress: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleControlsPosition: () -> Unit,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onRepeat: () -> Unit,
    onAspect: () -> Unit,
    onInfo: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
) {
    val panelEdge = if (controlsOnTop) Alignment.TopCenter else Alignment.BottomCenter
    val headerEdge = if (controlsOnTop) Alignment.BottomStart else Alignment.TopStart

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(headerEdge)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_close),
                    tint = Color.White,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (folder.isNotBlank()) {
                    Text(
                        text = folder,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onToggleControlsPosition) {
                Icon(
                    // The arrow points at the edge the bar is on now, so the icon is
                    // the state; the description below says what a tap will do.
                    if (controlsOnTop) {
                        Icons.Filled.VerticalAlignBottom
                    } else {
                        Icons.Filled.VerticalAlignTop
                    },
                    contentDescription = stringResource(
                        if (controlsOnTop) {
                            R.string.player_controls_move_bottom
                        } else {
                            R.string.player_controls_move_top
                        },
                    ),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onInfo) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.player_info),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onPip) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.player_pip),
                    tint = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(panelEdge)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (state.durationMs > 0L) {
                Slider(
                    value = if (scrubProgress >= 0f) scrubProgress else state.progress,
                    onValueChange = onScrub,
                    onValueChangeFinished = onScrubFinished,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
            } else {
                LinearProgressIndicator(
                    progress = { 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formatters.duration(state.positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.durationMs > 0L) {
                        Formatters.duration(state.durationMs)
                    } else {
                        stringResource(R.string.player_unknown_duration)
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (state.bufferedMs > state.positionMs) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.player_buffered,
                            Formatters.duration(state.bufferedMs - state.positionMs),
                        ),
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.queueSize > 1) {
                    Text(
                        text = stringResource(
                            R.string.player_queued_counter,
                            state.queueIndex + 1,
                            state.queueSize,
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleLock) {
                    Icon(
                        if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = stringResource(
                            if (locked) R.string.player_unlock else R.string.player_lock,
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.player_previous),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.player_next),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onAudio) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = stringResource(R.string.player_audio_tracks),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onSubtitles) {
                    Icon(
                        Icons.Filled.Subtitles,
                        contentDescription = stringResource(R.string.player_subtitles),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = stringResource(
                            when (state.repeatMode) {
                                Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                                Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                                else -> R.string.player_repeat_off
                            },
                        ),
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                            Color.White.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                IconButton(onClick = onSpeed) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = stringResource(
                            R.string.player_speed,
                            Formatters.speed(state.speed),
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onAspect) {
                    Icon(
                        Icons.Filled.AspectRatio,
                        contentDescription = stringResource(R.string.player_aspect),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onRotate) {
                    Icon(
                        Icons.Filled.ScreenRotation,
                        contentDescription = stringResource(R.string.player_rotate),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun HudOverlay(hud: HudState, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.72f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            hud.preview?.let { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.player_seek_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(220.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (hud.kind) {
                        HudKind.BRIGHTNESS -> Icons.Filled.Brightness6
                        HudKind.VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
                        HudKind.SEEK -> Icons.Filled.PlayArrow
                    },
                    contentDescription = null,
                    tint = Color.White,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = hud.primary,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = hud.secondary,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackInfoPanel(
    state: PlayerUiState,
    decoderPreference: DecoderPreference,
    decoderChips: List<String>,
    onPickDecoder: (DecoderPreference) -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.player_streams_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            InfoLine(
                stringResource(R.string.player_stream_video),
                state.videoCodec ?: stringResource(R.string.player_waiting_tracks),
            )
            InfoLine(
                stringResource(R.string.player_stream_audio),
                state.audioCodec ?: if (state.silentAudio != null) {
                    stringResource(R.string.player_audio_not_decoded)
                } else {
                    stringResource(R.string.player_waiting_tracks)
                },
            )
            InfoLine(
                stringResource(R.string.player_stream_subtitles),
                state.textCodec ?: stringResource(R.string.player_none_selected),
            )
            InfoLine(
                stringResource(R.string.player_stream_frame_size),
                if (state.videoWidth > 0) {
                    "${state.videoWidth}x${state.videoHeight}"
                } else {
                    stringResource(R.string.player_unknown)
                },
            )
            InfoLine(
                stringResource(R.string.player_stream_audio_tracks),
                // The picker is the list of audio tracks in the file, so it is also the
                // count: a second counter drifted from it when a track was not selected.
                state.audioTracks.size.toString(),
            )
            InfoLine(
                stringResource(R.string.player_stream_subtitles_loaded),
                state.subtitleCount.toString(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.player_decoder_current,
                    decoderPreferenceLabel(decoderPreference),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DecoderPreference.entries.forEachIndexed { index, entry ->
                    FilterChip(
                        selected = entry == decoderPreference,
                        onClick = { onPickDecoder(entry) },
                        label = {
                            Text(
                                text = decoderChips[index],
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCopyDiagnostics) {
                Text(stringResource(R.string.player_copy_diagnostics))
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The player's own snackbar host.
 *
 * The player is a full-screen overlay outside the app's `Scaffold`, so the host there
 * is never composed while a video is open: without this one, every announcement made
 * in the player — repeat mode, decoder switch — waits for a host that does not exist.
 * It sits above the control bar, and higher while the stream panel is open, because
 * that panel is anchored to the same edge. When the bar was moved to the top, the only
 * thing at this edge is the title bar, so the message drops closer to it. Kept as its
 * own composable so a device test can prove that it really renders a message.
 */
@Composable
fun PlayerSnackbarHost(
    hostState: SnackbarHostState,
    showInfo: Boolean,
    controlsOnTop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(
                bottom = when {
                    // With the bar at the top the stream panel follows it there, so the
                    // only thing left at this edge is the title bar.
                    controlsOnTop -> HEADER_CLEARANCE
                    showInfo -> STREAM_PANEL_CLEARANCE
                    else -> CONTROL_BAR_CLEARANCE
                },
            ),
    )
}

@Composable
private fun SilentAudioCard(
    codec: String,
    onUseFfmpeg: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_silent_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(R.string.player_silent_body, codec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            TextButton(onClick = onUseFfmpeg) {
                Text(stringResource(R.string.player_silent_action))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_silent_dismiss),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The warning for a file whose audio nothing on this device can decode.
 *
 * It offers no decoder switch, unlike [SilentAudioCard]: FFmpeg is the fix for a track the
 * platform refuses, and it is not a fix for a codec no decoder anywhere names. What the
 * card can do is say why the picture is quiet before the viewer has to wonder, and — when
 * [onPlayNext] is there, because the queue holds another video — offer the only way
 * forward that ends the silence: the next file.
 */
@Composable
private fun UnsupportedAudioCard(
    codec: String,
    onPlayNext: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.player_undecodable_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.player_undecodable_body, codec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (onPlayNext != null) {
                TextButton(onClick = onPlayNext) {
                    Text(
                        text = stringResource(R.string.player_undecodable_next),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_undecodable_dismiss),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun PlaybackErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.player_error_retry)) }
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                val selectedMark = stringResource(R.string.dialog_selected)
                options.forEachIndexed { index, label ->
                    TextButton(onClick = { onPick(index) }) {
                        Text(
                            text = if (label == selected) {
                                selectedMark.format(label)
                            } else {
                                label
                            },
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    val attributes = window.attributes
    attributes.screenBrightness = value.coerceIn(0.02f, 1f)
    window.attributes = attributes
}

private fun enterPip(activity: ComponentActivity?, state: PlayerUiState) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val width = state.videoWidth.takeIf { it > 0 } ?: 16
    val height = state.videoHeight.takeIf { it > 0 } ?: 9
    runCatching {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(true)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
        activity.enterPictureInPictureMode(params)
        LogBus.i(LogTag.UI, "Entered PiP with aspect $width:$height")
    }.onFailure {
        LogBus.w(LogTag.UI, "PiP was refused by the system", it)
    }
}

private fun copyDiagnostics(context: Context, playback: PlaybackViewModel) {
    runCatching {
        val text = Diagnostics.summary(context, playback.controller)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(
                context.getString(R.string.clipboard_diagnostics),
                text,
            ),
        )
        LogBus.i(LogTag.EXPORT, "Diagnostics copied to the clipboard (${text.length} chars)")
    }.onFailure {
        LogBus.e(LogTag.EXPORT, "Copying diagnostics failed", it)
    }
}
