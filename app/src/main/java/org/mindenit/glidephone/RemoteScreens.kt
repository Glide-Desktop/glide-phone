package org.mindenit.glidephone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.mindenit.glidephone.ui.theme.LocalGlideColors
import kotlin.math.roundToInt

/** Remote control for the computer's media players (kdeconnect.mpris). */
@Composable
internal fun MediaDetail(connector: PairingConnector?, deviceId: String?) {
    val players = deviceId?.let { GlideHub.mediaPlayers[it] } ?: emptyList()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val player = selected?.takeIf { it in players } ?: players.firstOrNull()
    val state = if (deviceId != null && player != null) GlideHub.mediaStates[GlideHub.mediaKey(deviceId, player)] else null
    LaunchedEffect(deviceId) { connector?.requestMediaPlayers() }
    LaunchedEffect(player) { if (player != null) connector?.requestMediaState(player) }

    Body("Керуйте музикою та відео, що відтворюються на комп’ютері.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    if (player == null) {
        InfoCard(Icons.Outlined.MusicNote, "Немає активних плеєрів", "Запустіть відтворення на комп’ютері — плеєр з’явиться тут.")
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { connector?.requestMediaPlayers() }) { Text("Оновити") }
        return
    }
    if (players.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            players.forEach { name ->
                FilterChip(selected = name == player, onClick = { selected = name }, label = { Text(name) })
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Column(
        Modifier.fillMaxWidth().background(LocalGlideColors.current.tonal, RoundedCornerShape(24.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val artBytes = state?.albumArtUrl?.takeIf { it.isNotEmpty() }?.let { GlideHub.mediaArt[it] }
        val art = remember(artBytes) {
            artBytes?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
        if (art != null) {
            androidx.compose.foundation.Image(
                art, contentDescription = "Обкладинка",
                modifier = Modifier.size(180.dp).clip(RoundedCornerShape(20.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Icon(Icons.Outlined.MusicNote, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state?.title?.ifBlank { null } ?: player,
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val subtitle = listOfNotNull(state?.artist?.ifBlank { null }, state?.album?.ifBlank { null }).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state?.canSeek == true) {
                IconButton(onClick = { connector?.sendMediaSeek(player, -10_000) }) { Icon(Icons.Outlined.Replay10, "Назад на 10 секунд") }
            }
            IconButton(onClick = { connector?.sendMediaAction(player, "Previous") }, enabled = state?.canGoPrevious != false) {
                Icon(Icons.Outlined.SkipPrevious, "Попередній трек", Modifier.size(32.dp))
            }
            FilledIconButton(onClick = { connector?.sendMediaAction(player, "PlayPause") }, modifier = Modifier.size(64.dp), shape = CircleShape) {
                if (state?.isPlaying == true) Icon(Icons.Outlined.Pause, "Пауза", Modifier.size(32.dp))
                else Icon(Icons.Outlined.PlayArrow, "Відтворити", Modifier.size(32.dp))
            }
            IconButton(onClick = { connector?.sendMediaAction(player, "Next") }, enabled = state?.canGoNext != false) {
                Icon(Icons.Outlined.SkipNext, "Наступний трек", Modifier.size(32.dp))
            }
            if (state?.canSeek == true) {
                IconButton(onClick = { connector?.sendMediaSeek(player, 10_000) }) { Icon(Icons.Outlined.Forward10, "Вперед на 10 секунд") }
            }
        }
        if (state?.shuffle != null || state?.loopStatus != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.shuffle?.let { shuffle ->
                    IconToggleButton(checked = shuffle, onCheckedChange = { connector?.sendMediaShuffle(player, it) }) {
                        Icon(Icons.Outlined.Shuffle, if (shuffle) "Перемішування увімкнено" else "Перемішування вимкнено",
                            tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.loopStatus?.let { loop ->
                    // KDE Connect cycles None -> Track -> Playlist -> None.
                    val next = when (loop) { "None" -> "Track"; "Track" -> "Playlist"; else -> "None" }
                    IconButton(onClick = { connector?.sendMediaLoop(player, next) }) {
                        Icon(
                            if (loop == "Track") Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            "Повтор: $loop",
                            tint = if (loop == "None") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
    val volume = state?.volume ?: -1
    if (volume >= 0) {
        Spacer(Modifier.height(20.dp))
        SectionLabel("Гучність плеєра")
        var local by remember(player, volume) { mutableFloatStateOf(volume.toFloat()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Slider(
                value = local, onValueChange = { local = it }, valueRange = 0f..100f,
                onValueChangeFinished = { connector?.sendMediaVolume(player, local.roundToInt()) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Гучність плеєра" }
            )
        }
    }
}

/** Control the computer's audio outputs (kdeconnect.systemvolume). */
@Composable
internal fun VolumeDetail(connector: PairingConnector?, deviceId: String?) {
    val sinks = deviceId?.let { GlideHub.volumeSinks[it] } ?: emptyList()
    LaunchedEffect(deviceId) { connector?.requestVolumeSinks() }
    Body("Змінюйте гучність аудіовиходів комп’ютера.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    if (sinks.isEmpty()) {
        InfoCard(Icons.AutoMirrored.Outlined.VolumeUp, "Немає аудіопристроїв", "Комп’ютер ще не надіслав список аудіовиходів.")
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { connector?.requestVolumeSinks() }) { Text("Оновити") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sinks.forEach { sink ->
            var local by remember(sink.name, sink.volume) { mutableFloatStateOf(sink.volume.toFloat()) }
            Column(
                Modifier.fillMaxWidth()
                    .border(1.dp, LocalGlideColors.current.cardBorder, RoundedCornerShape(20.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = sink.enabled, onClick = { connector?.sendSinkDefault(sink.name) },
                        modifier = Modifier.semantics { contentDescription = "Типовий вихід: ${sink.description}" }
                    )
                    Text(
                        sink.description, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { connector?.sendSinkMuted(sink.name, !sink.muted) }) {
                        if (sink.muted) Icon(Icons.AutoMirrored.Outlined.VolumeOff, "Увімкнути звук")
                        else Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Вимкнути звук")
                    }
                }
                Slider(
                    value = local.coerceIn(0f, sink.maxVolume.toFloat()), onValueChange = { local = it },
                    valueRange = 0f..sink.maxVolume.toFloat(),
                    onValueChangeFinished = { connector?.sendSinkVolume(sink.name, local.roundToInt()) },
                    modifier = Modifier.semantics { contentDescription = "Гучність: ${sink.description}" }
                )
            }
        }
    }
}

/** Use the phone as a drawing tablet for the computer (kdeconnect.digitizer). */
@Composable
internal fun DigitizerDetail(connector: PairingConnector?) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val stroke = remember { mutableStateListOf<Offset>() }
    val ink = MaterialTheme.colorScheme.primary

    Body("Малюйте пальцем або стилусом — рухи передаються на комп’ютер як графічний планшет.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    if (connector != null && GlideHub.connectedDevices.isNotEmpty() && !connector.supportsDigitizer) {
        InfoCard(Icons.Outlined.Draw, "Не підтримується", "Комп’ютер не має плагіна графічного планшета.")
        Spacer(Modifier.height(16.dp))
    }
    DisposableEffect(size) {
        if (size != IntSize.Zero) {
            val metrics = context.resources.displayMetrics
            connector?.sendDigitizerStart(
                size.width, size.height,
                (metrics.xdpi / 25.4f).roundToInt().coerceAtLeast(1),
                (metrics.ydpi / 25.4f).roundToInt().coerceAtLeast(1)
            )
        }
        onDispose { if (size != IntSize.Zero) connector?.sendDigitizerEnd() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(LocalGlideColors.current.tonal, RoundedCornerShape(24.dp))
            .onSizeChanged { size = it }
            .pointerInput(connector) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: continue
                        val x = change.position.x.roundToInt().coerceIn(0, this.size.width)
                        val y = change.position.y.roundToInt().coerceIn(0, this.size.height)
                        val pressure = change.pressure.toDouble().coerceIn(0.0, 1.0)
                        when {
                            change.pressed && !change.previousPressed -> {
                                stroke.clear()
                                stroke.add(change.position)
                                connector?.sendDigitizerEvent(active = true, touching = true, x = x, y = y, pressure = pressure)
                            }
                            change.pressed -> {
                                stroke.add(change.position)
                                connector?.sendDigitizerEvent(active = null, touching = null, x = x, y = y, pressure = pressure)
                            }
                            change.previousPressed -> {
                                connector?.sendDigitizerEvent(active = false, touching = false, x = null, y = null, pressure = null)
                            }
                        }
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (stroke.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Draw, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Зона для малювання", color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            for (i in 1 until stroke.size) {
                drawLine(ink, stroke[i - 1], stroke[i], strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
