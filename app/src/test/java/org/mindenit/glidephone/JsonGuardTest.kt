package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonGuardTest {
    @Test
    fun deeplyNestedLinesAreRefusedBeforeParsing() {
        val bomb = "[".repeat(60_000)
        assertFalse(JsonGuard.shallow(bomb))
        assertNull(IdentityPacket.decode("""{"type":"kdeconnect.identity","body":$bomb}"""))
        assertNull(PairingPacket.parse("""{"type":"kdeconnect.pair","body":{"x":$bomb}}"""))
    }

    @Test
    fun ordinaryPacketsAndBracketsInsideStringsPass() {
        assertTrue(JsonGuard.shallow("""{"body":{"a":[1,[2,{"b":3}]]}}"""))
        assertTrue(JsonGuard.shallow("""{"text":"${"[".repeat(500)} \" {{{"}"""))
        assertEquals(false, JsonGuard.shallow("[".repeat(65)))
        assertEquals(true, JsonGuard.shallow("[".repeat(64)))
    }
}

class IdentitySanitizingTest {
    @Test
    fun anOddDeviceTypeBecomesDesktop() {
        val line = """{"id":1,"type":"kdeconnect.identity","body":{"deviceId":"abcdefghijklmnopqrstuvwxyz012345","deviceName":"PC","protocolVersion":8,"deviceType":"${"x".repeat(5000)}"}}"""
        assertEquals("desktop", IdentityPacket.decode(line)?.type)
        assertTrue(WakeOnLan.isMac("a8:5e:45:01:02:03"))
        assertFalse(WakeOnLan.isMac("a8:5e:45:01:02:03" + "0".repeat(100)))
    }
}
