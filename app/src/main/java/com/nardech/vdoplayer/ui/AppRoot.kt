package com.nardech.vdoplayer.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.HistoryEntry
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.playback.PlaybackViewModel
import com.nardech.vdoplayer.ui.library.LibraryScreen
import com.nardech.vdoplayer.ui.logs.LogsScreen
import com.nardech.vdoplayer.ui.player.PlayerScreen
import com.nardech.vdoplayer.ui.recent.RecentScreen
import com.nardech.vdoplayer.ui.settings.SettingsScreen
import com.nardech.vdoplayer.ui.transfer.TransferScreen
import com.nardech.vdoplayer.util.IntentHelpers
import kotlinx.coroutines.launch

enum class AppTab(val labelRes: Int, val icon: ImageVector) {
    LIBRARY(R.string.tab_library, Icons.Filled.VideoLibrary),
    RECENT(R.string.tab_recent, Icons.Filled.History),
    TRANSFER(R.string.tab_transfer, Icons.Filled.WifiTethering),
    LOGS(R.string.tab_logs, Icons.Filled.BugReport),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(
    container: AppContainer,
    settings: AppSettings,
    sharedMediaUri: String?,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val playback = remember { PlaybackViewModel(app) }
    LaunchedEffect(Unit) {
        playback.connect()
    }
    DisposableEffect(Unit) {
        onDispose { playback.releaseController() }
    }

    var tab by rememberSaveable { mutableStateOf(AppTab.LIBRARY) }
    var queue by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var startIndex by remember { mutableIntStateOf(0) }
    var playerVisible by remember { mutableStateOf(false) }

    val history by container.history.recent.collectAsStateWithLifecycle(initialValue = emptyList())
    val resumePositions = remember(history) {
        history.associate { it.uri to it.positionMs }
    }

    // Media handed over by another app is queued first, then opened in the player.
    var incomingItem by remember { mutableStateOf<VideoItem?>(null) }
    LaunchedEffect(sharedMediaUri) {
        val raw = sharedMediaUri ?: return@LaunchedEffect
        val uri = Uri.parse(raw)
        val name = IntentHelpers.displayName(context, uri)
        val known = container.library.load().firstOrNull { it.uri == uri }
        incomingItem = known ?: VideoItem.fromUri(uri, name)
        LogBus.i(LogTag.UI, "Incoming media queued: '$name' from $uri")
    }
    LaunchedEffect(incomingItem) {
        val item = incomingItem ?: return@LaunchedEffect
        queue = listOf(item)
        startIndex = 0
        playerVisible = true
        incomingItem = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (playerVisible && queue.isNotEmpty()) {
            PlayerScreen(
                playback = playback,
                queue = queue,
                startIndex = startIndex,
                settings = settings,
                snackbarHost = snackbarHost,
                resumePositions = resumePositions,
                onSaveProgress = { item, positionMs, durationMs ->
                    scope.launch {
                        container.history.record(
                            HistoryEntry(
                                uri = item.uri.toString(),
                                title = item.title,
                                folder = item.folder,
                                durationMs = durationMs,
                                positionMs = positionMs,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onSetDecoderPreference = { preference ->
                    // Stored as well as sent: the service applies it to the running
                    // player, and the setting decides what the next player uses.
                    scope.launch { container.settings.setDecoderPreference(preference) }
                },
                onRememberAudioCodec = { codec ->
                    // The next file with this codec switches by itself (see AudioSilence).
                    scope.launch { container.settings.rememberFfmpegCodec(codec) }
                },
                onForgetAudioCodec = { codec ->
                    // Undo from the player's own message: the switch stays this time,
                    // but the codec stops switching by itself from now on.
                    scope.launch { container.settings.forgetFfmpegCodec(codec) }
                },
                onClose = {
                    LogBus.i(LogTag.UI, "Closing player screen")
                    playback.stop()
                    playerVisible = false
                },
            )
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = { Icon(entry.icon, contentDescription = stringResource(entry.labelRes)) },
                                label = { Text(stringResource(entry.labelRes)) },
                            )
                        }
                    }
                },
            ) { padding ->
                when (tab) {
                    AppTab.LIBRARY -> LibraryScreen(
                        container = container,
                        settings = settings,
                        snackbarHost = snackbarHost,
                        contentPadding = padding,
                        onPlay = { items, index, item ->
                            queue = items
                            startIndex = index
                            playerVisible = true
                            LogBus.i(LogTag.UI, "Launching player for '${item.title}' at $index of ${items.size}")
                        },
                    )

                    AppTab.RECENT -> RecentScreen(
                        container = container,
                        history = history,
                        contentPadding = padding,
                        onPlay = { entry ->
                            val item = VideoItem.fromUri(Uri.parse(entry.uri), entry.title)
                            queue = listOf(item)
                            startIndex = 0
                            playerVisible = true
                        },
                    )

                    AppTab.TRANSFER -> TransferScreen(
                        container = container,
                        settings = settings,
                        snackbarHost = snackbarHost,
                        contentPadding = padding,
                    )

                    AppTab.LOGS -> LogsScreen(
                        container = container,
                        settings = settings,
                        snackbarHost = snackbarHost,
                        contentPadding = padding,
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        container = container,
                        settings = settings,
                        contentPadding = padding,
                    )
                }
            }
        }
    }
}

