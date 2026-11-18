package org.mindenit.glidephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glide only: the computer sets this phone's volumes. The phone's audio streams are offered as
 * KDE Connect `systemvolume` sinks (the packets the phone itself uses for the computer's
 * outputs, the other way round), so glide-phone-desktop shows them like its own outputs.
 */
internal object PhoneVolume {
    private data class Stream(val name: String, val description: String, val type: Int)

    private val STREAMS = listOf(
        Stream("phone.media", "Медіа", AudioManager.STREAM_MUSIC),
        Stream("phone.ring", "Дзвінок", AudioManager.STREAM_RING),
        Stream("phone.notification", "Сповіщення", AudioManager.STREAM_NOTIFICATION),
        Stream("phone.alarm", "Будильник", AudioManager.STREAM_ALARM),
    )
    private const val MAX = 100

    private fun audio(context: Context) = context.getSystemService(AudioManager::class.java)

    /** `kdeconnect.systemvolume` with every stream as a sink, volume 0..100. */
    fun sinkListPacket(context: Context): String? {
        val audio = audio(context) ?: return null
        val sinks = JSONArray()
        STREAMS.forEach { stream ->
            val max = audio.getStreamMaxVolume(stream.type).coerceAtLeast(1)
            val muted = Build.VERSION.SDK_INT >= 23 && audio.isStreamMute(stream.type)
            sinks.put(JSONObject()
                .put("name", stream.name)
                .put("description", stream.description)
                .put("volume", audio.getStreamVolume(stream.type) * MAX / max)
                .put("maxVolume", MAX)
                .put("muted", muted)
                .put("enabled", stream.type == AudioManager.STREAM_MUSIC))
        }
        return packet(JSONObject().put("sinkList", sinks))
    }

    /** The computer's `kdeconnect.systemvolume.request`; returns the updated list to send back. */
    fun handle(context: Context, body: JSONObject): String? {
        val audio = audio(context) ?: return null
        val stream = STREAMS.firstOrNull { it.name == body.optString("name") }
        if (stream != null) {
            // Ringer and notification volumes can be refused while Do Not Disturb is on.
            if (body.has("volume")) runCatching {
                val max = audio.getStreamMaxVolume(stream.type)
                val volume = body.optInt("volume", 0).coerceIn(0, MAX)
                audio.setStreamVolume(stream.type, (volume * max + MAX / 2) / MAX, 0)
            }
            if (body.has("muted") && Build.VERSION.SDK_INT >= 23) runCatching {
                val direction = if (body.optBoolean("muted")) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audio.adjustStreamVolume(stream.type, direction, 0)
            }
        }
        return if (stream != null || body.optBoolean("requestSinks", false)) sinkListPacket(context) else null
    }

    private var receiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Sends the list again when a volume changes on the phone (its buttons, other apps). */
    fun start(context: Context, send: (String) -> Unit) {
        if (receiver != null) return
        val app = context.applicationContext
        val flush = Runnable {
            android.util.Log.i("GlideLink", "phone volume changed; telling the computer")
            sinkListPacket(app)?.let(send)
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // One press fires several broadcasts: send once they settle.
                handler.removeCallbacks(flush)
                handler.postDelayed(flush, 300)
            }
        }
        // The volume levels live in Settings.System, which notifies observers on every change
        // (the system's VOLUME_CHANGED_ACTION broadcast isn't delivered to apps everywhere).
        runCatching {
            app.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true,
                object : android.database.ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        listener.onReceive(app, Intent())
                    }
                })
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(listener, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(listener, filter)
        }
        receiver = listener
    }

    private fun packet(body: JSONObject): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", ProtocolPackets.TYPE_SYSTEMVOLUME)
        .put("body", body)
        .toString() + "\n"
}
