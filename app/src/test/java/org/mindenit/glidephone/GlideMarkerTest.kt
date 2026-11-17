package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideMarkerTest {
    private val id = "a".repeat(32)

    @Test
    fun ourIdentityCarriesTheMarkerAndDecodesAsGlide() {
        val peer = IdentityPacket.decode(IdentityPacket.encode(id, "Телефон"))!!
        assertTrue(peer.isGlide)
        assertEquals(IdentityPacket.GLIDE_PROTOCOL, peer.glideProtocol)
    }

    @Test
    fun aKdeConnectIdentityIsNotGlide() {
        val kde = """{"id":1,"type":"kdeconnect.identity","body":{"deviceId":"$id","deviceName":"kdeconnectd","protocolVersion":8}}"""
        assertFalse(IdentityPacket.decode(kde)!!.isGlide)
    }
}
