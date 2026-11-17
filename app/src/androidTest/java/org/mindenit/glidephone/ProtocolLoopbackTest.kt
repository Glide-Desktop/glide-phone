package org.mindenit.glidephone

import android.annotation.SuppressLint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Loopback tests where the test plays the remote peer, covering paths the desktop daemon
 * cannot exercise: both TLS roles, v7 peers, pairing in either direction, payloads, SFTP.
 */
class ProtocolLoopbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "glide.loopback.test." + UUID.randomUUID() + "."
    private val storeA = "loopback-a-${UUID.randomUUID()}.json"
    private val storeB = "loopback-b-${UUID.randomUUID()}.json"
    private val pool = Executors.newCachedThreadPool()
    private val localhost: InetAddress = InetAddress.getByName("127.0.0.1")

    @After
    fun cleanup() {
        pool.shutdownNow()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().filter { it.startsWith(prefix) }.forEach { store.deleteEntry(it) }
        listOf(storeA, storeB).forEach { name ->
            File(context.noBackupFilesDir, name).delete()
            File(context.noBackupFilesDir, "$name.bak").delete()
        }
    }

    private fun identity(name: String) = LocalIdentity(prefix + name + ".").loadOrCreate()

    /** A = phone side accepting (open), B = our own dialing code (connect). */
    private fun handshakePair(a: LocalIdentity.Identity, b: LocalIdentity.Identity): Pair<IncomingHandshake.Pending, IncomingHandshake.Pending> {
        ServerSocket(0, 1, localhost).use { listener ->
            val accepted: Future<IncomingHandshake.Pending> = pool.submit<IncomingHandshake.Pending> {
                IncomingHandshake.open(listener.accept(), a, "Телефон") { null }
            }
            val peerA = IdentityPacket.Peer(a.id, "Телефон", IdentityPacket.VERSION, "phone", emptySet(), listener.localPort)
            val dialed = IncomingHandshake.connect(peerA, localhost, b, "Комп’ютер") { null }
                .let { it.copy(socket = it.socket) }
            return accepted.get(20, TimeUnit.SECONDS) to dialed
        }
    }

    private fun IncomingHandshake.Pending.copy(socket: SSLSocket) = IncomingHandshake.Pending(socket, peer, certificate, previouslyPinned)

    @Test
    fun ourDialerAndListenerInteroperateInBothTlsRoles() {
        val a = identity("a")
        val b = identity("b")
        val (atA, atB) = handshakePair(a, b)
        try {
            assertEquals(b.id, atA.peer.id)
            assertEquals("Комп’ютер", atA.peer.name)
            assertEquals(a.id, atB.peer.id)
            assertEquals(b.certificate, atA.certificate)
            assertEquals(a.certificate, atB.certificate)
            assertTrue(atA.socket.useClientMode)   // accepted TCP => TLS client
            assertFalse(atB.socket.useClientMode)  // dialed TCP => TLS server
        } finally {
            atA.socket.close(); atB.socket.close()
        }
    }

    @Test
    fun protocolV7PeerSkipsTheEncryptedIdentity() {
        val a = identity("a7")
        val b = identity("b7")
        ServerSocket(0, 1, localhost).use { listener ->
            val accepted = pool.submit<IncomingHandshake.Pending> { IncomingHandshake.open(listener.accept(), a, "Телефон") { null } }
            Socket(localhost, listener.localPort).use { raw ->
                val v7 = org.json.JSONObject(IdentityPacket.encode(b.id, "Старий ПК"))
                v7.getJSONObject("body").put("protocolVersion", 7)
                raw.getOutputStream().write((v7.toString() + "\n").toByteArray())
                val tls = SSLContext.getInstance("TLSv1.2").apply {
                    init(arrayOf(IdentityKeyManager(b)), arrayOf(trustAll()), null)
                }.socketFactory.createSocket(raw, "127.0.0.1", raw.port, true) as SSLSocket
                tls.useClientMode = false
                tls.needClientAuth = true
                tls.startHandshake()
                val pending = accepted.get(20, TimeUnit.SECONDS)
                assertEquals(7, pending.peer.version)
                assertEquals(b.id, pending.peer.id)
                pending.socket.close()
                tls.close()
            }
        }
    }

    @Test
    fun pairingWorksFromEitherSideAndSurvivesARejection() {
        val a = identity("pa")
        val b = identity("pb")
        val (atA, atB) = handshakePair(a, b)
        val storeAtA = TrustedPeerStore(context, storeA)
        val storeAtB = TrustedPeerStore(context, storeB)
        val sessionA = PairingSession(atA, a, storeAtA)
        val sessionB = PairingSession(atB, b, storeAtB)
        val codeA = AtomicReference<String>()
        val codeB = AtomicReference<String>()
        val shownA = AtomicInteger()
        val shownB = AtomicInteger()
        val endedA = AtomicInteger()
        val runA = pool.submit<IncomingHandshake.Pending> {
            sessionA.run({ codeA.set(it); shownA.incrementAndGet() }, { endedA.incrementAndGet() })
        }
        val runB = pool.submit<IncomingHandshake.Pending> {
            sessionB.run({ codeB.set(it); shownB.incrementAndGet() }, { })
        }
        try {
            // A asks, B declines: both sides go back to idle, the link stays up.
            sessionA.initiate()
            waitUntil { shownB.get() == 1 }
            sessionB.decide(false)
            waitUntil { endedA.get() == 1 }
            // B asks this time, both confirm.
            sessionB.initiate()
            waitUntil { shownA.get() == 2 && shownB.get() == 2 }
            assertEquals(codeA.get(), codeB.get())
            sessionA.decide(true)
            sessionB.decide(true)
            runA.get(20, TimeUnit.SECONDS)
            runB.get(20, TimeUnit.SECONDS)
            assertEquals(b.certificate, storeAtA.certificate(b.id, a.id))
            assertEquals(a.certificate, storeAtB.certificate(a.id, b.id))
        } finally {
            atA.socket.close(); atB.socket.close()
        }
    }

    @Test
    fun staleTimestampIsRefusedWithoutShowingACode() {
        val a = identity("sa")
        val b = identity("sb")
        val (atA, atB) = handshakePair(a, b)
        val shown = AtomicInteger()
        val ended = CountDownLatch(1)
        pool.submit<IncomingHandshake.Pending> {
            PairingSession(atA, a, TrustedPeerStore(context, storeA)).run({ shown.incrementAndGet() }, { ended.countDown() })
        }
        try {
            val stale = System.currentTimeMillis() / 1000 - 3 * 3600
            atB.socket.outputStream.write(PairingPacket.request(stale).toByteArray())
            atB.socket.outputStream.flush()
            val reply = PairingPacket.parse(atB.socket.inputStream.bufferedReader().readLine())
            assertNotNull(reply)
            assertFalse(reply!!.accepted)
            assertTrue(ended.await(5, TimeUnit.SECONDS))
            assertEquals(0, shown.get())
        } finally {
            atA.socket.close(); atB.socket.close()
        }
    }

    @Test
    fun payloadsTransferOneAfterAnotherBetweenPinnedPeers() {
        val sender = identity("ps")
        val receiver = identity("pr")
        val files = listOf(ByteArray(10) { 1 }, ByteArray(70_000) { (it % 251).toByte() }, ByteArray(0))
        files.forEach { bytes ->
            val handle = SharePayload.serve(sender, receiver.certificate, ByteArrayInputStream(bytes), bytes.size.toLong())
            val got = SharePayload.receiveBytes(receiver, sender.certificate, localhost, handle.port, bytes.size.toLong())
            assertArrayEquals(bytes, got)
            assertTrue(handle.await(5_000))
        }
    }

    @Test
    fun payloadRefusesAnUnpinnedReceiver() {
        val sender = identity("us")
        val receiver = identity("ur")
        val stranger = identity("ux")
        val handle = SharePayload.serve(sender, receiver.certificate, ByteArrayInputStream(ByteArray(8)), 8)
        assertEquals(null, SharePayload.receiveBytes(stranger, sender.certificate, localhost, handle.port, 8))
        assertFalse(handle.await(5_000))
    }

    @Test
    fun sftpServerAcceptsThePairingKeyAndThePassword() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val deviceKeys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val root = File(context.filesDir, "sftp-test-${UUID.randomUUID()}").apply { mkdirs() }
        File(root, "hello.txt").writeText("hi")
        val server = SftpServer(context, deviceKeys.public)
        server.setStorageDirs(listOf(java.nio.file.Paths.get(root.path)))
        assertTrue(server.start())
        val password = server.regeneratePassword()
        val client = org.apache.sshd.client.SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
            start()
        }
        try {
            // Password login, then list the storage directory.
            client.connect(SftpServer.USER, "127.0.0.1", server.port).verify(10_000).session.use { session ->
                session.addPasswordIdentity(password)
                session.auth().verify(10_000)
                org.apache.sshd.sftp.client.SftpClientFactory.instance().createSftpClient(session).use { sftp ->
                    val names = sftp.readDir(root.path).map { it.filename }
                    assertTrue(names.contains("hello.txt"))
                    assertEquals("hi", sftp.read(File(root, "hello.txt").path).use { it.readBytes().decodeToString() })
                    // Nothing outside the volumes: not the app's own data, not /data.
                    val secret = File(context.noBackupFilesDir, "sftp-test-secret").apply { writeText("pin") }
                    try {
                        assertFalse(runCatching { sftp.read(secret.path).use { it.readBytes() } }.isSuccess)
                        assertFalse(runCatching { sftp.write(File(context.filesDir, "planted").path).use { it.write(1) } }.isSuccess)
                        assertFalse(File(context.filesDir, "planted").exists())
                        // Above the volume (here the test root lives in filesDir) only the way down shows.
                        val above = sftp.readDir(context.dataDir.path).map { it.filename }.filter { it != "." && it != ".." }
                        assertEquals(listOf("files"), above)
                        assertFalse(runCatching { sftp.read(File(root, "../../no_backup/sftp-test-secret").path).use { it.readBytes() } }.isSuccess)
                        val top = sftp.readDir("/").map { it.filename }.filter { it != "." && it != ".." }
                        assertEquals("only the way down to the volume", listOf("data"), top)
                    } finally {
                        secret.delete()
                    }
                }
            }
            // Key login with the device's pairing key.
            client.connect(SftpServer.USER, "127.0.0.1", server.port).verify(10_000).session.use { session ->
                session.addPublicKeyIdentity(deviceKeys)
                session.auth().verify(10_000)
                assertTrue(session.isAuthenticated)
            }
            // A wrong password is rejected.
            client.connect(SftpServer.USER, "127.0.0.1", server.port).verify(10_000).session.use { session ->
                session.addPasswordIdentity("wrong")
                assertFalse(runCatching { session.auth().verify(10_000) }.isSuccess)
            }
        } finally {
            client.stop()
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun disabledPluginsDropPacketsAndBlockSends() {
        val settings = DevicePluginSettings(context)
        val device = "test-device-" + UUID.randomUUID().toString().take(8)
        try {
            // Receiving the computer's notifications is opt-in, as in KDE Connect.
            assertFalse(settings.accepts(device, ProtocolPackets.TYPE_NOTIFICATION))
            assertFalse(settings.allowsSending(device, ProtocolPackets.TYPE_NOTIFICATION_REQUEST))
            settings.setEnabled(device, DevicePlugin.RECEIVE_NOTIFICATIONS, true)
            assertTrue(settings.accepts(device, ProtocolPackets.TYPE_NOTIFICATION))
            // Clipboard is on by default and can be switched off.
            assertTrue(settings.accepts(device, ProtocolPackets.TYPE_CLIPBOARD))
            settings.setEnabled(device, DevicePlugin.CLIPBOARD, false)
            assertFalse(settings.accepts(device, ProtocolPackets.TYPE_CLIPBOARD))
            assertFalse(settings.allowsSending(device, ProtocolPackets.TYPE_CLIPBOARD))
        } finally {
            settings.clear(device)
        }
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition not met in time")
            Thread.sleep(50)
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun trustAll(): X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }
}
