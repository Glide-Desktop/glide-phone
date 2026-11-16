package org.mindenit.glidephone

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.json.JSONObject

/**
 * Exposes this phone's active media players to a connected computer and applies its
 * control requests (kdeconnect.mpris / kdeconnect.mpris.request). Uses the active
 * MediaSessions, available because the app already holds notification-listener access.
 */
internal object MediaControl {
    private val watched = java.util.concurrent.ConcurrentHashMap<android.media.session.MediaSession.Token, MediaController.Callback>()
    @Volatile private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    /**
     * Push player-list and now-playing changes to connected devices as they happen, like
     * KDE Connect's mprisreceiver. Safe to call repeatedly; it only registers once, and
     * silently does nothing until notification access has been granted.
     */
    fun start(context: Context, emit: (String) -> Unit) {
        if (sessionsListener != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val component = ComponentName(app, GlideNotificationListener::class.java)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun attach(list: List<MediaController>) {
            val live = list.map { it.sessionToken }.toSet()
            watched.keys.filterNot { it in live }.forEach { watched.remove(it) }
            list.forEach { controller ->
                if (watched.containsKey(controller.sessionToken)) return@forEach
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { push() }
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) { push() }
                    private fun push() { runCatching { statePacket(app, label(app, controller))?.let(emit) } }
                }
                watched[controller.sessionToken] = callback
                runCatching { controller.registerCallback(callback, handler) }
            }
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            attach(list.orEmpty())
            runCatching { emit(playerListPacket(app)) }
        }
        handler.post {
            if (sessionsListener != null) return@post
            try {
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                sessionsListener = listener
                attach(manager.getActiveSessions(component))
            } catch (_: SecurityException) {
                // Notification access not granted yet; a later call will try again.
            }
        }
    }

    private fun controllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return emptyList()
        val component = ComponentName(context, GlideNotificationListener::class.java)
        return runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
    }

    private fun label(context: Context, controller: MediaController): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(controller.packageName, 0)
        ).toString()
    }.getOrDefault(controller.packageName)

    fun playerListPacket(context: Context): String {
        val names = controllers(context).map { label(context, it) }.distinct()
        return ProtocolPackets.mprisList(names)
    }

    fun statePacket(context: Context, player: String? = null): String? {
        val list = controllers(context)
        val controller = (player?.let { p -> list.firstOrNull { label(context, it) == p } } ?: list.firstOrNull()) ?: return null
        val meta = controller.metadata
        val state = controller.playbackState
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val length = meta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        val pos = state?.position ?: 0L
        return ProtocolPackets.mprisState(label(context, controller), title, artist, album, isPlaying, length = length, pos = pos)
    }

    /** @return an updated state packet after applying the request, or null. */
    fun handleRequest(context: Context, body: JSONObject): String? {
        val player = body.optString("player").takeIf { it.isNotEmpty() }
        val list = controllers(context)
        val controller = (player?.let { p -> list.firstOrNull { label(context, it) == p } } ?: list.firstOrNull())
        if (body.optBoolean("requestPlayerList", false)) return playerListPacket(context)
        if (controller == null) return null
        val controls = controller.transportControls
        when (body.optString("action")) {
            "Play" -> controls.play()
            "Pause" -> controls.pause()
            "PlayPause" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "Stop" -> controls.stop()
            "Next" -> controls.skipToNext()
            "Previous" -> controls.skipToPrevious()
        }
        if (body.has("setVolume")) runCatching {
            controller.setVolumeTo(body.optInt("setVolume", 50) * (controller.playbackInfo?.maxVolume ?: 100) / 100, 0)
        }
        if (body.has("SetPosition")) runCatching { controls.seekTo(body.optLong("SetPosition", 0L)) }
        if (body.has("Seek")) runCatching {
            val cur = controller.playbackState?.position ?: 0L
            controls.seekTo(cur + body.optLong("Seek", 0L) / 1000)
        }
        return statePacket(context, player)
    }
}
