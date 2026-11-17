package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clear() {
        context.getSharedPreferences("notification_filter", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultsMatchKdeConnect() {
        val filter = NotificationFilter(context)
        assertTrue(filter.isEnabled("com.example.chat"))
        assertFalse(filter.isEnabled("com.google.android.googlequicksearchbox"))
        assertFalse(filter.blocksContents("com.example.chat"))
        assertFalse(filter.blocksImages("com.example.chat"))
        assertFalse(filter.alertOnlyWhenLocked)
    }

    @Test
    fun globalSwitchResetsPerAppChoices() {
        val filter = NotificationFilter(context)
        filter.setEnabled("com.example.a", false)
        filter.allEnabled = false
        assertFalse(filter.isEnabled("com.example.b"))
        filter.allEnabled = true
        assertTrue(filter.isEnabled("com.example.a"))
        filter.setBlocksContents("com.example.a", true)
        assertTrue(filter.blocksContents("com.example.a"))
    }
}
