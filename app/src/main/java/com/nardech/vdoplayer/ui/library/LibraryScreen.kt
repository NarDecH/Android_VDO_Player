package com.nardech.vdoplayer.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.VdoPlayerApp
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.SortOrder
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.playback.AudioCodecProbe
import com.nardech.vdoplayer.playback.AudioPreflight
import com.nardech.vdoplayer.playback.AudioVerdict
import com.nardech.vdoplayer.playback.AudioVerdictSweep
import com.nardech.vdoplayer.playback.AudioVerdicts
import com.nardech.vdoplayer.playback.probeKey
import com.nardech.vdoplayer.ui.folderLabel
import com.nardech.vdoplayer.ui.sortOrderLabel
import com.nardech.vdoplayer.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(
    container: AppContainer,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues,
    onPlay: (List<VideoItem>, Int, VideoItem) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasVideoPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result.values.any { it } || hasVideoPermission(context)
        LogBus.i(LogTag.UI, "Media permission result: $result granted=$hasPermission")
    }

    if (!hasPermission) {
        PermissionGate(
            contentPadding = contentPadding,
            onRequest = { permissionLauncher.launch(requiredPermissions() + notificationPermission()) },
        )
        return
    }

    val libraryFlow = remember { container.library.observe() }
    val videos by libraryFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var query by rememberSaveable { mutableStateOf("") }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var sortOrder by rememberSaveable { mutableStateOf(settings.sortOrder) }
    var gridMode by rememberSaveable { mutableStateOf(true) }
    var groupByFolder by rememberSaveable { mutableStateOf(settings.groupByFolder) }
    val scope = rememberCoroutineScope()
    var detailsFor by remember { mutableStateOf<VideoItem?>(null) }

    // Files whose audio this device is known not to be able to decode — the library's
    // answer to the player's no-sound warning, given before the file is opened.
    //
    // The badges are decided once per file and kept: a verdict already written is drawn
    // without opening anything, and whatever this visit has not decided yet is filled in
    // by a bounded background sweep, so the marks reach the whole library instead of only
    // the files the player happened to play. A file nobody has looked at is still drawn
    // unmarked until the sweep reaches it, which is the honest thing to show.
    var knownSilent by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(videos) {
        if (videos.isEmpty()) {
            knownSilent = emptySet()
            return@LaunchedEffect
        }
        val files = videos.mapNotNull { item ->
            val key = item.probeKey() ?: return@mapNotNull null
            key to item.uri
        }
        // What earlier visits decided, before a single file is opened.
        knownSilent = withContext(Dispatchers.Default) {
            files.asSequence()
                .filter { (key, _) -> AudioVerdicts.of(context, key) == AudioVerdict.UNDECODABLE }
                .map { (_, uri) -> uri.toString() }
                .toSet()
        }
        val uriByKey = files.toMap()
        try {
            withContext(Dispatchers.IO) {
                AudioVerdictSweep.sweep(
                    keys = files.map { (key, _) -> key },
                    known = { key -> AudioVerdicts.of(context, key) },
                    remembered = { key -> AudioCodecProbe.recall(context, key) },
                    // The probe's own cache is left alone on purpose: a sweep of the whole
                    // library would evict the handful of entries the player relies on.
                    readMimes = { key ->
                        uriByKey[key]?.let { uri ->
                            AudioCodecProbe.probeAudioMimesOrNull(context, uri)
                        }
                    },
                    outlook = { mimes -> AudioPreflight.outlook(mimes) },
                    onVerdict = { key, verdict ->
                        AudioVerdicts.remember(context, key, verdict)
                        val uri = uriByKey[key]
                        if (verdict == AudioVerdict.UNDECODABLE && uri != null) {
                            withContext(Dispatchers.Main) {
                                knownSilent = knownSilent + uri.toString()
                            }
                        }
                    },
                )
            }
        } finally {
            // Even a cancelled visit keeps what it learned, or a quick look at the library
            // would decide the same first files again on the next one.
            withContext(NonCancellable + Dispatchers.IO) { AudioVerdicts.persist() }
        }
    }

    LaunchedEffect(settings.sortOrder) { sortOrder = settings.sortOrder }
    LaunchedEffect(settings.groupByFolder) { groupByFolder = settings.groupByFolder }

    val folders = remember(videos) { videos.map { it.folder }.distinct().sorted() }
    val visible = remember(videos, query, folderFilter, sortOrder) {
        videos
            .filter { item ->
                (folderFilter == null || item.folder == folderFilter) &&
                    (query.isBlank() ||
                        item.title.contains(query, ignoreCase = true) ||
                        item.folder.contains(query, ignoreCase = true))
            }
            .let { sortOrder.apply(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.library_clear_search),
                            )
                        }
                    }
                },
            )
            SortMenu(current = sortOrder, onPick = { sortOrder = it })
            IconButton(
                onClick = {
                    val grouped = !groupByFolder
                    groupByFolder = grouped
                    LogBus.d(LogTag.UI, "Library grouped by folder: $grouped")
                    // Kept in the settings, so the next launch opens the way it was left.
                    scope.launch { container.settings.setGroupByFolder(grouped) }
                },
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.library_group_folders),
                    tint = if (groupByFolder) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = { gridMode = !gridMode }) {
                Icon(
                    if (gridMode) {
                        Icons.AutoMirrored.Filled.ViewList
                    } else {
                        Icons.Filled.GridView
                    },
                    contentDescription = stringResource(R.string.library_toggle_layout),
                )
            }
        }

        if (folders.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    FilterChip(
                        selected = folderFilter == null,
                        onClick = { folderFilter = null },
                        label = { Text(stringResource(R.string.library_folder_all, videos.size)) },
                    )
                }
                items(folders.size) { index ->
                    val folder = folders[index]
                    FilterChip(
                        selected = folderFilter == folder,
                        onClick = {
                            folderFilter = if (folderFilter == folder) null else folder
                        },
                        label = { Text(folderLabel(folder)) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            EmptyState(loading = videos.isEmpty(), modifier = Modifier.fillMaxSize())
        } else {
            // Folder sections in name order; the chosen sort still orders the files
            // inside each one, because `visible` is already sorted before it is split.
            val sections = remember(visible, groupByFolder) {
                LibrarySections.of(visible, groupByFolder) { item -> item.folder }
            }
            // Next/previous follow what is on screen, so a grouped library plays folder
            // by folder in the order drawn rather than jumping around the flat list.
            val playOrder = remember(sections, visible) {
                LibrarySections.playOrder(visible, sections)
            }
            val playIndex = remember(playOrder) {
                playOrder.withIndex().associate { (index, item) -> item.id to index }
            }
            LibraryGrid(
                items = visible,
                sections = sections,
                gridMode = gridMode,
                gridColumns = settings.gridColumns,
                knownSilent = knownSilent,
                onPlay = { item -> onPlay(playOrder, playIndex.getValue(item.id), item) },
                onLongClick = { item ->
                    detailsFor = item
                    LogBus.d(LogTag.UI, "Showing details for '${item.title}'")
                },
                // The heading is the same filter the chips set — and the same toggle — so
                // a section can be narrowed without hunting for its chip.
                onFolderPick = { folder ->
                    folderFilter = if (folderFilter == folder) null else folder
                },
                // Playing a folder queues that folder alone, in the order drawn, so it
                // does not roll on into the next section when the last file ends.
                onPlaySection = { files ->
                    files.firstOrNull()?.let { first ->
                        LogBus.i(LogTag.UI, "Playing folder '${first.folder}' from '${first.title}' (${files.size} files)")
                        onPlay(files, 0, first)
                    }
                },
            )
        }

        if (videos.isNotEmpty()) {
            Text(
                text = pluralStringResource(
                    R.plurals.library_count,
                    videos.size,
                    visible.size,
                    videos.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    detailsFor?.let { item ->
        VideoDetailsDialog(
            item = item,
            silent = item.uri.toString() in knownSilent,
            onDismiss = { detailsFor = null },
        )
    }
}

/**
 * The files themselves: the flat list when nothing is grouped, otherwise one heading and
 * its files per folder.
 *
 * Kept apart from [LibraryScreen] because only a device can show that the grouping is
 * drawn as promised — a heading that spans the whole row, names its folder, counts its
 * files and acts as that folder's filter — while the screen around it needs a media
 * permission, MediaStore and the settings store before it can draw a single tile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGrid(
    items: List<VideoItem>,
    sections: List<Pair<String, List<VideoItem>>>,
    gridMode: Boolean,
    gridColumns: Int,
    knownSilent: Set<String>,
    onPlay: (VideoItem) -> Unit,
    onLongClick: (VideoItem) -> Unit,
    onFolderPick: (String) -> Unit,
    onPlaySection: (List<VideoItem>) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (gridMode) gridColumns.coerceIn(1, 4) else 1),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (gridMode) 12.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(if (gridMode) 12.dp else 8.dp),
    ) {
        if (sections.isEmpty()) {
            itemsIndexed(items = items, key = { _, item -> item.id }) { _, item ->
                LibraryTile(
                    item = item,
                    silent = item.uri.toString() in knownSilent,
                    gridMode = gridMode,
                    onClick = { onPlay(item) },
                    onLongClick = { onLongClick(item) },
                )
            }
        } else {
            sections.forEach { (folder, files) ->
                // Sticky, so the folder you are looking at keeps naming itself while its
                // files scroll past: a long section would otherwise leave the heading far
                // above the screen and every tile unexplained.
                stickyHeader(key = "folder:$folder") {
                    FolderHeader(
                        folder = folder,
                        count = files.size,
                        onPick = { onFolderPick(folder) },
                        onPlayAll = { onPlaySection(files) },
                    )
                }
                itemsIndexed(items = files, key = { _, item -> item.id }) { _, item ->
                    LibraryTile(
                        item = item,
                        silent = item.uri.toString() in knownSilent,
                        gridMode = gridMode,
                        onClick = { onPlay(item) },
                        onLongClick = { onLongClick(item) },
                    )
                }
            }
        }
    }
}

/**
 * The heading of one folder section: its name, how many files it holds, and the whole row
 * as a shortcut to that folder's filter — the same filter the chips above set, without
 * having to find the chip again on a long list.
 *
 * It is drawn on the library's own background rather than left transparent, because the
 * heading stays pinned while the files scroll underneath it and those tiles must not show
 * through its text.
 *
 * The play button starts the folder from its first file. It sits on the heading rather
 * than somewhere else because the heading is the only place that speaks for the whole
 * section — the pinned copy stays reachable however far into the folder you have scrolled.
 */
@Composable
private fun FolderHeader(folder: String, count: Int, onPick: () -> Unit, onPlayAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onPick)
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = folderLabel(folder),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = pluralStringResource(R.plurals.library_folder_count, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onPlayAll, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.library_play_folder, folderLabel(folder)),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One file, drawn the way the current layout asks for. Shared by the flat and the grouped
 * lists so a section never renders differently from the list it sits in.
 */
@Composable
private fun LibraryTile(
    item: VideoItem,
    silent: Boolean,
    gridMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    if (gridMode) {
        VideoCard(item = item, silent = silent, onClick = onClick, onLongClick = onLongClick)
    } else {
        VideoRow(item = item, silent = silent, onClick = onClick, onLongClick = onLongClick)
    }
}

@Composable
private fun SortMenu(current: SortOrder, onPick: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.library_sort),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(sortOrderLabel(order)) },
                    onClick = {
                        onPick(order)
                        open = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    item: VideoItem,
    silent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AsyncImage(
                model = item.uri,
                imageLoader = VdoPlayerApp.imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (item.isPortrait) 0.7f else 16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        ),
                    )
                    .padding(8.dp),
            ) {
                Text(
                    text = Formatters.duration(item.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // A file nothing here can decode keeps its picture, which is why the mark is
            // about the sound rather than a "broken file" badge.
            if (silent) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(R.string.library_no_sound),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(
                    R.string.library_meta,
                    item.resolutionLabel,
                    Formatters.size(item.sizeBytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    item: VideoItem,
    silent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.uri,
                imageLoader = VdoPlayerApp.imageLoader,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (silent) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = stringResource(R.string.library_no_sound),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.library_meta,
                        Formatters.duration(item.durationMs),
                        "${item.resolutionLabel} · ${Formatters.size(item.sizeBytes)}",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.library_meta,
                        folderLabel(item.folder),
                        Formatters.relativeTime(context, item.dateAddedSeconds),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(contentPadding: PaddingValues, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onRequest) {
            Text(stringResource(R.string.permission_grant))
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (loading) R.string.library_scanning else R.string.library_empty,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (loading) R.string.library_scanning_hint else R.string.library_empty_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VideoDetailsDialog(item: VideoItem, silent: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.details_close)) }
        },
        title = { Text(item.title) },
        text = {
            Column {
                DetailRow(stringResource(R.string.details_folder), folderLabel(item.folder))
                DetailRow(stringResource(R.string.details_resolution), item.resolutionLabel)
                DetailRow(stringResource(R.string.details_duration), Formatters.duration(item.durationMs))
                DetailRow(stringResource(R.string.details_size), Formatters.size(item.sizeBytes))
                DetailRow(
                    stringResource(R.string.details_type),
                    item.mimeType ?: stringResource(R.string.player_unknown),
                )
                DetailRow(
                    stringResource(R.string.details_added),
                    Formatters.relativeTime(context, item.dateAddedSeconds),
                )
                if (silent) {
                    DetailRow(
                        stringResource(R.string.details_audio),
                        stringResource(R.string.library_no_sound_hint),
                    )
                }
                item.relativePath?.let {
                    DetailRow(stringResource(R.string.details_path), it)
                }
                DetailRow(stringResource(R.string.details_uri), item.uri.toString())
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Media permissions differ wildly across API levels; ask for the right ones. */
fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun hasVideoPermission(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/** Asked together with media access so the playback notification can be shown. */
fun notificationPermission(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
} else {
    emptyArray()
}
