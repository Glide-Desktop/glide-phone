package org.mindenit.glidephone

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject

/** Reports this phone's mobile network type and a coarse signal strength (kdeconnect.connectivity_report). */
internal object ConnectivityReporter {
    fun reportPacket(context: Context): String? {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val networkType = runCatching { networkTypeName(tm) }.getOrDefault("Unknown")
        val strength = runCatching { signalBars(tm) }.getOrDefault(-1)
        val entry = JSONObject()
            .put("networkType", networkType)
            .put("signalStrength", strength)
        val signals = JSONObject().put("0", entry)
        return ProtocolPackets.connectivityReport(signals)
    }

    private fun networkTypeName(tm: TelephonyManager): String {
        @Suppress("DEPRECATION")
        val type = try {
            tm.dataNetworkType
        } catch (_: SecurityException) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_A -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> "Mobile"
        }
    }

    private fun signalBars(tm: TelephonyManager): Int {
        if (android.os.Build.VERSION.SDK_INT < 28) return -1
        val ss = tm.signalStrength ?: return -1
        return ss.level // 0..4
    }
}
