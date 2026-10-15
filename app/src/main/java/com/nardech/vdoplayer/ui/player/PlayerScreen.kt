package com.nardech.vdoplayer.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.Diagnostics
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.playback.PlaybackViewModel
import com.nardech.vdoplayer.playback.PlayerUiState
import com.nardech.vdoplayer.util.Formatters
import com.nardech.vdoplayer.util.IntentHelpers
import kotlin.math.abs

private enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit to screen", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Zoom to fill (crop)", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    FIXED_WIDTH("Match width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
}

private enum class HudKind { BRIGHTNESS, VOLUME, SEEK }

private data class HudState(val kind: HudKind, val primary: String, val secondary: String)

private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
fun PlayerScreen(
    playback: PlaybackViewModel,
    queue: List<VideoItem>,
    startIndex: Int,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    resumePositions: Map<String, Long>,
    onSaveProgress: (VideoItem, Long, Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val configuration = LocalConfiguration.current
    val state by playback.state.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var aspectMode by remember { mutableStateOf(AspectMode.FIT) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showAspectDialog by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<HudState?>(null) }
    var scrubProgress by remember { mutableFloatStateOf(-1f) }

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

    // Start (or restart) playback whenever a new queue/index arrives.
    LaunchedEffect(queue, startIndex) {
        if (queue.isNotEmpty()) {
            playback.play(queue, startIndex, resumePositions = resumePositions)
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
                                    hud = HudState(
                                        HudKind.SEEK,
                                        Formatters.duration(seekTarget),
                                        Formatters.duration(duration),
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
                                        "Brightness",
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
                                        "Volume",
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
                                    if (forward) "+${step / 1000}s" else "-${step / 1000}s",
                                    "Double tap seek",
                                )
                            }
                        },
                        onLongPress = {
                            if (!locked) {
                                playback.setSpeed(3f)
                                hud = HudState(HudKind.SEEK, "3x", "Hold to speed up")
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
                    folder = currentItem?.folder.orEmpty(),
                    locked = locked,
                    scrubProgress = scrubProgress,
                    onScrub = { scrubProgress = it },
                    onScrubFinished = {
                        if (scrubProgress >= 0f && state.durationMs > 0L) {
                            playback.seekTo((scrubProgress * state.durationMs).toLong())
                        }
                        scrubProgress = -1f
                    },
                    onToggleLock = { locked = !locked },
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
                    onCopyDiagnostics = { copyDiagnostics(context, playback) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }

    if (showSpeedDialog) {
        ChoiceDialog(
            title = "Playback speed",
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
            title = "Aspect ratio",
            options = AspectMode.entries.map { it.label },
            selected = aspectMode.label,
            onPick = { index ->
                aspectMode = AspectMode.entries[index]
                showAspectDialog = false
            },
            onDismiss = { showAspectDialog = false },
        )
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    title: String,
    folder: String,
    locked: Boolean,
    scrubProgress: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onToggleLock: () -> Unit,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeed: () -> Unit,
    onAspect: () -> Unit,
    onInfo: () -> Unit,
    onSubtitles: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close player", tint = Color.White)
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
            IconButton(onClick = onInfo) {
                Icon(Icons.Filled.Info, contentDescription = "Track info", tint = Color.White)
            }
            IconButton(onClick = onPip) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = "Picture in picture",
                    tint = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                    text = if (state.durationMs > 0L) Formatters.duration(state.durationMs) else "live",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (state.bufferedMs > state.positionMs) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "+${Formatters.duration(state.bufferedMs - state.positionMs)} buffered",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.queueSize > 1) {
                    Text(
                        text = "${state.queueIndex + 1}/${state.queueSize}",
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
                        contentDescription = "Lock controls",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White)
                }
                IconButton(onClick = onSubtitles) {
                    Icon(Icons.Filled.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                }
                IconButton(onClick = onSpeed) {
                    Icon(Icons.Filled.Speed, contentDescription = "Speed ${Formatters.speed(state.speed)}", tint = Color.White)
                }
                IconButton(onClick = onAspect) {
                    Icon(Icons.Filled.AspectRatio, contentDescription = "Aspect ratio", tint = Color.White)
                }
                IconButton(onClick = onRotate) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
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
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

@Composable
private fun TrackInfoPanel(
    state: PlayerUiState,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Streams", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            InfoLine("Video", state.videoCodec ?: "waiting for tracks")
            InfoLine("Audio", state.audioCodec ?: "waiting for tracks")
            InfoLine("Subtitles", state.textCodec ?: "none selected")
            InfoLine(
                "Frame size",
                if (state.videoWidth > 0) "${state.videoWidth}x${state.videoHeight}" else "unknown",
            )
            InfoLine("Audio tracks", state.audioTrackCount.toString())
            InfoLine("Subtitles loaded", state.subtitleCount.toString())
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCopyDiagnostics) {
                Text("Copy full diagnostics")
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

@Composable
private fun PlaybackErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "This file could not be played",
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
            TextButton(onClick = onDismiss) { Text("Retry") }
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
                options.forEachIndexed { index, label ->
                    TextButton(onClick = { onPick(index) }) {
                        Text(
                            text = if (label == selected) "✓  $label" else label,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VDO Player diagnostics", text))
        LogBus.i(LogTag.EXPORT, "Diagnostics copied to the clipboard (${text.length} chars)")
    }.onFailure {
        LogBus.e(LogTag.EXPORT, "Copying diagnostics failed", it)
    }
}
