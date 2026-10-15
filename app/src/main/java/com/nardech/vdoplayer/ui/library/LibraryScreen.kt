package com.nardech.vdoplayer.ui.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.VdoPlayerApp
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.SortOrder
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.util.Formatters

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
    var detailsFor by remember { mutableStateOf<VideoItem?>(null) }

    LaunchedEffect(settings.sortOrder) { sortOrder = settings.sortOrder }

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
                placeholder = { Text("Search videos") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
            )
            SortMenu(
                current = sortOrder,
                onPick = { sortOrder = it },
            )
            IconButton(onClick = { gridMode = !gridMode }) {
                Icon(
                    if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = "Toggle layout",
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
                        label = { Text("All (${videos.size})") },
                    )
                }
                items(folders.size) { index ->
                    val folder = folders[index]
                    FilterChip(
                        selected = folderFilter == folder,
                        onClick = {
                            folderFilter = if (folderFilter == folder) null else folder
                        },
                        label = { Text(folder) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            EmptyState(
                loading = videos.isEmpty(),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(settings.gridColumns.coerceIn(1, 4)),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = visible,
                        key = { _, item -> item.id },
                    ) { index, item ->
                        VideoCard(
                            item = item,
                            onClick = { onPlay(visible, index, item) },
                            onLongClick = {
                                detailsFor = item
                                LogBus.d(LogTag.UI, "Showing details for '${item.title}'")
                            },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(items = visible, key = { _, item -> item.id }) { index, item ->
                        VideoRow(
                            item = item,
                            onClick = { onPlay(visible, index, item) },
                            onLongClick = { detailsFor = item },
                        )
                    }
                }
            }
        }

        if (videos.isNotEmpty()) {
            Text(
                text = "${visible.size} of ${videos.size} videos · library updates automatically",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    detailsFor?.let { item ->
        VideoDetailsDialog(item = item, onDismiss = { detailsFor = null })
    }
}

@Composable
private fun SortMenu(current: SortOrder, onPick: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort order")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
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
                text = "${item.resolutionLabel} · ${Formatters.size(item.sizeBytes)}",
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
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
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${Formatters.duration(item.durationMs)} · ${item.resolutionLabel} · ${Formatters.size(item.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${item.folder} · ${Formatters.relativeTime(item.dateAddedSeconds)}",
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
            text = "Video access needed",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "VDO Player reads the videos already on this device. Nothing leaves your phone unless you export a log yourself.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onRequest) {
            Text("Grant access")
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
            text = if (loading) "Scanning your device…" else "No videos matched",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (loading) {
                "Looking through the device media library."
            } else {
                "Try a different folder filter or clear the search box."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VideoDetailsDialog(item: VideoItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(item.title) },
        text = {
            Column {
                DetailRow("Folder", item.folder)
                DetailRow("Resolution", item.resolutionLabel)
                DetailRow("Duration", Formatters.duration(item.durationMs))
                DetailRow("Size", Formatters.size(item.sizeBytes))
                DetailRow("Type", item.mimeType ?: "unknown")
                DetailRow("Added", Formatters.relativeTime(item.dateAddedSeconds))
                item.relativePath?.let { DetailRow("Path", it) }
                DetailRow("URI", item.uri.toString())
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(88.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
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
