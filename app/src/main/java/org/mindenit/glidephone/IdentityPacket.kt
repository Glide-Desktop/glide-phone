package org.mindenit.glidephone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Version 8 identity framing. Discovery identity is untrusted until the same
 * ID and version are received again over TLS and the peer is paired.
 */
internal object IdentityPacket {
    const val VERSION = 8
    private const val TYPE = "kdeconnect.identity"
    private val validId = Regex("^[a-zA-Z0-9_-]{32,38}$")
    private val invalidName = Regex("[\\\"',;:.!?()\\[\\]<>]")

    data class Peer(val id: String, val name: String, val version: Int, val type: String, val incomingCapabilities: Set<String>, val tcpPort: Int? = null)

    fun encode(id: String, name: String, tcpPort: Int? = null): String {
        require(validId.matches(id))
        require(tcpPort == null || tcpPort in 1716..1764)
        val safeName = invalidName.replace(name, "").filterNot { it.isISOControl() }.trim().take(32)
            .ifBlank { "Glide Phone" }
        val body = JSONObject()
            .put("deviceId", id)
            .put("deviceName", safeName)
            .put("deviceType", "phone")
            .put("protocolVersion", VERSION)
            .put("incomingCapabilities", JSONArray()
                .put(ProtocolPackets.TYPE_BATTERY)
                .put(ProtocolPackets.TYPE_CLIPBOARD)
                .put(ProtocolPackets.TYPE_CLIPBOARD_CONNECT)
                .put(ProtocolPackets.TYPE_FINDMYPHONE_REQUEST)
                .put(ProtocolPackets.TYPE_SHARE_REQUEST)
                .put(ProtocolPackets.TYPE_RUNCOMMAND)
                .put(ProtocolPackets.TYPE_NOTIFICATION)
                .put(ProtocolPackets.TYPE_NOTIFICATION_REQUEST)
                .put(ProtocolPackets.TYPE_NOTIFICATION_REPLY)
                .put(ProtocolPackets.TYPE_NOTIFICATION_ACTION)
                .put(ProtocolPackets.TYPE_MPRIS_REQUEST)
                .put(ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST)
                .put(ProtocolPackets.TYPE_CONTACTS_REQUEST_UIDS)
                .put(ProtocolPackets.TYPE_CONTACTS_REQUEST_VCARDS)
                .put(ProtocolPackets.TYPE_SMS_REQUEST)
                .put(ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATIONS)
                .put(ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATION)
                .put(ProtocolPackets.TYPE_PING))
            .put("outgoingCapabilities", JSONArray()
                .put(ProtocolPackets.TYPE_NOTIFICATION)
                .put(ProtocolPackets.TYPE_BATTERY)
                .put(ProtocolPackets.TYPE_CLIPBOARD)
                .put(ProtocolPackets.TYPE_CLIPBOARD_CONNECT)
                .put(ProtocolPackets.TYPE_FINDMYPHONE_REQUEST)
                .put(ProtocolPackets.TYPE_PRESENTER)
                .put(ProtocolPackets.TYPE_MOUSEPAD_REQUEST)
                .put(ProtocolPackets.TYPE_SHARE_REQUEST)
                .put(ProtocolPackets.TYPE_RUNCOMMAND_REQUEST)
                .put(ProtocolPackets.TYPE_CONNECTIVITY_REPORT)
                .put(ProtocolPackets.TYPE_MPRIS)
                .put(ProtocolPackets.TYPE_SYSTEMVOLUME)
                .put(ProtocolPackets.TYPE_TELEPHONY)
                .put(ProtocolPackets.TYPE_CONTACTS_RESPONSE_UIDS)
                .put(ProtocolPackets.TYPE_CONTACTS_RESPONSE_VCARDS)
                .put(ProtocolPackets.TYPE_SMS_MESSAGES)
                .put(ProtocolPackets.TYPE_PING))
        if (tcpPort != null) body.put("tcpPort", tcpPort)
        return JSONObject().put("id", System.currentTimeMillis()).put("type", TYPE)
            .put("body", body).toString() + "\n"
    }

    fun decode(line: String): Peer? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        return try {
            val packet = JSONObject(line)
            if (packet.optString("type") != TYPE) return null
            val body = packet.optJSONObject("body") ?: return null
            val id = body.optString("deviceId")
            val rawName = body.optString("deviceName")
            val name = rawName.filterNot { it.isISOControl() }.trim().take(32)
            val version = body.optInt("protocolVersion", -1)
            if (!validId.matches(id) || name.isBlank() || version < 1) return null
            val capabilities = body.optJSONArray("incomingCapabilities")
            val incoming = buildSet {
                if (capabilities != null) {
                    for (index in 0 until minOf(capabilities.length(), 256)) {
                        val value = capabilities.opt(index)
                        if (value is String && value.length <= 128) add(value)
                    }
                }
            }
            val tcpPort = if (body.has("tcpPort")) {
                body.optInt("tcpPort", -1).takeIf { it in 1716..1764 } ?: return null
            } else null
            Peer(id, name, version, body.optString("deviceType", "desktop"), incoming, tcpPort)
        } catch (_: Exception) {
            null
        }
    }

    const val MAX_BYTES = 512 * 1024
}
