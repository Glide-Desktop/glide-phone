package org.mindenit.glidephone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotSyncTest {
    @Test
    fun screenshotFoldersAreRecognised() {
        assertTrue(ScreenshotSync.isScreenshotPath("Pictures/Screenshots/"))
        assertTrue(ScreenshotSync.isScreenshotPath("DCIM/Screenshots/"))
        assertTrue(ScreenshotSync.isScreenshotPath("/storage/emulated/0/Pictures/Screenshots/a.png"))
        assertFalse(ScreenshotSync.isScreenshotPath("DCIM/Camera/"))
        assertFalse(ScreenshotSync.isScreenshotPath(null))
    }
}
