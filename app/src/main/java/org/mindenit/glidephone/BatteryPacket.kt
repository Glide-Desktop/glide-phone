package org.mindenit.glidephone

import android.content.Intent
import android.os.BatteryManager
import org.json.JSONObject

/** Battery payload is sent only after a peer has been authenticated and paired. */
internal data class BatterySnapshot(val charge: Int, val charging: Boolean) {
    companion object {
        fun fromIntent(intent: Intent): BatterySnapshot? {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0 || level > scale) return null
            return BatterySnapshot(
                charge = (level.toLong() * 100 / scale).toInt(),
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            )
        }
    }

    fun packet(timestampMillis: Long = System.currentTimeMillis()): String {
        val thresholdEvent = if (charge <= 15 && !charging) 1 else 0
        val body = JSONObject()
            .put("currentCharge", charge)
            .put("isCharging", charging)
            .put("thresholdEvent", thresholdEvent)
        return JSONObject()
            .put("id", timestampMillis)
            .put("type", "kdeconnect.battery")
            .put("body", body)
            .toString() + "\n"
    }
}
