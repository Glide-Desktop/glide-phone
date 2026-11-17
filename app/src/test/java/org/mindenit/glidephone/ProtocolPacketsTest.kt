package org.mindenit.glidephone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolPacketsTest {
    @Test
    fun presenterSpecialKeyPacket() {
        val json = ProtocolPackets.presenterSpecialKey(ProtocolPackets.KEY_PREV_SLIDE)
        assertTrue(json.endsWith("\n"))
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_MOUSEPAD_REQUEST, packet.getString("type"))
        assertEquals(8, packet.getJSONObject("body").getInt("specialKey"))

        val nextJson = ProtocolPackets.presenterSpecialKey(ProtocolPackets.KEY_NEXT_SLIDE)
        val nextPacket = JSONObject(nextJson)
        assertEquals(9, nextPacket.getJSONObject("body").getInt("specialKey"))
    }

    @Test
    fun presenterKeyPacket() {
        val json = ProtocolPackets.presenterKey("+", ctrl = true)
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_MOUSEPAD_REQUEST, packet.getString("type"))
        assertEquals("+", packet.getJSONObject("body").getString("key"))
        assertTrue(packet.getJSONObject("body").getBoolean("ctrl"))
    }

    @Test
    fun presenterMoveAndStopPackets() {
        val moveJson = ProtocolPackets.presenterMove(12.5f, -8.0f)
        val movePacket = JSONObject(moveJson)
        assertEquals(ProtocolPackets.TYPE_PRESENTER, movePacket.getString("type"))
        assertEquals(12.5, movePacket.getJSONObject("body").getDouble("dx"), 0.001)
        assertEquals(-8.0, movePacket.getJSONObject("body").getDouble("dy"), 0.001)

        val stopJson = ProtocolPackets.presenterStop()
        val stopPacket = JSONObject(stopJson)
        assertEquals(ProtocolPackets.TYPE_PRESENTER, stopPacket.getString("type"))
        assertTrue(stopPacket.getJSONObject("body").getBoolean("stop"))
    }

    @Test
    fun findMyPhonePacket() {
        val json = ProtocolPackets.findMyPhone()
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_FINDMYPHONE_REQUEST, packet.getString("type"))
        assertEquals(0, packet.getJSONObject("body").length())
    }

    @Test
    fun clipboardPacket() {
        val json = ProtocolPackets.clipboard("Hello Desktop!", 123456789L)
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_CLIPBOARD, packet.getString("type"))
        assertEquals("Hello Desktop!", packet.getJSONObject("body").getString("content"))
        assertEquals(123456789L, packet.getJSONObject("body").getLong("timestamp"))
    }

    @Test
    fun shareTextAndUrlPackets() {
        val textJson = ProtocolPackets.shareText("Note text")
        val textPacket = JSONObject(textJson)
        assertEquals(ProtocolPackets.TYPE_SHARE_REQUEST, textPacket.getString("type"))
        assertEquals("Note text", textPacket.getJSONObject("body").getString("text"))

        val urlJson = ProtocolPackets.shareUrl("https://glide.org")
        val urlPacket = JSONObject(urlJson)
        assertEquals(ProtocolPackets.TYPE_SHARE_REQUEST, urlPacket.getString("type"))
        assertEquals("https://glide.org", urlPacket.getJSONObject("body").getString("url"))
    }

    @Test
    fun notificationPacket() {
        val json = ProtocolPackets.notification(
            id = "notif-1",
            appName = "Glide Phone",
            title = "Test Title",
            text = "Test Message",
            silent = false,
            actions = listOf("Reply", "Dismiss")
        )
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_NOTIFICATION, packet.getString("type"))
        val body = packet.getJSONObject("body")
        assertEquals("notif-1", body.getString("id"))
        assertEquals("Glide Phone", body.getString("appName"))
        assertEquals("Test Title", body.getString("title"))
        assertEquals("Test Message", body.getString("text"))
        assertFalse(body.getBoolean("isCancel"))
        assertFalse(body.getBoolean("silent"))
        val actions = body.getJSONArray("actions")
        assertEquals(2, actions.length())
        assertEquals("Reply", actions.getString(0))
        assertEquals("Dismiss", actions.getString(1))

        val cancelJson = ProtocolPackets.notificationCancel("notif-1")
        val cancelPacket = JSONObject(cancelJson)
        assertEquals("notif-1", cancelPacket.getJSONObject("body").getString("id"))
        assertTrue(cancelPacket.getJSONObject("body").getBoolean("isCancel"))
    }

    @Test
    fun pingPacket() {
        val json = ProtocolPackets.ping("Desktop check")
        val packet = JSONObject(json)
        assertEquals(ProtocolPackets.TYPE_PING, packet.getString("type"))
        assertEquals("Desktop check", packet.getJSONObject("body").getString("message"))
    }

    @Test
    fun clipboardConnectPacket() {
        val packet = JSONObject(ProtocolPackets.clipboardConnect("sync", 42L))
        assertEquals(ProtocolPackets.TYPE_CLIPBOARD_CONNECT, packet.getString("type"))
        assertEquals("sync", packet.getJSONObject("body").getString("content"))
        assertEquals(42L, packet.getJSONObject("body").getLong("timestamp"))
    }

    @Test
    fun mousepadPackets() {
        val move = JSONObject(ProtocolPackets.mouseMove(3.0f, -4.0f))
        assertEquals(ProtocolPackets.TYPE_MOUSEPAD_REQUEST, move.getString("type"))
        assertEquals(3.0, move.getJSONObject("body").getDouble("dx"), 0.001)
        assertEquals(-4.0, move.getJSONObject("body").getDouble("dy"), 0.001)

        assertTrue(JSONObject(ProtocolPackets.mouseClick("left")).getJSONObject("body").getBoolean("singleclick"))
        assertTrue(JSONObject(ProtocolPackets.mouseClick("right")).getJSONObject("body").getBoolean("rightclick"))
        assertTrue(JSONObject(ProtocolPackets.mouseClick("middle")).getJSONObject("body").getBoolean("middleclick"))
        assertTrue(JSONObject(ProtocolPackets.mouseClick("double")).getJSONObject("body").getBoolean("doubleclick"))

        val scroll = JSONObject(ProtocolPackets.mouseScroll(0f, 10f))
        assertTrue(scroll.getJSONObject("body").getBoolean("scroll"))
        assertEquals(10.0, scroll.getJSONObject("body").getDouble("dy"), 0.001)

        val type = JSONObject(ProtocolPackets.mouseType("abc"))
        assertEquals("abc", type.getJSONObject("body").getString("key"))
    }

    @Test
    fun shareFilePacket() {
        val packet = JSONObject(ProtocolPackets.shareFile("doc.pdf", 2048L, 1740))
        assertEquals(ProtocolPackets.TYPE_SHARE_REQUEST, packet.getString("type"))
        val body = packet.getJSONObject("body")
        assertEquals("doc.pdf", body.getString("filename"))
        // Payload metadata is a top-level packet field in the KDE Connect protocol.
        assertFalse(body.has("payloadSize"))
        assertEquals(2048L, packet.getLong("payloadSize"))
        assertEquals(1740, packet.getJSONObject("payloadTransferInfo").getInt("port"))
    }

    @Test
    fun runCommandPackets() {
        val req = JSONObject(ProtocolPackets.runCommandRequest("abc-key"))
        assertEquals(ProtocolPackets.TYPE_RUNCOMMAND_REQUEST, req.getString("type"))
        assertEquals("abc-key", req.getJSONObject("body").getString("key"))

        val list = JSONObject(ProtocolPackets.runCommandRequestList())
        assertTrue(list.getJSONObject("body").getBoolean("requestCommandList"))
    }

    @Test
    fun mprisStatePacket() {
        val packet = JSONObject(ProtocolPackets.mprisState("VLC", "Song", "Artist", "Album", isPlaying = true))
        assertEquals(ProtocolPackets.TYPE_MPRIS, packet.getString("type"))
        val body = packet.getJSONObject("body")
        assertEquals("VLC", body.getString("player"))
        assertEquals("Song", body.getString("title"))
        assertTrue(body.getBoolean("isPlaying"))
    }

    @Test
    fun digitizerPackets() {
        val start = JSONObject(ProtocolPackets.digitizerStart(1000, 600, 16, 16))
        assertEquals(ProtocolPackets.TYPE_DIGITIZER_SESSION, start.getString("type"))
        assertEquals("start", start.getJSONObject("body").getString("action"))
        assertEquals(1000, start.getJSONObject("body").getInt("width"))
        assertEquals("end", JSONObject(ProtocolPackets.digitizerEnd()).getJSONObject("body").getString("action"))

        val down = JSONObject(ProtocolPackets.digitizerEvent(true, true, 5, 7, 0.5)).getJSONObject("body")
        assertTrue(down.getBoolean("active"))
        assertEquals("Pen", down.getString("tool"))
        assertEquals(7, down.getInt("y"))
        val up = JSONObject(ProtocolPackets.digitizerEvent(false, false, null, null, null)).getJSONObject("body")
        assertFalse(up.has("x"))
        assertFalse(up.getBoolean("touching"))
    }

    @Test
    fun telephonyAndContactsPackets() {
        val ring = JSONObject(ProtocolPackets.telephony("ringing", "Anna", "+380")).getJSONObject("body")
        assertEquals("ringing", ring.getString("event"))
        assertEquals("Anna", ring.getString("contactName"))
        assertFalse(ring.has("isCancel"))
        assertTrue(JSONObject(ProtocolPackets.telephony("ringing", "", "", isCancel = true)).getJSONObject("body").getBoolean("isCancel"))

        val uids = JSONObject(ProtocolPackets.contactsUids(mapOf("7" to 99L))).getJSONObject("body")
        assertEquals("7", uids.getJSONArray("uids").getString(0))
        assertEquals(99L, uids.getLong("7"))
    }

    @Test
    fun identityAdvertisesControllerDirections() {
        // The phone controls the computer's volume and media, so it must *send* requests
        // and *receive* state, not the other way round.
        assertTrue(ProtocolPackets.TYPE_SYSTEMVOLUME in IdentityPacket.INCOMING)
        assertTrue(ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST in IdentityPacket.OUTGOING)
        assertFalse(ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST in IdentityPacket.INCOMING)
        assertTrue(ProtocolPackets.TYPE_MPRIS in IdentityPacket.INCOMING)
        assertTrue(ProtocolPackets.TYPE_MPRIS_REQUEST in IdentityPacket.OUTGOING)
        val body = JSONObject(IdentityPacket.encode("a".repeat(36), "Phone", 1716)).getJSONObject("body")
        assertEquals(IdentityPacket.INCOMING.size, body.getJSONArray("incomingCapabilities").length())
    }

    @Test
    fun vcardGetsKdeConnectMetadataBeforeEnd() {
        val card = ContactsProvider.withMetadata("BEGIN:VCARD\r\nFN:Anna\r\nEND:VCARD\r\n", "dev-1", "42", 1700L)
        assertTrue(card.contains("X-KDECONNECT-ID-DEV-dev-1:42\r\nREV:1700\r\nEND:VCARD"))
        assertTrue(card.startsWith("BEGIN:VCARD"))
    }

    @Test
    fun multiFileShareCarriesBatchTotalsAndUpdate() {
        val packet = JSONObject(ProtocolPackets.shareFile("b.jpg", 10L, 1745, numberOfFiles = 3, totalPayloadSize = 30L, lastModified = 5L, open = false))
        val body = packet.getJSONObject("body")
        assertEquals(3, body.getInt("numberOfFiles"))
        assertEquals(30L, body.getLong("totalPayloadSize"))
        assertEquals(5L, body.getLong("lastModified"))
        assertFalse(body.has("open"))
        assertEquals(10L, packet.getLong("payloadSize"))

        assertTrue(JSONObject(ProtocolPackets.shareFile("a", 1L, 1740, open = true)).getJSONObject("body").getBoolean("open"))

        val update = JSONObject(ProtocolPackets.shareUpdate(4, 40L))
        assertEquals(ProtocolPackets.TYPE_SHARE_REQUEST_UPDATE, update.getString("type"))
        assertEquals(4, update.getJSONObject("body").getInt("numberOfFiles"))
        assertTrue(ProtocolPackets.TYPE_SHARE_REQUEST_UPDATE in IdentityPacket.INCOMING)
    }

    @Test
    fun runCommandSetupStopAndOutputCapability() {
        assertTrue(JSONObject(ProtocolPackets.runCommandSetup()).getJSONObject("body").getBoolean("setup"))
        assertTrue(JSONObject(ProtocolPackets.runCommandStop()).getJSONObject("body").getBoolean("stop"))
        assertEquals(ProtocolPackets.TYPE_RUNCOMMAND_REQUEST, JSONObject(ProtocolPackets.runCommandStop()).getString("type"))
        assertTrue(ProtocolPackets.TYPE_RUNCOMMAND_OUTPUT in IdentityPacket.INCOMING)
    }

    @Test
    fun privateLanMatchesKdeConnectRule() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        assertTrue(ip("192.168.1.5").isPrivateLan())
        assertTrue(ip("10.0.0.1").isPrivateLan())
        assertTrue(ip("100.101.102.103").isPrivateLan()) // CGNAT / Tailscale
        assertFalse(ip("100.128.0.1").isPrivateLan())
        assertTrue(ip("fd12:3456::1").isPrivateLan()) // ULA
        assertFalse(ip("8.8.8.8").isPrivateLan())
        assertTrue(NetworkSettings.isValidHost("192.168.1.20"))
        assertTrue(NetworkSettings.isValidHost("my-pc.local"))
        assertFalse(NetworkSettings.isValidHost("bad host"))
    }

    @Test
    fun mprisAdvertisesAlbumArtPayloadsAndArtReply() {
        assertTrue(JSONObject(ProtocolPackets.mprisList(listOf("VLC"))).getJSONObject("body").getBoolean("supportAlbumArtPayload"))
        val art = ProtocolPackets.mprisAlbumArt("VLC", "kdeconnect:/artUri?orig=x&kdeArtHash=1").getJSONObject("body")
        assertTrue(art.getBoolean("transferringAlbumArt"))
        assertEquals("VLC", art.getString("player"))
        val state = JSONObject(ProtocolPackets.mprisState("VLC", "t", "a", "b", true, canSeek = false, length = 5000, albumArtUrl = "u")).getJSONObject("body")
        assertFalse(state.getBoolean("canSeek"))
        assertEquals("u", state.getString("albumArtUrl"))
    }

    @Test
    fun superKeyThumbnailAndKeyboardState() {
        val sup = JSONObject(ProtocolPackets.superKey()).getJSONObject("body")
        assertEquals(20, sup.getInt("specialKey"))
        assertTrue(sup.getBoolean("super"))
        val ring = JSONObject(ProtocolPackets.telephony("ringing", "A", "1", thumbnail = "QUJD")).getJSONObject("body")
        assertEquals("QUJD", ring.getString("phoneThumbnail"))
        assertTrue(ProtocolPackets.TYPE_MOUSEPAD_KEYBOARDSTATE in IdentityPacket.INCOMING)
    }

    @Test
    fun everyAdvertisedTypeBelongsToAPlugin() {
        val incoming = DevicePlugin.entries.flatMap { it.incoming }.toSet()
        val outgoing = DevicePlugin.entries.flatMap { it.outgoing }.toSet()
        IdentityPacket.INCOMING.forEach { assertTrue("no plugin receives $it", it in incoming) }
        IdentityPacket.OUTGOING.forEach { assertTrue("no plugin sends $it", it in outgoing) }
    }

    @Test
    fun remoteInputReplies() {
        val echo = JSONObject(ProtocolPackets.mousepadEcho(JSONObject().put("key", "a").put("shift", true).put("sendAck", true)))
        assertEquals(ProtocolPackets.TYPE_MOUSEPAD_ECHO, echo.getString("type"))
        assertTrue(echo.getJSONObject("body").getBoolean("isAck"))
        assertTrue(echo.getJSONObject("body").getBoolean("shift"))
        assertFalse(echo.getJSONObject("body").has("sendAck"))
        assertFalse(JSONObject(ProtocolPackets.keyboardState(false)).getJSONObject("body").getBoolean("state"))
        val release = JSONObject(ProtocolPackets.shareInputDevicesRelease(3, 0)).getJSONObject("body")
        assertEquals(3, release.getInt("releaseDeltax"))
        assertTrue(ProtocolPackets.TYPE_SHAREINPUTDEVICES_REQUEST in IdentityPacket.INCOMING)
    }
}
