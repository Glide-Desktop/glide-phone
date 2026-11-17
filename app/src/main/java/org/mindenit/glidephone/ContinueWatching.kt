package org.mindenit.glidephone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.URL

/** VideoUrlsHelper: carry the playback position over to a link opened on the phone. */
internal object VideoUrls {
    private val peerTubePath = Regex("^/w/[1-9a-km-zA-HJ-NP-Z]{22}(\\?.+)?$")

    fun withSeek(address: String, positionMillis: Long): String {
        val seconds = positionMillis / 1000
        if (seconds <= 0) return address
        val url = runCatching { URL(address) }.getOrNull() ?: return address
        val host = url.host.lowercase()
        return when {
            listOf("youtube.com", "youtu.be", "pornhub.com").any { it in host } -> editOrAdd(address, "t", Regex("\\d+"), "$seconds")
            "vimeo.com" in host -> editOrAdd(address, "t", Regex("\\d+s"), "${seconds}s")
            "dailymotion.com" in host -> editOrAdd(address, "start", Regex("\\d+"), "$seconds")
            "twitch.tv" in host -> editOrAdd(address, "t", Regex("(\\d+[hH])?(\\d+[mM])?\\d+[sS]"), hms(seconds))
            url.path.matches(peerTubePath) -> editOrAdd(address, "start", Regex("(\\d+[hH])?(\\d+[mM])?\\d+[sS]"), hms(seconds))
            else -> address
        }
    }

    fun editOrAdd(url: String, parameter: String, valuePattern: Regex, newValue: String): String {
        val (withoutFragment, fragment) = url.split("#", limit = 2).let { it[0] to it.getOrNull(1) }
        val (base, query) = withoutFragment.split("?", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val params = query.split("&").filter { it.isNotEmpty() }.associate {
            val parts = it.split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }.toMutableMap()
        val current = params[parameter]
        if (current != null && !current.matches(valuePattern)) return url
        params[parameter] = newValue
        val newQuery = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val rebuilt = if (newQuery.isNotEmpty()) "$base?$newQuery" else base
        return if (fragment != null) "$rebuilt#$fragment" else rebuilt
    }

    /** A phone opens normal YouTube links, not the TV interface. */
    fun fromTvLink(url: String): String =
        if (url.contains(Regex("youtube\\.com/tv.*#/watch"))) url.replace(Regex("youtube\\.com/tv.*#/watch"), "youtube.com/watch") else url

    private fun hms(total: Long): String {
        if (total == 0L) return "0s"
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        fun pad(v: String) = v.padStart(3, '0')
        return (if (h > 0) pad("${h}h") else "") + (if (m > 0 || h > 0) pad("${m}m") else "") + pad("${s}s")
    }
}

/**
 * MprisPlugin.showContinueWatchingNotification: when a web video stops on the computer
 * (after playing at least 5 s), offer to continue it on the phone at the same position.
 */
internal object ContinueWatching {
    private const val CHANNEL_ID = "glide_continue_watching"

    fun maybeShow(context: Context, state: PairingConnector.MediaState, stillPaused: () -> Boolean) {
        if (state.playStartTime + 5000 > android.os.SystemClock.elapsedRealtime()) return // played too briefly
        val url = state.url.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        val app = context.applicationContext
        // One delayed check on the main thread (not a thread per player), and one offer shown.
        handler.removeCallbacksAndMessages(TOKEN)
        handler.postAtTime(check@{
            if (!stillPaused()) return@check // just the gap between songs
            if (!SetupPreferences(app).mprisKeepWatching || !RemoteNotifications.hasPermission(app)) return@check
            val target = VideoUrls.withSeek(VideoUrls.fromTvLink(url), state.currentPosition())
            ensureChannel(app)
            val intent = PendingIntent.getActivity(app, 0x6377, Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setContentTitle("Glide Phone")
                .setContentText("Продовжити перегляд на телефоні: ${state.title}")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setTimeoutAfter(3000)
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build()
            runCatching {
                app.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification)
            }
        }, TOKEN, android.os.SystemClock.uptimeMillis() + 500)
    }

    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val TOKEN = Any()
    private const val NOTIFICATION_ID = 0x6378

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Продовження перегляду", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
