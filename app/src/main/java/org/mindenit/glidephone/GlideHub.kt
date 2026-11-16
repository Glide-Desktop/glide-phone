package org.mindenit.glidephone

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Process-wide owner of the connector and of everything the UI observes. Living outside
 * the Activity keeps connections (and notification/clipboard sync) alive across
 * configuration changes and while [GlideService] holds the process in the foreground.
 * All state is mutated on the main thread.
 */
internal object GlideHub {
    var pairingEvent by mutableStateOf<PairingConnector.Event?>(null)
    var connectedDevices by mutableStateOf<List<RemoteDevice>>(emptyList())
        private set
    var availableDevices by mutableStateOf<List<RemoteDevice>>(emptyList())
        private set
    var isPhoneRinging by mutableStateOf(false)
    var lastClipboard by mutableStateOf<String?>(null)
        private set
    var lastShare by mutableStateOf<PairingConnector.Event.ShareReceived?>(null)
        private set
    var lastFile by mutableStateOf<PairingConnector.Event.FileReceived?>(null)
        private set
    var peerBatteries by mutableStateOf<Map<String, Pair<Int, Boolean>>>(emptyMap())
        private set
    var remoteCommands by mutableStateOf<Map<String, List<PairingConnector.RemoteCommand>>>(emptyMap())
        private set
    var mediaPlayers by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var mediaStates by mutableStateOf<Map<String, PairingConnector.MediaState>>(emptyMap())
        private set
    var volumeSinks by mutableStateOf<Map<String, List<PairingConnector.VolumeSink>>>(emptyMap())
        private set

    @Volatile private var instance: PairingConnector? = null

    fun connector(context: Context): PairingConnector {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: PairingConnector(context.applicationContext) { event ->
                onEvent(context.applicationContext, event)
            }.also { instance = it }
        }
    }

    /** Make the phone discoverable and keep it running in the background. */
    fun goOnline(context: Context) {
        connector(context).startServer()
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GlideService::class.java))
        }
    }

    fun mediaKey(deviceId: String, player: String) = "$deviceId/$player"

    private fun onEvent(context: Context, event: PairingConnector.Event) {
        when (event) {
            PairingConnector.Event.Connecting -> pairingEvent = event
            is PairingConnector.Event.Verify -> {
                pairingEvent = event
                RemoteNotifications.post(
                    context, "pair-${event.id}", "Glide Phone", "Запит на сполучення",
                    "«${event.name}» хоче підключитися. Код: ${event.code}"
                )
            }
            PairingConnector.Event.Failed -> {
                (pairingEvent as? PairingConnector.Event.Verify)?.let { RemoteNotifications.cancel(context, "pair-${it.id}") }
                pairingEvent = event
            }
            PairingConnector.Event.PeersChanged -> availableDevices = connector(context).availableDevices()
            is PairingConnector.Event.Connected -> {
                connectedDevices = (connectedDevices.filterNot { it.id == event.id } + RemoteDevice(event.id, event.name))
                    .sortedBy { it.name.lowercase() }
                // Only announce connections that finish a pairing the user is watching;
                // silent auto-reconnects of already paired devices must not pop a dialog.
                val shown = pairingEvent
                if (shown == PairingConnector.Event.Connecting || shown is PairingConnector.Event.Verify) pairingEvent = event
                RemoteNotifications.cancel(context, "pair-${event.id}")
                GlideService.refresh(context)
            }
            is PairingConnector.Event.Disconnected -> {
                connectedDevices = connectedDevices.filterNot { it.id == event.id }
                peerBatteries = peerBatteries - event.id
                remoteCommands = remoteCommands - event.id
                mediaPlayers = mediaPlayers - event.id
                mediaStates = mediaStates.filterKeys { !it.startsWith("${event.id}/") }
                volumeSinks = volumeSinks - event.id
                val shown = pairingEvent
                if (shown is PairingConnector.Event.Connected && shown.id == event.id) pairingEvent = event
                GlideService.refresh(context)
            }
            is PairingConnector.Event.RingRequest -> isPhoneRinging = true
            is PairingConnector.Event.ClipboardReceived -> lastClipboard = event.content
            is PairingConnector.Event.ShareReceived -> {
                lastShare = event
                RemoteNotifications.post(
                    context, "share-${event.id}", "Glide Phone", "Отримано з комп’ютера",
                    event.url ?: event.text.orEmpty()
                )
            }
            is PairingConnector.Event.FileReceived -> {
                lastFile = event
                RemoteNotifications.post(
                    context, "file-${System.currentTimeMillis()}", "Glide Phone",
                    if (event.success) "Файл отримано" else "Не вдалося отримати файл",
                    if (event.success) "${event.fileName} збережено в «Завантаження»" else event.fileName
                )
            }
            is PairingConnector.Event.PeerBattery ->
                peerBatteries = peerBatteries + (event.id to Pair(event.charge, event.charging))
            is PairingConnector.Event.PingReceived ->
                Toast.makeText(context, "Пінг від «${event.name}»: ${event.message}", Toast.LENGTH_SHORT).show()
            is PairingConnector.Event.CommandList -> remoteCommands = remoteCommands + (event.id to event.commands)
            is PairingConnector.Event.RemoteNotification -> {
                if (event.cancel) RemoteNotifications.cancel(context, event.notificationId)
                else RemoteNotifications.post(context, event.notificationId, event.appName, event.title, event.text)
            }
            is PairingConnector.Event.MediaPlayers -> {
                mediaPlayers = mediaPlayers + (event.id to event.players)
                mediaStates = mediaStates.filterKeys { key ->
                    !key.startsWith("${event.id}/") || key.removePrefix("${event.id}/") in event.players
                }
            }
            is PairingConnector.Event.MediaUpdate ->
                mediaStates = mediaStates + (mediaKey(event.id, event.state.player) to event.state)
            is PairingConnector.Event.VolumeSinks -> volumeSinks = volumeSinks + (event.id to event.sinks)
        }
    }
}
