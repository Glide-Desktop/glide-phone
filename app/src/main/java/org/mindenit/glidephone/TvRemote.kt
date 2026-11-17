package org.mindenit.glidephone

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import org.json.JSONObject

/** pref_bigscreen_*: which TV remote buttons are shown. */
internal class BigscreenSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mousepad", Context.MODE_PRIVATE)
    var showBack: Boolean
        get() = prefs.getBoolean("pref_bigscreen_show_back", true)
        set(v) = prefs.edit { putBoolean("pref_bigscreen_show_back", v) }
    var showHome: Boolean
        get() = prefs.getBoolean("pref_bigscreen_show_home", true)
        set(v) = prefs.edit { putBoolean("pref_bigscreen_show_home", v) }
    var showClose: Boolean
        get() = prefs.getBoolean("pref_bigscreen_show_close", true)
        set(v) = prefs.edit { putBoolean("pref_bigscreen_show_close", v) }
    /** pref_bigscreen_hide_mouse_input: a TV only gets the remote, no touchpad. */
    var hideMouseInput: Boolean
        get() = prefs.getBoolean("pref_bigscreen_hide_mouse_input", false)
        set(v) = prefs.edit { putBoolean("pref_bigscreen_hide_mouse_input", v) }
}

private fun mousepad(body: JSONObject) = JSONObject().put("id", System.currentTimeMillis())
    .put("type", ProtocolPackets.TYPE_MOUSEPAD_REQUEST).put("body", body).toString() + "\n"

/** BigscreenActivity: a D-pad remote for TVs (e.g. Plasma Bigscreen). */
@Composable
internal fun TvRemote(connector: PairingConnector?, deviceId: String?) {
    val context = LocalContext.current
    val settings = remember { BigscreenSettings(context) }
    fun send(body: JSONObject) { connector?.sendPacketTo(deviceId, mousepad(body)) }
    fun key(code: Int) = send(JSONObject().put("specialKey", code))
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { connector?.sendType(it, deviceId) }
    }
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteButton(Icons.Outlined.KeyboardArrowUp, "Вгору") { key(ProtocolPackets.KEY_UP) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Ліворуч") { key(ProtocolPackets.KEY_LEFT) }
            FilledTonalButton(onClick = { key(ProtocolPackets.KEY_ENTER) }, Modifier.size(84.dp).padding(6.dp), shape = CircleShape) { Text("OK") }
            RemoteButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Праворуч") { key(ProtocolPackets.KEY_RIGHT) }
        }
        RemoteButton(Icons.Outlined.KeyboardArrowDown, "Вниз") { key(ProtocolPackets.KEY_DOWN) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.showBack) RemoteButton(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") { key(ProtocolPackets.KEY_ESCAPE) }
            // HACK from KDE Connect: "super" too, for desktops without super as a special key.
            if (settings.showHome) RemoteButton(Icons.Outlined.Home, "Додому") {
                send(JSONObject().put("super", true).put("specialKey", ProtocolPackets.KEY_SUPER))
            }
            if (settings.showClose) RemoteButton(Icons.Outlined.Close, "Закрити") {
                send(JSONObject().put("alt", true).put("specialKey", KEY_F4))
            }
            if (canListen) RemoteButton(Icons.Outlined.Mic, "Голосове введення") {
                speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говоріть — текст надрукується на ТБ"))
            }
        }
    }
}

@Composable
internal fun BigscreenSettingsSection() {
    val context = LocalContext.current
    val settings = remember { BigscreenSettings(context) }
    var version by remember { mutableIntStateOf(0) }
    version
    @Composable
    fun toggle(title: String, value: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(value, { set(it); version++ })
        }
    }
    toggle("Кнопка «Назад»", settings.showBack) { settings.showBack = it }
    toggle("Кнопка «Додому»", settings.showHome) { settings.showHome = it }
    toggle("Кнопка «Закрити»", settings.showClose) { settings.showClose = it }
    toggle("Лише пульт для ТБ", settings.hideMouseInput) { settings.hideMouseInput = it }
}

@Composable
private fun RemoteButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp).padding(4.dp)) { Icon(icon, label) }
}

private const val KEY_F4 = 24
