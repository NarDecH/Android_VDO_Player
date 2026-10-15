package com.nardech.vdoplayer.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogEntry
import com.nardech.vdoplayer.logging.LogExportFormat
import com.nardech.vdoplayer.logging.LogLevel
import com.nardech.vdoplayer.logging.LogShare
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(
    container: AppContainer,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val version by LogBus.version.collectAsStateWithLifecycle(initialValue = 0)
    var frozen by remember { mutableStateOf(false) }
    var frozenSnapshot by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.DEBUG) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    val allEntries = if (frozen) frozenSnapshot else remember(version) { LogBus.snapshot() }
    val visible = remember(allEntries, minLevel, selectedTag, query) {
        allEntries.filter { entry ->
            entry.level.priority >= minLevel.priority &&
                (selectedTag == null || entry.tag.name == selectedTag) &&
                (query.isBlank() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.tag.name.contains(query, ignoreCase = true))
        }
    }

    // Live tail: keep the newest line in view unless the user froze the list.
    LaunchedEffect(visible.size, frozen) {
        if (!frozen && visible.isNotEmpty()) {
            listState.scrollToItem(visible.lastIndex)
        }
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
                placeholder = { Text("Filter log text") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                        }
                    }
                },
            )
            IconButton(onClick = {
                frozen = !frozen
                if (frozen) {
                    frozenSnapshot = LogBus.snapshot()
                }
            }) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = if (frozen) "Resume live tail" else "Freeze list",
                    tint = if (frozen) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = minLevel == level,
                    onClick = { minLevel = level },
                    label = { Text(level.name) },
                )
            }
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = selectedTag == null,
                onClick = { selectedTag = null },
                label = { Text("All tags") },
            )
            LogTag.entries.forEach { tag ->
                FilterChip(
                    selected = selectedTag == tag.name,
                    onClick = { selectedTag = if (selectedTag == tag.name) null else tag.name },
                    label = { Text(tag.name) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                scope.launch {
                    exportLogs(
                        context = context,
                        container = container,
                        format = settings.logExportFormat,
                        includeDiagnostics = settings.includeDiagnosticsInExport,
                        share = true,
                        snackbarHost = snackbarHost,
                        entries = LogBus.snapshot(),
                    )
                }
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text("Share")
            }
            TextButton(onClick = {
                scope.launch {
                    exportLogs(
                        context = context,
                        container = container,
                        format = settings.logExportFormat,
                        includeDiagnostics = settings.includeDiagnosticsInExport,
                        share = false,
                        snackbarHost = snackbarHost,
                        entries = LogBus.snapshot(),
                    )
                }
            }) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Text("Save")
            }
            TextButton(onClick = {
                copyVisible(context, visible)
                scope.launch { snackbarHost.showSnackbar("${visible.size} log lines copied") }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text("Copy")
            }
            TextButton(onClick = {
                LogBus.clear()
                frozenSnapshot = emptyList()
                frozen = false
            }) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text("Clear")
            }
        }

        Text(
            text = buildString {
                append("${visible.size} of ${allEntries.size} lines")
                append(" · file level ${settings.logLevel.name}")
                append(" · ")
                append(LogExportFormat.entries.firstOrNull { it == settings.logExportFormat }?.extension?.uppercase() ?: "ZIP")
                append(" export")
                append(if (frozen) " · list frozen" else " · live")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No log lines match", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Lower the level filter or clear the search box.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visible, key = { it.timestamp.toString() + it.message.hashCode() }) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = levelColor(entry.level),
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.timeText(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            entry.throwable?.let { trace ->
                Text(
                    text = trace,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.VERBOSE -> Color(0xFF7A7A7A)
    LogLevel.DEBUG -> Color(0xFF4A90D9)
    LogLevel.INFO -> Color(0xFF2E9E6B)
    LogLevel.WARN -> Color(0xFFD08B00)
    LogLevel.ERROR -> Color(0xFFD24545)
}

private suspend fun exportLogs(
    context: Context,
    container: AppContainer,
    format: LogExportFormat,
    includeDiagnostics: Boolean,
    share: Boolean,
    snackbarHost: SnackbarHostState,
    entries: List<LogEntry>,
) {
    runCatching {
        val result = LogShare.export(
            context = context,
            format = format,
            includeDiagnostics = includeDiagnostics,
            player = null,
            entries = entries,
        )
        if (share) {
            val intent = LogShare.shareIntent(context, result, format)
            context.startActivity(
                Intent.createChooser(intent, "Share VDO Player diagnostics").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            snackbarHost.showSnackbar("Sharing ${result.file.name} — ${result.summary}")
        } else {
            val uri = LogShare.saveToDownloads(context, result, format)
            snackbarHost.showSnackbar(
                if (uri != null) {
                    "Saved ${result.file.name} to Downloads"
                } else {
                    "Could not write to Downloads, use Share instead"
                },
            )
        }
    }.onFailure {
        LogBus.e(LogTag.EXPORT, "Log export failed", it)
        snackbarHost.showSnackbar("Export failed: ${it.message}")
    }
}

private fun copyVisible(context: Context, entries: List<LogEntry>) {
    val text = entries.joinToString("\n") { it.toLine() }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("VDO Player logs", text))
    LogBus.i(LogTag.EXPORT, "Copied ${entries.size} log lines to the clipboard")
}
