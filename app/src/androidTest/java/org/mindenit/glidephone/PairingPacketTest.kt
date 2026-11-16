package org.mindenit.glidephone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingPacketTest {
    @Test
    fun requestAndResponseUseProtocolFraming() {
        val request = PairingPacket.request(1234, 42)
        assertTrue(request.endsWith("\n"))
        val root = JSONObject(request)
        assertEquals("kdeconnect.pair", root.getString("type"))
        assertEquals(42L, root.getLong("id"))
        assertEquals(1234L, root.getJSONObject("body").getLong("timestamp"))
        assertEquals(PairingPacket.Message(true, 1234), PairingPacket.parse(request))
        val rejection = PairingPacket.response(false, 43)
        assertFalse(JSONObject(rejection).getJSONObject("body").has("timestamp"))
        assertEquals(PairingPacket.Message(false, null), PairingPacket.parse(rejection))
    }

    @Test
    fun invalidPacketsCannotImplyAcceptance() {
        assertNull(PairingPacket.parse("{}"))
        assertNull(PairingPacket.parse("""{"type":"kdeconnect.pair","body":{"pair":"true"}}"""))
        assertNull(PairingPacket.parse("""{"type":"kdeconnect.pair","body":{"pair":true,"timestamp":-1}}"""))
        assertNull(PairingPacket.parse("x".repeat(IdentityPacket.MAX_BYTES + 1)))
    }
}
