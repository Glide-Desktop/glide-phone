package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun theMagicPacketIsSixFfThenTheMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket("2c:f0:5d:79:a2:9a")!!
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        val mac = byteArrayOf(0x2c, 0xf0.toByte(), 0x5d, 0x79, 0xa2.toByte(), 0x9a.toByte()).toList()
        for (i in 0 until 16) assertEquals(mac, packet.toList().subList(6 + i * 6, 12 + i * 6))
        assertNull(WakeOnLan.magicPacket("not-a-mac"))
    }

    @Test
    fun wakeOnLanIsAdvertisedAndOwnedByItsPlugin() {
        assertTrue(ProtocolPackets.TYPE_GLIDE_WOL in IdentityPacket.INCOMING)
        assertTrue(DevicePlugin.entries.any { ProtocolPackets.TYPE_GLIDE_WOL in it.incoming })
    }
}
