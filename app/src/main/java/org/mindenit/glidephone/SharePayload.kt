package org.mindenit.glidephone

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Serves a single file over an encrypted KDE Connect payload channel. The phone acts
 * as the TLS server (as the payload sender does in the KDE Connect LAN protocol);
 * the receiver connects to the advertised port and authenticates with its pinned
 * certificate before any bytes are transferred.
 */
internal object SharePayload {
    class Handle(val port: Int, private val server: ServerSocket) {
        fun cancel() { runCatching { server.close() } }
    }

    fun serve(
        identity: LocalIdentity.Identity,
        peerCertificate: X509Certificate,
        uri: Uri,
        resolver: ContentResolver,
        size: Long
    ): Handle {
        val server = openPayloadServer()
        server.soTimeout = ACCEPT_TIMEOUT_MS
        Thread({
            server.use {
                try {
                    val raw = server.accept()
                    val secure = wrapServer(raw, identity, peerCertificate)
                    secure.startHandshake()
                    resolver.openInputStream(uri)?.use { input ->
                        val out = secure.outputStream
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            sent += read
                            if (size in 0 until sent) break
                        }
                        out.flush()
                    }
                    runCatching { secure.close() }
                } catch (_: Exception) {
                }
            }
        }, "GlideSharePayload").apply { isDaemon = true; start() }
        return Handle(server.localPort, server)
    }

    fun queryName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun querySize(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun openPayloadServer(): ServerSocket {
        for (port in 1739..1764) {
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                return socket
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
        error("No available payload port")
    }

    private fun wrapServer(socket: java.net.Socket, identity: LocalIdentity.Identity, peer: X509Certificate): SSLSocket {
        val trust = PinnedClientCheck(peer)
        val context = SSLContext.getInstance("TLSv1.2")
        context.init(arrayOf(IdentityKeyManager(identity)), arrayOf(trust), null)
        val secure = context.socketFactory.createSocket(socket, socket.inetAddress.hostAddress, socket.port, true) as SSLSocket
        secure.useClientMode = false
        secure.needClientAuth = true
        secure.enabledCipherSuites = secure.enabledCipherSuites.filter {
            it.startsWith("TLS_ECDHE_") || it.startsWith("TLS_AES_") || it.startsWith("TLS_CHACHA20_")
        }.toTypedArray()
        secure.soTimeout = HANDSHAKE_TIMEOUT_MS
        return secure
    }

    @SuppressLint("CustomX509TrustManager")
    private class PinnedClientCheck(private val pinned: X509Certificate) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Unexpected server certificate")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("Missing client certificate")
            if (!MessageDigest.isEqual(cert.encoded, pinned.encoded)) {
                throw CertificateException("Payload peer certificate mismatch")
            }
        }
    }

    private const val ACCEPT_TIMEOUT_MS = 30_000
    private const val HANDSHAKE_TIMEOUT_MS = 15_000
}
