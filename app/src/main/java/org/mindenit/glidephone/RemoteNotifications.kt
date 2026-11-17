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
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Сповіщення з комп’ютера", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun post(context: Context, key: String, appName: String, title: String, text: String,
             silent: Boolean = false, icon: ByteArray? = null) {
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
            .setSilent(silent)
            // Never bounce it back to other KDE Connect peers through our own listener.
            .setLocalOnly(true)
            .setLargeIcon(icon?.let { largeIcon(context, it) })
            .setContentIntent(android.app.PendingIntent.getActivity(
                context, localId, android.content.Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(localId, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun largeIcon(context: Context, bytes: ByteArray): android.graphics.Bitmap? {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val width = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val height = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        return if (bitmap.width > width || bitmap.height > height) android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
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
