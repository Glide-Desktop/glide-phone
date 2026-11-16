package org.mindenit.glidephone

import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPacketTest {
    @Test
    fun batteryStateProducesProtocolPacket() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 42)
            .putExtra(BatteryManager.EXTRA_SCALE, 80)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB)
        val snapshot = requireNotNull(BatterySnapshot.fromIntent(intent))
        val wire = snapshot.packet(1234)
        assertTrue(wire.endsWith("\n"))
        val packet = JSONObject(wire)
        assertEquals(1234L, packet.getLong("id"))
        assertEquals("kdeconnect.battery", packet.getString("type"))
        val body = packet.getJSONObject("body")
        assertEquals(52, body.getInt("currentCharge"))
        assertTrue(body.getBoolean("isCharging"))
        assertEquals(0, body.getInt("thresholdEvent"))
        assertFalse(packet.has("payloadSize"))
    }

    @Test
    fun rejectsInvalidBatteryReadings() {
        assertNull(BatterySnapshot.fromIntent(Intent(Intent.ACTION_BATTERY_CHANGED)))
        assertNull(BatterySnapshot.fromIntent(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 101)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)))
    }

    @Test
    fun lowBatterySetsThresholdEvent() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 10)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val snapshot = requireNotNull(BatterySnapshot.fromIntent(intent))
        val wire = snapshot.packet(100)
        val packet = JSONObject(wire)
        val body = packet.getJSONObject("body")
        assertEquals(10, body.getInt("currentCharge"))
        assertFalse(body.getBoolean("isCharging"))
        assertEquals(1, body.getInt("thresholdEvent"))
    }
}
