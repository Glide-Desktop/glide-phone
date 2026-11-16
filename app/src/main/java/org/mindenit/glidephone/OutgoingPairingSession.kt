package org.mindenit.glidephone

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Run on an IO thread. The returned socket is owned by the caller only after
 * both confirmations and durable certificate pinning succeed.
 */
internal class OutgoingPairingSession(
    private val pending: IncomingHandshake.Pending,
    private val identity: LocalIdentity.Identity,
    private val trustedPeers: TrustedPeerStore
) : PairingDecider {
    @Volatile private var decision: Boolean? = null

    @Synchronized
    override fun decide(accept: Boolean) {
        if (decision == null) decision = accept
    }

    @Throws(IOException::class)
    fun run(showCode: (String) -> Unit): IncomingHandshake.Pending {
        val socket = pending.socket
        if (pending.previouslyPinned) {
            socket.close()
            throw IOException("Already trusted peer must use reconnection")
        }
        val timestamp = System.currentTimeMillis() / 1000
        val state = PairingState(timestamp, SystemClock.elapsedRealtime(),
            identity.certificate.publicKey.encoded, pending.certificate.publicKey.encoded)
        var success = false
        try {
            socket.outputStream.write(PairingPacket.request(timestamp).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            showCode(state.verificationCode)
            socket.soTimeout = 250
            val line = ByteArrayOutputStream()
            var localProcessed = false
            while (state.expire(SystemClock.elapsedRealtime()) == PairingState.Status.WAITING) {
                val selected = decision
                if (!localProcessed && selected != null) {
                    localProcessed = true
                    if (selected) state.confirmLocally(SystemClock.elapsedRealtime()) else state.reject()
                }
                if (state.status != PairingState.Status.WAITING) break
                val next = try { socket.inputStream.read() } catch (_: SocketTimeoutException) { continue }
                if (next < 0) throw EOFException("Peer disconnected")
                if (next == '\n'.code) {
                    val message = PairingPacket.parse(line.toString(Charsets.UTF_8.name()))
                    line.reset()
                    if (message != null) state.onPeerResponse(message.accepted, SystemClock.elapsedRealtime())
                } else {
                    if (line.size() >= IdentityPacket.MAX_BYTES) throw IOException("Packet too large")
                    line.write(next)
                }
            }
            if (state.status != PairingState.Status.CONFIRMED) throw IOException("Pairing rejected or expired")
            trustedPeers.pin(pending.peer.id, identity, pending.certificate, state)
            success = true
            return pending
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Pairing failed", error)
        } finally {
            if (!success) {
                runCatching {
                    socket.outputStream.write(PairingPacket.response(false).toByteArray(Charsets.UTF_8))
                    socket.outputStream.flush()
                }
                runCatching { socket.close() }
            }
        }
    }
}
