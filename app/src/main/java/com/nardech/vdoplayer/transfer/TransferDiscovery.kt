package com.nardech.vdoplayer.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** A receiver found on the local network. */
data class DiscoveredPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
) {
    val baseUrl: String get() = "http://$host:$port"
    val label: String get() = "$host:$port"
}

/**
 * Finds receivers on the same Wi-Fi network with mDNS (NSD).
 *
 * Discovery is a convenience, not a requirement: every screen that uses it also
 * accepts a typed `ip:port`, because guest networks and some routers silently drop
 * multicast traffic.
 */
class TransferDiscovery(
    context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val discovering = AtomicBoolean(false)

    @Volatile
    private var registeredServiceName: String? = null

    // The framework resolves one service at a time; extra findings queue up here.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    /** Advertises this device as a receiver. Returns the advertised service name. */
    fun register(deviceName: String, port: Int): String? {
        unregister()
        val serviceName = "VDO-${sanitize(deviceName)}-${UUID.randomUUID().toString().take(4)}"
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = TransferProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("app", TransferProtocol.APP_NAME)
            // The service name has to stay ASCII and unique, so the name the user chose
            // travels as an attribute and is what the sending phone lists.
            setAttribute("name", deviceName)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredServiceName = serviceInfo.serviceName
                onLog("Advertised as '${serviceInfo.serviceName}' on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onLog("mDNS registration failed (error $errorCode)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onLog("mDNS unregistration failed (error $errorCode)")
            }
        }
        registration = listener
        return runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            serviceName
        }.onFailure { onLog("mDNS registration threw: ${it.message}") }.getOrNull()
    }

    fun unregister() {
        val listener = registration ?: return
        registration = null
        registeredServiceName = null
        runCatching { nsdManager.unregisterService(listener) }
            .onFailure { onLog("mDNS unregistration threw: ${it.message}") }
    }

    fun startDiscovery(
        onFound: (DiscoveredPeer) -> Unit,
        onLost: (String) -> Unit,
    ) {
        if (!discovering.compareAndSet(false, true)) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onLog("Scanning the local network for receivers")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == registeredServiceName) return
                synchronized(resolveQueue) { resolveQueue.addLast(serviceInfo) }
                resolveNext(onFound, onLost)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                onLog("Stopped scanning")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog("mDNS discovery failed to start (error $errorCode)")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog("mDNS discovery failed to stop (error $errorCode)")
                runCatching { nsdManager.stopServiceDiscovery(this) }
                discovering.set(false)
            }
        }
        discovery = listener
        beginDiscovery(listener)
    }

    private fun beginDiscovery(listener: NsdManager.DiscoveryListener) {
        runCatching {
            nsdManager.discoverServices(
                TransferProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        }.onFailure {
            onLog("mDNS discovery threw: ${it.message}")
            discovering.set(false)
            discovery = null
        }
    }

    fun stopDiscovery() {
        val listener = discovery
        discovery = null
        discovering.set(false)
        synchronized(resolveQueue) { resolveQueue.clear() }
        if (listener != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { onLog("mDNS discovery stop threw: ${it.message}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(
        onFound: (DiscoveredPeer) -> Unit,
        onLost: (String) -> Unit,
    ) {
        if (!resolving.compareAndSet(false, true)) return
        val next = synchronized(resolveQueue) { resolveQueue.removeFirstOrNull() }
        if (next == null) {
            resolving.set(false)
            return
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // A device that stops advertising lands here; the queue moves on.
                onLog("Could not resolve '${serviceInfo.serviceName}' (error $errorCode)")
                resolving.set(false)
                resolveNext(onFound, onLost)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null && serviceInfo.port > 0) {
                    onFound(
                        DiscoveredPeer(
                            id = serviceInfo.serviceName,
                            name = serviceInfo.attributes["name"]
                                ?.toString(Charsets.UTF_8)
                                ?.takeIf { it.isNotBlank() }
                                ?: serviceInfo.serviceName,
                            host = host,
                            port = serviceInfo.port,
                        ),
                    )
                }
                resolving.set(false)
                resolveNext(onFound, onLost)
            }
        }
        // resolveService is deprecated from API 34 but still the only call that works
        // down to minSdk 26 without a second code path.
        runCatching {
            nsdManager.resolveService(next, listener)
        }.onFailure {
            onLog("Resolving '${next.serviceName}' threw: ${it.message}")
            resolving.set(false)
            resolveNext(onFound, onLost)
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(20).ifBlank { "device" }
}
