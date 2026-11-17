package org.mindenit.glidephone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-only discovery via UDP broadcast (port 1716) and NSD. Service names
 * are unverified identifiers; they must never be treated as paired devices or used
 * to authorize data transfer.
 */
internal class NearbyDiscovery(context: Context, private val onUpdate: (Set<String>, Boolean, Boolean) -> Unit) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val candidates = ConcurrentHashMap<String, Candidate>()
    private val running = AtomicBoolean(false)
    private var udpSocket: DatagramSocket? = null
    private var receiverThread: Thread? = null
    private var resolving = false
    private val nameResolveTried = mutableSetOf<String>()

    internal data class Candidate(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val protocolVersion: Int?
    )

    fun deviceName(id: String): String? = candidates[id]?.name

    fun start() {
        if (running.getAndSet(true)) return
        services.clear()
        candidates.clear()
        nameResolveTried.clear()

        try {
            multicastLock = wifi.createMulticastLock("glide-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        // Bind UDP socket on port 1716 for KDE Connect discovery
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(1716))
            }
        } catch (_: Exception) {
            try {
                DatagramSocket().apply { broadcast = true }
            } catch (_: Exception) {
                null
            }
        }
        udpSocket = socket

        if (socket != null) {
            // Receiver thread: listens on UDP 1716 for KDE Connect identity packets
            receiverThread = Thread({
                val selfId = runCatching { LocalIdentity().loadOrCreate().id }.getOrNull()
                val buffer = ByteArray(8192)
                while (running.get() && !socket.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val peer = IdentityPacket.decode(text)
                        if (peer != null && peer.id != selfId && packet.address.isPrivateLan() && (peer.tcpPort == null || peer.tcpPort in 1716..1764)) {
                            val host = packet.address.hostAddress ?: ""
                            val port = peer.tcpPort ?: 1716
                            val candidate = Candidate(peer.id, peer.name, host, port, peer.version)
                            val prev = candidates.put(peer.id, candidate)
                            if (prev != candidate) {
                                postUpdate(active = true, failed = false)
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "GlideDiscovery-UDPReceiver").apply { isDaemon = true; start() }

        }

        // Also start NSD discovery for interoperability
        val currentNsd = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = post(this) {
                postUpdate(active = true, failed = false)
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = post(this) {
                val id = serviceInfo.serviceName
                if (id.matches(Regex("^[a-zA-Z0-9_-]{32,38}$"))) {
                    services[id] = serviceInfo
                    postUpdate(active = true, failed = false)
                    resolveNames()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = post(this) {
                services.remove(serviceInfo.serviceName)
                if (!candidates.containsKey(serviceInfo.serviceName)) {
                    postUpdate(active = true, failed = false)
                }
            }
            override fun onDiscoveryStopped(serviceType: String) = post(this) {
                nsdListener = null
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = post(this) {
                nsdListener = null
                if (udpSocket == null) {
                    stop()
                    postUpdate(active = false, failed = true)
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = post(this) {
                nsdListener = null
            }
        }
        nsdListener = currentNsd

        try {
            nsd.discoverServices("_kdeconnect._udp", NsdManager.PROTOCOL_DNS_SD, currentNsd)
        } catch (_: RuntimeException) {
            nsdListener = null
        }

        postUpdate(active = true, failed = false)
    }

    /** Resolve discovered NSD services in the background so their names can be shown. */
    private fun resolveNames() {
        if (!running.get() || resolving) return
        val next = services.keys.firstOrNull { !candidates.containsKey(it) && nameResolveTried.add(it) } ?: return
        resolve(next) { resolveNames() }
    }

    fun resolve(id: String, onResult: (Candidate?) -> Unit) = resolve(id, 0, onResult)

    @Suppress("DEPRECATION")
    private fun resolve(id: String, attempt: Int, onResult: (Candidate?) -> Unit) {
        val cached = candidates[id]
        if (cached != null) {
            handler.post { onResult(cached) }
            return
        }

        val service = services[id]
        if (service == null) {
            handler.post { onResult(null) }
            return
        }
        val owner = nsdListener
        if (owner == null) {
            handler.post { onResult(null) }
            return
        }
        if (resolving) {
            // Another resolve (usually a background name lookup) is in flight: wait for it.
            if (attempt < 20) handler.postDelayed({ resolve(id, attempt + 1, onResult) }, 250)
            else handler.post { onResult(null) }
            return
        }
        resolving = true
        try {
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    handler.post {
                        if (nsdListener === owner) {
                            resolving = false
                            onResult(null)
                        }
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handler.post {
                        if (nsdListener !== owner) return@post
                        resolving = false
                        val address = serviceInfo.host
                        val attrs = serviceInfo.attributes
                        val advertisedId = attrs["id"]?.toString(Charsets.UTF_8)
                        val name = attrs["name"]?.toString(Charsets.UTF_8)
                            ?.filterNot { it.isISOControl() }?.trim()?.take(64)
                        val version = attrs["protocol"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                        val isLocal = address != null && address.isPrivateLan()
                        val candidate = if (id in services && serviceInfo.serviceName == id &&
                            (advertisedId == null || advertisedId == id) && isLocal &&
                            serviceInfo.port in 1716..1764) {
                            Candidate(id, name?.takeIf { it.isNotBlank() } ?: "Пристрій поблизу",
                                address.hostAddress.orEmpty(), serviceInfo.port, version)
                        } else null
                        if (candidate != null) {
                            candidates[id] = candidate
                            postUpdate(active = true, failed = false)
                        }
                        onResult(candidate)
                    }
                }
            })
        } catch (_: RuntimeException) {
            resolving = false
            handler.post { onResult(null) }
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { udpSocket?.close() }
        udpSocket = null
        receiverThread?.interrupt()
        receiverThread = null

        val currentNsd = nsdListener
        nsdListener = null
        resolving = false
        if (currentNsd != null) {
            try {
                nsd.stopServiceDiscovery(currentNsd)
            } catch (_: RuntimeException) {}
        }
        releaseLock()
        services.clear()
        candidates.clear()
        postUpdate(active = false, failed = false)
    }

    private fun post(owner: NsdManager.DiscoveryListener, action: () -> Unit) {
        handler.post { if (running.get() && nsdListener === owner) action() }
    }

    private fun postUpdate(active: Boolean, failed: Boolean) {
        handler.post {
            if (active != running.get()) return@post
            onUpdate((services.keys + candidates.keys).toSet(), active, failed)
        }
    }

    private fun releaseLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}
