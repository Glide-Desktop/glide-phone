package org.mindenit.glidephone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mindenit.glidephone.ui.theme.GlidePhoneTheme

/**
 * Android "Share" target (ACTION_SEND / ACTION_SEND_MULTIPLE), like KDE Connect's
 * ShareActivity: files are sent as payloads, a lone URL is opened on the computer, other
 * text is shared as text, and `text/x-keystrokes` is typed on the computer.
 */
class ShareActivity : ComponentActivity() {
    private sealed interface Content {
        data class Files(val uris: List<Uri>) : Content
        data class Url(val url: String) : Content
        data class Text(val text: String) : Content
        data class Keystrokes(val text: String) : Content
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = parse(intent)
        if (content == null) {
            finish(); return
        }
        if (!SetupPreferences(this).isSetupComplete) {
            Toast.makeText(this, "Спочатку налаштуйте Glide Phone", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        GlideHub.goOnline(this)
        val connector = GlideHub.connector(this)
        if (content is Content.Keystrokes && !SetupPreferences(this).sendKeystrokes) {
            Toast.makeText(this, "Надсилання натискань клавіш вимкнено", Toast.LENGTH_LONG).show()
            finish(); return
        }
        // Direct share from the system sheet names the computer already.
        val shortcutDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
            ?: if (Build.VERSION.SDK_INT >= 29) intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) else null
        val shortcutOnline = shortcutDevice != null && GlideHub.connectedDevices.any { it.id == shortcutDevice }
        if (shortcutDevice != null && content !is Content.Keystrokes && (shortcutOnline || content is Content.Url)) {
            deliver(connector, shortcutDevice, content); return
        }
        // Otherwise (e.g. the app was just started) wait in the chooser and send once it connects.
        val waitFor = shortcutDevice
        // SendKeystrokesToHostActivity: short digit-only text (codes) goes out right away.
        val safeKeys = content is Content.Keystrokes && SetupPreferences(this).sendSafeTextImmediately &&
            content.text.length <= 8 && content.text.all { it in "1234567890" }
        val paired = connector.pairedDevices()
        setContent {
            GlidePhoneTheme {
                val connected = GlideHub.connectedDevices
                val connectedIds = connected.map { it.id }.toSet()
                // Links can wait for an offline computer; everything else needs it online.
                val offline = if (content is Content.Url) paired.filter { it.id !in connectedIds } else emptyList()
                var keys by remember { mutableStateOf((content as? Content.Keystrokes)?.text.orEmpty()) }
                var autoSent by remember { mutableStateOf(false) }
                LaunchedEffect(connected) {
                    if (waitFor != null && !autoSent && waitFor in connectedIds) {
                        autoSent = true
                        deliver(connector, waitFor, content)
                    }
                    if (safeKeys && !autoSent && connected.size == 1) {
                        autoSent = true
                        deliver(connector, connected.single().id, Content.Keystrokes(keys))
                    }
                }
                fun send(id: String) = deliver(connector, id, if (content is Content.Keystrokes) Content.Keystrokes(keys.trim()) else content)
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(if (content is Content.Keystrokes) "Надрукувати на комп’ютері" else "Надіслати на пристрій") },
                    text = {
                        Column {
                            if (content is Content.Keystrokes) {
                                OutlinedTextField(keys, { keys = it }, Modifier.fillMaxWidth(), label = { Text("Текст") })
                                Spacer(Modifier.height(8.dp))
                            }
                            if (connected.isEmpty() && offline.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Очікування підключення комп’ютера…")
                                }
                            }
                            connected.forEach { device ->
                                ListItem(
                                    headlineContent = { Text(device.name) },
                                    leadingContent = { Icon(Icons.Outlined.Laptop, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingContent = { TextButton(onClick = { send(device.id) }) { Text("Надіслати") } }
                                )
                            }
                            offline.forEach { device ->
                                ListItem(
                                    headlineContent = { Text(device.name) },
                                    supportingContent = { Text("Недоступний — надішлеться, коли підключиться") },
                                    leadingContent = { Icon(Icons.Outlined.Laptop, null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingContent = { TextButton(onClick = { send(device.id) }) { Text("Надіслати") } }
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Скасувати") } }
                )
            }
        }
    }

    private fun deliver(connector: PairingConnector, deviceId: String, content: Content) {
        val online = GlideHub.connectedDevices.any { it.id == deviceId }
        if (!online) {
            // ShareActivity.storeUrlForFutureDelivery
            if (content is Content.Url) {
                connector.queueUrls(deviceId, listOf(content.url))
                Toast.makeText(this, "Посилання відкриється, коли пристрій підключиться", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Пристрій недоступний", Toast.LENGTH_LONG).show()
            }
            finish(); return
        }
        val ok = when (content) {
            is Content.Files -> connector.sendFiles(content.uris, deviceId)
            is Content.Url -> connector.sendShareUrl(content.url, deviceId)
            is Content.Text -> connector.sendShareText(content.text, deviceId)
            is Content.Keystrokes -> connector.sendType(content.text, deviceId)
        }
        Toast.makeText(this, if (ok) "Надсилання розпочато" else "Пристрій не підтримує цю дію", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }

    private fun parse(intent: Intent?): Content? {
        intent ?: return null
        val type = intent.type.orEmpty()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (type == "text/x-keystrokes" && !text.isNullOrEmpty()) return Content.Keystrokes(text)
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> streamList(intent)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return Content.Files(uris)
        if (text.isNullOrEmpty()) return null
        return if (Patterns.WEB_URL.matcher(text).matches() && !text.contains(' ')) Content.Url(text) else Content.Text(text)
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
