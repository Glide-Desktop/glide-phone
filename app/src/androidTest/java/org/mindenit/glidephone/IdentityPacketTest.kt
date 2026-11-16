package org.mindenit.glidephone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityPacketTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun identityHasProtocolCapabilitiesAndNewline() {
        val wire = IdentityPacket.encode(id, "Мій телефон", 1716)
        assertTrue(wire.endsWith("\n"))
        val packet = JSONObject(wire)
        assertEquals("kdeconnect.identity", packet.getString("type"))
        val body = packet.getJSONObject("body")
        assertEquals(id, body.getString("deviceId"))
        assertEquals(8, body.getInt("protocolVersion"))
        assertEquals(1716, body.getInt("tcpPort"))
        val outgoing = body.getJSONArray("outgoingCapabilities")
        val capabilities = (0 until outgoing.length()).map { outgoing.getString(it) }.toSet()
        assertTrue("kdeconnect.battery" in capabilities)
        assertTrue("kdeconnect.notification" in capabilities)
        assertTrue(IdentityPacket.decode(wire)!!.incomingCapabilities.contains("kdeconnect.battery"))
        assertFalse(JSONObject(IdentityPacket.encode(id, "Phone")).getJSONObject("body").has("tcpPort"))
        assertEquals(id, IdentityPacket.decode(wire)?.id)
    }

    @Test
    fun rejectsUntrustedMalformedIdentity() {
        assertNull(IdentityPacket.decode("{}"))
        assertNull(IdentityPacket.decode("not json"))
        val wire = IdentityPacket.encode(id, "Phone")
        val changed = JSONObject(wire)
        changed.getJSONObject("body").put("deviceId", "bad")
        assertNull(IdentityPacket.decode(changed.toString()))
        assertNull(IdentityPacket.decode("x".repeat(IdentityPacket.MAX_BYTES + 1)))
    }
}
