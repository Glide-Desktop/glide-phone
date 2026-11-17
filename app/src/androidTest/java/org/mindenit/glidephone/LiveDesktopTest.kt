package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Talks to a real glide-phone-desktop build (not a fake). Skipped unless run with
 * `-e desktopHost 10.0.2.2 -e desktopPort <port> -e desktopId <id>`; the desktop side
 * accepts the pairing over D-Bus (see scripts/live-desktop-test.sh).
 */
class LiveDesktopTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "glide.live.test." + UUID.randomUUID() + "."
    private val store = "live-desktop-${UUID.randomUUID()}.json"
    private val pool = Executors.newCachedThreadPool()

    @After
    fun cleanup() {
        pool.shutdownNow()
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.aliases().toList().filter { it.startsWith(prefix) }.forEach { keys.deleteEntry(it) }
        File(context.noBackupFilesDir, store).delete()
    }

    @Test
    fun pairsWithTheRealDesktopAndExchangesPlugins() {
        val host = args.getString("desktopHost")
        val port = args.getString("desktopPort")?.toIntOrNull()
        val desktopId = args.getString("desktopId")
        assumeTrue("no live desktop given", host != null && port != null && desktopId != null)

        val identity = LocalIdentity(prefix).loadOrCreate()
        val peer = IdentityPacket.Peer(desktopId!!, "glide-phone", IdentityPacket.VERSION, "desktop", emptySet(), port)
        val pending = IncomingHandshake.connect(peer, InetAddress.getByName(host), identity, "Glide test phone") { null }
        assertTrue("the desktop must announce glideProtocol", pending.peer.isGlide)
        assertTrue("capabilities: ${pending.peer.incomingCapabilities}",
            ProtocolPackets.TYPE_RUNCOMMAND_REQUEST in pending.peer.incomingCapabilities)
        assertTrue(ProtocolPackets.TYPE_GLIDE_WOL in IdentityPacket.INCOMING)

        val session = PairingSession(pending, identity, TrustedPeerStore(context, store))
        session.initiate()
        val paired = pool.submit<IncomingHandshake.Pending> {
            session.run({ session.decide(true) }, { })
        }.get(90, TimeUnit.SECONDS)

        val reader = paired.socket.inputStream.bufferedReader()
        val out = paired.socket.outputStream
        paired.socket.soTimeout = 20_000
        val seen = mutableMapOf<String, JSONObject>()
        fun send(type: String, body: JSONObject) {
            out.write((JSONObject().put("id", System.currentTimeMillis()).put("type", type).put("body", body).toString() + "\n").toByteArray())
            out.flush()
        }
        fun awaitType(type: String, timeoutMs: Long = 20_000, check: (JSONObject) -> Boolean = { true }): JSONObject {
            seen[type]?.takeIf(check)?.let { return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val line = reader.readLine() ?: break
                val packet = JSONObject(line)
                val body = packet.optJSONObject("body") ?: JSONObject()
                seen[packet.optString("type")] = body
                if (packet.optString("type") == type && check(body)) return body
            }
            throw AssertionError("no $type from the desktop; got ${seen.keys}")
        }

        // The greeting of every desktop plugin.
        awaitType(ProtocolPackets.TYPE_RUNCOMMAND) { it.has("commandList") }
        awaitType(ProtocolPackets.TYPE_CONTACTS_REQUEST_UIDS)
        val wol = awaitType(ProtocolPackets.TYPE_GLIDE_WOL)
        assertTrue(wol.has("macs"))
        awaitType(ProtocolPackets.TYPE_MOUSEPAD_KEYBOARDSTATE)

        // Requests the phone makes, answered by the real desktop.
        send(ProtocolPackets.TYPE_MPRIS_REQUEST, JSONObject().put("requestPlayerList", true))
        val players = awaitType(ProtocolPackets.TYPE_MPRIS) { it.has("playerList") }
        assertEquals(true, players.optBoolean("supportAlbumArtPayload"))
        send(ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST, JSONObject().put("requestSinks", true))
        val sinks = awaitType(ProtocolPackets.TYPE_SYSTEMVOLUME) { it.has("sinkList") }
        assertTrue(sinks.getJSONArray("sinkList").length() >= 0)
        send(ProtocolPackets.TYPE_RUNCOMMAND_REQUEST, JSONObject().put("requestCommandList", true))
        awaitType(ProtocolPackets.TYPE_RUNCOMMAND) { it.has("commandList") }

        // What the computer sends to the phone (the script triggers it over D-Bus).
        awaitType(ProtocolPackets.TYPE_PING)
        val shared = awaitType(ProtocolPackets.TYPE_SHARE_REQUEST) { it.has("text") }
        assertEquals("glide-live-text", shared.getString("text"))
        val typed = awaitType(ProtocolPackets.TYPE_MOUSEPAD_REQUEST) { it.has("key") }
        assertEquals("ї", typed.getString("key"))

        // Things the desktop shows; the script checks them over D-Bus afterwards.
        send(ProtocolPackets.TYPE_BATTERY, JSONObject().put("currentCharge", 42).put("isCharging", true).put("thresholdEvent", 0))
        send(ProtocolPackets.TYPE_CONNECTIVITY_REPORT,
            JSONObject().put("signalStrengths", JSONObject().put("1", JSONObject().put("networkType", "LTE").put("signalStrength", 3))))
        send(ProtocolPackets.TYPE_PING, JSONObject())
        Thread.sleep(1500)
        paired.socket.close()
    }
}
