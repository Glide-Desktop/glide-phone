package org.mindenit.glidephone

import android.content.ClipData
import android.content.ClipboardManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClipboardFloatingActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        GlideHub.offline = true
        instrumentation.runOnMainSync { GlideHub.resetForTests() }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { GlideHub.resetForTests() }
        GlideHub.offline = false
    }

    @Test
    fun readsTheClipboardWhenFocusedThenCloses() {
        val connector = GlideHub.connector(context)
        val text = "glide-clip-" + System.nanoTime()
        instrumentation.runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", text))
        }
        ActivityScenario.launch<ClipboardFloatingActivity>(ClipboardFloatingActivity.intent(context, showToast = false)).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals(text, connector.clipboardSnapshot)
    }
}
