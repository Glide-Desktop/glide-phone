package org.mindenit.glidephone

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/** Runs only after pairing and certificate pinning. Caller closes the socket. */
internal class TrustedConnection(
    val pending: IncomingHandshake.Pending,
    private val identity: LocalIdentity.Identity,
    private val trusted: TrustedPeerStore,
    private val batteryEnabled: () -> Boolean,
    private val readBattery: () -> BatterySnapshot?,
    private val onPacketReceived: (type: String, body: JSONObject) -> Unit = { _, _ -> }
) {
    private val writeLock = Any()

    fun send(packetJson: String): Boolean = synchronized(writeLock) {
        try {
            val socket = pending.socket
            val type = JSONObject(packetJson).optString("type")
            if (type !in pending.peer.incomingCapabilities) return@synchronized false
            if (!socket.isClosed && socket.isConnected) {
                val bytes = packetJson.toByteArray(Charsets.UTF_8)
                socket.outputStream.write(bytes)
                if (!packetJson.endsWith("\n")) {
                    socket.outputStream.write('\n'.code)
                }
                socket.outputStream.flush()
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a protocol-level packet (e.g. unpair) without the capability gate. */
    fun sendRaw(packetJson: String): Boolean = synchronized(writeLock) {
        try {
            val out = pending.socket.outputStream
            out.write(packetJson.toByteArray(Charsets.UTF_8))
            if (!packetJson.endsWith("\n")) out.write('\n'.code)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun run(keepRunning: () -> Boolean) {
        val socket = pending.socket
        socket.soTimeout = 1_000
        socket.keepAlive = true
        val line = ByteArrayOutputStream()
        var lastBattery: BatterySnapshot? = null
        var nextCheck = 0L

        while (keepRunning()) {
            if (SystemClock.elapsedRealtime() >= nextCheck) {
                nextCheck = SystemClock.elapsedRealtime() + 1_000
                if (batteryEnabled()) {
                    val battery = readBattery()
                    if (battery != null && battery != lastBattery) {
                        if (send(battery.packet())) lastBattery = battery
                    }
                } else {
                    lastBattery = null
                }
            }

            val byte = try { socket.inputStream.read() } catch (_: SocketTimeoutException) { continue }
            if (byte < 0) throw EOFException("Peer disconnected")
            if (byte == '\n'.code) {
                val lineStr = line.toString(Charsets.UTF_8.name())
                line.reset()
                handleIncomingLine(lineStr)
            } else {
                if (line.size() >= IdentityPacket.MAX_BYTES) throw IOException("Packet too large")
                line.write(byte)
            }
        }
    }

    private fun handleIncomingLine(lineStr: String) {
        try {
            val packet = JSONObject(lineStr)
            val type = packet.optString("type")
            val body = packet.optJSONObject("body") ?: JSONObject()
            // Payload metadata lives at the top level of a KDE Connect packet.
            if (packet.has("payloadSize")) body.put(ProtocolPackets.KEY_PAYLOAD_SIZE, packet.optLong("payloadSize", -1))
            packet.optJSONObject("payloadTransferInfo")?.optInt("port", -1)?.takeIf { it > 0 }
                ?.let { body.put(ProtocolPackets.KEY_PAYLOAD_PORT, it) }

            if (type == ProtocolPackets.TYPE_PAIR) {
                val pair = body.opt("pair") as? Boolean ?: true
                trusted.remove(pending.peer.id, identity.id, pending.certificate)
                // pair=false is an unpair. pair=true from a device we already trust means it
                // lost our pairing: KDE Connect unpairs too, so the peer can pair afresh
                // over a new, untrusted link.
                throw IOException(if (pair) "Peer requested pairing again" else "Peer unpaired")
            }

            onPacketReceived(type, body)
        } catch (e: Exception) {
            if (e is IOException) throw e
        }
    }
}
