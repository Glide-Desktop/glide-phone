package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingStateTest {
    private fun request() = PairingState(123, 1_000, byteArrayOf(1), byteArrayOf(2))

    @Test
    fun bothSidesMustConfirmInEitherOrder() {
        val first = request()
        assertEquals(PairingState.Status.WAITING, first.onPeerResponse(true, 2_000))
        assertEquals(PairingState.Status.CONFIRMED, first.confirmLocally(3_000))
        val second = request()
        assertEquals(PairingState.Status.WAITING, second.confirmLocally(2_000))
        assertEquals(PairingState.Status.CONFIRMED, second.onPeerResponse(true, 3_000))
    }

    @Test
    fun rejectedOrExpiredRequestsCannotBecomeTrusted() {
        val rejected = request()
        assertEquals(PairingState.Status.REJECTED, rejected.onPeerResponse(false, 2_000))
        assertEquals(PairingState.Status.REJECTED, rejected.confirmLocally(3_000))
        val expired = request()
        assertEquals(PairingState.Status.WAITING, expired.confirmLocally(30_999))
        assertEquals(PairingState.Status.EXPIRED, expired.onPeerResponse(true, 31_000))
        assertEquals(PairingState.Status.EXPIRED, expired.confirmLocally(31_001))
        val cancelled = request()
        assertEquals(PairingState.Status.REJECTED, cancelled.reject())
        assertEquals(PairingState.Status.REJECTED, cancelled.onPeerResponse(true, 2_000))
    }
}
