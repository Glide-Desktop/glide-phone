package org.mindenit.glidephone

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.mindenit.glidephone.ui.theme.LocalGlideColors

@Composable
internal fun NotificationAccessControl(enabled: Boolean, connected: Boolean, supported: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    fun hasAccess() = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    var granted by remember { mutableStateOf(hasAccess()) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasAccess()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    val glide = LocalGlideColors.current
    val active = granted && enabled && connected && supported
    val statusText = when {
        !granted -> "Доступ до сповіщень не надано"
        !enabled -> "Синхронізацію вимкнено"
        !connected -> "Очікування підключення комп’ютера"
        !supported -> "Комп’ютер не підтримує отримання сповіщень"
        else -> "Синхронізацію увімкнено"
    }
    Column {
        Text(
            "Сповіщення телефону передаються підключеному комп’ютеру. Доступ до їхнього вмісту потрібно надати в налаштуваннях Android.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (active) glide.connectedContainer else glide.tonal,
                    RoundedCornerShape(16.dp)
                )
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Circle,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = if (active) glide.connectedDot else MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.width(10.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.titleMedium,
                color = if (active) glide.onConnected else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CircleShape
        ) {
            Text("Налаштувати доступ")
        }
    }
}
