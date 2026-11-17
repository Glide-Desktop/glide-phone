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
        setContent {
            GlidePhoneTheme {
                val devices = GlideHub.connectedDevices
                var autoSent by remember { mutableStateOf(false) }
                // With exactly one device there is nothing to choose.
                LaunchedEffect(devices) {
                    if (!autoSent && devices.size == 1) {
                        autoSent = true
                        deliver(connector, devices.single().id, content)
                    }
                }
                if (devices.size != 1 || !autoSent) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text("Надіслати на пристрій") },
                        text = {
                            if (devices.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Очікування підключення комп’ютера…")
                                }
                            } else {
                                Column {
                                    devices.forEach { device ->
                                        ListItem(
                                            headlineContent = { Text(device.name) },
                                            leadingContent = { Icon(Icons.Outlined.Laptop, null) },
                                            modifier = Modifier.fillMaxWidth(),
                                            trailingContent = {
                                                TextButton(onClick = { deliver(connector, device.id, content) }) { Text("Надіслати") }
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { finish() }) { Text("Скасувати") } }
                    )
                }
            }
        }
    }

    private fun deliver(connector: PairingConnector, deviceId: String, content: Content) {
        val ok = when (content) {
            is Content.Files -> connector.sendFiles(content.uris, deviceId)
            is Content.Url -> connector.sendShareUrl(content.url, deviceId)
            is Content.Text -> connector.sendShareText(content.text, deviceId)
            is Content.Keystrokes -> connector.sendType(content.text, deviceId)
        }
        Toast.makeText(this, if (ok) "Надсилання розпочато" else "Пристрій не підтримує цю дію", Toast.LENGTH_SHORT).show()
        finish()
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
