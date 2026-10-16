package com.nardech.vdoplayer.transfer

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the receiving screen needs to show. */
data class ReceiveState(
    val running: Boolean = false,
    val port: Int = 0,
    val code: String = "",
    val deviceName: String = "",
    val addresses: List<String> = emptyList(),
    val advertised: Boolean = false,
) {
    val primaryAddress: String? get() = addresses.firstOrNull()
    val url: String? get() = primaryAddress?.let { "http://$it:$port" }
}

data class ReceivedFile(
    val name: String,
    val bytes: Long,
    val location: String?,
    val at: Long = System.currentTimeMillis(),
)

enum class UploadState { RUNNING, DONE, FAILED, CANCELLED }

data class UploadJob(
    val id: Long,
    val name: String,
    val peer: String,
    val state: UploadState,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val message: String? = null,
)

/**
 * Ties the pieces together for the UI: the receiving server, mDNS discovery, the files
 * that arrived, and the uploads this device started. All state is exposed as
 * `StateFlow`s so the Compose screen is a pure function of them.
 *
 * One instance lives in [com.nardech.vdoplayer.AppContainer]; the server survives
 * screen switches because the user may be waiting for a transfer to finish.
 */
class TransferManager(context: Context) {

    companion object {

        /**
         * Part of the Nearby devices permission group, so a user who already allowed
         * Bluetooth or nearby Wi-Fi is not asked again.
         */
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Id of the synthetic receiver that stands for a Wi-Fi Direct group owner. */
        const val DIRECT_PEER_ID = "direct:owner"

        /** Android 17, the release that starts enforcing [LOCAL_NETWORK_PERMISSION]. */
        private const val LOCAL_NETWORK_PERMISSION_API = 37
    }

    private val appContext = context.applicationContext
    private val client = TransferClient()
    private val sink = MediaStoreReceivedFileSink(appContext)
    private val discovery = TransferDiscovery(appContext) { message ->
        LogBus.d(LogTag.TRANSFER, message)
    }
    private val direct = DirectTransfer(
        context = appContext,
        onState = ::onDirectState,
        onLog = { message -> LogBus.d(LogTag.TRANSFER, message) },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobIds = AtomicLong()
    private val cancelledJobs = ConcurrentHashMap.newKeySet<Long>()

    private var server: TransferServer? = null

    private val _receiveState = MutableStateFlow(ReceiveState())
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    private val _received = MutableStateFlow<List<ReceivedFile>>(emptyList())
    val received: StateFlow<List<ReceivedFile>> = _received.asStateFlow()

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _uploads = MutableStateFlow<List<UploadJob>>(emptyList())
    val uploads: StateFlow<List<UploadJob>> = _uploads.asStateFlow()

    private val _directState = MutableStateFlow(DirectState())

    /** The Wi-Fi Direct side: only used when there is no router between the two phones. */
    val directState: StateFlow<DirectState> = _directState.asStateFlow()

    /** Starts receiving and advertises on the network. Returns the bound port. */
    fun startReceiving(deviceName: String): Int {
        stopReceiving("restarting")
        val name = deviceName.ifBlank { defaultDeviceName() }
        val created = TransferServer(
            deviceName = name,
            appVersion = com.nardech.vdoplayer.BuildConfig.VERSION_NAME,
            sink = sink,
            onEvent = ::onServerEvent,
        )
        server = created
        val port = created.start()
        LogBus.i(LogTag.TRANSFER, "Receiving on port $port as '$name' (code ${created.code})")
        val advertised = discovery.register(name, port) != null
        _receiveState.value = ReceiveState(
            running = true,
            port = port,
            code = created.code,
            deviceName = name,
            addresses = localAddresses(),
            advertised = advertised,
        )
        return port
    }

    fun stopReceiving(reason: String = "stopped by the user") {
        discovery.unregister()
        val current = server
        server = null
        current?.stop(reason)
        if (current != null) {
            LogBus.i(LogTag.TRANSFER, "Stopped receiving ($reason)")
        }
        _receiveState.value = ReceiveState(deviceName = _receiveState.value.deviceName)
    }

    fun rotateCode(): String? {
        val current = server ?: return null
        val code = current.rotateCode()
        _receiveState.value = _receiveState.value.copy(code = code)
        LogBus.i(LogTag.TRANSFER, "Receive code rotated to $code")
        return code
    }

    fun clearReceived() {
        _received.value = emptyList()
    }

    /** Watches the network for receivers until [stopScanning] is called. */
    fun startScanning() {
        if (_scanning.value) return
        _scanning.value = true
        _peers.value = emptyList()
        discovery.startDiscovery(
            onFound = { peer ->
                _peers.value = (_peers.value.filterNot { it.id == peer.id } + peer)
                    .sortedBy { it.name }
                LogBus.i(LogTag.TRANSFER, "Found receiver '${peer.name}' at ${peer.label}")
            },
            onLost = { id -> _peers.value = _peers.value.filterNot { it.id == id } },
        )
    }

    fun stopScanning() {
        if (!_scanning.value) return
        _scanning.value = false
        discovery.stopDiscovery()
    }

    /** The runtime permission Wi-Fi Direct needs on this release. */
    fun directPermission(): String = direct.permission()

    fun hasDirectPermission(): Boolean = direct.hasPermission()

    /**
     * Opens a Wi-Fi Direct group, which is how this phone receives when the two devices
     * share no router. The group owner is the one the sender dials, so the receiving
     * phone has to be the owner.
     */
    fun startDirectGroup() {
        direct.start()
        direct.createGroup()
        LogBus.i(LogTag.TRANSFER, "Opening a Wi-Fi Direct group for receiving")
    }

    fun stopDirectGroup() {
        direct.removeGroup()
        direct.stop()
        LogBus.i(LogTag.TRANSFER, "Closing the Wi-Fi Direct group")
    }

    /** Looks for group owners nearby, so a sender with no router can join one. */
    fun startDirectScan() {
        direct.start()
        direct.discoverPeers()
    }

    fun stopDirectScan() {
        direct.stop()
    }

    fun connectDirect(peer: DirectPeer) {
        direct.connect(peer)
    }

    private fun onDirectState(state: DirectState) {
        _directState.value = state
        refreshDirectPeer(state)
        if (state.groupFormed && state.isGroupOwner) {
            _receiveState.value = _receiveState.value.copy(
                addresses = (
                    listOfNotNull(DirectGroupPlanner.ownerAddress(state.ownerAddress)) +
                        localAddresses()
                    ).distinct(),
            )
        }
    }

    /**
     * A group we joined means the owner is the receiving phone: add it to the receiver
     * list so sending is still "pick the phone, type the code". The owner's server uses
     * the default port; if it had to fall back, the address on the receiving screen is
     * the one to type.
     */
    private fun refreshDirectPeer(state: DirectState) {
        val baseUrl = DirectGroupPlanner.senderBaseUrl(
            ownerAddress = state.ownerAddress,
            isGroupOwner = state.isGroupOwner,
            port = TransferProtocol.DEFAULT_PORT,
        )
        val others = _peers.value.filterNot { it.id == DIRECT_PEER_ID }
        _peers.value = if (baseUrl == null) {
            others
        } else {
            listOf(
                DiscoveredPeer(
                    id = DIRECT_PEER_ID,
                    name = "Wi-Fi Direct",
                    host = state.ownerAddress.orEmpty(),
                    port = TransferProtocol.DEFAULT_PORT,
                ),
            ) + others
        }
    }

    suspend fun probe(address: String): Result<TransferDeviceInfo> = withContext(Dispatchers.IO) {
        val baseUrl = TransferProtocol.baseUrlFrom(address)
            ?: return@withContext Result.failure(IllegalArgumentException("no address"))
        client.probe(baseUrl).onSuccess {
            LogBus.i(LogTag.TRANSFER, "Address $baseUrl answered: '${it.name}' v${it.version}")
        }.onFailure {
            LogBus.w(LogTag.TRANSFER, "No receiver answered at $baseUrl", it)
        }
    }

    /** Queues [uris] for upload to [baseUrl]; jobs run one after another. */
    fun upload(baseUrl: String, code: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            uris.forEach { uri ->
                val id = jobIds.incrementAndGet()
                val name = displayName(uri)
                val size = sizeOf(uri)
                _uploads.value = _uploads.value + UploadJob(
                    id = id,
                    name = name,
                    peer = baseUrl,
                    state = UploadState.RUNNING,
                    total = size,
                )
                LogBus.i(LogTag.TRANSFER, "Sending '$name' to $baseUrl")
                val result = runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        client.upload(
                            baseUrl = baseUrl,
                            code = code,
                            fileName = name,
                            sizeBytes = size,
                            source = stream,
                            shouldCancel = { id in cancelledJobs },
                            onProgress = { bytes, total ->
                                updateJob(id) { it.copy(bytes = bytes, total = total) }
                            },
                        )
                    } ?: UploadResult(false, 0L, "The file could not be opened", null)
                }.getOrElse { error ->
                    UploadResult(false, 0L, error.message ?: error.javaClass.simpleName, null)
                }

                val wasCancelled = cancelledJobs.remove(id) || result.message == "Cancelled"
                updateJob(id) {
                    it.copy(
                        state = when {
                            result.ok -> UploadState.DONE
                            wasCancelled -> UploadState.CANCELLED
                            else -> UploadState.FAILED
                        },
                        bytes = if (result.ok && it.total > 0) it.total else result.bytes,
                        message = result.location ?: result.message,
                    )
                }
                if (result.ok) {
                    LogBus.i(LogTag.TRANSFER, "'$name' delivered (${result.bytes} bytes)")
                } else {
                    LogBus.e(
                        LogTag.TRANSFER,
                        "'$name' failed after ${result.bytes} bytes: ${result.message}",
                    )
                }
            }
        }
    }

    fun cancelUpload(id: Long) {
        cancelledJobs.add(id)
    }

    /**
     * Whether this phone lets the app talk on the local network.
     *
     * Android 17 (API 37) blocks local network traffic — the receiving socket, mDNS and
     * outgoing connections alike — until [LOCAL_NETWORK_PERMISSION] is granted, and the
     * app targets SDK 37. On Android 16 and below the permission is implicit through
     * `INTERNET`, and asking for it there is actively discouraged, so the check answers
     * "granted" without touching the system.
     */
    fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_PERMISSION_API ||
            appContext.checkSelfPermission(LOCAL_NETWORK_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    fun defaultDeviceName(): String = listOfNotNull(
        Build.MANUFACTURER?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
        Build.MODEL?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { "Android device" }

    /** IPv4 addresses that a device on the same Wi-Fi can reach; needs no permission. */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun updateJob(id: Long, transform: (UploadJob) -> UploadJob) {
        _uploads.value = _uploads.value.map { job -> if (job.id == id) transform(job) else job }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        return TransferProtocol.safeFileName(
            fromProvider ?: uri.lastPathSegment,
            fallback = "shared-${System.currentTimeMillis() % 100000}.bin",
        )
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
    }.getOrDefault(-1L)

    private fun onServerEvent(event: TransferServer.Event) {
        when (event) {
            is TransferServer.Event.Started ->
                LogBus.i(LogTag.TRANSFER, "Server listening on port ${event.port}")

            is TransferServer.Event.Stopped ->
                LogBus.i(LogTag.TRANSFER, "Server stopped: ${event.reason}")

            is TransferServer.Event.UploadStarted ->
                LogBus.i(
                    LogTag.TRANSFER,
                    "Incoming '${event.fileName}' (${event.sizeBytes} bytes) from ${event.from}",
                )

            is TransferServer.Event.UploadProgress -> Unit

            is TransferServer.Event.UploadFinished -> {
                LogBus.i(
                    LogTag.TRANSFER,
                    "Received '${event.fileName}' (${event.bytes} bytes) → ${event.location}",
                )
                _received.value = listOf(
                    ReceivedFile(event.fileName, event.bytes, event.location),
                ) + _received.value
            }

            is TransferServer.Event.UploadRejected ->
                LogBus.w(
                    LogTag.TRANSFER,
                    "Rejected '${event.fileName ?: "?"}' from ${event.from}: ${event.reason}",
                )

            is TransferServer.Event.CodeRotated -> {
                _receiveState.value = _receiveState.value.copy(code = event.code)
                LogBus.w(LogTag.TRANSFER, "Code rotated to ${event.code} after failed attempts")
            }

            is TransferServer.Event.Failed ->
                LogBus.e(LogTag.TRANSFER, event.message, event.error)
        }
    }
}
