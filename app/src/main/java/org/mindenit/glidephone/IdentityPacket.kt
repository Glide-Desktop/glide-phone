package org.mindenit.glidephone

import org.json.JSONArray
import org.json.JSONObject

/**
 * Version 8 identity framing. Discovery identity is untrusted until the same
 * ID and version are received again over TLS and the peer is paired.
 */
internal object IdentityPacket {
    const val VERSION = 8
    /** Oldest protocol version accepted from peers (KDE Connect still talks to v7). */
    const val MIN_VERSION = 7
    private const val TYPE = "kdeconnect.identity"
    private val validId = Regex("^[a-zA-Z0-9_-]{32,38}$")
    private val invalidName = Regex("[\\\"',;:.!?()\\[\\]<>]")

    data class Peer(
        val id: String, val name: String, val version: Int, val type: String, val incomingCapabilities: Set<String>,
        val tcpPort: Int? = null,
        /** glideProtocol of a Glide Phone desktop; null for KDE Connect peers. */
        val glideProtocol: Int? = null,
    ) {
        val isGlide: Boolean get() = glideProtocol != null && glideProtocol >= 1
    }

    /** Glide extensions version we announce (must match glide-phone-desktop's GLIDE_PROTOCOL). */
    const val GLIDE_PROTOCOL = 1

    fun encode(id: String, name: String, tcpPort: Int? = null, targetDeviceId: String? = null, targetProtocolVersion: Int? = null): String {
        require(validId.matches(id))
        require(tcpPort == null || tcpPort in 1716..1764)
        val safeName = invalidName.replace(name, "").filterNot { it.isISOControl() }.trim().take(32)
            .ifBlank { "Glide Phone" }
        val body = JSONObject()
            .put("deviceId", id)
            .put("deviceName", safeName)
            .put("deviceType", "phone")
            .put("protocolVersion", VERSION)
            .put("incomingCapabilities", JSONArray(INCOMING))
            .put("outgoingCapabilities", JSONArray(OUTGOING))
            .put("glideProtocol", GLIDE_PROTOCOL)
        if (tcpPort != null) body.put("tcpPort", tcpPort)
        if (targetDeviceId != null) body.put("targetDeviceId", targetDeviceId)
        if (targetProtocolVersion != null) body.put("targetProtocolVersion", targetProtocolVersion)
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
            val glide = body.optInt("glideProtocol", -1).takeIf { it >= 1 }
            Peer(id, name, version, body.optString("deviceType", "desktop"), incoming, tcpPort, glide)
        } catch (_: Exception) {
            null
        }
    }

    const val MAX_BYTES = 512 * 1024

    /** Packet types this phone understands. */
    val INCOMING = listOf(
        ProtocolPackets.TYPE_BATTERY,
        ProtocolPackets.TYPE_CLIPBOARD,
        ProtocolPackets.TYPE_CLIPBOARD_CONNECT,
        ProtocolPackets.TYPE_FINDMYPHONE_REQUEST,
        ProtocolPackets.TYPE_SHARE_REQUEST,
        ProtocolPackets.TYPE_SHARE_REQUEST_UPDATE,
        ProtocolPackets.TYPE_RUNCOMMAND,
        ProtocolPackets.TYPE_RUNCOMMAND_OUTPUT,
        ProtocolPackets.TYPE_NOTIFICATION,
        ProtocolPackets.TYPE_NOTIFICATION_REQUEST,
        ProtocolPackets.TYPE_NOTIFICATION_REPLY,
        ProtocolPackets.TYPE_NOTIFICATION_ACTION,
        ProtocolPackets.TYPE_MPRIS,
        ProtocolPackets.TYPE_MPRIS_REQUEST,
        ProtocolPackets.TYPE_SYSTEMVOLUME,
        ProtocolPackets.TYPE_CONTACTS_REQUEST_UIDS,
        ProtocolPackets.TYPE_CONTACTS_REQUEST_VCARDS,
        ProtocolPackets.TYPE_TELEPHONY_REQUEST_MUTE,
        ProtocolPackets.TYPE_MOUSEPAD_KEYBOARDSTATE,
        ProtocolPackets.TYPE_MOUSEPAD_REQUEST,
        ProtocolPackets.TYPE_SHAREINPUTDEVICES_REQUEST,
        ProtocolPackets.TYPE_SFTP_REQUEST,
        ProtocolPackets.TYPE_SMS_REQUEST,
        ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATIONS,
        ProtocolPackets.TYPE_SMS_REQUEST_CONVERSATION,
        ProtocolPackets.TYPE_PING,
        ProtocolPackets.TYPE_GLIDE_WOL
    )

    /** Packet types this phone may send. */
    val OUTGOING = listOf(
        ProtocolPackets.TYPE_NOTIFICATION,
        ProtocolPackets.TYPE_NOTIFICATION_REQUEST,
        ProtocolPackets.TYPE_BATTERY,
        ProtocolPackets.TYPE_CLIPBOARD,
        ProtocolPackets.TYPE_CLIPBOARD_CONNECT,
        ProtocolPackets.TYPE_FINDMYPHONE_REQUEST,
        ProtocolPackets.TYPE_PRESENTER,
        ProtocolPackets.TYPE_MOUSEPAD_REQUEST,
        ProtocolPackets.TYPE_MOUSEPAD_ECHO,
        ProtocolPackets.TYPE_MOUSEPAD_KEYBOARDSTATE,
        ProtocolPackets.TYPE_SHAREINPUTDEVICES,
        ProtocolPackets.TYPE_SFTP,
        ProtocolPackets.TYPE_SHARE_REQUEST,
        ProtocolPackets.TYPE_RUNCOMMAND_REQUEST,
        ProtocolPackets.TYPE_CONNECTIVITY_REPORT,
        ProtocolPackets.TYPE_MPRIS,
        ProtocolPackets.TYPE_MPRIS_REQUEST,
        ProtocolPackets.TYPE_SYSTEMVOLUME_REQUEST,
        ProtocolPackets.TYPE_TELEPHONY,
        ProtocolPackets.TYPE_CONTACTS_RESPONSE_UIDS,
        ProtocolPackets.TYPE_CONTACTS_RESPONSE_VCARDS,
        ProtocolPackets.TYPE_SMS_MESSAGES,
        ProtocolPackets.TYPE_DIGITIZER,
        ProtocolPackets.TYPE_DIGITIZER_SESSION,
        ProtocolPackets.TYPE_PING
    )
}
