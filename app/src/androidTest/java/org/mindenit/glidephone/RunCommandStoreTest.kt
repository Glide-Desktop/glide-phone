package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RunCommandStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = "store-test-device"

    @After fun cleanup() = RunCommandStore.forget(context, device)

    @Test
    fun commandsSurviveForOfflineControlsAndWidgets() {
        val commands = listOf(PairingConnector.RemoteCommand("k1", "Заблокувати", "loginctl lock-session"))
        RunCommandStore.save(context, device, "Ноутбук", commands)
        val stored = RunCommandStore.device(context, device)!!
        assertEquals("Ноутбук", stored.name)
        assertEquals(commands, stored.commands)
        // The computer is not connected: running reports failure instead of pretending.
        GlideHub.offline = true
        try { assertFalse(RunCommandStore.run(context, device, "k1")) } finally { GlideHub.offline = false }
        RunCommandStore.forget(context, device)
        assertNull(RunCommandStore.device(context, device))
    }
}
