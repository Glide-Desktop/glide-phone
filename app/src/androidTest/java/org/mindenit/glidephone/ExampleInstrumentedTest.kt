package org.mindenit.glidephone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun launcherIconCanBeLoadedAndDrawn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.applicationInfo.loadIcon(context.packageManager)
        val bitmap = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(android.graphics.Canvas(bitmap))
        assertTrue((0 until bitmap.width).any { x ->
            (0 until bitmap.height).any { y -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) != 0 }
        })
        bitmap.recycle()
    }

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.mindenit.glidephone", appContext.packageName)
    }
}