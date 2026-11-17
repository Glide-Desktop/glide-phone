package org.mindenit.glidephone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/** Progress / result notifications for file transfers, like KDE Connect's Upload/ReceiveNotification. */
internal object TransferNotifications {
    private const val CHANNEL_ID = "glide_transfers"
    private val ids = AtomicInteger(5000)

    fun newId(): Int = ids.incrementAndGet()

    private fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Передавання файлів", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!RemoteNotifications.hasPermission(context)) return
        ensureChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun base(context: Context, title: String) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)

    /** [fileIndex] is 1-based. */
    fun progress(context: Context, id: Int, outgoing: Boolean, device: String, fileName: String, fileIndex: Int, fileCount: Int, percent: Int) {
        val title = if (outgoing) "Надсилання на «$device»" else "Отримання з «$device»"
        val text = if (fileCount > 1) "$fileName ($fileIndex з $fileCount)" else fileName
        post(context, id, base(context, title)
            .setSmallIcon(if (outgoing) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0))
    }

    fun finished(context: Context, id: Int, outgoing: Boolean, device: String, count: Int, failed: Int, open: Uri? = null) {
        val title = when {
            failed > 0 -> if (outgoing) "Не вдалося надіслати $failed з $count" else "Не вдалося отримати $failed з $count"
            outgoing -> if (count == 1) "Файл надіслано на «$device»" else "Надіслано $count файлів на «$device»"
            else -> if (count == 1) "Файл отримано з «$device»" else "Отримано $count файлів з «$device»"
        }
        val builder = base(context, title)
            .setSmallIcon(if (outgoing) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentText(if (!outgoing && failed == 0) "Збережено в «Завантаження»" else "")
        if (open != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(open, context.contentResolver.getType(open))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(PendingIntent.getActivity(context, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        post(context, id, builder)
    }
}
