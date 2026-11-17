package org.mindenit.glidephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import org.json.JSONObject

/**
 * Media notification for the computer's players, after KDE Connect's MprisMediaSession:
 * controls in the shade and on the lock screen, album art, and the phone's volume keys
 * driving the computer's default output while it is shown.
 */
internal object RemoteMediaNotification {
    private const val CHANNEL_ID = "glide_media"
    private const val NOTIFICATION_ID = 0x6d70 // "mp"
    private const val ACTION = "org.mindenit.glidephone.MPRIS_ACTION"
    const val EXTRA_DEVICE = "device"
    const val EXTRA_PLAYER = "player"
    const val EXTRA_ACTION = "action"

    private var session: MediaSession? = null
    /** "device/player" currently in the notification, kept while it still exists. */
    private var showing: String? = null
    /** The user swiped a paused player away; don't bring it back until it plays again. */
    private var dismissed: String? = null

    @Synchronized
    fun update(context: Context) {
        val app = context.applicationContext
        if (!SetupPreferences(app).mprisNotification || !RemoteNotifications.hasPermission(app)) {
            close(app); return
        }
        val states = GlideHub.mediaStates
        val connected = GlideHub.connectedDevices.map { it.id }.toSet()
        fun usable(key: String) = key.substringBefore('/') in connected && states.containsKey(key)
        val playing = states.filter { usable(it.key) && it.value.isPlaying }.keys
        if (dismissed != null && dismissed in playing) dismissed = null
        // Prefer the shown player while it plays, then any playing one, then keep the shown one.
        val key = showing?.takeIf { it in playing }
            ?: playing.firstOrNull { it.substringBefore('/') == showing?.substringBefore('/') }
            ?: playing.firstOrNull()
            ?: showing?.takeIf { usable(it) && it != dismissed }
        if (key == null) { close(app); return }
        val state = states.getValue(key)
        val deviceId = key.substringBefore('/')
        val deviceName = GlideHub.connectedDevices.firstOrNull { it.id == deviceId }?.name.orEmpty()
        val art = GlideHub.mediaArt[state.albumArtUrl]?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }

        val media = session ?: MediaSession(app, "GlideMpris").also { session = it }
        media.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { send(app, deviceId, state.player, JSONObject().put("action", "Play")) }
            override fun onPause() { send(app, deviceId, state.player, JSONObject().put("action", "Pause")) }
            override fun onSkipToNext() { send(app, deviceId, state.player, JSONObject().put("action", "Next")) }
            override fun onSkipToPrevious() { send(app, deviceId, state.player, JSONObject().put("action", "Previous")) }
            override fun onSeekTo(pos: Long) { send(app, deviceId, state.player, JSONObject().put("SetPosition", pos)) }
        })
        media.setMetadata(MediaMetadata.Builder().apply {
            putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifEmpty { state.player })
            if (state.artist.isNotEmpty()) {
                putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                putString(MediaMetadata.METADATA_KEY_AUTHOR, state.artist)
            }
            if (state.album.isNotEmpty()) putString(MediaMetadata.METADATA_KEY_ALBUM, state.album)
            if (state.length > 0) putLong(MediaMetadata.METADATA_KEY_DURATION, state.length)
            if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        }.build())

        var actions = 0L
        val buttons = mutableListOf<Notification.Action>()
        fun button(icon: Int, label: String, action: String, flag: Long) {
            actions = actions or flag
            buttons += Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(app, icon), label,
                pending(app, deviceId, state.player, action, buttons.size)).build()
        }
        if (state.canGoPrevious) button(android.R.drawable.ic_media_previous, "Попередній", "Previous", PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        if (state.isPlaying && state.canPause) button(android.R.drawable.ic_media_pause, "Пауза", "Pause", PlaybackState.ACTION_PAUSE)
        if (!state.isPlaying && state.canPlay) button(android.R.drawable.ic_media_play, "Відтворити", "Play", PlaybackState.ACTION_PLAY)
        if (state.canGoNext) button(android.R.drawable.ic_media_next, "Наступний", "Next", PlaybackState.ACTION_SKIP_TO_NEXT)
        if (state.canSeek && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) actions = actions or PlaybackState.ACTION_SEEK_TO
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.position, if (state.isPlaying) 1f else 0f)
            .build())
        configureVolume(app, media, deviceId)
        media.isActive = true

        ensureChannel(app)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(app)
        val text = listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" - ")
        builder.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.title.ifEmpty { state.player })
            .setContentText(if (text.isEmpty()) state.player else "$text (${state.player})")
            .setSubText(deviceName)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setLocalOnly(true)
            .setGroup("MprisMediaSession")
            .setContentIntent(PendingIntent.getActivity(app, 0,
                Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DETAIL, "MEDIA")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken)
                .setShowActionsInCompactView(*IntArray(buttons.size.coerceAtMost(3)) { it }))
        if (art != null) builder.setLargeIcon(art)
        buttons.forEach(builder::addAction)
        if (!state.isPlaying) builder.setDeleteIntent(pending(app, deviceId, state.player, "dismiss", 9))
        runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, builder.build()) }
        showing = key
    }

    /** Volume keys change the computer's default sink while our session is shown. */
    private fun configureVolume(context: Context, media: MediaSession, deviceId: String) {
        val sink = GlideHub.volumeSinks[deviceId]?.let { sinks -> sinks.firstOrNull { it.enabled } ?: sinks.firstOrNull() }
        if (sink == null || !SetupPreferences(context).mprisVolumeKeys) {
            media.setPlaybackToLocal(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            return
        }
        val current = sink.volume * 100 / sink.maxVolume
        media.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, current) {
            override fun onSetVolumeTo(volume: Int) = setTo(volume)
            override fun onAdjustVolume(direction: Int) = setTo(currentVolume + direction * 5)
            private fun setTo(percent: Int) {
                val value = percent.coerceIn(0, 100)
                currentVolume = value
                GlideHub.connectorIfCreated()?.sendPacketTo(deviceId, ProtocolPackets.systemVolumeRequest(
                    JSONObject().put("name", sink.name).put("volume", value * sink.maxVolume / 100)))
            }
        })
    }

    @Synchronized
    fun close(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        session?.let { it.isActive = false; it.release() }
        session = null
        showing = null
    }

    @Synchronized
    internal fun onDismissed(context: Context, deviceId: String, player: String) {
        dismissed = GlideHub.mediaKey(deviceId, player)
        if (showing == dismissed) showing = null
        update(context)
    }

    private fun send(context: Context, deviceId: String, player: String, body: JSONObject) {
        GlideHub.connectorIfCreated()?.sendPacketTo(deviceId, ProtocolPackets.mprisRequest(body.put("player", player)))
    }

    private fun pending(context: Context, deviceId: String, player: String, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code,
            Intent(context, MediaNotificationReceiver::class.java).setAction(ACTION)
                .putExtra(EXTRA_DEVICE, deviceId).putExtra(EXTRA_PLAYER, player).putExtra(EXTRA_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun handle(context: Context, intent: Intent) {
        val device = intent.getStringExtra(EXTRA_DEVICE) ?: return
        val player = intent.getStringExtra(EXTRA_PLAYER) ?: return
        when (val action = intent.getStringExtra(EXTRA_ACTION) ?: return) {
            "dismiss" -> onDismissed(context, device, player)
            else -> send(context, device, player, JSONObject().put("action", action))
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Медіа на комп’ютері", NotificationManager.IMPORTANCE_LOW))
        }
    }
}

/** Buttons of the media notification (MprisMediaNotificationReceiver). */
class MediaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RemoteMediaNotification.handle(context, intent)
}
