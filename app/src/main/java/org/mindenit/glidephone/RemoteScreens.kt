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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val players = deviceId?.let { GlideHub.mediaPlayers[it] } ?: emptyList()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val player = selected?.takeIf { it in players } ?: players.firstOrNull()
    val state = if (deviceId != null && player != null) GlideHub.mediaStates[GlideHub.mediaKey(deviceId, player)] else null
    LaunchedEffect(deviceId) { connector?.requestMediaPlayers() }
    LaunchedEffect(player) { if (player != null) connector?.requestMediaState(player) }

    Body("Керуйте музикою та відео, що відтворюються на комп’ютері.")
    Spacer(Modifier.height(16.dp))
    NotConnectedNotice(connector)
    MediaNotificationSettings()
    Spacer(Modifier.height(12.dp))
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
        val seekMs = remember { SetupPreferences(context).mprisSeekMicros / 1000 }
        if (state != null && state.canSeek && state.length > 0) {
            Spacer(Modifier.height(12.dp))
            MediaPosition(state) { connector?.sendMediaPosition(player, it) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state?.canSeek == true) {
                IconButton(onClick = { connector?.sendMediaSeek(player, -seekMs) }) { Icon(Icons.Outlined.FastRewind, "Назад на ${seekMs / 1000} с") }
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
                IconButton(onClick = { connector?.sendMediaSeek(player, seekMs) }) { Icon(Icons.Outlined.FastForward, "Вперед на ${seekMs / 1000} с") }
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
internal fun DigitizerDetail(connector: PairingConnector?) = DigitizerDetail(connector) { active, touching, x, y, pressure, rubber ->
    connector?.sendDigitizerEvent(active, touching, x, y, pressure, rubber)
}

/** [report] receives DigitizerPlugin.reportEvent fields (null = not sent). */
@Composable
internal fun DigitizerDetail(
    connector: PairingConnector?,
    report: (active: Boolean?, touching: Boolean?, x: Int?, y: Int?, pressure: Double?, rubber: Boolean?) -> Unit
) {
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
    val prefs = remember { SetupPreferences(context) }
    var drawHeld by remember { mutableStateOf(false) }
    var fingerDown by remember { mutableStateOf(false) }
    val drawHeldNow by rememberUpdatedState(drawHeld)
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(LocalGlideColors.current.tonal, RoundedCornerShape(24.dp))
            .onSizeChanged { size = it }
            .pointerInput(report) {
                // DrawingPadView: a stylus draws with its pressure (eraser end = Rubber) and
                // reports hover; a finger only moves the cursor unless "draw" is held.
                awaitPointerEventScope {
                    var fingerDraws = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: continue
                        val x = change.position.x.roundToInt().coerceIn(0, this.size.width)
                        val y = change.position.y.roundToInt().coerceIn(0, this.size.height)
                        val finger = change.type == PointerType.Touch || change.type == PointerType.Mouse
                        val rubber = change.type == PointerType.Eraser
                        fun pressure() = if (finger) (if (fingerDraws) 1.0 else 0.0) else change.pressure.toDouble().coerceIn(0.0, 1.0)
                        when {
                            change.pressed && !change.previousPressed -> {
                                if (finger) { fingerDraws = drawHeldNow; fingerDown = true }
                                stroke.clear()
                                stroke.add(change.position)
                                report(true, !finger, x, y, pressure(), rubber)
                            }
                            change.pressed -> {
                                if (!finger || fingerDraws) stroke.add(change.position)
                                report(null, null, x, y, pressure(), rubber)
                            }
                            change.previousPressed -> {
                                // A stylus lifted is still hovering; a finger leaves.
                                report(!finger, false, null, null, null, null)
                                if (finger) { fingerDown = false; fingerDraws = false }
                            }
                            event.type == PointerEventType.Enter && !finger ->
                                report(true, null, x, y, null, null)
                            event.type == PointerEventType.Exit && !finger ->
                                report(false, null, null, null, null, null)
                            event.type == PointerEventType.Move && !finger ->
                                report(null, null, x, y, null, rubber)
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
        if (!prefs.digitizerHideDrawButton) {
            val side = when (prefs.digitizerDrawButtonSide) {
                "top_left" -> Alignment.TopStart
                "top_right" -> Alignment.TopEnd
                "bottom_right" -> Alignment.BottomEnd
                else -> Alignment.BottomStart
            }
            // Hold with one finger while drawing with another (buttonDraw).
            Box(
                Modifier.align(side).padding(12.dp).size(64.dp)
                    .background(if (drawHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            drawHeld = true
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            drawHeld = false
                        }
                    }
                    .semantics { contentDescription = "Малювати пальцем" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Draw, null, tint = if (drawHeld) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    DigitizerSettings()
}

/** Position bar ticking once a second while playing (MprisNowPlayingFragment.positionSeek). */
@Composable
private fun MediaPosition(state: PairingConnector.MediaState, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            kotlinx.coroutines.delay(1000)
            now = android.os.SystemClock.elapsedRealtime()
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: state.currentPosition(now).toFloat()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shown.coerceIn(0f, state.length.toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..state.length.toFloat(),
            modifier = Modifier.semantics { contentDescription = "Позиція відтворення" }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMediaTime(shown.toLong()), style = MaterialTheme.typography.bodySmall)
            Text(formatMediaTime(state.length), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** MprisNowPlayingFragment.milisToProgress: m:ss, or h:mm:ss past an hour. */
internal fun formatMediaTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
