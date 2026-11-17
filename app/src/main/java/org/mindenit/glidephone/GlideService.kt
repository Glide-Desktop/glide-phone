package org.mindenit.glidephone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process (and therefore every connection held by [GlideHub]) alive while the
 * app is in the background, the same role KDE Connect's BackgroundService plays.
 */
class GlideService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        promote()
        if (SetupPreferences(this).isSetupComplete) GlideHub.connector(this).startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun promote() {
        // Before Android 8 a background service may run without a notification (KDE's toggle).
        if (Build.VERSION.SDK_INT < 26 && !SetupPreferences(this).persistentNotification) {
            @Suppress("DEPRECATION") stopForeground(true)
            return
        }
        runCatching {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, buildNotification(this), type)
            else startForeground(NOTIFICATION_ID, buildNotification(this))
        }
    }

    companion object {
        const val CHANNEL_ID = "glide_status"
        private const val NOTIFICATION_ID = 1
        @Volatile private var running = false

        /** Update the ongoing notification after the set of connected devices changed. */
        internal fun refresh(context: Context) {
            if (!running) return
            if (Build.VERSION.SDK_INT < 26) {
                val intent = Intent(context, GlideService::class.java)
                // Re-evaluate the persistent-notification setting.
                runCatching { context.startService(intent) }
                if (!SetupPreferences(context).persistentNotification) return
            }
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(context))
            }
        }

        private fun buildNotification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Стан підключення", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val devices = GlideHub.connectedDevices
            val text = if (devices.isEmpty()) "Немає підключених пристроїв"
            else "Підключено: " + devices.joinToString { it.name }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Glide Phone")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
            if (devices.isNotEmpty()) {
                // Android 10+ can't read the clipboard in the background: offer a manual send.
                if (!ClipboardFloatingActivity.canSyncAutomatically()) {
                    builder.addAction(0, "Надіслати буфер обміну", PendingIntent.getActivity(
                        context, 1, ClipboardFloatingActivity.intent(context, showToast = true),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                if (devices.size == 1) {
                    val device = devices.single()
                    builder.addAction(0, "Надіслати файли", PendingIntent.getActivity(
                        context, 2, Intent(context, SendFileActivity::class.java)
                            .putExtra(SendFileActivity.EXTRA_DEVICE_ID, device.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    if (GlideHub.remoteCommands[device.id].orEmpty().isNotEmpty()) {
                        builder.addAction(0, "Команди", PendingIntent.getActivity(
                            context, 3, Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DETAIL, "COMMANDS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    }
                }
            }
            return builder.build()
        }
    }
}
