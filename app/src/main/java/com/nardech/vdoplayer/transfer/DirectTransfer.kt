package com.nardech.vdoplayer.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Address a Wi-Fi Direct group owner always has while its group is up.
 *
 * The framework hands out this address on the group owner's `p2p0` interface, which is why
 * the receiving phone can be reached without a router at all.
 */
object DirectGroupPlanner {

    const val OWNER_ADDRESS = "192.168.49.1"

    fun isUsableAddress(address: String?): Boolean =
        !address.isNullOrBlank() && address != "0.0.0.0" && address != "::"

    /**
     * The address a *sender* inside a group must use: the group owner's, never its own.
     * Null means there is nothing to dial — either this phone is the owner (it receives) or
     * the group is not up yet.
     */
    fun senderBaseUrl(ownerAddress: String?, isGroupOwner: Boolean, port: Int): String? =
        if (isGroupOwner || !isUsableAddress(ownerAddress)) null else "http://$ownerAddress:$port"

    /** The address the receiving phone should show for Wi-Fi Direct senders. */
    fun ownerAddress(receiverAddress: String?): String? =
        if (isUsableAddress(receiverAddress)) receiverAddress else OWNER_ADDRESS
}

/** A device offering itself for a Wi-Fi Direct group. */
data class DirectPeer(
    val deviceAddress: String,
    val name: String,
    val status: Int,
) {
    val label: String get() = "$name · $deviceAddress"
}

/** Why the Wi-Fi Direct side is (not) usable, as a code the UI turns into words. */
enum class DirectIssue { NONE, UNSUPPORTED, NO_PERMISSION, ERROR }

/** Everything the transfer screen needs to know about Wi-Fi Direct. */
data class DirectState(
    val supported: Boolean = true,
    val permissionGranted: Boolean = true,
    val scanning: Boolean = false,
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val ownerAddress: String? = null,
    val peers: List<DirectPeer> = emptyList(),
    val issue: DirectIssue = DirectIssue.NONE,
    val errorCode: Int? = null,
)

/**
 * Wi-Fi Direct, wrapped down to what a transfer needs: form a group or join one, then hand
 * the address of the group owner to the ordinary HTTP client.
 *
 * This is the fallback for two phones with no router between them (a hotspot works too, but
 * a hotspot needs one phone to share data). Everything about the *transfer* stays in
 * [TransferProtocol]; this class only answers "what address do I dial".
 *
 * The framework is chatty and version-dependent, so every call is guarded: a refused or
 * unsupported operation leaves [DirectState.issue] set and the app keeps working over the
 * normal Wi-Fi path.
 */
class DirectTransfer(
    context: Context,
    private val onState: (DirectState) -> Unit,
    private val onLog: (String) -> Unit,
) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = runCatching {
        manager?.initialize(appContext, appContext.mainLooper, null)
    }.getOrNull()

    private var receiver: BroadcastReceiver? = null
    private var state = DirectState()

    val isSupported: Boolean get() = manager != null && channel != null

    /** The runtime permission this release needs to touch Wi-Fi Direct at all. */
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun state(): DirectState = state

    /** Starts listening to the framework's broadcasts. Safe to call repeatedly. */
    fun start() {
        if (receiver != null) return
        if (!isSupported) {
            update { it.copy(supported = false, issue = DirectIssue.UNSUPPORTED) }
            onLog("Wi-Fi Direct is not available on this device")
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        onLog("Wi-Fi Direct ${if (enabled) "enabled" else "disabled"}")
                        if (!enabled) {
                            update { it.copy(groupFormed = false, peers = emptyList()) }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestGroupInfo()

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
                }
            }
        }
        receiver = created
        ContextCompat.registerReceiver(
            appContext,
            created,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        update { it.copy(supported = true, permissionGranted = hasPermission()) }
    }

    fun stop() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
        update {
            it.copy(
                scanning = false,
                groupFormed = false,
                isGroupOwner = false,
                ownerAddress = null,
                peers = emptyList(),
            )
        }
    }

    /**
     * Becomes the group owner, which is what the receiving phone wants.
     *
     * The `MissingPermission` warnings below are suppressed only because lint cannot follow
     * [hasPermission]: every entry point checks it before touching the framework, and the
     * framework calls are wrapped so a refusal can never crash the app.
     */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        val manager = manager ?: return unsupported()
        if (!hasPermission()) return missingPermission()
        update { it.copy(issue = DirectIssue.NONE, errorCode = null, scanning = false) }
        runCatching {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    manager.createGroup(channel, action("create the group"))
                } else {
                    onLog("A Wi-Fi Direct group already exists")
                    requestGroupInfo()
                }
            }
        }.onFailure { fail("asking for the group failed", it) }
    }

    /** Leaves the group this phone owns, so the next transfer starts clean. */
    @SuppressLint("MissingPermission")
    fun removeGroup() {
        val manager = manager ?: return unsupported()
        if (!hasPermission()) return missingPermission()
        runCatching {
            manager.removeGroup(channel, action("remove the group"))
        }.onFailure { fail("removing the group failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val manager = manager ?: return unsupported()
        if (!hasPermission()) return missingPermission()
        update {
            it.copy(
                scanning = true,
                peers = emptyList(),
                issue = DirectIssue.NONE,
                errorCode = null,
            )
        }
        runCatching {
            manager.discoverPeers(channel, action("scan for devices"))
        }.onFailure { fail("starting the scan failed", it) }
    }

    /** Joins the group owned by [peer]; the owner then receives, this phone sends. */
    @SuppressLint("MissingPermission")
    fun connect(peer: DirectPeer) {
        val manager = manager ?: return unsupported()
        if (!hasPermission()) return missingPermission()
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        onLog("Joining the Wi-Fi Direct group of '${peer.name}'")
        runCatching {
            manager.connect(channel, config, action("connect to the group"))
        }.onFailure { fail("joining the group failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = manager ?: return
        if (!hasPermission()) return
        runCatching {
            manager.requestPeers(channel) { list ->
                val peers = list.deviceList.map { device ->
                    DirectPeer(
                        deviceAddress = device.deviceAddress.orEmpty(),
                        name = device.deviceName.orEmpty().ifBlank { device.deviceAddress.orEmpty() },
                        status = device.status,
                    )
                }.filter { it.deviceAddress.isNotBlank() }
                    .sortedBy { it.name.lowercase() }
                update { it.copy(peers = peers, scanning = false) }
            }
        }.onFailure { fail("listing devices failed", it) }
    }

    @Suppress("DEPRECATION")
    private fun requestGroupInfo() {
        val manager = manager ?: return
        runCatching {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                val owner = DirectGroupPlanner.ownerAddress(
                    info.groupOwnerAddress?.hostAddress,
                ).takeIf { info.groupFormed }
                onLog(
                    if (info.groupFormed) {
                        "Wi-Fi Direct group up (owner=${info.isGroupOwner}, address=$owner)"
                    } else {
                        "Wi-Fi Direct group is not formed"
                    },
                )
                update {
                    it.copy(
                        groupFormed = info.groupFormed,
                        isGroupOwner = info.isGroupOwner,
                        ownerAddress = owner,
                        scanning = false,
                    )
                }
            }
        }.onFailure { fail("reading the group state failed", it) }
    }

    private fun action(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            onLog("Wi-Fi Direct: managed to $what")
            when (what) {
                "connect to the group" -> requestGroupInfo()
                "scan for devices" -> Unit
            }
        }

        override fun onFailure(reason: Int) {
            onLog("Wi-Fi Direct: could not $what (error $reason)")
            update { it.copy(scanning = false, issue = DirectIssue.ERROR, errorCode = reason) }
        }
    }

    private fun unsupported() {
        update { it.copy(supported = false, issue = DirectIssue.UNSUPPORTED) }
    }

    private fun missingPermission() {
        update { it.copy(permissionGranted = false, issue = DirectIssue.NO_PERMISSION) }
    }

    private fun fail(what: String, error: Throwable) {
        onLog("Wi-Fi Direct: $what (${error.message})")
        update { it.copy(scanning = false, issue = DirectIssue.ERROR) }
    }

    private fun update(transform: (DirectState) -> DirectState) {
        val updated = transform(state)
        if (updated == state) return
        state = updated
        onState(updated)
    }
}

/** Wi-Fi Direct device status codes, for the log and the peer list. */
fun describeDirectStatus(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "available"
    WifiP2pDevice.INVITED -> "invited"
    WifiP2pDevice.CONNECTED -> "connected"
    WifiP2pDevice.FAILED -> "failed"
    else -> "status($status)"
}
