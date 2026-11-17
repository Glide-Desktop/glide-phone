package org.mindenit.glidephone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerBatteryTest {
    @Test
    fun lowAndFullAreReportedOnce() {
        assertNull(ComputerBattery.change(null, 10, false))
        assertEquals(ComputerBattery.Change.LOW, ComputerBattery.change(16 to false, 15, false))
        assertNull(ComputerBattery.change(15 to false, 14, false))
        assertEquals(ComputerBattery.Change.CHARGING, ComputerBattery.change(14 to false, 14, true))
        assertEquals(ComputerBattery.Change.FULL, ComputerBattery.change(99 to true, 100, true))
        assertNull(ComputerBattery.change(100 to true, 100, true))
    }
}
