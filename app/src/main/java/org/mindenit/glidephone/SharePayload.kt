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
        private val done = java.util.concurrent.CountDownLatch(1)
        @Volatile internal var succeeded = false

        internal fun finish(success: Boolean) { succeeded = success; done.countDown() }

        fun cancel() { runCatching { server.close() } }

        /** Blocks until the peer fetched the whole file (or the transfer failed). */
        fun await(timeoutMs: Long): Boolean =
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && succeeded
    }

    /** Result of a completed download. */
    class Received(val name: String, val uri: Uri?)

    fun serve(
        identity: LocalIdentity.Identity,
        peerCertificate: X509Certificate,
        source: java.io.InputStream,
        size: Long,
        acceptTimeoutMs: Int = ACCEPT_TIMEOUT_MS,
        onProgress: (Long) -> Unit = {}
    ): Handle {
        val server = openPayloadServer()
        server.soTimeout = acceptTimeoutMs
        lateinit var handle: Handle
        Thread({
            var ok = false
            server.use {
                try {
                    val raw = server.accept()
                    val secure = wrapServer(raw, identity, peerCertificate)
                    secure.startHandshake()
                    source.use { input ->
                        val out = secure.outputStream
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            sent += read
                            onProgress(sent)
                            if (size in 0 until sent) break
                        }
                        out.flush()
                        ok = size < 0 || sent >= size
                    }
                    runCatching { secure.close() }
                } catch (_: Exception) {
                }
            }
            runCatching { source.close() }
            handle.finish(ok)
        }, "GlideSharePayload").apply { isDaemon = true }.also { thread ->
            handle = Handle(server.localPort, server)
            thread.start()
        }
        return handle
    }

    /**
     * Downloads a file a trusted peer is offering on [port] (we are the TLS client) and
     * stores it in the public Downloads folder. Returns the saved file name, or null.
     */
    fun receive(
        context: android.content.Context,
        identity: LocalIdentity.Identity,
        peerCertificate: X509Certificate,
        host: java.net.InetAddress,
        port: Int,
        size: Long,
        filename: String,
        onProgress: (Long) -> Unit = {}
    ): Received? {
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.trim().take(120).ifBlank { "glide-file" }
        var secure: SSLSocket? = null
        return try {
            val raw = java.net.Socket()
            raw.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            val context2 = SSLContext.getInstance("TLSv1.2")
            context2.init(arrayOf(IdentityKeyManager(identity)), arrayOf(PinnedServerCheck(peerCertificate)), null)
            secure = (context2.socketFactory.createSocket(raw, host.hostAddress, port, true) as SSLSocket).apply {
                useClientMode = true
                soTimeout = ACCEPT_TIMEOUT_MS
                startHandshake()
            }
            val input = secure.inputStream
            val target = openDownload(context, safeName) ?: return null
            target.second.use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (size < 0 || total < size) {
                    val want = if (size < 0) buffer.size else minOf(buffer.size.toLong(), size - total).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
                out.flush()
                if (size >= 0 && total < size) {
                    target.first?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                    return null
                }
            }
            Received(safeName, target.first)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { secure?.close() }
        }
    }

    /** Fetch a small payload (album art, icons) into memory; null on failure or if too large. */
    fun receiveBytes(
        identity: LocalIdentity.Identity,
        peerCertificate: X509Certificate,
        host: java.net.InetAddress,
        port: Int,
        size: Long,
        maxBytes: Int = 8 * 1024 * 1024
    ): ByteArray? {
        if (size > maxBytes) return null
        var secure: SSLSocket? = null
        return try {
            val raw = java.net.Socket()
            raw.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
            val tls = SSLContext.getInstance("TLSv1.2")
            tls.init(arrayOf(IdentityKeyManager(identity)), arrayOf(PinnedServerCheck(peerCertificate)), null)
            secure = (tls.socketFactory.createSocket(raw, host.hostAddress, port, true) as SSLSocket).apply {
                useClientMode = true
                soTimeout = HANDSHAKE_TIMEOUT_MS
                startHandshake()
            }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            val input = secure.inputStream
            while (size < 0 || out.size() < size) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > maxBytes) return null
            }
            if (size >= 0 && out.size() < size) null else out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { secure?.close() }
        }
    }

    private fun openDownload(context: android.content.Context, name: String): Pair<Uri?, java.io.OutputStream>? {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            }
            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val out = context.contentResolver.openOutputStream(uri) ?: return null
            return uri to out
        }
        val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return null
        val file = java.io.File(dir, name)
        return null as Uri? to file.outputStream()
    }

    @SuppressLint("CustomX509TrustManager")
    private class PinnedServerCheck(private val pinned: X509Certificate) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Unexpected client certificate")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
            if (!MessageDigest.isEqual(cert.encoded, pinned.encoded)) {
                throw CertificateException("Payload peer certificate mismatch")
            }
        }
    }

    fun queryName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun queryLastModified(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

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
