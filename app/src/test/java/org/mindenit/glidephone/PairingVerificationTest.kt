package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingVerificationTest {
    @Test
    fun v8CodeIsIndependentOfPeerOrder() {
        val a = byteArrayOf(97)
        val b = byteArrayOf(98)
        assertEquals("B070C279", PairingVerification.code(a, b, 0))
        assertEquals(PairingVerification.code(a, b, 0), PairingVerification.code(b, a, 0))
        assertNotEquals(PairingVerification.code(a, b, 0), PairingVerification.code(a, b, 1))
    }

    @Test
    fun sortsKeysAsUnsignedBytes() {
        val low = byteArrayOf(0x01)
        val high = byteArrayOf(0xff.toByte())
        assertEquals(PairingVerification.code(low, high, 10), PairingVerification.code(high, low, 10))
    }

    @Test
    fun certificateCodeMatchesSha256FingerprintPrefix() {
        val bytes = "GlideCertificate".toByteArray()
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(4).joinToString("") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xff) }
        assertEquals(expected, PairingVerification.certificateCode(bytes))
    }

    @org.junit.Test
    fun v7CodeIsOrderIndependentAndIgnoresTimestamp() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8, 7)
        org.junit.Assert.assertEquals(PairingVerification.codeV7(a, b), PairingVerification.codeV7(b, a))
        org.junit.Assert.assertEquals(8, PairingVerification.codeV7(a, b).length)
        // v7 key = SHA-256(sorted concat) without any timestamp suffix.
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(b + a)
            .take(4).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        org.junit.Assert.assertEquals(expected, PairingVerification.codeV7(a, b))
        org.junit.Assert.assertNotEquals(PairingVerification.code(a, b, 0), PairingVerification.codeV7(a, b))
    }
}
