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
    /** True while one of our activities is visible (FindMyPhone picks screen vs notification). */
    @Volatile var inForeground = false
    var lastClipboard by mutableStateOf<String?>(null)
        private set
    var lastShare by mutableStateOf<PairingConnector.Event.ShareReceived?>(null)
        private set
    var lastFile by mutableStateOf<PairingConnector.Event.FileReceived?>(null)
        private set
    /** The computer's folder last shown (Glide only). */
    var computerFiles by mutableStateOf<PairingConnector.Event.ComputerFiles?>(null)
        private set
    var peerBatteries by mutableStateOf<Map<String, Pair<Int, Boolean>>>(emptyMap())
        private set
    var remoteCommands by mutableStateOf<Map<String, List<PairingConnector.RemoteCommand>>>(emptyMap())
        private set
    var canAddCommand by mutableStateOf<Set<String>>(emptySet())
        private set
    var commandOutput by mutableStateOf<Map<String, List<PairingConnector.CommandLine>>>(emptyMap())
        private set
    var mediaPlayers by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set
    var mediaStates by mutableStateOf<Map<String, PairingConnector.MediaState>>(emptyMap())
        private set
    var volumeSinks by mutableStateOf<Map<String, List<PairingConnector.VolumeSink>>>(emptyMap())
        private set
    /** Devices whose desktop reported its keyboard as unavailable (mousepad.keyboardstate). */
    var keyboardDisabled by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Album art by URL (most recent few only). */
    var mediaArt by mutableStateOf<Map<String, ByteArray>>(emptyMap())
        private set

    @Volatile private var instance: PairingConnector? = null

    /** Instrumented UI tests keep the app off the network so live peers cannot leak in. */
    @androidx.annotation.VisibleForTesting
    @Volatile var offline = false

    @androidx.annotation.VisibleForTesting
    internal fun injectForTests(context: Context, event: PairingConnector.Event) = onEvent(context.applicationContext, event)

    /** Drop the connector and all observed state (tests run many launches in one process). */
    @androidx.annotation.VisibleForTesting
    fun resetForTests() {
        synchronized(this) { instance?.close(); instance = null }
        pairingEvent = null
        connectedDevices = emptyList()
        availableDevices = emptyList()
        isPhoneRinging = false
        lastClipboard = null
        lastShare = null
        lastFile = null
        peerBatteries = emptyMap()
        remoteCommands = emptyMap()
        canAddCommand = emptySet()
        commandOutput = emptyMap()
        mediaPlayers = emptyMap()
        mediaStates = emptyMap()
        volumeSinks = emptyMap()
        keyboardDisabled = emptySet()
        mediaArt = emptyMap()
    }

    fun connector(context: Context): PairingConnector {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: PairingConnector(context.applicationContext) { event ->
                onEvent(context.applicationContext, event)
            }.also { instance = it }
        }
    }

    fun connectorIfCreated(): PairingConnector? = instance

    /** Make the phone discoverable and keep it running in the background. */
    fun goOnline(context: Context) {
        if (offline) return
        connector(context).startServer()
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GlideService::class.java))
        }
    }

    fun mediaKey(deviceId: String, player: String) = "$deviceId/$player"

    private const val MAX_CONSOLE_LINES = 500

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
                DirectShare.connected(context, event.id, event.name)
                // Only announce connections that finish a pairing the user is watching;
                // silent auto-reconnects of already paired devices must not pop a dialog.
                val shown = pairingEvent
                if (shown == PairingConnector.Event.Connecting || shown is PairingConnector.Event.Verify) pairingEvent = event
                RemoteNotifications.cancel(context, "pair-${event.id}")
                GlideService.refresh(context)
            }
            is PairingConnector.Event.Disconnected -> {
                connectedDevices = connectedDevices.filterNot { it.id == event.id }
                DirectShare.disconnected(context, event.id)
                peerBatteries = peerBatteries - event.id
                remoteCommands = remoteCommands - event.id
                canAddCommand = canAddCommand - event.id
                // Running commands will never report completion on this link.
                commandOutput = commandOutput - event.id
                mediaPlayers = mediaPlayers - event.id
                mediaStates = mediaStates.filterKeys { !it.startsWith("${event.id}/") }
                volumeSinks = volumeSinks - event.id
                keyboardDisabled = keyboardDisabled - event.id
                RemoteMediaNotification.update(context)
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
            is PairingConnector.Event.ComputerFiles -> computerFiles = event
            is PairingConnector.Event.FileReceived -> {
                lastFile = event
                RemoteNotifications.post(
                    context, "file-${System.currentTimeMillis()}", "Glide Phone",
                    if (event.success) "Файл отримано" else "Не вдалося отримати файл",
                    if (event.success) "${event.fileName} збережено в «Завантаження»" else event.fileName
                )
            }
            is PairingConnector.Event.PeerBattery -> {
                val before = peerBatteries[event.id]
                peerBatteries = peerBatteries + (event.id to Pair(event.charge, event.charging))
                // Glide-only: say when the computer runs low or has finished charging.
                val name = connectedDevices.firstOrNull { it.id == event.id }?.name ?: "Комп’ютер"
                when (ComputerBattery.change(before, event.charge, event.charging)) {
                    ComputerBattery.Change.LOW -> RemoteNotifications.post(context, "battery-${event.id}", "Glide Phone",
                        "$name: низький заряд", "Залишилося ${event.charge}%. Під’єднайте зарядний пристрій.")
                    ComputerBattery.Change.FULL -> RemoteNotifications.post(context, "battery-${event.id}", "Glide Phone",
                        "$name повністю заряджено", "Можна від’єднати зарядний пристрій.")
                    ComputerBattery.Change.CHARGING -> RemoteNotifications.cancel(context, "battery-${event.id}")
                    null -> Unit
                }
            }
            is PairingConnector.Event.PingReceived ->
                Toast.makeText(context, "Пінг від «${event.name}»: ${event.message}", Toast.LENGTH_SHORT).show()
            is PairingConnector.Event.CommandList -> {
                remoteCommands = remoteCommands + (event.id to event.commands)
                val deviceName = connectedDevices.firstOrNull { it.id == event.id }?.name ?: event.id
                RunCommandStore.save(context, event.id, deviceName, event.commands)
                GlideService.refresh(context)
                canAddCommand = if (event.canAddCommand) canAddCommand + event.id else canAddCommand - event.id
            }
            is PairingConnector.Event.CommandOutput -> {
                val lines = commandOutput[event.id].orEmpty()
                val line = event.line
                commandOutput = commandOutput + (event.id to when (line.status) {
                    // Completion updates the "$ command" line instead of adding a new one.
                    PairingConnector.CommandStatus.SUCCESSFUL, PairingConnector.CommandStatus.FAILED -> {
                        val index = lines.indexOfLast { it.commandId == line.commandId && it.status == PairingConnector.CommandStatus.RUNNING }
                        if (index < 0) lines else lines.toMutableList().also { it[index] = it[index].copy(status = line.status) }
                    }
                    else -> (lines + line).takeLast(MAX_CONSOLE_LINES)
                })
            }
            is PairingConnector.Event.RemoteNotification -> {
                if (event.cancel) RemoteNotifications.cancel(context, "${event.id}/${event.notificationId}")
                else RemoteNotifications.post(context, "${event.id}/${event.notificationId}", event.appName, event.title, event.text,
                    silent = event.silent, icon = event.icon)
            }
            is PairingConnector.Event.MediaPlayers -> {
                mediaPlayers = mediaPlayers + (event.id to event.players)
                mediaStates = mediaStates.filterKeys { key ->
                    !key.startsWith("${event.id}/") || key.removePrefix("${event.id}/") in event.players
                }
                RemoteMediaNotification.update(context)
            }
            is PairingConnector.Event.MediaUpdate -> {
                mediaStates = mediaStates + (mediaKey(event.id, event.state.player) to event.state)
                RemoteMediaNotification.update(context)
            }
            is PairingConnector.Event.VolumeSinks -> {
                volumeSinks = volumeSinks + (event.id to event.sinks)
                RemoteMediaNotification.update(context)
            }
            is PairingConnector.Event.KeyboardState ->
                keyboardDisabled = if (event.enabled) keyboardDisabled - event.id else keyboardDisabled + event.id
            is PairingConnector.Event.MediaArt -> {
                mediaArt = (mediaArt - event.url + (event.url to event.image)).entries.toList().takeLast(12).associate { it.key to it.value }
                RemoteMediaNotification.update(context)
            }
        }
    }
}
