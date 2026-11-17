package org.mindenit.glidephone

import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class RemoteMediaNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val device = "media-test-device"

    @Before
    fun setUp() {
        GlideHub.offline = true
        instrumentation.runOnMainSync { GlideHub.resetForTests() }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            GlideHub.injectForTests(context, PairingConnector.Event.Disconnected(device))
            GlideHub.resetForTests()
        }
        GlideHub.offline = false
    }

    private fun shown(): Notification? = manager.activeNotifications.firstOrNull { it.id == 0x6d70 }?.notification

    private fun inject(event: PairingConnector.Event) = instrumentation.runOnMainSync { GlideHub.injectForTests(context, event) }

    @Test
    fun followsTheComputersPlayer() {
        inject(PairingConnector.Event.Connected(device, "Ноутбук"))
        inject(PairingConnector.Event.MediaPlayers(device, listOf("VLC")))
        inject(PairingConnector.Event.MediaUpdate(device, PairingConnector.MediaState("VLC", title = "Пісня", artist = "Гурт", isPlaying = true)))
        Thread.sleep(300)
        val playing = requireNotNull(shown())
        assertEquals("Пісня", playing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(3, playing.actions.size) // previous, pause, next
        assertTrue(playing.flags and Notification.FLAG_ONGOING_EVENT != 0)

        inject(PairingConnector.Event.MediaUpdate(device, PairingConnector.MediaState("VLC", title = "Пісня", isPlaying = false, canGoNext = false)))
        Thread.sleep(300)
        val paused = shown()!!
        assertFalse(paused.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(2, paused.actions.size) // previous, play

        inject(PairingConnector.Event.Disconnected(device))
        Thread.sleep(300)
        assertNull(shown())
    }
}
