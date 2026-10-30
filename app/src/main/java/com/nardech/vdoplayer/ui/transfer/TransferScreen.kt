package com.nardech.vdoplayer.ui.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.transfer.DirectIssue
import com.nardech.vdoplayer.transfer.DiscoveredPeer
import com.nardech.vdoplayer.transfer.ReceivedFile
import com.nardech.vdoplayer.transfer.TransferManager
import com.nardech.vdoplayer.transfer.TransferProtocol
import com.nardech.vdoplayer.transfer.UploadJob
import com.nardech.vdoplayer.transfer.UploadState
import com.nardech.vdoplayer.util.Formatters
import kotlinx.coroutines.launch

/**
 * Send or receive videos over Wi-Fi without leaving the app.
 *
 * The receive side runs a small HTTP server; the send side either finds it over mDNS
 * or takes a typed `ip:port`. The four digit code is the only thing standing between
 * "anyone on this network" and "the phone I am holding", so it is always required.
 */
@Composable
fun TransferScreen(
    container: AppContainer,
    settings: AppSettings,
    snackbarHost: SnackbarHostState,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = container.transfer
    val repository = container.settings

    val receive by manager.receiveState.collectAsStateWithLifecycle()
    val received by manager.received.collectAsStateWithLifecycle()
    val peers by manager.peers.collectAsStateWithLifecycle()
    val scanning by manager.scanning.collectAsStateWithLifecycle()
    val uploads by manager.uploads.collectAsStateWithLifecycle()
    val direct by manager.directState.collectAsStateWithLifecycle()

    var sendMode by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf(settings.deviceName) }
    var manualAddress by rememberSaveable { mutableStateOf("") }
    val manualAddressLabel = stringResource(R.string.transfer_manual_address)
    var code by rememberSaveable { mutableStateOf("") }
    var selectedPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var probeOutcome by remember { mutableStateOf<ProbeOutcome?>(null) }
    var localNetworkGranted by remember { mutableStateOf(manager.hasLocalNetworkPermission()) }

    val needTarget = stringResource(R.string.transfer_need_target)
    val needCode = stringResource(R.string.transfer_need_code)
    val copied = stringResource(R.string.transfer_copied)
    val permissionDenied = stringResource(R.string.transfer_permission_denied)
    val directGrantedNotice = stringResource(R.string.direct_granted)
    val directNoPermissionNotice = stringResource(R.string.direct_no_permission)
    val directIssueMessage = when (direct.issue) {
        DirectIssue.NONE -> null
        DirectIssue.UNSUPPORTED -> stringResource(R.string.direct_unsupported)
        DirectIssue.NO_PERMISSION -> stringResource(R.string.direct_no_permission)
        DirectIssue.ERROR -> stringResource(R.string.direct_error, direct.errorCode ?: -1)
    }

    var directGranted by remember { mutableStateOf(manager.hasDirectPermission()) }
    val directPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        directGranted = granted
        scope.launch {
            snackbarHost.showSnackbar(
                if (granted) directGrantedNotice else directNoPermissionNotice,
            )
        }
    }

    /** Runs [action] once Android has let the app use Wi-Fi Direct at all. */
    fun withDirectPermission(action: () -> Unit) {
        if (directGranted) action() else directPermissionRequest.launch(manager.directPermission())
    }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        localNetworkGranted = granted
        if (!granted) scope.launch { snackbarHost.showSnackbar(permissionDenied) }
    }

    /** Runs [action] once Android 17 has let the app onto the local network. */
    fun withLocalNetwork(action: () -> Unit) {
        if (localNetworkGranted) {
            action()
        } else {
            permissionRequest.launch(TransferManager.LOCAL_NETWORK_PERMISSION)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // Arriving here means the permission was already granted when the picker opened.
        localNetworkGranted = manager.hasLocalNetworkPermission()
        val peer = peers.firstOrNull { it.id == selectedPeer }
        val target = peer?.baseUrl ?: TransferProtocol.baseUrlFrom(manualAddress)
        when {
            uris.isEmpty() -> Unit
            target == null -> scope.launch { snackbarHost.showSnackbar(needTarget) }
            code.trim().length != 4 -> scope.launch { snackbarHost.showSnackbar(needCode) }
            else -> manager.upload(target, code.trim(), uris)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !sendMode,
                    onClick = { sendMode = false },
                    label = { Text(stringResource(R.string.transfer_mode_receive)) },
                )
                FilterChip(
                    selected = sendMode,
                    onClick = { sendMode = true },
                    label = { Text(stringResource(R.string.transfer_mode_send)) },
                )
            }
        }

        if (!localNetworkGranted) {
            item {
                TransferCard(stringResource(R.string.transfer_permission_title)) {
                    Text(
                        text = stringResource(R.string.transfer_permission_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            permissionRequest.launch(TransferManager.LOCAL_NETWORK_PERMISSION)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.transfer_permission_grant))
                    }
                }
            }
        }

        if (!sendMode) {
            item {
                TransferCard(stringResource(R.string.transfer_receive_title)) {
                    Text(
                        text = stringResource(R.string.transfer_receive_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.transfer_device_name)) },
                        placeholder = { Text(stringResource(R.string.transfer_device_name_hint)) },
                        trailingIcon = {
                            IconButton(onClick = { scope.launch { repository.setDeviceName(nameDraft) } }) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.transfer_save_device_name),
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    if (receive.running) {
                        OutlinedButton(
                            onClick = { manager.stopReceiving() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.transfer_stop))
                        }
                    } else {
                        Button(
                            onClick = {
                                val name = nameDraft.ifBlank { settings.deviceName }
                                withLocalNetwork { manager.startReceiving(name) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.transfer_start))
                        }
                    }
                }
            }

            if (receive.running) {
                item {
                    TransferCard(stringResource(R.string.transfer_code)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = receive.code,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 34.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { manager.rotateCode() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.transfer_new_code))
                            }
                        }
                        Text(
                            text = stringResource(R.string.transfer_code_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.transfer_address),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = receive.url ?: "—",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, receive.url.orEmpty())
                                    scope.launch { snackbarHost.showSnackbar(copied) }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.transfer_copy_address),
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                if (receive.advertised) {
                                    R.string.transfer_address_advertised
                                } else {
                                    R.string.transfer_address_not_advertised
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (receive.addresses.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = receive.addresses.joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.transfer_privacy_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                TransferCard(stringResource(R.string.direct_receive_title)) {
                    Text(
                        text = stringResource(R.string.direct_receive_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (direct.groupFormed && direct.isGroupOwner) {
                        Text(
                            text = stringResource(
                                R.string.direct_group_open,
                                direct.ownerAddress.orEmpty(),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { withDirectPermission { manager.stopDirectGroup() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.direct_close_group))
                        }
                    } else {
                        Button(
                            onClick = { withDirectPermission { manager.startDirectGroup() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.direct_open_group))
                        }
                    }
                    directIssueMessage?.let { message ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.transfer_received_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (received.isNotEmpty()) {
                        TextButton(onClick = { manager.clearReceived() }) {
                            Text(stringResource(R.string.transfer_clear_received))
                        }
                    }
                }
            }

            if (received.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.transfer_received_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(received, key = { "${it.name}-${it.at}" }) { file ->
                    ReceivedRow(file)
                }
            }
        } else {
            item {
                TransferCard(stringResource(R.string.transfer_send_title)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                if (scanning) {
                                    manager.stopScanning()
                                } else {
                                    withLocalNetwork { manager.startScanning() }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (scanning) R.string.transfer_scan_stop else R.string.transfer_scan,
                                ),
                            )
                        }
                        if (scanning) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.transfer_scanning),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input ->
                            code = input.filter { it.isDigit() }.take(4)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        label = { Text(stringResource(R.string.transfer_code)) },
                        placeholder = { Text(stringResource(R.string.transfer_manual_code)) },
                    )
                    Spacer(Modifier.height(8.dp))

                    if (peers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.transfer_peers_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        peers.forEach { peer ->
                            PeerRow(
                                peer = peer,
                                selected = peer.id == selectedPeer,
                                onSelect = { selectedPeer = peer.id },
                            )
                        }
                        selectedPeer?.let { id ->
                            val peer = peers.firstOrNull { it.id == id }
                            if (peer != null) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.transfer_selected_peer, peer.name),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.transfer_manual_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualAddress,
                            onValueChange = {
                                manualAddress = it.take(40)
                                selectedPeer = null
                                probeOutcome = null
                            },
                            // This field has a placeholder instead of a label, and a
                            // placeholder is not a name: once something is typed it is gone,
                            // leaving an anonymous edit box.
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = manualAddressLabel
                                },
                            singleLine = true,
                            placeholder = { Text(manualAddressLabel) },
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                withLocalNetwork {
                                    scope.launch {
                                        probeOutcome = manager.probe(manualAddress).fold(
                                            onSuccess = { it -> ProbeOutcome.Answered(it.name) },
                                            onFailure = { ProbeOutcome.Silent },
                                        )
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.transfer_probe))
                        }
                    }
                    probeOutcome?.let { outcome ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // Resolved here rather than in the callback, so the wording follows
                            // a language or configuration change instead of the context captured
                            // when the request was made.
                            text = when (outcome) {
                                is ProbeOutcome.Answered ->
                                    stringResource(R.string.transfer_probe_ok, outcome.deviceName)

                                ProbeOutcome.Silent -> stringResource(R.string.transfer_probe_failed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.direct_send_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.direct_send_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                if (direct.scanning) {
                                    manager.stopDirectScan()
                                } else {
                                    withDirectPermission { manager.startDirectScan() }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (direct.scanning) {
                                        R.string.direct_scan_stop
                                    } else {
                                        R.string.direct_scan
                                    },
                                ),
                            )
                        }
                        if (direct.scanning) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.direct_scanning),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (direct.peers.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.direct_none_found),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        direct.peers.forEach { device ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = device.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        withDirectPermission { manager.connectDirect(device) }
                                    },
                                ) {
                                    Text(stringResource(R.string.direct_connect))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.direct_port_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { withLocalNetwork { filePicker.launch(arrayOf("*/*")) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.transfer_pick_files))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.transfer_uploads_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (uploads.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.transfer_uploads_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uploads, key = { it.id }) { job ->
                    UploadRow(job = job, onCancel = { manager.cancelUpload(job.id) })
                }
            }
        }
    }

}

/** What the last **Check** of a typed address found. */
private sealed interface ProbeOutcome {
    data class Answered(val deviceName: String) : ProbeOutcome

    data object Silent : ProbeOutcome
}

@Composable
private fun TransferCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PeerRow(peer: DiscoveredPeer, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        // The whole row chooses the phone, not just the dot on its left: a radio button
        // by itself is announced as "radio button, not selected" with no name, because the
        // device name beside it is a separate node. Selecting the row merges both into one
        // announcement and gives a thumb the full width to aim at.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = peer.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = peer.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReceivedRow(file: ReceivedFile) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(
                    R.string.transfer_received_saved,
                    file.location ?: Formatters.size(file.bytes),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UploadRow(job: UploadJob, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = job.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(
                            when (job.state) {
                                UploadState.RUNNING -> R.string.transfer_state_running
                                UploadState.DONE -> R.string.transfer_state_done
                                UploadState.FAILED -> R.string.transfer_state_failed
                                UploadState.CANCELLED -> R.string.transfer_state_cancelled
                            },
                        ) + " · " + stringResource(
                            R.string.transfer_progress,
                            Formatters.size(job.bytes),
                            if (job.total > 0) Formatters.size(job.total) else "?",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (job.state == UploadState.RUNNING) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.transfer_cancel))
                    }
                }
            }
            if (job.state == UploadState.RUNNING) {
                Spacer(Modifier.height(6.dp))
                if (job.total > 0) {
                    LinearProgressIndicator(
                        progress = { (job.bytes.toFloat() / job.total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            (job.message?.takeIf { job.state == UploadState.FAILED })?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    if (value.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.transfer_address), value),
    )
}
