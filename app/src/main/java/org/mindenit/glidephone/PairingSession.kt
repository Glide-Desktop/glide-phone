package org.mindenit.glidephone

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

/** Common hook so the UI can confirm/reject a pairing by device id. */
internal interface PairingDecider {
    fun decide(accept: Boolean)
}

/**
 * Pairing over an encrypted but not yet trusted link. The link stays open (so the
 * peer remains "available") until either side asks to pair: call [initiate] to send
 * our own request, or the peer's request is picked up automatically. Either way the
 * same v8 verification code is shown and must be confirmed locally via [decide].
 * A rejected or expired attempt returns the session to idle instead of dropping the
 * link. Runs on an IO thread; returns only once the peer certificate is pinned.
 */
internal class PairingSession(
    private val pending: IncomingHandshake.Pending,
    private val identity: LocalIdentity.Identity,
    private val trustedPeers: TrustedPeerStore
) : PairingDecider {
    @Volatile private var decision: Boolean? = null
    @Volatile private var initiateRequested = false

    /** True while a verification code is on screen (a request is pending either way). */
    @Volatile var isPairing = false
        private set

    fun initiate() { initiateRequested = true }

    override fun decide(accept: Boolean) { decision = accept }

    @Throws(IOException::class)
    fun run(showCode: (String) -> Unit, onAttemptEnded: (cancelledLocally: Boolean) -> Unit): IncomingHandshake.Pending {
        val socket = pending.socket
        val line = ByteArrayOutputStream()
        var state: PairingState? = null
        var weInitiated = false
        var localProcessed = false
        var cancelledLocally = false
        var success = false
        socket.soTimeout = 250
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val current = state
                isPairing = current != null
                if (current == null) {
                    decision = null
                    if (initiateRequested) {
                        initiateRequested = false
                        val timestamp = System.currentTimeMillis() / 1000
                        write(PairingPacket.request(timestamp))
                        state = newState(timestamp, now)
                        weInitiated = true
                        localProcessed = false
                        showCode(state.verificationCode)
                        continue
                    }
                } else {
                    current.expire(now)
                    val selected = decision
                    if (!localProcessed && selected != null && current.status == PairingState.Status.WAITING) {
                        localProcessed = true
                        if (selected) current.confirmLocally(now) else { cancelledLocally = true; current.reject() }
                    }
                    when (current.status) {
                        PairingState.Status.CONFIRMED -> break
                        PairingState.Status.REJECTED, PairingState.Status.EXPIRED -> {
                            runCatching { write(PairingPacket.response(false)) }
                            state = null
                            weInitiated = false
                            initiateRequested = false
                            onAttemptEnded(cancelledLocally)
                            cancelledLocally = false
                            continue
                        }
                        PairingState.Status.WAITING -> Unit
                    }
                }

                val next = try { socket.inputStream.read() } catch (_: SocketTimeoutException) { continue }
                if (next < 0) throw EOFException("Peer disconnected")
                if (next != '\n'.code) {
                    if (line.size() >= IdentityPacket.MAX_BYTES) throw IOException("Packet too large")
                    line.write(next)
                    continue
                }
                val message = PairingPacket.parse(line.toString(Charsets.UTF_8.name()))
                line.reset()
                if (message == null) continue // ignore non-pair traffic while untrusted
                val active = state
                val at = SystemClock.elapsedRealtime()
                if (active == null) {
                    val timestamp = message.timestampSeconds
                    val nowSeconds = System.currentTimeMillis() / 1000
                    if (message.accepted && timestamp != null &&
                        kotlin.math.abs(nowSeconds - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
                        // Same guard as KDE Connect: a stale or future timestamp is refused.
                        runCatching { write(PairingPacket.response(false)) }
                        onAttemptEnded(false)
                    } else if (message.accepted && timestamp != null) {
                        state = newState(timestamp, at).also { it.onPeerResponse(true, at) }
                        weInitiated = false
                        localProcessed = false
                        decision = null
                        showCode(state.verificationCode)
                    }
                } else {
                    active.onPeerResponse(message.accepted, at)
                }
            }
            val confirmed = state ?: throw IOException("Pairing state lost")
            if (!weInitiated) write(PairingPacket.response(true))
            trustedPeers.pin(pending.peer.id, identity, pending.certificate, confirmed)
            success = true
            return pending
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Pairing failed", error)
        } finally {
            if (!success) runCatching { socket.close() }
        }
    }

    private fun newState(timestamp: Long, now: Long) = PairingState(
        timestamp, now, identity.certificate.publicKey.encoded, pending.certificate.publicKey.encoded
    )

    private fun write(packet: String) {
        pending.socket.outputStream.write(packet.toByteArray(Charsets.UTF_8))
        pending.socket.outputStream.flush()
    }

    private companion object {
        const val MAX_CLOCK_SKEW_SECONDS = 1_800L
    }
}
