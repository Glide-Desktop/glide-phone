package org.mindenit.glidephone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.mindenit.glidephone.ui.theme.GlidePhoneTheme

/** Full-screen "found it" screen shown over the lock screen while the phone rings. */
class FindMyPhoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
        setContent {
            GlidePhoneTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.NotificationsActive, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(24.dp))
                        Text("Телефон шукають", style = MaterialTheme.typography.headlineLarge)
                        if (name.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Запит від «$name»", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(48.dp))
                        Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().height(64.dp), shape = CircleShape) {
                            Text("Знайшов", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AlarmPlayer.start(this)
        FindMyPhone.hideNotification(this)
    }

    override fun onStop() {
        super.onStop()
        AlarmPlayer.stop()
        GlideHub.isPhoneRinging = false
    }

    companion object {
        const val EXTRA_DEVICE_NAME = "deviceName"
    }
}

/** Stops the alarm from the notification's "found it" action. */
class FindMyPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FindMyPhone.ACTION_FOUND_IT) {
            AlarmPlayer.stop()
            GlideHub.isPhoneRinging = false
            FindMyPhone.hideNotification(context)
        }
    }
}

/** FindMyPhonePlugin.onPacketReceived: pick the screen, a ringing notification, or both. */
internal object FindMyPhone {
    const val ACTION_FOUND_IT = "org.mindenit.glidephone.FOUND_IT"
    private const val CHANNEL_ID = "glide_find_my_phone"
    private const val NOTIFICATION_ID = 4242

    fun ring(context: Context, deviceName: String) {
        val app = context.applicationContext
        val activity = Intent(app, FindMyPhoneActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FindMyPhoneActivity.EXTRA_DEVICE_NAME, deviceName)
        if (Build.VERSION.SDK_INT < 29 || GlideHub.inForeground) {
            runCatching { app.startActivity(activity) }.onSuccess { return }
        }
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        val pending = if (interactive) {
            AlarmPlayer.start(app)
            PendingIntent.getBroadcast(app, NOTIFICATION_ID, Intent(app, FindMyPhoneReceiver::class.java).setAction(ACTION_FOUND_IT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(app, NOTIFICATION_ID, activity, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            app.getSystemService(NotificationManager::class.java)?.let { manager ->
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Пошук телефону", NotificationManager.IMPORTANCE_HIGH))
                }
            }
        }
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Знайшов")
            .setContentText(if (deviceName.isNotEmpty()) "Телефон шукає «$deviceName»" else "Телефон шукають")
            .setOngoing(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    fun hideNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
