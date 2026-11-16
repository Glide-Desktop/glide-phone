package org.mindenit.glidephone

import android.annotation.SuppressLint
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Establishes an encrypted but NOT YET TRUSTED connection. No application
 * packets may be sent until explicit pairing and certificate pinning succeed.
 * The caller owns the returned socket and must close it.
 */
internal object IncomingHandshake {
    data class Pending(val socket: SSLSocket, val peer: IdentityPacket.Peer, val certificate: X509Certificate, val previouslyPinned: Boolean)

    @Throws(IOException::class)
    fun open(socket: Socket, identity: LocalIdentity.Identity, deviceName: String, expectedPeerId: String? = null, trustedCertificateFor: (String) -> X509Certificate?): Pending {
        var secure: SSLSocket? = null
        try {
            socket.soTimeout = TIMEOUT_MS
            val firstLine = readLine(socket)
            val first = IdentityPacket.decode(firstLine) ?: throw IOException("Invalid identity")
            if (first.version != IdentityPacket.VERSION || first.id == identity.id ||
                (expectedPeerId != null && first.id != expectedPeerId)) {
                throw IOException("Unsupported or self identity")
            }
            val body = JSONObject(firstLine).getJSONObject("body")
            if (body.has("targetDeviceId") && body.optString("targetDeviceId") != identity.id) {
                throw IOException("Wrong destination")
            }
            if (body.has("targetProtocolVersion") &&
                body.optInt("targetProtocolVersion", -1) != IdentityPacket.VERSION) {
                throw IOException("Wrong protocol version")
            }

            // A self-signed certificate is accepted only for this pending pairing
            // channel. It is not an authorization to transfer user data.
            val pinnedCertificate = trustedCertificateFor(first.id)
            val trust = PendingCertificateCheck(first.id, pinnedCertificate)
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(arrayOf(IdentityKeyManager(identity)), arrayOf(trust), null)
            secure = context.socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
            secure.useClientMode = true
            secure.enabledCipherSuites = secure.enabledCipherSuites.filter {
                it.startsWith("TLS_ECDHE_") || it.startsWith("TLS_AES_") || it.startsWith("TLS_CHACHA20_")
            }.toTypedArray()
            secure.soTimeout = TIMEOUT_MS
            secure.startHandshake()
            val certificate = secure.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("Missing peer certificate")

            secure.outputStream.write(IdentityPacket.encode(identity.id, deviceName).toByteArray(Charsets.UTF_8))
            secure.outputStream.flush()
            val verified = IdentityPacket.decode(readLine(secure)) ?: throw IOException("Invalid encrypted identity")
            if (verified.id != first.id || verified.version != first.version) {
                throw IOException("Identity changed during handshake")
            }
            return Pending(secure, verified, certificate, pinnedCertificate != null)
        } catch (error: Exception) {
            runCatching { secure?.close() ?: socket.close() }
            if (error is IOException) throw error
            throw IOException("Handshake failed", error)
        }
    }

    private fun readLine(socket: Socket): String {
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L
        val input = socket.getInputStream()
        while (bytes.size() <= IdentityPacket.MAX_BYTES) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw SocketTimeoutException("Identity timeout")
            socket.soTimeout = remaining.coerceAtMost(TIMEOUT_MS.toLong()).coerceAtLeast(1).toInt()
            val next = input.read()
            if (next == -1) throw EOFException("Incomplete identity")
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            bytes.write(next)
        }
        throw IOException("Identity too large")
    }

    @SuppressLint("CustomX509TrustManager")
    private class PendingCertificateCheck(private val expectedId: String, private val pinned: X509Certificate?) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Unexpected client certificate")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.singleOrNull() ?: throw CertificateException("Invalid peer certificate chain")
            cert.checkValidity()
            if (pinned != null && !MessageDigest.isEqual(cert.encoded, pinned.encoded)) {
                throw CertificateException("Peer certificate changed")
            }
            val principal = cert.subjectX500Principal.getName("RFC2253")
            if (!principal.split(',').any { it.trim().equals("CN=$expectedId", ignoreCase = true) }) {
                throw CertificateException("Peer certificate identity mismatch")
            }
            try {
                cert.verify(cert.publicKey)
            } catch (e: Exception) {
                throw CertificateException("Peer certificate is not self-signed", e)
            }
        }
    }

    private const val TIMEOUT_MS = 10_000
}
