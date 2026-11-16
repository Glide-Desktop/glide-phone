package org.mindenit.glidephone

import android.annotation.SuppressLint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingHandshakeTest {
    private val prefix = "glide.handshake.test." + UUID.randomUUID() + "."
    private val filename = "handshake-$prefix.json"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanup() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.aliases().toList().filter { it.startsWith(prefix) }.forEach { store.deleteEntry(it) }
        File(context.noBackupFilesDir, filename).delete()
        File(context.noBackupFilesDir, "$filename.bak").delete()
    }

    @Test
    fun incomingV8ConnectionExchangesIdentityInsideTls() {
        val phone = LocalIdentity(prefix + "phone.").loadOrCreate()
        val peer = LocalIdentity(prefix + "peer.").loadOrCreate()
        val executor = Executors.newSingleThreadExecutor()
        val code = AtomicReference<String>()
        val timestamp = AtomicLong()
        val ready = CountDownLatch(1)
        val session = AtomicReference<OutgoingPairingSession>()
        val trustedPeers = TrustedPeerStore(context, filename)
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                val result = executor.submit<IdentityPacket.Peer> {
                    listener.accept().use { incoming ->
                        val pending = IncomingHandshake.open(incoming, phone, "Телефон") { null }
                        val pairing = OutgoingPairingSession(pending, phone, trustedPeers)
                        session.set(pairing)
                        val paired = pairing.run { displayedCode ->
                            code.set(displayedCode)
                            ready.countDown()
                        }
                        paired.socket.use {
                            try {
                                TrustedConnection(paired, phone, trustedPeers, { true }, { BatterySnapshot(63, true) }).run { true }
                                fail("Expected unpair")
                            } catch (e: IOException) {
                                assertEquals("Peer unpaired", e.message)
                            }
                        }
                        paired.peer
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write(IdentityPacket.encode(peer.id, "Ноутбук").toByteArray())
                    socket.getOutputStream().flush()
                    val tls = SSLContext.getInstance("TLSv1.2").apply {
                        init(arrayOf(IdentityKeyManager(peer)), arrayOf(acceptTestCertificate()), null)
                    }.socketFactory.createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
                    tls.use {
                        it.useClientMode = false
                        it.needClientAuth = true
                        it.soTimeout = 10_000
                        it.startHandshake()
                        val reader = it.inputStream.bufferedReader()
                        val received = IdentityPacket.decode(reader.readLine())
                        assertEquals(phone.id, received?.id)
                        it.outputStream.write(IdentityPacket.encode(peer.id, "Ноутбук").toByteArray())
                        it.outputStream.flush()
                        val request = requireNotNull(PairingPacket.parse(reader.readLine()))
                        assertTrue(request.accepted)
                        timestamp.set(request.timestampSeconds!!)
                        it.outputStream.write(PairingPacket.response(true).toByteArray())
                        it.outputStream.flush()
                        assertTrue(ready.await(5, TimeUnit.SECONDS))
                        assertNull(trustedPeers.certificate(peer.id, phone.id))
                        session.get().decide(true)
                        val battery = JSONObject(reader.readLine())
                        assertEquals("kdeconnect.battery", battery.getString("type"))
                        assertEquals(63, battery.getJSONObject("body").getInt("currentCharge"))
                        assertTrue(battery.getJSONObject("body").getBoolean("isCharging"))
                        assertEquals(peer.certificate, trustedPeers.certificate(peer.id, phone.id))
                        it.outputStream.write(PairingPacket.response(false).toByteArray())
                        it.outputStream.flush()
                    }
                }
                assertEquals(peer.id, result.get(15, TimeUnit.SECONDS).id)
                assertNull(trustedPeers.certificate(peer.id, phone.id))
                assertEquals(PairingVerification.code(phone.certificate.publicKey.encoded, peer.certificate.publicKey.encoded, timestamp.get()), code.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun acceptTestCertificate(): X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            assertTrue(!chain.isNullOrEmpty())
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }
}
