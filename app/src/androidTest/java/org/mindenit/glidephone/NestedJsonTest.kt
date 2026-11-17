package org.mindenit.glidephone

import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** The crash JsonGuard prevents, on Android's own org.json (the JVM unit tests use another). */
class NestedJsonTest {
    private val bomb = """{"type":"kdeconnect.identity","body":${"[".repeat(60_000)}}"""

    @Test
    fun androidsOrgJsonOverflowsTheStackOnDeepNesting() {
        val thrown = AtomicReference<Throwable?>()
        // A thread with the default stack, like the app's network threads.
        Thread {
            try { JSONObject(bomb) } catch (t: Throwable) { thrown.set(t) }
        }.apply { start(); join() }
        assertTrue("got ${thrown.get()}", thrown.get() is StackOverflowError)
    }

    @Test
    fun theAppsParsersRefuseItWithoutCrashing() {
        val result = AtomicReference<Any?>("unset")
        val error = AtomicReference<Throwable?>()
        Thread {
            try { result.set(IdentityPacket.decode(bomb) ?: PairingPacket.parse(bomb)) } catch (t: Throwable) { error.set(t) }
        }.apply { start(); join() }
        assertNull(error.get())
        assertNull(result.get())
    }
}
