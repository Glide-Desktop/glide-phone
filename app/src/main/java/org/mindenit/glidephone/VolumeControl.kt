package org.mindenit.glidephone

import android.content.Context
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

/** Lets a connected computer read and control this phone's media volume (kdeconnect.systemvolume). */
internal object VolumeControl {
    private const val SINK = "glide-media"

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun sinkListPacket(context: Context): String? {
        val am = audio(context) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        val sink = JSONObject()
            .put("name", SINK)
            .put("description", "Гучність телефону")
            .put("muted", muted)
            .put("volume", cur)
            .put("maxVolume", max)
            .put("enabled", true)
        return ProtocolPackets.systemVolumeSinks(JSONArray().put(sink))
    }

    /** @return a sink-list packet to send back after applying the request, or null. */
    fun handleRequest(context: Context, body: JSONObject): String? {
        val am = audio(context) ?: return null
        if (body.has("volume")) {
            val volume = body.optInt("volume", -1)
            if (volume >= 0) runCatching {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceAtMost(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
            }
        }
        if (body.has("muted")) {
            val mute = body.optBoolean("muted", false)
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0) }
        }
        return sinkListPacket(context)
    }
}
