package org.mindenit.glidephone

import org.json.JSONObject

internal object PairingPacket {
    private const val TYPE = "kdeconnect.pair"

    fun request(timestampSeconds: Long, packetId: Long = System.currentTimeMillis()): String {
        require(timestampSeconds >= 0)
        return encode(JSONObject().put("pair", true).put("timestamp", timestampSeconds), packetId)
    }

    fun response(accepted: Boolean, packetId: Long = System.currentTimeMillis()): String =
        encode(JSONObject().put("pair", accepted), packetId)

    fun parse(line: String): Message? {
        if (line.toByteArray(Charsets.UTF_8).size > IdentityPacket.MAX_BYTES) return null
        return try {
            val packet = JSONObject(line)
            if (packet.optString("type") != TYPE) return null
            val body = packet.optJSONObject("body") ?: return null
            val accepted = body.opt("pair") as? Boolean ?: return null
            val timestamp = when (val value = body.opt("timestamp")) {
                null -> null
                is Int -> value.toLong()
                is Long -> value
                else -> return null
            }
            if (timestamp != null && timestamp < 0) return null
            Message(accepted, timestamp)
        } catch (_: Exception) {
            null
        }
    }

    data class Message(val accepted: Boolean, val timestampSeconds: Long?)

    private fun encode(body: JSONObject, id: Long): String =
        JSONObject().put("id", id).put("type", TYPE).put("body", body).toString() + "\n"
}
