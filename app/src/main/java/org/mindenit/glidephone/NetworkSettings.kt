package org.mindenit.glidephone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.net.InetAddress

/**
 * Network-level preferences mirroring KDE Connect's CustomDevicesActivity and
 * TrustedNetworkHelper: manually added hosts that always get our identity packet, and
 * an optional list of trusted Wi-Fi networks outside of which the phone neither
 * announces itself nor accepts unpaired devices.
 */
internal class NetworkSettings(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("glide_network", Context.MODE_PRIVATE)

    var customDevices: List<String>
        get() = split(preferences.getString(KEY_CUSTOM_DEVICES, ""))
        set(value) = preferences.edit { putString(KEY_CUSTOM_DEVICES, value.distinct().joinToString(DELIMITER)) }

    var trustedNetworks: List<String>
        get() = split(preferences.getString(KEY_TRUSTED_NETWORKS, ""))
        set(value) = preferences.edit { putString(KEY_TRUSTED_NETWORKS, value.distinct().joinToString(DELIMITER)) }

    /** Reading the SSID needs location access; without it every network is allowed. */
    val hasSsidPermission: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    var allNetworksAllowed: Boolean
        get() = !hasSsidPermission || preferences.getBoolean(KEY_TRUST_ALL, true)
        set(value) = preferences.edit { putBoolean(KEY_TRUST_ALL, value) }

    /** The current Wi-Fi SSID (with quotes, as Android reports it) or null. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        if (info.supplicantState != SupplicantState.COMPLETED) return null
        val ssid = info.ssid ?: return null
        return ssid.takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) }
    }

    val isTrustedNetwork: Boolean
        get() = allNetworksAllowed || (trustedNetworks.isNotEmpty() && currentSsid() in trustedNetworks)

    /** Resolve the custom hosts; call from a background thread (DNS lookups). */
    fun resolveCustomDevices(): List<InetAddress> = customDevices.flatMap { host ->
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
    }

    private fun split(value: String?): List<String> = value.orEmpty().split(DELIMITER).filter { it.isNotBlank() }

    companion object {
        private const val KEY_CUSTOM_DEVICES = "custom_devices"
        private const val KEY_TRUSTED_NETWORKS = "trusted_networks"
        private const val KEY_TRUST_ALL = "trust_all_networks"
        private const val DELIMITER = "\u0000"

        /** Accept hostnames and IPv4/IPv6 literals, like the reference's custom device input. */
        fun isValidHost(input: String): Boolean {
            val host = input.trim()
            if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() }) return false
            return Regex("^[A-Za-z0-9.:\\-\\[\\]%]+$").matches(host)
        }
    }
}

/** KDE Connect's isPrivateAddress: loopback, RFC 1918, link-local, CGNAT and IPv6 ULA. */
internal fun InetAddress.isPrivateLan(): Boolean {
    if (isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress) return true
    val bytes = address
    if (this is java.net.Inet4Address) {
        // 100.64.0.0/10 (carrier-grade NAT; also used by Tailscale and similar VPNs)
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }
    // fc00::/7 unique local addresses
    return (bytes[0].toInt() and 0xfe) == 0xfc
}
