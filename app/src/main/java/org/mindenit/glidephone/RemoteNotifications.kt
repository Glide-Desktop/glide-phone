package org.mindenit.glidephone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Displays notifications received from a connected computer as local Android notifications. */
internal object RemoteNotifications {
    private const val CHANNEL_ID = "glide_remote"
    private val ids = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val counter = java.util.concurrent.atomic.AtomicInteger(1000)

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Сповіщення з комп’ютера", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun post(context: Context, key: String, appName: String, title: String, text: String) {
        if (title.isBlank() && text.isBlank()) return
        if (!hasPermission(context)) return
        ensureChannel(context)
        val localId = ids.getOrPut(key) { counter.incrementAndGet() }
        val heading = listOf(appName, title).filter { it.isNotBlank() }.joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(heading.ifBlank { title })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(localId, notification) }
    }

    fun cancel(context: Context, key: String) {
        val localId = ids.remove(key) ?: return
        runCatching { NotificationManagerCompat.from(context).cancel(localId) }
    }

    fun hasPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
