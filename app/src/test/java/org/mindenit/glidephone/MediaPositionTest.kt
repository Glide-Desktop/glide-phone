package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPositionTest {
    @Test
    fun positionAdvancesOnlyWhilePlayingAndStaysInRange() {
        val playing = PairingConnector.MediaState("vlc", isPlaying = true, position = 10_000, length = 60_000, positionTime = 1_000)
        assertEquals(15_000, playing.currentPosition(now = 6_000))
        assertEquals(60_000, playing.currentPosition(now = 600_000))
        assertEquals(10_000, playing.copy(isPlaying = false).currentPosition(now = 6_000))
    }

    @Test
    fun timesFormatLikeKdeConnect() {
        assertEquals("0:05", formatMediaTime(5_400))
        assertEquals("12:34", formatMediaTime((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatMediaTime((3600 + 2 * 60 + 3) * 1000L))
    }
}
