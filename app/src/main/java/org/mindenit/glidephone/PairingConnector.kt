package org.mindenit.glidephone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the phone discoverable (UDP identity broadcast + persistent TCP listener on
 * port 1716) and manages one or more simultaneous trusted connections. Callbacks are
 * delivered on the main thread. Both device-initiated and desktop-initiated pairing
 * are supported.
 */
internal class PairingConnector(context: Context, private val onEvent: (Event) -> Unit) : Closeable {
    sealed interface Event {
        data object Connecting : Event
        data class Verify(val id: String, val name: String, val code: String) : Event
        data class Connected(val id: String, val name: String) : Event
        data class Disconnected(val id: String) : Event
        data object Failed : Event
        data class RingRequest(val name: String) : Event
        data class ClipboardReceived(val content: String) : Event
        data class PeerBattery(val id: String, val charge: Int, val charging: Boolean) : Event
        data class PingReceived(val message: String) : Event
        data class ShareReceived(val id: String, val text: String?, val url: String?) : Event
        data class CommandList(val id: String, val commands: List<RemoteCommand>) : Event
        data class RemoteNotification(val id: String, val notificationId: String, val appName: String, val title: String, val text: String, val cancel: Boolean) : Event
    }

    data class RemoteCommand(val key: String, val name: String, val command: String)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val accepts = Executors.newCachedThreadPool()
    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64))
    private val generation = AtomicLong()

    @Volatile private var deviceName: String = SetupPreferences(app).deviceName
    @Volatile private var server: ServerSocket? = null
    private val advertising = AtomicBoolean(false)
    @Volatile private var broadcaster: Thread? = null
    @Volatile private var acceptor: Thread? = null

    @Volatile private var outgoingListener: ServerSocket? = null
    @Volatile private var outgoingSocket: Socket? = null

    private val connections = ConcurrentHashMap<String, TrustedConnection>()
    private val pairingSessions = ConcurrentHashMap<String, PairingDecider>()
    /** Peers we are actively initiating a pairing with (we send the request, not them). */
    private val outgoingIntents = ConcurrentHashMap<String, Long>()
    @Volatile private var target: String? = null

    val isConnected: Boolean
        get() = connections.isNotEmpty()

    fun connectedDevices(): List<RemoteDevice> =
        connections.values.map { RemoteDevice(it.pending.peer.id, it.pending.peer.name) }
            .sortedBy { it.name.lowercase() }

    private fun targetConnection(): TrustedConnection? =
        connections[target] ?: connections.values.firstOrNull()

    fun setTarget(id: String?) { target = id }

    fun setDeviceName(name: String) {
        deviceName = name
        if (advertising.get()) broadcastNow()
    }

    val supportsPresenter: Boolean
        get() = ProtocolPackets.TYPE_PRESENTER in (targetConnection()?.pending?.peer?.incomingCapabilities ?: emptySet())

    val supportsNotifications: Boolean
        get() = ProtocolPackets.TYPE_NOTIFICATION in (targetConnection()?.pending?.peer?.incomingCapabilities ?: emptySet())

    val isRinging: Boolean
        get() = AlarmPlayer.isPlaying

    fun stopAlarm() = AlarmPlayer.stop()

    // ---- Discoverability (advertise + listen) ----

    fun startServer() {
        if (advertising.getAndSet(true)) return
        deviceName = SetupPreferences(app).deviceName
        val listener = runCatching { openListener() }.getOrNull()
        server = listener
        if (listener != null) {
            acceptor = Thread({ acceptLoop(listener) }, "GlideServer-Accept").apply { isDaemon = true; start() }
        }
        broadcaster = Thread({ broadcastLoop() }, "GlideServer-Broadcast").apply { isDaemon = true; start() }
        runCatching { TelephonyReporter.start(app) { packet -> broadcastPacket(packet) } }
    }

    fun stopServer() {
        if (!advertising.getAndSet(false)) return
        runCatching { TelephonyReporter.stop(app) }
        runCatching { server?.close() }
        server = null
        broadcaster?.interrupt(); broadcaster = null
        acceptor?.interrupt(); acceptor = null
    }

    /** (Re)start telephony reporting once the READ_PHONE_STATE permission is granted. */
    fun refreshTelephony() {
        if (advertising.get()) runCatching { TelephonyReporter.start(app) { packet -> broadcastPacket(packet) } }
    }

    /** Re-broadcast immediately (e.g. when the user opens the devices screen). */
    fun refreshAdvertise() {
        if (advertising.get()) broadcastNow()
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (advertising.get() && !listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (_: Exception) {
                if (!advertising.get()) break else continue
            }
            accepts.execute { handleIncoming(socket) }
        }
    }

    private fun handleIncoming(socket: Socket) {
        var peerId: String? = null
        var becameConnected = false
        try {
            val address = socket.inetAddress
            if (!(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)) {
                socket.close(); return
            }
            val identity = LocalIdentity().loadOrCreate()
            val trusted = TrustedPeerStore(app)
            val pending = IncomingHandshake.open(socket, identity, deviceName, null) { trusted.certificate(it, identity.id) }
            peerId = pending.peer.id
            if (pending.peer.id == identity.id) { pending.socket.close(); return }
            if (connections.containsKey(pending.peer.id)) { pending.socket.close(); return }

            if (!pending.previouslyPinned) {
                val initiated = outgoingIntents.remove(pending.peer.id) != null
                if (initiated) {
                    val session = OutgoingPairingSession(pending, identity, trusted)
                    pairingSessions[pending.peer.id] = session
                    session.run { code -> emit(Event.Verify(pending.peer.id, pending.peer.name, code)) }
                } else {
                    val session = IncomingPairingSession(pending, identity, trusted)
                    pairingSessions[pending.peer.id] = session
                    session.run { code -> emit(Event.Verify(pending.peer.id, pending.peer.name, code)) }
                }
            }
            becameConnected = true
            maintainConnection(pending, identity, trusted)
        } catch (_: Exception) {
            if (becameConnected && peerId != null) emit(Event.Disconnected(peerId))
            runCatching { socket.close() }
        } finally {
            peerId?.let { pairingSessions.remove(it) }
        }
    }

    private fun broadcastLoop() {
        broadcastNow()
        while (advertising.get()) {
            try { Thread.sleep(BROADCAST_INTERVAL_MS) } catch (_: InterruptedException) { break }
            broadcastNow()
        }
    }

    private fun broadcastNow() {
        val port = server?.localPort ?: return
        val identity = runCatching { LocalIdentity().loadOrCreate() }.getOrNull() ?: return
        val bytes = runCatching { IdentityPacket.encode(identity.id, deviceName, port).toByteArray(Charsets.UTF_8) }.getOrNull() ?: return
        accepts.execute {
            runCatching {
                DatagramSocket().use { udp ->
                    udp.broadcast = true
                    broadcastTargets().forEach { addr ->
                        runCatching { udp.send(DatagramPacket(bytes, bytes.size, addr, 1716)) }
                    }
                }
            }
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

    // ---- Device-initiated pairing ----

    fun start(candidate: NearbyDiscovery.Candidate, deviceName: String) {
        this.deviceName = deviceName
        val attempt = generation.incrementAndGet()
        emit(Event.Connecting)
        worker.execute {
            try {
                val address = InetAddress.getByName(candidate.host)
                check(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)
                val identity = LocalIdentity().loadOrCreate()
                check(candidate.id != identity.id)
                if (connections.containsKey(candidate.id)) return@execute
                // Mark intent so the accept loop (if the peer connects to our server) initiates pairing,
                // and also run a dedicated listener for a prompt direct connection.
                outgoingIntents[candidate.id] = System.currentTimeMillis()
                val server = openListener()
                server.soTimeout = 20_000
                outgoingListener = server
                ensureActive(attempt)
                server.use {
                    val bytes = IdentityPacket.encode(identity.id, deviceName, server.localPort).toByteArray(Charsets.UTF_8)
                    DatagramSocket().use { udp ->
                        udp.broadcast = true
                        udp.send(DatagramPacket(bytes, bytes.size, address, 1716))
                        try { Thread.sleep(50) } catch (_: InterruptedException) {}
                        udp.send(DatagramPacket(bytes, bytes.size, address, 1716))
                    }
                    val socket = server.accept()
                    outgoingSocket = socket
                    ensureActive(attempt)
                    check(socket.inetAddress == address || (address.isLoopbackAddress && socket.inetAddress.isLoopbackAddress)) { "Unexpected peer address" }
                    val trusted = TrustedPeerStore(app)
                    val pending = IncomingHandshake.open(socket, identity, deviceName, candidate.id) { peerId ->
                        trusted.certificate(peerId, identity.id)
                    }
                    outgoingSocket = pending.socket
                    ensureActive(attempt)
                    outgoingIntents.remove(candidate.id)
                    if (!pending.previouslyPinned) {
                        val pairing = OutgoingPairingSession(pending, identity, trusted)
                        pairingSessions[pending.peer.id] = pairing
                        pairing.run { code -> emit(Event.Verify(pending.peer.id, pending.peer.name, code)) }
                    }
                    ensureActive(attempt)
                    pairingSessions.remove(pending.peer.id)
                    maintainConnection(pending, identity, trusted)
                }
            } catch (_: Exception) {
                outgoingIntents.remove(candidate.id)
                if (generation.get() == attempt && !connections.containsKey(candidate.id)) emit(Event.Failed)
            } finally {
                runCatching { outgoingListener?.close() }
                outgoingListener = null
                outgoingSocket = null
            }
        }
    }

    private fun maintainConnection(
        pending: IncomingHandshake.Pending,
        identity: LocalIdentity.Identity,
        trusted: TrustedPeerStore
    ) {
        val id = pending.peer.id
        val preferences = SetupPreferences(app)
        val connection = TrustedConnection(
            pending, identity, trusted,
            batteryEnabled = { FeatureId.BATTERY.key in preferences.selectedFeatures() },
            readBattery = {
                app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.let(BatterySnapshot::fromIntent)
            },
            onPacketReceived = { type, body -> handleIncomingPacket(id, pending.peer.name, type, body) }
        )
        val previous = connections.put(id, connection)
        runCatching { previous?.pending?.socket?.close() }
        if (target == null) target = id
        NotificationBridge.attach(this)
        emit(Event.Connected(id, pending.peer.name))
        // Sync current clipboard on connect (kdeconnect.clipboard.connect).
        maybeSendClipboardOnConnect(connection)
        // Ask the peer for its available commands (kdeconnect.runcommand).
        sender.execute { runCatching { connection.send(ProtocolPackets.runCommandRequestList()) } }
        // Advertise this phone's media players, volume and connectivity to the peer.
        sender.execute {
            runCatching { MediaControl.playerListPacket(app).let { connection.send(it) } }
            runCatching { VolumeControl.sinkListPacket(app)?.let { connection.send(it) } }
            runCatching { ConnectivityReporter.reportPacket(app)?.let { connection.send(it) } }
            runCatching { MediaControl.statePacket(app)?.let { connection.send(it) } }
        }
        try {
            connection.run { connections[id] === connection }
        } finally {
            connections.remove(id, connection)
            if (target == id) target = connections.keys.firstOrNull()
            if (connections.isEmpty()) NotificationBridge.detach(this)
            emit(Event.Disconnected(id))
        }
    }

    private fun maybeSendClipboardOnConnect(connection: TrustedConnection) {
        if (FeatureId.CLIPBOARD.key !in SetupPreferences(app).selectedFeatures()) return
        main.post {
            val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()
            if (!clip.isNullOrEmpty()) sender.execute {
                runCatching { connection.send(ProtocolPackets.clipboardConnect(clip)) }
            }
        }
    }

    // ---- Incoming packet handling ----

    private fun handleIncomingPacket(peerId: String, peerName: String, type: String, body: JSONObject) {
        when (type) {
            ProtocolPackets.TYPE_FINDMYPHONE_REQUEST -> {
                if (FeatureId.RING.key in SetupPreferences(app).selectedFeatures()) {
                    AlarmPlayer.start(app)
                    emit(Event.RingRequest(peerName))
                }
            }
            ProtocolPackets.TYPE_CLIPBOARD, ProtocolPackets.TYPE_CLIPBOARD_CONNECT -> {
                val content = body.optString("content")
                if (content.isNotEmpty() && FeatureId.CLIPBOARD.key in SetupPreferences(app).selectedFeatures()) {
                    main.post {
                        if (FeatureId.CLIPBOARD.key in SetupPreferences(app).selectedFeatures()) {
                            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Glide Phone", content))
                        }
                    }
                    emit(Event.ClipboardReceived(content))
                }
            }
            ProtocolPackets.TYPE_BATTERY -> {
                val charge = body.optInt("currentCharge", -1)
                val charging = body.optBoolean("isCharging", false)
                if (charge in 0..100) emit(Event.PeerBattery(peerId, charge, charging))
            }
            ProtocolPackets.TYPE_SHARE_REQUEST -> {
                val text = body.optString("text").takeIf { it.isNotEmpty() }
                val url = body.optString("url").takeIf { it.isNotEmpty() }
                if (text != null || url != null) emit(Event.ShareReceived(peerId, text, url))
            }
            ProtocolPackets.TYPE_PING -> {
                val msg = body.optString("message", "Ping!")
                emit(Event.PingReceived(msg))
            }
            ProtocolPackets.TYPE_RUNCOMMAND -> {
                val commands = parseCommandList(body)
                if (commands.isNotEmpty()) emit(Event.CommandList(peerId, commands))
            }
            ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST -> {
                val response = VolumeControl.handleRequest(app, body)
                if (response != null) sendPacketTo(peerId, response)
            }
            ProtocolPackets.TYPE_MPRIS_REQUEST -> {
                val response = MediaControl.handleRequest(app, body)
                if (response != null) sendPacketTo(peerId, response)
            }
            ProtocolPackets.TYPE_CONTACTS_REQUEST_UIDS -> {
                val uids = ContactsProvider.uidTimestamps(app)
                if (uids.isNotEmpty()) sendPacketTo(peerId, ProtocolPackets.contactsUids(uids))
            }
            ProtocolPackets.TYPE_CONTACTS_REQUEST_VCARDS -> {
                val requested = body.optJSONArray("uids")
                val list = buildList { if (requested != null) for (i in 0 until requested.length()) add(requested.optString(i)) }
                val cards = ContactsProvider.vcards(app, list)
                if (cards.isNotEmpty()) sendPacketTo(peerId, ProtocolPackets.contactsVcards(cards))
            }
            ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATIONS -> {
                val msgs = SmsProvider.conversations(app)
                if (msgs.length() > 0) sendPacketTo(peerId, ProtocolPackets.smsMessages(msgs))
            }
            ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATION -> {
                val thread = body.optString("threadID").ifBlank { body.optString("thread_id") }
                if (thread.isNotBlank()) {
                    val msgs = SmsProvider.conversation(app, thread)
                    if (msgs.length() > 0) sendPacketTo(peerId, ProtocolPackets.smsMessages(msgs))
                }
            }
            ProtocolPackets.TYPE_NOTIFICATION_ACTION,
            ProtocolPackets.TYPE_NOTIFICATION_REPLY,
            ProtocolPackets.TYPE_NOTIFICATION_REQUEST -> NotificationBridge.handleIncoming(type, body)
            ProtocolPackets.TYPE_SMS_REQUEST -> {
                val address = body.optJSONArray("addresses")?.optJSONObject(0)?.optString("address")
                    ?: body.optString("address")
                val text = body.optString("messageBody").ifBlank { body.optString("body") }
                if (!address.isNullOrBlank() && text.isNotBlank()) SmsProvider.send(app, address, text)
            }
            ProtocolPackets.TYPE_NOTIFICATION -> {
                val nid = body.optString("id")
                if (nid.isNotEmpty()) {
                    val cancel = body.optBoolean("isCancel", false)
                    emit(Event.RemoteNotification(
                        peerId, nid,
                        body.optString("appName", peerName),
                        body.optString("title"),
                        body.optString("text"),
                        cancel
                    ))
                }
            }
        }
    }

    private fun parseCommandList(body: JSONObject): List<RemoteCommand> {
        val raw = body.opt("commandList") ?: return emptyList()
        val obj = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return emptyList()
        val out = mutableListOf<RemoteCommand>()
        obj.keys().forEach { key ->
            val entry = obj.optJSONObject(key) ?: return@forEach
            out.add(RemoteCommand(key, entry.optString("name", key), entry.optString("command", "")))
        }
        return out
    }

    // ---- Outgoing packets ----

    fun sendPacket(packetJson: String): Boolean = sendPacketTo(target, packetJson)

    fun sendPacketTo(deviceId: String?, packetJson: String): Boolean {
        val connection = (deviceId?.let { connections[it] }) ?: targetConnection() ?: return false
        val packetType = runCatching { JSONObject(packetJson).optString("type") }.getOrNull()
        if (packetType !in connection.pending.peer.incomingCapabilities) return false
        return try {
            sender.execute {
                if (packetType == ProtocolPackets.TYPE_NOTIFICATION &&
                    (FeatureId.NOTIFICATIONS.key !in SetupPreferences(app).selectedFeatures() ||
                        app.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app))) return@execute
                if (connections.containsValue(connection) && !connection.send(packetJson)) {
                    runCatching { connection.pending.socket.close() }
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /** Send to every connected device (used for broadcasting phone notifications). */
    fun broadcastPacket(packetJson: String): Boolean {
        if (connections.isEmpty()) return false
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
    fun sendType(text: String): Boolean = sendPacket(ProtocolPackets.mouseType(text))
    fun sendSpecialKey(key: Int): Boolean = sendPacket(ProtocolPackets.presenterSpecialKey(key))

    fun sendMpris(request: JSONObject): Boolean = sendPacket(ProtocolPackets.mprisRequest(request))
    fun sendSystemVolume(request: JSONObject): Boolean = sendPacket(ProtocolPackets.systemVolumeRequest(request))
    fun sendRunCommandRequest(key: String): Boolean = sendPacket(ProtocolPackets.runCommandRequest(key))
    fun requestCommands(): Boolean = sendPacket(ProtocolPackets.runCommandRequestList())

    fun sendRing(): Boolean = sendPacket(ProtocolPackets.findMyPhone())

    fun sendClipboard(content: String): Boolean =
        FeatureId.CLIPBOARD.key in SetupPreferences(app).selectedFeatures() && sendPacket(ProtocolPackets.clipboard(content))

    fun sendShareText(text: String): Boolean = sendPacket(ProtocolPackets.shareText(text))
    fun sendShareUrl(url: String): Boolean = sendPacket(ProtocolPackets.shareUrl(url))

    fun sendNotificationPacket(packet: String): Boolean =
        FeatureId.NOTIFICATIONS.key in SetupPreferences(app).selectedFeatures() && broadcastPacket(packet)

    fun sendNotification(title: String, text: String): Boolean =
        broadcastPacket(ProtocolPackets.notification(
            id = "notif-" + System.currentTimeMillis(),
            appName = "Glide Phone",
            title = title,
            text = text
        ))

    fun sendPing(message: String = "Ping!"): Boolean = sendPacket(ProtocolPackets.ping(message))

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
        val identity = runCatching { LocalIdentity().loadOrCreate() }.getOrNull() ?: return false
        val resolver = app.contentResolver
        val name = SharePayload.queryName(resolver, uri) ?: "file"
        val size = SharePayload.querySize(resolver, uri)
        val payload = runCatching {
            SharePayload.serve(identity, connection.pending.certificate, uri, resolver, size)
        }.getOrNull() ?: return false
        val packet = ProtocolPackets.shareFile(name, size, payload.port)
        return if (sendPacketTo(target, packet)) true else { payload.cancel(); false }
    }

    // ---- Pairing control ----

    fun decide(id: String, accepted: Boolean) { pairingSessions[id]?.decide(accepted) }

    fun cancel() {
        generation.incrementAndGet()
        pairingSessions.values.forEach { it.decide(false) }
        stopAlarm()
        runCatching { outgoingListener?.close() }
        runCatching { outgoingSocket?.close() }
    }

    fun disconnect(id: String) {
        pairingSessions[id]?.decide(false)
        connections.remove(id)?.let { runCatching { it.pending.socket.close() } }
    }

    override fun close() {
        cancel()
        stopServer()
        connections.values.forEach { runCatching { it.pending.socket.close() } }
        connections.clear()
        NotificationBridge.detach(this)
        worker.shutdownNow()
        sender.shutdownNow()
        accepts.shutdownNow()
    }

    private fun ensureActive(attempt: Long) {
        check(generation.get() == attempt) { "Pairing cancelled" }
    }

    private fun emit(event: Event) {
        main.post { onEvent(event) }
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
    }
}
