package org.mindenit.glidephone

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Wake-on-LAN magic packets for a sleeping Glide desktop (Glide-only feature). */
internal object WakeOnLan {
    private val MAC = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

    /** 6 x 0xFF followed by the MAC 16 times. */
    fun magicPacket(mac: String): ByteArray? {
        if (!MAC.matches(mac)) return null
        val bytes = mac.split(':').map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + (0 until 16).flatMap { bytes }.toByteArray()
    }

    /** Where to send: the global broadcast, this network's broadcast, the computer's last address. */
    private fun targets(context: Context, lastAddress: String?): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm?.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { link ->
                val address = link.address as? Inet4Address ?: return@forEach
                val prefix = link.prefixLength
                val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                val broadcast = ip or (if (prefix >= 32) 0 else (-1 ushr prefix))
                out += InetAddress.getByAddress(ByteArray(4) { i -> (broadcast ushr (24 - 8 * i)).toByte() })
            }
        }
        lastAddress?.let { runCatching { out += InetAddress.getByName(it) } }
        return out.distinct()
    }

    /** Sends the magic packets on UDP 9 and 7; true when at least one went out. */
    fun wake(context: Context, macs: List<String>, lastAddress: String?): Boolean {
        val packets = macs.mapNotNull(::magicPacket)
        if (packets.isEmpty()) return false
        var sent = false
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in targets(context, lastAddress)) for (port in listOf(9, 7)) for (bytes in packets) {
                    if (runCatching { socket.send(DatagramPacket(bytes, bytes.size, target, port)) }.isSuccess) sent = true
                }
            }
        }
        return sent
    }
}
