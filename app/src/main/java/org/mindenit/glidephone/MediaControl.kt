package org.mindenit.glidephone

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes this phone's media players to connected computers (kdeconnect.mpris /
 * kdeconnect.mpris.request), after KDE Connect's MprisReceiverPlugin/MprisReceiverPlayer.
 * Relies on the active MediaSessions, available through notification-listener access.
 */
internal object MediaControl {
    /** Immutable album-art snapshot so the URL and bitmap sent always match. */
    class AlbumArt(val hash: Long, val bitmap: Bitmap, val url: String, val album: String?, val artist: String?) {
        fun toJpeg(): ByteArray = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    private class Player(val name: String, val controller: MediaController) {
        @Volatile var art: AlbumArt? = artFrom(controller.metadata, null)
        lateinit var callback: MediaController.Callback
    }

    private val players = ConcurrentHashMap<String, Player>()
    @Volatile private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    @Volatile private var emit: ((String) -> Unit)? = null

    /** Watch media sessions and push list/state changes. Idempotent; needs notification access. */
    fun start(context: Context, send: (String) -> Unit) {
        emit = send
        if (sessionsListener != null) return
        val app = context.applicationContext
        val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val component = ComponentName(app, GlideNotificationListener::class.java)
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (sessionsListener != null) return@post
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
                rebuild(app, list.orEmpty(), handler)
                emit?.invoke(playerListPacket(app))
            }
            try {
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                sessionsListener = listener
                rebuild(app, manager.getActiveSessions(component), handler)
            } catch (_: SecurityException) {
                // Notification access not granted yet; a later call retries.
            }
        }
    }

    private fun ensurePlayers(context: Context) {
        if (players.isNotEmpty() || sessionsListener != null) return
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val sessions = try {
            manager.getActiveSessions(ComponentName(context, GlideNotificationListener::class.java))
        } catch (_: SecurityException) {
            return
        }
        rebuild(context, sessions, Handler(Looper.getMainLooper()))
    }

    private fun rebuild(context: Context, sessions: List<MediaController>, handler: Handler) {
        players.keys.toList().forEach { name -> players.remove(name)?.let { runCatching { it.controller.unregisterCallback(it.callback) } } }
        sessions.forEach { controller ->
            if (controller.packageName == context.packageName) return@forEach // our own session
            val player = Player(appName(context, controller.packageName), controller)
            player.callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = push(context, player)
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    player.art = artFrom(metadata, player.art)
                    push(context, player)
                }
                override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = push(context, player)
            }
            runCatching { controller.registerCallback(player.callback, handler) }
            players.put(player.name, player)?.let { old -> runCatching { old.controller.unregisterCallback(old.callback) } }
        }
    }

    private fun push(context: Context, player: Player) {
        emit?.invoke(statePacket(player))
    }

    fun playerListPacket(context: Context): String {
        ensurePlayers(context)
        return ProtocolPackets.mprisList(players.keys.toList())
    }

    fun statePacket(context: Context, playerName: String? = null): String? {
        ensurePlayers(context)
        val player = (playerName?.let { players[it] } ?: players.values.firstOrNull()) ?: return null
        return statePacket(player)
    }

    private fun statePacket(player: Player): String {
        val controller = player.controller
        val meta = controller.metadata
        val state = controller.playbackState
        val actions = state?.actions ?: 0L
        val info = controller.playbackInfo
        val volume = if (info == null || info.maxVolume == 0) 0 else 100 * info.currentVolume / info.maxVolume
        return ProtocolPackets.mprisState(
            player = player.name,
            title = firstNonEmpty(meta, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = firstNonEmpty(meta, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_AUTHOR, MediaMetadata.METADATA_KEY_WRITER),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            canPlay = state != null && (state.state == PlaybackState.STATE_PLAYING ||
                actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L),
            canPause = state != null && (state.state == PlaybackState.STATE_PAUSED ||
                actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L),
            canGoNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canGoPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            volume = volume,
            length = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            pos = state?.position ?: 0L,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            albumArtUrl = player.art?.url.orEmpty()
        )
    }

    /**
     * Apply a request. Returns a state/list packet to send back, or null. Album-art requests
     * are answered through [sendArt] because the image travels as a payload.
     */
    fun handleRequest(context: Context, body: JSONObject, sendArt: (JSONObject, ByteArray) -> Unit = { _, _ -> }): String? {
        ensurePlayers(context)
        if (body.optBoolean("requestPlayerList", false)) return playerListPacket(context)
        val player = players[body.optString("player")] ?: return null
        val artUrl = body.optString("albumArtUrl")
        if (artUrl.isNotEmpty()) {
            val art = player.art
            if (art != null && art.url == artUrl) sendArt(ProtocolPackets.mprisAlbumArt(player.name, art.url), art.toJpeg())
            return null
        }
        if (body.optBoolean("requestNowPlaying", false)) return statePacket(player)
        val controller = player.controller
        val controls = controller.transportControls
        if (body.has("SetPosition")) runCatching { controls.seekTo(body.optLong("SetPosition", 0L)) }
        if (body.has("Seek")) runCatching {
            controls.seekTo((controller.playbackState?.position ?: 0L) + body.optLong("Seek", 0L) / 1000)
        }
        if (body.has("setVolume")) {
            runCatching {
                val info = controller.playbackInfo
                controller.setVolumeTo((info.maxVolume * body.optInt("setVolume", 100) / 100.0 + 0.5).toInt(), 0)
            }
            return statePacket(player) // volume changes don't always trigger a callback
        }
        when (body.optString("action")) {
            "Play" -> controls.play()
            "Pause" -> controls.pause()
            "PlayPause" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "Stop" -> controls.stop()
            "Next" -> controls.skipToNext()
            "Previous" -> controls.skipToPrevious()
        }
        return null
    }

    private fun firstNonEmpty(meta: MediaMetadata?, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> meta?.getString(key)?.takeIf { it.isNotEmpty() } }.orEmpty()

    private fun appName(context: Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private val BITMAP_KEYS = arrayOf(MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART)
    private val URI_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
        MediaMetadata.METADATA_KEY_ARTIST
    )

    /** MprisReceiverPlayer.updateArt: keep the old art when the player resends the same album. */
    private fun artFrom(meta: MediaMetadata?, current: AlbumArt?): AlbumArt? {
        meta ?: return null
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val bitmap = BITMAP_KEYS.firstNotNullOfOrNull { runCatching { meta.getBitmap(it) }.getOrNull() }
        val uri = URI_KEYS.firstNotNullOfOrNull { key ->
            meta.getString(key)?.takeIf { it.isNotEmpty() }?.let { value ->
                val kind = when (key) {
                    MediaMetadata.METADATA_KEY_ALBUM -> "album"
                    MediaMetadata.METADATA_KEY_TITLE -> "title"
                    MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST -> "artist"
                    else -> "orig"
                }
                Uri.Builder().scheme("kdeconnect").path("/artUri").appendQueryParameter(kind, value).build().toString()
            }
        }
        if (bitmap == null || uri == null) {
            return if (album != current?.album || artist != current?.artist) null else current
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        runCatching { bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val hash = pixels.contentHashCode().toLong()
        if (current != null && current.hash == hash && bitmap.sameAs(current.bitmap)) return current
        val url = Uri.parse(uri).buildUpon().appendQueryParameter("kdeArtHash", hash.toString()).build().toString()
        return AlbumArt(hash, bitmap, url, album, artist)
    }
}
