package org.mindenit.glidephone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the phone discoverable (UDP identity broadcast + persistent TCP listener on
 * port 1716) and manages every link to other devices: untrusted links that are
 * available for pairing, and trusted connections that exchange plugin packets.
 * Callbacks are delivered on the main thread.
 */
internal class PairingConnector(context: Context, private val onEvent: (Event) -> Unit) : Closeable {
    sealed interface Event {
        data object Connecting : Event
        data class Verify(val id: String, val name: String, val code: String) : Event
        data class Connected(val id: String, val name: String) : Event
        data class Disconnected(val id: String) : Event
        data object Failed : Event
        /** The set of reachable-but-unpaired devices changed. */
        data object PeersChanged : Event
        data class RingRequest(val name: String) : Event
        data class ClipboardReceived(val content: String) : Event
        data class PeerBattery(val id: String, val charge: Int, val charging: Boolean) : Event
        data class PingReceived(val name: String, val message: String) : Event
        data class ShareReceived(val id: String, val text: String?, val url: String?) : Event
        data class FileReceived(val id: String, val fileName: String, val success: Boolean) : Event
        data class CommandList(val id: String, val commands: List<RemoteCommand>) : Event
        data class RemoteNotification(val id: String, val notificationId: String, val appName: String, val title: String, val text: String, val cancel: Boolean) : Event
        data class MediaPlayers(val id: String, val players: List<String>) : Event
        data class MediaUpdate(val id: String, val state: MediaState) : Event
        data class VolumeSinks(val id: String, val sinks: List<VolumeSink>) : Event
    }

    data class RemoteCommand(val key: String, val name: String, val command: String)

    data class MediaState(
        val player: String,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val isPlaying: Boolean = false,
        val volume: Int = -1,
        val position: Long = 0,
        val length: Long = -1,
        val canPlay: Boolean = true,
        val canPause: Boolean = true,
        val canGoNext: Boolean = true,
        val canGoPrevious: Boolean = true,
        val canSeek: Boolean = false
    )

    data class VolumeSink(
        val name: String,
        val description: String,
        val volume: Int,
        val maxVolume: Int,
        val muted: Boolean,
        val enabled: Boolean
    )

    private class PendingPeer(val name: String, val socket: Socket, val session: PairingSession)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(128))
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)

    @Volatile private var deviceName: String = SetupPreferences(app).deviceName
    @Volatile private var server: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null
    private val lastDial = ConcurrentHashMap<String, Long>()
    private val advertising = AtomicBoolean(false)
    @Volatile private var broadcaster: Thread? = null

    private val connections = ConcurrentHashMap<String, TrustedConnection>()
    private val pending = ConcurrentHashMap<String, PendingPeer>()
    /** Peers the user asked to pair with before their link was established. */
    private val outgoingIntents = ConcurrentHashMap<String, Long>()
    private val mediaStates = ConcurrentHashMap<String, MediaState>()
    private val volumeSinks = ConcurrentHashMap<String, List<VolumeSink>>()
    @Volatile private var target: String? = null
    @Volatile private var lastClipboardSeen: String? = null

    /** Like KDE Connect, announce ourselves again whenever the network changes. */
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            main.postDelayed({ if (advertising.get()) broadcastNow() }, 2_000)
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { onLocalClipboardChanged() }

    init {
        main.post {
            runCatching {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.addPrimaryClipChangedListener(clipboardListener)
            }
        }
    }

    val isConnected: Boolean
        get() = connections.isNotEmpty()

    fun connectedDevices(): List<RemoteDevice> =
        connections.values.map { RemoteDevice(it.pending.peer.id, it.pending.peer.name) }
            .sortedBy { it.name.lowercase() }

    /** Devices with an open link that are not paired yet. */
    fun availableDevices(): List<RemoteDevice> =
        pending.entries.map { RemoteDevice(it.key, it.value.name) }.sortedBy { it.name.lowercase() }

    private fun targetConnection(): TrustedConnection? =
        connections[target ?: ""] ?: connections.values.firstOrNull()

    fun setTarget(id: String?) { target = id }

    fun setDeviceName(name: String) {
        deviceName = name
        if (advertising.get()) broadcastNow()
    }

    private fun targetSupports(type: String): Boolean =
        type in (targetConnection()?.pending?.peer?.incomingCapabilities ?: emptySet())

    val supportsPresenter: Boolean get() = targetSupports(ProtocolPackets.TYPE_PRESENTER)
    val supportsNotifications: Boolean get() = targetSupports(ProtocolPackets.TYPE_NOTIFICATION)
    val supportsMedia: Boolean get() = targetSupports(ProtocolPackets.TYPE_MPRIS_REQUEST)
    val supportsVolume: Boolean get() = targetSupports(ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST)
    val supportsDigitizer: Boolean get() = targetSupports(ProtocolPackets.TYPE_DIGITIZER)
    val supportsCommands: Boolean get() = targetSupports(ProtocolPackets.TYPE_RUNCOMMAND_REQUEST)
    val supportsMouse: Boolean get() = targetSupports(ProtocolPackets.TYPE_MOUSEPAD_REQUEST)

    val isRinging: Boolean
        get() = AlarmPlayer.isPlaying

    fun stopAlarm() = AlarmPlayer.stop()

    // ---- Discoverability (advertise + listen) ----

    fun startServer() {
        if (closed.get() || advertising.getAndSet(true)) return
        deviceName = SetupPreferences(app).deviceName
        broadcaster = Thread({ serverLoop() }, "GlideServer").apply { isDaemon = true; start() }
        runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
        runCatching { TelephonyReporter.start(app) { packet -> broadcastPacket(packet) } }
    }

    fun stopServer() {
        if (!advertising.getAndSet(false)) return
        runCatching { TelephonyReporter.stop(app) }
        runCatching {
            (app.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)
                ?.unregisterNetworkCallback(networkCallback)
        }
        runCatching { server?.close() }
        server = null
        runCatching { udp?.close() }
        udp = null
        broadcaster?.interrupt(); broadcaster = null
    }

    /** Re-open the listener, e.g. right after the local-network permission was granted. */
    fun restartServer() {
        stopServer()
        startServer()
    }

    /** (Re)start telephony reporting once the READ_PHONE_STATE permission is granted. */
    fun refreshTelephony() {
        if (advertising.get()) runCatching { TelephonyReporter.start(app) { packet -> broadcastPacket(packet) } }
    }

    /** Re-broadcast immediately (e.g. when the user opens the devices screen). */
    fun refreshAdvertise() {
        if (advertising.get()) broadcastNow()
    }

    private fun serverLoop() {
        var first = true
        while (advertising.get()) {
            if (server?.isClosed != false) {
                val listener = runCatching { openListener() }.getOrNull()
                server = listener
                if (listener != null) {
                    Thread({ acceptLoop(listener) }, "GlideServer-Accept").apply { isDaemon = true; start() }
                    first = true
                }
            }
            if (udp?.isClosed != false) {
                val socket = runCatching {
                    DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(1716)) }
                }.getOrNull()
                udp = socket
                if (socket != null) {
                    Thread({ udpLoop(socket) }, "GlideServer-Udp").apply { isDaemon = true; start() }
                }
            }
            // Peers open a fresh link for every identity broadcast they receive, so only
            // announce when it is needed: on start, and while nobody is linked to us.
            if (first || (connections.isEmpty() && pending.isEmpty())) broadcastNow()
            first = false
            try { Thread.sleep(BROADCAST_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (advertising.get() && !listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (_: Exception) {
                if (listener.isClosed) break else continue
            }
            try {
                io.execute { handleIncoming(socket) }
            } catch (_: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    /** Someone answered our broadcast: they connect, we are the TLS client. */
    private fun handleIncoming(socket: Socket) {
        try {
            val address = socket.inetAddress
            if (!(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)) {
                socket.close(); return
            }
            val identity = LocalIdentity().loadOrCreate()
            val trusted = TrustedPeerStore(app)
            val handshake = IncomingHandshake.open(socket, identity, deviceName, null) { trusted.certificate(it, identity.id) }
            adopt(handshake, identity, trusted)
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    /** We heard someone's broadcast: connect to them, as KDE Connect peers expect. */
    private fun udpLoop(socket: DatagramSocket) {
        val selfId = runCatching { LocalIdentity().loadOrCreate().id }.getOrNull()
        val buffer = ByteArray(IdentityPacket.MAX_BYTES.coerceAtMost(64 * 1024))
        while (advertising.get() && !socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val address = packet.address
                if (!(address.isSiteLocalAddress || address.isLinkLocalAddress)) continue
                val peer = IdentityPacket.decode(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) ?: continue
                if (peer.id == selfId || peer.version != IdentityPacket.VERSION) continue
                if (connections.containsKey(peer.id) || pending.containsKey(peer.id)) continue
                val now = android.os.SystemClock.elapsedRealtime()
                val last = lastDial[peer.id]
                if (last != null && now - last < DIAL_INTERVAL_MS) continue
                lastDial[peer.id] = now
                io.execute {
                    runCatching {
                        val identity = LocalIdentity().loadOrCreate()
                        val trusted = TrustedPeerStore(app)
                        val handshake = IncomingHandshake.connect(peer, address, identity, deviceName) { trusted.certificate(it, identity.id) }
                        adopt(handshake, identity, trusted)
                    }
                }
            } catch (_: RejectedExecutionException) {
                break
            } catch (_: Exception) {
                if (socket.isClosed) break
            }
        }
    }

    /** Owns a freshly secured link until it closes: pair if needed, then run it. */
    private fun adopt(handshake: IncomingHandshake.Pending, identity: LocalIdentity.Identity, trusted: TrustedPeerStore) {
        try {
            val id = handshake.peer.id
            if (id == identity.id) { handshake.socket.close(); return }

            if (!handshake.previouslyPinned) {
                // Never let a duplicate link interrupt a verification the user is looking at.
                if (pending[id]?.session?.isPairing == true) { handshake.socket.close(); return }
                val session = PairingSession(handshake, identity, trusted)
                val entry = PendingPeer(handshake.peer.name, handshake.socket, session)
                pending.put(id, entry)?.let { old -> runCatching { old.socket.close() } }
                emit(Event.PeersChanged)
                if (outgoingIntents.remove(id) != null) session.initiate()
                try {
                    session.run(
                        showCode = { code -> emit(Event.Verify(id, handshake.peer.name, code)) },
                        onAttemptEnded = { emit(Event.Failed) }
                    )
                } finally {
                    pending.remove(id, entry)
                    emit(Event.PeersChanged)
                }
            }
            maintainConnection(handshake, trusted, identity)
        } catch (_: Exception) {
            runCatching { handshake.socket.close() }
        }
    }

    private fun broadcastNow() {
        val port = server?.takeIf { !it.isClosed }?.localPort ?: return
        try {
            io.execute {
                runCatching {
                    val identity = LocalIdentity().loadOrCreate()
                    val bytes = IdentityPacket.encode(identity.id, deviceName, port).toByteArray(Charsets.UTF_8)
                    DatagramSocket().use { udp ->
                        udp.broadcast = true
                        broadcastTargets().forEach { addr ->
                            runCatching { udp.send(DatagramPacket(bytes, bytes.size, addr, 1716)) }
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (nif.isUp && !nif.isLoopback) {
                    nif.interfaceAddresses.forEach { ia -> ia.broadcast?.let { list.add(it) } }
                }
            }
        }
        return list.distinctBy { it.hostAddress }
    }

    // ---- Pairing ----

    /**
     * Ask to pair with a discovered device. If its link is already open the request goes
     * out immediately; otherwise we announce ourselves to it and pair once it connects.
     */
    fun start(candidate: NearbyDiscovery.Candidate, deviceName: String) {
        this.deviceName = deviceName
        connections[candidate.id]?.let {
            emit(Event.Connected(candidate.id, it.pending.peer.name))
            return
        }
        emit(Event.Connecting)
        if (requestPairingInternal(candidate.id)) return
        val attempt = generation.incrementAndGet()
        outgoingIntents[candidate.id] = attempt
        startServer()
        try {
            io.execute {
                runCatching {
                    val address = InetAddress.getByName(candidate.host)
                    check(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)
                    // Give a freshly started listener a moment to bind.
                    var port = server?.takeIf { !it.isClosed }?.localPort
                    var waited = 0
                    while (port == null && waited < 2_000) {
                        Thread.sleep(100); waited += 100
                        port = server?.takeIf { !it.isClosed }?.localPort
                    }
                    val identity = LocalIdentity().loadOrCreate()
                    val bytes = IdentityPacket.encode(identity.id, this.deviceName, port).toByteArray(Charsets.UTF_8)
                    DatagramSocket().use { udp ->
                        repeat(3) {
                            udp.send(DatagramPacket(bytes, bytes.size, address, 1716))
                            Thread.sleep(150)
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
        main.postDelayed({
            if (outgoingIntents.remove(candidate.id, attempt)) emit(Event.Failed)
        }, CONNECT_TIMEOUT_MS)
    }

    /** Ask to pair with a device whose (untrusted) link is already open. */
    fun requestPairing(id: String): Boolean {
        if (pending[id] == null) return false
        emit(Event.Connecting)
        return requestPairingInternal(id)
    }

    private fun requestPairingInternal(id: String): Boolean {
        val entry = pending[id] ?: return false
        entry.session.initiate()
        return true
    }

    fun decide(id: String, accepted: Boolean) { pending[id]?.session?.decide(accepted) }

    /** Abort any pairing attempt in progress (links stay open). */
    fun cancel() {
        generation.incrementAndGet()
        outgoingIntents.clear()
        pending.values.forEach { it.session.decide(false) }
    }

    /** Remove the pairing with a connected device and drop the link. */
    fun unpair(id: String) {
        val connection = connections[id] ?: return
        try {
            io.execute {
                runCatching { connection.sendRaw(PairingPacket.response(false)) }
                runCatching {
                    val identity = LocalIdentity().loadOrCreate()
                    TrustedPeerStore(app).remove(id, identity.id, connection.pending.certificate)
                }
                runCatching { connection.pending.socket.close() }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun maintainConnection(
        handshake: IncomingHandshake.Pending,
        trusted: TrustedPeerStore,
        identity: LocalIdentity.Identity
    ) {
        val id = handshake.peer.id
        val name = handshake.peer.name
        val preferences = SetupPreferences(app)
        val connection = TrustedConnection(
            handshake, identity, trusted,
            batteryEnabled = { FeatureId.BATTERY.key in preferences.selectedFeatures() },
            readBattery = {
                app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.let(BatterySnapshot::fromIntent)
            },
            onPacketReceived = { type, body -> handleIncomingPacket(id, name, type, body) }
        )
        connections.put(id, connection)?.let { old -> runCatching { old.pending.socket.close() } }
        if (target == null) target = id
        NotificationBridge.attach(this)
        emit(Event.Connected(id, name))
        greetPeer(connection)
        try {
            connection.run { !closed.get() && connections[id] === connection }
        } finally {
            runCatching { handshake.socket.close() }
            if (connections.remove(id, connection)) {
                mediaStates.keys.removeAll { it.startsWith("$id/") }
                volumeSinks.remove(id)
                if (target == id) target = connections.keys.firstOrNull()
                if (connections.isEmpty()) NotificationBridge.detach(this)
                emit(Event.Disconnected(id))
            }
        }
    }

    /** Initial state exchange right after a trusted connection comes up. */
    private fun greetPeer(connection: TrustedConnection) {
        enqueue {
            val features = SetupPreferences(app).selectedFeatures()
            connection.send(ProtocolPackets.runCommandRequestList())
            connection.send(ProtocolPackets.mprisRequest(JSONObject().put("requestPlayerList", true)))
            connection.send(ProtocolPackets.systemVolumeRequest(JSONObject().put("requestSinks", true)))
            runCatching { ConnectivityReporter.reportPacket(app)?.let { connection.send(it) } }
            runCatching { connection.send(MediaControl.playerListPacket(app)) }
            runCatching { MediaControl.statePacket(app)?.let { connection.send(it) } }
            if (FeatureId.CLIPBOARD.key in features) {
                lastClipboardSeen?.takeIf { it.isNotEmpty() }?.let { connection.send(ProtocolPackets.clipboardConnect(it)) }
            }
        }
    }

    private fun onLocalClipboardChanged() {
        val text = runCatching {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        if (text.isNullOrEmpty() || text == lastClipboardSeen) return
        lastClipboardSeen = text
        if (FeatureId.CLIPBOARD.key in SetupPreferences(app).selectedFeatures()) {
            broadcastPacket(ProtocolPackets.clipboard(text))
        }
    }

    // ---- Incoming packet handling ----

    private fun handleIncomingPacket(peerId: String, peerName: String, type: String, body: JSONObject) {
        val features = SetupPreferences(app).selectedFeatures()
        when (type) {
            ProtocolPackets.TYPE_FINDMYPHONE_REQUEST -> {
                if (FeatureId.RING.key in features) {
                    AlarmPlayer.start(app)
                    emit(Event.RingRequest(peerName))
                }
            }
            ProtocolPackets.TYPE_CLIPBOARD, ProtocolPackets.TYPE_CLIPBOARD_CONNECT -> {
                val content = body.optString("content")
                if (content.isNotEmpty() && FeatureId.CLIPBOARD.key in features && content != lastClipboardSeen) {
                    lastClipboardSeen = content
                    main.post {
                        runCatching {
                            (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.setPrimaryClip(ClipData.newPlainText("Glide Phone", content))
                        }
                    }
                    emit(Event.ClipboardReceived(content))
                }
            }
            ProtocolPackets.TYPE_BATTERY -> {
                val charge = body.optInt("currentCharge", -1)
                if (charge in 0..100) emit(Event.PeerBattery(peerId, charge, body.optBoolean("isCharging", false)))
            }
            ProtocolPackets.TYPE_SHARE_REQUEST -> handleShare(peerId, body, FeatureId.FILES.key in features)
            ProtocolPackets.TYPE_PING -> emit(Event.PingReceived(peerName, body.optString("message", "Ping!")))
            ProtocolPackets.TYPE_RUNCOMMAND -> {
                if (body.has("commandList")) emit(Event.CommandList(peerId, parseCommandList(body)))
            }
            ProtocolPackets.TYPE_NOTIFICATION -> {
                val nid = body.optString("id")
                if (nid.isNotEmpty()) {
                    emit(Event.RemoteNotification(
                        peerId, nid,
                        body.optString("appName", peerName),
                        body.optString("title"),
                        body.optString("text"),
                        body.optBoolean("isCancel", false)
                    ))
                }
            }
            ProtocolPackets.TYPE_NOTIFICATION_ACTION,
            ProtocolPackets.TYPE_NOTIFICATION_REPLY,
            ProtocolPackets.TYPE_NOTIFICATION_REQUEST -> {
                if (FeatureId.NOTIFICATIONS.key in features) {
                    if (type == ProtocolPackets.TYPE_NOTIFICATION_REQUEST && body.optBoolean("request", false)) {
                        NotificationBridge.refresh()
                    } else {
                        NotificationBridge.handleIncoming(type, body)
                    }
                }
            }
            ProtocolPackets.TYPE_MPRIS -> handleMpris(peerId, body)
            ProtocolPackets.TYPE_MPRIS_REQUEST -> {
                MediaControl.handleRequest(app, body)?.let { sendPacketTo(peerId, it) }
            }
            ProtocolPackets.TYPE_SYSTEMVOLUME -> handleVolume(peerId, body)
            ProtocolPackets.TYPE_CONTACTS_REQUEST_UIDS -> {
                sendPacketTo(peerId, ProtocolPackets.contactsUids(ContactsProvider.uidTimestamps(app)))
            }
            ProtocolPackets.TYPE_CONTACTS_REQUEST_VCARDS -> {
                val requested = body.optJSONArray("uids")
                val list = buildList { if (requested != null) for (i in 0 until requested.length()) add(requested.optString(i)) }
                val selfId = runCatching { LocalIdentity().loadOrCreate().id }.getOrDefault("")
                sendPacketTo(peerId, ProtocolPackets.contactsVcards(ContactsProvider.vcards(app, list.distinct(), selfId)))
            }
            ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATIONS -> {
                sendPacketTo(peerId, ProtocolPackets.smsMessages(SmsProvider.conversations(app)))
            }
            ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATION -> {
                val thread = body.opt("threadID")?.toString().orEmpty()
                if (thread.isNotBlank()) {
                    sendPacketTo(peerId, ProtocolPackets.smsMessages(SmsProvider.conversation(
                        app, thread, body.optLong("rangeStartTimestamp", -1), body.optLong("numberToRequest", -1)
                    )))
                }
            }
            ProtocolPackets.TYPE_SMS_REQUEST -> {
                val address = body.optJSONArray("addresses")?.optJSONObject(0)?.optString("address")
                    ?.takeIf { it.isNotBlank() } ?: body.optString("phoneNumber")
                val text = body.optString("messageBody")
                if (address.isNotBlank() && text.isNotBlank()) SmsProvider.send(app, address, text)
            }
        }
    }

    private fun handleShare(peerId: String, body: JSONObject, enabled: Boolean) {
        if (!enabled) return
        val port = body.optInt(ProtocolPackets.KEY_PAYLOAD_PORT, -1)
        val filename = body.optString("filename")
        if (port > 0 && filename.isNotEmpty()) {
            val connection = connections[peerId] ?: return
            val size = body.optLong(ProtocolPackets.KEY_PAYLOAD_SIZE, -1)
            try {
                io.execute {
                    val saved = runCatching {
                        SharePayload.receive(
                            app, LocalIdentity().loadOrCreate(), connection.pending.certificate,
                            connection.pending.socket.inetAddress, port, size, filename
                        )
                    }.getOrNull()
                    emit(Event.FileReceived(peerId, saved ?: filename, saved != null))
                }
            } catch (_: RejectedExecutionException) {
            }
            return
        }
        val text = body.optString("text").takeIf { it.isNotEmpty() }
        val url = body.optString("url").takeIf { it.isNotEmpty() }
        if (text != null || url != null) emit(Event.ShareReceived(peerId, text, url))
    }

    private fun handleMpris(peerId: String, body: JSONObject) {
        body.optJSONArray("playerList")?.let { array ->
            val players = buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let(::add) }
            mediaStates.keys.removeAll { key -> key.startsWith("$peerId/") && key.removePrefix("$peerId/") !in players }
            emit(Event.MediaPlayers(peerId, players))
            players.forEach { player ->
                sendPacketTo(peerId, ProtocolPackets.mprisRequest(
                    JSONObject().put("player", player).put("requestNowPlaying", true).put("requestVolume", true)
                ))
            }
            return
        }
        val player = body.optString("player").takeIf { it.isNotEmpty() } ?: return
        val key = "$peerId/$player"
        val old = mediaStates[key] ?: MediaState(player)
        val updated = old.copy(
            title = body.optString("title", old.title),
            artist = body.optString("artist", old.artist),
            album = body.optString("album", old.album),
            isPlaying = body.optBoolean("isPlaying", old.isPlaying),
            volume = body.optInt("volume", old.volume),
            position = body.optLong("pos", old.position),
            length = body.optLong("length", old.length),
            canPlay = body.optBoolean("canPlay", old.canPlay),
            canPause = body.optBoolean("canPause", old.canPause),
            canGoNext = body.optBoolean("canGoNext", old.canGoNext),
            canGoPrevious = body.optBoolean("canGoPrevious", old.canGoPrevious),
            canSeek = body.optBoolean("canSeek", old.canSeek)
        )
        mediaStates[key] = updated
        emit(Event.MediaUpdate(peerId, updated))
    }

    private fun handleVolume(peerId: String, body: JSONObject) {
        val array = body.optJSONArray("sinkList")
        if (array != null) {
            val sinks = buildList {
                for (i in 0 until array.length()) {
                    val sink = array.optJSONObject(i) ?: continue
                    val name = sink.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    add(VolumeSink(
                        name, sink.optString("description", name), sink.optInt("volume", 0),
                        sink.optInt("maxVolume", 65536).coerceAtLeast(1), sink.optBoolean("muted", false),
                        sink.optBoolean("enabled", false)
                    ))
                }
            }
            volumeSinks[peerId] = sinks
            emit(Event.VolumeSinks(peerId, sinks))
            return
        }
        val name = body.optString("name").takeIf { it.isNotEmpty() } ?: return
        val current = volumeSinks[peerId] ?: return
        val becameDefault = body.optBoolean("enabled", false)
        val updated = current.map { sink ->
            if (sink.name == name) sink.copy(
                volume = body.optInt("volume", sink.volume),
                muted = body.optBoolean("muted", sink.muted),
                enabled = body.optBoolean("enabled", sink.enabled)
            ) else if (becameDefault) sink.copy(enabled = false) else sink
        }
        volumeSinks[peerId] = updated
        emit(Event.VolumeSinks(peerId, updated))
    }

    private fun parseCommandList(body: JSONObject): List<RemoteCommand> {
        val obj = when (val raw = body.opt("commandList")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<RemoteCommand>()
        obj.keys().forEach { key ->
            val entry = obj.optJSONObject(key) ?: return@forEach
            out.add(RemoteCommand(key, entry.optString("name", key), entry.optString("command", "")))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // ---- Outgoing packets ----

    private fun enqueue(task: () -> Unit): Boolean = try {
        sender.execute { runCatching(task) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun sendPacket(packetJson: String): Boolean = sendPacketTo(null, packetJson)

    fun sendPacketTo(deviceId: String?, packetJson: String): Boolean {
        val connection = (if (deviceId != null) connections[deviceId] else targetConnection()) ?: return false
        val packetType = runCatching { JSONObject(packetJson).optString("type") }.getOrNull() ?: return false
        if (packetType !in connection.pending.peer.incomingCapabilities) return false
        if (packetType == ProtocolPackets.TYPE_NOTIFICATION &&
            (FeatureId.NOTIFICATIONS.key !in SetupPreferences(app).selectedFeatures() ||
                app.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app))) return false
        return enqueue {
            if (connections[connection.pending.peer.id] === connection && !connection.send(packetJson)) {
                runCatching { connection.pending.socket.close() }
            }
        }
    }

    /** Send to every connected device (notifications, clipboard, telephony). */
    fun broadcastPacket(packetJson: String): Boolean {
        var any = false
        connections.keys.forEach { id -> if (sendPacketTo(id, packetJson)) any = true }
        return any
    }

    fun sendPresenterSpecialKey(key: Int): Boolean = sendPacket(ProtocolPackets.presenterSpecialKey(key))
    fun sendPresenterKey(key: String, ctrl: Boolean = false): Boolean = sendPacket(ProtocolPackets.presenterKey(key, ctrl))
    fun sendPresenterMove(dx: Float, dy: Float): Boolean = sendPacket(ProtocolPackets.presenterMove(dx, dy))
    fun sendPresenterStop(): Boolean = sendPacket(ProtocolPackets.presenterStop())

    fun sendMouseMove(dx: Float, dy: Float): Boolean = sendPacket(ProtocolPackets.mouseMove(dx, dy))
    fun sendMouseClick(button: String): Boolean = sendPacket(ProtocolPackets.mouseClick(button))
    fun sendMouseScroll(dx: Float, dy: Float): Boolean = sendPacket(ProtocolPackets.mouseScroll(dx, dy))
    fun sendSpecialKey(key: Int): Boolean = sendPacket(ProtocolPackets.presenterSpecialKey(key))

    /** Types text on the computer one character at a time, as the desktop expects. */
    fun sendType(text: String): Boolean {
        if (text.isEmpty() || !supportsMouse) return false
        var index = 0
        var ok = true
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            ok = when (codePoint) {
                '\n'.code -> sendSpecialKey(ProtocolPackets.KEY_ENTER)
                '\t'.code -> sendSpecialKey(ProtocolPackets.KEY_TAB)
                else -> sendPacket(ProtocolPackets.mouseType(String(Character.toChars(codePoint))))
            } && ok
        }
        return ok
    }

    fun requestMediaPlayers(): Boolean =
        sendPacket(ProtocolPackets.mprisRequest(JSONObject().put("requestPlayerList", true)))

    fun requestMediaState(player: String): Boolean = sendPacket(ProtocolPackets.mprisRequest(
        JSONObject().put("player", player).put("requestNowPlaying", true).put("requestVolume", true)
    ))

    fun sendMediaAction(player: String, action: String): Boolean =
        sendPacket(ProtocolPackets.mprisRequest(JSONObject().put("player", player).put("action", action)))

    fun sendMediaVolume(player: String, volume: Int): Boolean =
        sendPacket(ProtocolPackets.mprisRequest(JSONObject().put("player", player).put("setVolume", volume.coerceIn(0, 100))))

    fun sendMediaSeek(player: String, offsetMs: Long): Boolean =
        sendPacket(ProtocolPackets.mprisRequest(JSONObject().put("player", player).put("Seek", offsetMs * 1000)))

    fun requestVolumeSinks(): Boolean =
        sendPacket(ProtocolPackets.systemVolumeRequest(JSONObject().put("requestSinks", true)))

    fun sendSinkVolume(name: String, volume: Int): Boolean =
        sendPacket(ProtocolPackets.systemVolumeRequest(JSONObject().put("name", name).put("volume", volume)))

    fun sendSinkMuted(name: String, muted: Boolean): Boolean =
        sendPacket(ProtocolPackets.systemVolumeRequest(JSONObject().put("name", name).put("muted", muted)))

    fun sendSinkDefault(name: String): Boolean =
        sendPacket(ProtocolPackets.systemVolumeRequest(JSONObject().put("name", name).put("enabled", true)))

    fun sendRunCommandRequest(key: String): Boolean = sendPacket(ProtocolPackets.runCommandRequest(key))
    fun requestCommands(): Boolean = sendPacket(ProtocolPackets.runCommandRequestList())

    fun sendDigitizerStart(width: Int, height: Int, resolutionX: Int, resolutionY: Int): Boolean =
        sendPacket(ProtocolPackets.digitizerStart(width, height, resolutionX, resolutionY))

    fun sendDigitizerEnd(): Boolean = sendPacket(ProtocolPackets.digitizerEnd())

    fun sendDigitizerEvent(active: Boolean?, touching: Boolean?, x: Int?, y: Int?, pressure: Double?, rubber: Boolean = false): Boolean =
        sendPacket(ProtocolPackets.digitizerEvent(active, touching, x, y, pressure, rubber))

    fun sendRing(): Boolean = sendPacket(ProtocolPackets.findMyPhone())

    fun sendClipboard(content: String): Boolean {
        if (FeatureId.CLIPBOARD.key !in SetupPreferences(app).selectedFeatures()) return false
        lastClipboardSeen = content
        return sendPacket(ProtocolPackets.clipboard(content))
    }

    fun sendShareText(text: String): Boolean = sendPacket(ProtocolPackets.shareText(text))
    fun sendShareUrl(url: String): Boolean = sendPacket(ProtocolPackets.shareUrl(url))

    fun sendNotificationPacket(packet: String): Boolean =
        FeatureId.NOTIFICATIONS.key in SetupPreferences(app).selectedFeatures() && broadcastPacket(packet)

    fun sendPing(message: String? = null): Boolean = sendPacket(
        if (message == null) JSONObject().put("id", System.currentTimeMillis())
            .put("type", ProtocolPackets.TYPE_PING).put("body", JSONObject()).toString() + "\n"
        else ProtocolPackets.ping(message)
    )

    fun sendBattery(): Boolean {
        if (FeatureId.BATTERY.key !in SetupPreferences(app).selectedFeatures()) return false
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let(BatterySnapshot::fromIntent)
        return if (battery != null) sendPacket(battery.packet()) else false
    }

    /** Send a real file to the target device over an encrypted KDE Connect payload channel. */
    fun sendFile(uri: android.net.Uri): Boolean {
        val connection = targetConnection() ?: return false
        if (ProtocolPackets.TYPE_SHARE_REQUEST !in connection.pending.peer.incomingCapabilities) return false
        return try {
            io.execute {
                runCatching {
                    val identity = LocalIdentity().loadOrCreate()
                    val resolver = app.contentResolver
                    val name = SharePayload.queryName(resolver, uri) ?: "file"
                    val size = SharePayload.querySize(resolver, uri)
                    val payload = SharePayload.serve(identity, connection.pending.certificate, uri, resolver, size)
                    if (!sendPacketTo(connection.pending.peer.id, ProtocolPackets.shareFile(name, size, payload.port))) {
                        payload.cancel()
                    }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        cancel()
        stopServer()
        main.post {
            runCatching {
                (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.removePrimaryClipChangedListener(clipboardListener)
            }
        }
        pending.values.forEach { runCatching { it.socket.close() } }
        connections.values.forEach { runCatching { it.pending.socket.close() } }
        NotificationBridge.detach(this)
        sender.shutdownNow()
        io.shutdownNow()
    }

    private fun emit(event: Event) {
        main.post { if (!closed.get()) onEvent(event) }
    }

    private fun openListener(): ServerSocket {
        for (port in 1716..1764) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        error("No available port")
    }

    private companion object {
        const val BROADCAST_INTERVAL_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DIAL_INTERVAL_MS = 3_000L
    }
}
