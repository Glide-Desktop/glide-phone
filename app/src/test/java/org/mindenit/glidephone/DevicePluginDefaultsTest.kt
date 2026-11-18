package org.mindenit.glidephone

import android.content.res.Configuration
import android.content.res.Resources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePluginDefaultsTest {
    /** Every plugin's default can be asked for (the digitizer's used to throw a NullPointerException). */
    @Test
    fun everyDefaultCanBeRead() {
        val config = Configuration().apply { screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL }
        val context = object : android.content.ContextWrapper(null) {
            override fun getResources(): Resources = object : Resources(null, null, null) {
                override fun getConfiguration(): Configuration = config
            }
        }
        val defaults = DevicePlugin.entries.associateWith { it.isEnabledByDefault(context) }
        assertFalse("a phone draws on no tablet by default", defaults.getValue(DevicePlugin.DIGITIZER))
        assertTrue(defaults.getValue(DevicePlugin.BATTERY))
    }
}
