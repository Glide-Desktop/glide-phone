package org.mindenit.glidephone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolPacketsAndroidTest {
    @Test
    fun presenterPacketsProduceCorrectPayloads() {
        val prev = JSONObject(ProtocolPackets.presenterSpecialKey(ProtocolPackets.KEY_PREV_SLIDE))
        assertEquals(ProtocolPackets.TYPE_MOUSEPAD_REQUEST, prev.getString("type"))
        assertEquals(8, prev.getJSONObject("body").getInt("specialKey"))

        val next = JSONObject(ProtocolPackets.presenterSpecialKey(ProtocolPackets.KEY_NEXT_SLIDE))
        assertEquals(9, next.getJSONObject("body").getInt("specialKey"))

        val move = JSONObject(ProtocolPackets.presenterMove(5.0f, -3.0f))
        assertEquals(ProtocolPackets.TYPE_PRESENTER, move.getString("type"))
        assertEquals(5.0, move.getJSONObject("body").getDouble("dx"), 0.001)
        assertEquals(-3.0, move.getJSONObject("body").getDouble("dy"), 0.001)

        val stop = JSONObject(ProtocolPackets.presenterStop())
        assertTrue(stop.getJSONObject("body").getBoolean("stop"))
    }

    @Test
    fun findMyPhonePacketIsCompliant() {
        val packet = JSONObject(ProtocolPackets.findMyPhone())
        assertEquals(ProtocolPackets.TYPE_FINDMYPHONE_REQUEST, packet.getString("type"))
        assertTrue(packet.has("id"))
    }

    @Test
    fun clipboardAndSharePacketsAreCompliant() {
        val clip = JSONObject(ProtocolPackets.clipboard("test", 1000L))
        assertEquals(ProtocolPackets.TYPE_CLIPBOARD, clip.getString("type"))
        assertEquals("test", clip.getJSONObject("body").getString("content"))

        val share = JSONObject(ProtocolPackets.shareUrl("https://glide.org"))
        assertEquals(ProtocolPackets.TYPE_SHARE_REQUEST, share.getString("type"))
        assertEquals("https://glide.org", share.getJSONObject("body").getString("url"))
    }
}
