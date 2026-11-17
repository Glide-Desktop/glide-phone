package org.mindenit.glidephone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import java.util.LinkedList

/**
 * mDNS / DNS-SD half of the LAN backend, after KDE Connect's MdnsDiscovery: announce this
 * phone as a `_kdeconnect._udp` service so peers on networks that drop UDP broadcasts can
 * still find it, and run short discovery rounds whose results get our identity packet
 * (the peer then opens the link, exactly as after a broadcast).
 */
internal class LanMdns(
    context: Context,
    private val identity: () -> Pair<String, String>?, // (device id, device name)
    private val onPeerResolved: (id: String, address: InetAddress) -> Unit,
    private val isKnown: (id: String) -> Boolean
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val multicastLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("glide-mdns").apply { setReferenceCounted(false) }
    private val handler = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = LinkedList<NsdServiceInfo>()
    private val stopDiscoveryRunnable = Runnable { stopDiscovering() }

    @Synchronized
    fun startAnnouncing(tcpPort: Int) {
        if (registration != null) return
        val (id, name) = identity() ?: return
        val info = NsdServiceInfo().apply {
            serviceName = id
            serviceType = SERVICE_TYPE
            port = tcpPort
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("type", "phone")
            setAttribute("protocol", IdentityPacket.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                synchronized(this@LanMdns) { if (registration === this) registration = null }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            registration = null
        }
    }

    @Synchronized
    fun stopAnnouncing() {
        val listener = registration ?: return
        registration = null
        runCatching { nsd.unregisterService(listener) }
    }

    /** Re-register so a renamed device is announced under its new name. */
    fun reannounce(tcpPort: Int) {
        stopAnnouncing()
        handler.postDelayed({ startAnnouncing(tcpPort) }, 500)
    }

    @Synchronized
    fun startDiscovering() {
        if (discovery != null) return
        val selfId = identity()?.first
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.serviceName
                if (id == selfId || isKnown(id)) return
                enqueueResolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = clear(this)
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = clear(this)
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { multicastLock.acquire() }
        handler.postDelayed(stopDiscoveryRunnable, DISCOVERY_TIMEOUT_MS)
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: RuntimeException) {
            clear(listener)
        }
    }

    @Synchronized
    fun stopDiscovering() {
        handler.removeCallbacks(stopDiscoveryRunnable)
        val listener = discovery ?: return
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: RuntimeException) {
        } finally {
            clear(listener)
        }
    }

    /** Start a fresh discovery round (e.g. after a network change). */
    fun rediscover() {
        stopDiscovering()
        startDiscovering()
    }

    fun stop() {
        stopAnnouncing()
        stopDiscovering()
    }

    @Synchronized
    private fun clear(listener: NsdManager.DiscoveryListener) {
        if (discovery !== listener) return
        handler.removeCallbacks(stopDiscoveryRunnable)
        discovery = null
        if (multicastLock.isHeld) runCatching { multicastLock.release() }
    }

    // NsdManager resolves one service at a time (otherwise FAILURE_ALREADY_ACTIVE).
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.addLast(info)
            if (resolveQueue.size == 1) resolveNext()
        }
    }

    private fun resolveNext() {
        val next = synchronized(resolveQueue) { resolveQueue.firstOrNull() } ?: return
        @Suppress("DEPRECATION")
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = done()
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try {
                        pickAddress(serviceInfo)?.let { onPeerResolved(serviceInfo.serviceName, it) }
                    } finally {
                        done()
                    }
                }
            })
        } catch (_: RuntimeException) {
            done()
        }
    }

    private fun done() {
        synchronized(resolveQueue) { resolveQueue.pollFirst() }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): InetAddress? {
        val candidates = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        return candidates.firstOrNull { it is Inet4Address && (it.isSiteLocalAddress || it.isLinkLocalAddress) }
            ?: candidates.firstOrNull { it.isSiteLocalAddress || it.isLinkLocalAddress }
    }

    private companion object {
        const val SERVICE_TYPE = "_kdeconnect._udp"
        const val DISCOVERY_TIMEOUT_MS = 30_000L
    }
}
