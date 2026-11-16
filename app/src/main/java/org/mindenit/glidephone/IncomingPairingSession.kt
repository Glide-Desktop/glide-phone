package org.mindenit.glidephone

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/** Common hook so the UI can confirm/reject either an incoming or outgoing pairing by id. */
internal interface PairingDecider {
    fun decide(accept: Boolean)
}

/**
 * Handles a pairing request initiated by the *remote* peer (e.g. the user pressed
 * "pair" on the desktop). Mirrors [OutgoingPairingSession] but waits for the peer's
 * pair request first, then shows the same v8 verification code for local confirmation.
 * Runs on an IO thread; the socket is owned by the caller only on success.
 */
internal class IncomingPairingSession(
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
        val line = ByteArrayOutputStream()

        // 1) Wait for the peer's pair request (carries the shared timestamp).
        socket.soTimeout = 500
        var timestamp: Long? = null
        val waitDeadline = SystemClock.elapsedRealtime() + REQUEST_WAIT_MILLIS
        while (timestamp == null) {
            if (SystemClock.elapsedRealtime() > waitDeadline) throw IOException("No pair request")
            val next = try { socket.inputStream.read() } catch (_: SocketTimeoutException) { continue }
            if (next < 0) throw EOFException("Peer disconnected")
            if (next == '\n'.code) {
                val message = PairingPacket.parse(line.toString(Charsets.UTF_8.name()))
                line.reset()
                if (message != null && message.accepted && message.timestampSeconds != null) {
                    timestamp = message.timestampSeconds
                }
                // Ignore any non-pair traffic while still untrusted.
            } else {
                if (line.size() >= IdentityPacket.MAX_BYTES) throw IOException("Packet too large")
                line.write(next)
            }
        }

        val state = PairingState(
            timestamp, SystemClock.elapsedRealtime(),
            identity.certificate.publicKey.encoded, pending.certificate.publicKey.encoded
        )
        state.onPeerResponse(true, SystemClock.elapsedRealtime())
        showCode(state.verificationCode)

        var success = false
        try {
            socket.soTimeout = 250
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
                    if (message != null && !message.accepted) state.reject()
                } else {
                    if (line.size() >= IdentityPacket.MAX_BYTES) throw IOException("Packet too large")
                    line.write(next)
                }
            }
            if (state.status != PairingState.Status.CONFIRMED) throw IOException("Pairing rejected or expired")
            socket.outputStream.write(PairingPacket.response(true).toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
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

    private companion object {
        const val REQUEST_WAIT_MILLIS = 120_000L
    }
}
