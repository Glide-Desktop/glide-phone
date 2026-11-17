package org.mindenit.glidephone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** MousePadPlugin preferences, with KDE Connect's keys and defaults. */
internal class MousepadSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mousepad", Context.MODE_PRIVATE)

    var singleTap: String
        get() = prefs.getString("mousepad_single_tap_key", "left")!!
        set(v) = prefs.edit { putString("mousepad_single_tap_key", v) }
    var doubleTap: String // two-finger tap
        get() = prefs.getString("mousepad_double_tap_key", "right")!!
        set(v) = prefs.edit { putString("mousepad_double_tap_key", v) }
    var tripleTap: String // three-finger tap
        get() = prefs.getString("mousepad_triple_tap_key", "middle")!!
        set(v) = prefs.edit { putString("mousepad_triple_tap_key", v) }
    var sensitivity: String
        get() = prefs.getString("mousepad_sensitivity_key", "default")!!
        set(v) = prefs.edit { putString("mousepad_sensitivity_key", v) }
    var acceleration: String
        get() = prefs.getString("mousepad_acceleration_profile_key", "medium")!!
        set(v) = prefs.edit { putString("mousepad_acceleration_profile_key", v) }
    var invertScroll: Boolean
        get() = prefs.getBoolean("mousepad_scroll_direction", false)
        set(v) = prefs.edit { putBoolean("mousepad_scroll_direction", v) }
    var scrollSensitivity: Int
        get() = prefs.getInt("mousepad_scroll_sensitivity", 100)
        set(v) = prefs.edit { putInt("mousepad_scroll_sensitivity", v) }
    var gyroMouse: Boolean
        get() = prefs.getBoolean("gyro_mouse_enabled", false)
        set(v) = prefs.edit { putBoolean("gyro_mouse_enabled", v) }
    var gyroSensitivity: Int
        get() = prefs.getInt("gyro_mouse_sensitivity", 100)
        set(v) = prefs.edit { putInt("gyro_mouse_sensitivity", v) }
    var mouseButtons: Boolean
        get() = prefs.getBoolean("mousepad_mouse_buttons_enabled_pref", true)
        set(v) = prefs.edit { putBoolean("mousepad_mouse_buttons_enabled_pref", v) }
    var doubleTapDrag: Boolean
        get() = prefs.getBoolean("mousepad_doubletap_drag_enabled_pref", true)
        set(v) = prefs.edit { putBoolean("mousepad_doubletap_drag_enabled_pref", v) }

    val sensitivityFactor: Float get() = SENSITIVITIES[sensitivity] ?: 1f

    companion object {
        val TAP_ACTIONS = linkedMapOf("left" to "Лівий клік", "right" to "Правий клік", "middle" to "Середній клік", "none" to "Нічого")
        val SENSITIVITY_NAMES = linkedMapOf("slowest" to "Найповільніше", "aboveSlowest" to "Повільно", "default" to "Звичайно",
            "aboveDefault" to "Швидко", "fastest" to "Найшвидше")
        val ACCELERATION_NAMES = linkedMapOf("noacceleration" to "Без прискорення", "weaker" to "Найслабше", "weak" to "Слабке",
            "medium" to "Середнє", "strong" to "Сильне", "stronger" to "Найсильніше")
        private val SENSITIVITIES = mapOf("slowest" to 0.2f, "aboveSlowest" to 0.5f, "default" to 1f, "aboveDefault" to 1.5f, "fastest" to 2f)
    }
}

/** PointerAccelerationProfileFactory: speed-based polynomial curves over the last 150 ms. */
internal class PointerAcceleration(name: String) {
    private val exponent: Float? = when (name) {
        "weaker" -> 0.25f
        "weak" -> 0.5f
        "medium" -> 1f
        "strong" -> 1.5f
        "stronger" -> 2f
        else -> null
    }
    private class Sample(val x: Float, val y: Float, val time: Long)
    private val history = ArrayDeque<Sample>()
    private var accX = 0f
    private var accY = 0f

    fun touchMoved(dx: Float, dy: Float, time: Long) {
        val power = exponent
        if (power == null) { accX += dx; accY += dy; return }
        var distance = 0f
        var deltaT = 0L
        for (sample in history) {
            if (time - sample.time > 150) break
            distance += sqrt(sample.x * sample.x + sample.y * sample.y)
            deltaT = time - sample.time
        }
        val multiplier = (if (deltaT == 0L) 0f else ((distance / (deltaT / 1000f)) / 600f).toDouble().pow(power.toDouble()).toFloat())
            .coerceAtLeast(0.01f)
        accX += dx * multiplier
        accY += dy * multiplier
        history.addFirst(Sample(dx, dy, time))
        while (history.size > 32) history.removeLast()
    }

    /** Whole pixels to send now; the remainder carries over (commitAcceleratedMouseDelta). */
    fun commit(): Offset {
        if (exponent == null) return Offset(accX, accY).also { accX = 0f; accY = 0f }
        val out = Offset(accX.toInt().toFloat(), accY.toInt().toFloat())
        accX %= 1f
        accY %= 1f
        return out
    }
}

/**
 * The MousePadActivity touch surface: one finger moves (with acceleration), taps with one,
 * two or three fingers run the configured clicks, two fingers scroll, double-tap-and-drag
 * (or long press) holds the button until the next tap.
 */
internal interface TouchpadOutput {
    fun click(button: String)
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)
}

private class ConnectorOutput(private val connector: PairingConnector?) : TouchpadOutput {
    override fun click(button: String) { connector?.sendMouseClick(button) }
    override fun move(dx: Float, dy: Float) { connector?.sendMouseMove(dx, dy) }
    override fun scroll(dx: Float, dy: Float) { connector?.sendMouseScroll(dx, dy) }
}

@Composable
internal fun TouchpadSurface(connector: PairingConnector?, modifier: Modifier, content: @Composable BoxScope.() -> Unit) =
    TouchpadSurface(remember(connector) { ConnectorOutput(connector) }, modifier, content)

@Composable
internal fun TouchpadSurface(out: TouchpadOutput, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { MousepadSettings(context) }
    val density = context.resources.displayMetrics
    val dpiMultiplier = 240f / density.xdpi
    val scope = rememberCoroutineScope()
    val holding = remember { mutableStateOf(false) }

    fun click(action: String) {
        when (action) {
            "left" -> if (holding.value) { out.click("singlerelease"); holding.value = false } else out.click("left")
            "right" -> out.click("right")
            "middle" -> out.click("middle")
        }
    }

    Box(modifier.pointerInput(out) {
        val tapTimeout = 200L // ViewConfiguration.getTapTimeout() + 100
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        val longPress = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        var lastTapUp = 0L
        var pendingTap: Job? = null
        awaitPointerEventScope {
            while (true) {
                // ---- gesture start ----
                var event = awaitPointerEvent()
                val firstDown = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                val downTime = firstDown.uptimeMillis
                val secondTap = downTime - lastTapUp <= doubleTapTimeout && pendingTap?.isActive == true
                pendingTap?.cancel()
                var maybeDragging = false
                if (secondTap && !holding.value) {
                    if (settings.doubleTapDrag) maybeDragging = true else out.click("double")
                }
                val acceleration = PointerAcceleration(settings.acceleration)
                val sensitivity = settings.sensitivityFactor
                val scrollCoefficient = (settings.scrollSensitivity.coerceAtLeast(1) / 100f).toDouble().pow(1.5)
                val scrollDirection = if (settings.invertScroll) -1 else 1
                var maxPointers = 1
                var multiHandled = false
                var scrolling = false
                var movedSq = 0f
                var dragDistance2 = 0f
                var accScrollX = 0.0
                var accScrollY = 0.0
                var longPressDone = false
                val longPressJob = if (!settings.doubleTapDrag && !secondTap) scope.launch {
                    delay(longPress)
                    if (!holding.value) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        out.click("singlehold")
                        holding.value = true
                        longPressDone = true
                    }
                } else null

                // ---- gesture body ----
                while (true) {
                    event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    maxPointers = maxOf(maxPointers, pressed)
                    // MousePadGestureDetector: first finger lifted soon after the first down.
                    if (!multiHandled && event.changes.any { it.previousPressed && !it.pressed } && pressed >= 1) {
                        val count = pressed + event.changes.count { it.previousPressed && !it.pressed }
                        if (event.changes.first().uptimeMillis - downTime <= tapTimeout && movedSq < slop * slop) {
                            when (count) {
                                3 -> { click(settings.tripleTap); multiHandled = true }
                                2 -> { click(settings.doubleTap); multiHandled = true }
                            }
                        }
                    }
                    if (event.type == PointerEventType.Move) {
                        val moving = event.changes.filter { it.pressed && it.previousPressed }
                        if (moving.isNotEmpty()) {
                            val dx = moving.map { it.position.x - it.previousPosition.x }.average().toFloat()
                            val dy = moving.map { it.position.y - it.previousPosition.y }.average().toFloat()
                            movedSq += dx * dx + dy * dy
                            if (movedSq > slop * slop) longPressJob?.cancel()
                            if (pressed >= 2) {
                                scrolling = true
                                // GestureDetector distances are previous - current.
                                var sx = -dx
                                var sy = -dy
                                if (abs(sx) < abs(sy) * 0.5f) sx = 0f else if (abs(sy) < abs(sx) * 0.5f) sy = 0f
                                accScrollX += sx * scrollCoefficient
                                accScrollY += sy * scrollCoefficient
                                if (abs(accScrollX) > 2.5 || abs(accScrollY) > 2.5) {
                                    out.scroll((-scrollDirection * accScrollX).toFloat(), (scrollDirection * accScrollY).toFloat())
                                    accScrollX = 0.0; accScrollY = 0.0
                                }
                            } else if (!scrolling) {
                                val mx = dx * dpiMultiplier * sensitivity
                                val my = dy * dpiMultiplier * sensitivity
                                if (maybeDragging) {
                                    dragDistance2 += mx * mx + my * my
                                    if (dragDistance2 >= 25f) {
                                        maybeDragging = false
                                        holding.value = true
                                        out.click("singlehold")
                                    }
                                } else {
                                    acceleration.touchMoved(mx, my, event.changes.first().uptimeMillis)
                                    val delta = acceleration.commit()
                                    if (delta.x != 0f || delta.y != 0f) out.move(delta.x, delta.y)
                                }
                            }
                            moving.forEach { it.consume() }
                        }
                    }
                    if (event.changes.none { it.pressed }) break
                }

                // ---- gesture end ----
                longPressJob?.cancel()
                val upTime = event.changes.first().uptimeMillis
                if (maybeDragging) {
                    out.click("double") // double tap without a drag
                } else if (maxPointers == 1 && !secondTap && !longPressDone && movedSq <= slop * slop && upTime - downTime < longPress) {
                    lastTapUp = upTime
                    // onSingleTapConfirmed: only once no second tap follows.
                    pendingTap = scope.launch {
                        delay(doubleTapTimeout)
                        click(settings.singleTap)
                    }
                }
            }
        }
    }.semantics { contentDescription = "Сенсорна панель" }) { content() }

    GyroMouse(out, settings)
}

/** Gyroscope mouse from MousePadActivity.onSensorChanged, active while the pad is shown. */
@Composable
private fun GyroMouse(out: TouchpadOutput, settings: MousepadSettings) {
    val context = LocalContext.current
    if (!settings.gyroMouse) return
    DisposableEffect(out) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val factor = settings.gyroSensitivity / 100f
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            override fun onSensorChanged(event: SensorEvent) {
                var x = -event.values[2] * 70 * factor
                var y = -event.values[0] * 70 * factor
                x = if (x < 0.25f && x > -0.25f) 0f else x * factor
                y = if (y < 0.25f && y > -0.25f) 0f else y * factor
                if (x != 0f || y != 0f) out.move(x, y)
            }
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager?.unregisterListener(listener) }
    }
}

/** MousePad preferences screen section. */
@Composable
internal fun MousepadSettingsSection() {
    val context = LocalContext.current
    val settings = remember { MousepadSettings(context) }
    var version by remember { mutableIntStateOf(0) }
    version
    val hasGyro = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null }
    Choice("Торкання одним пальцем", MousepadSettings.TAP_ACTIONS, settings.singleTap) { settings.singleTap = it; version++ }
    Choice("Торкання двома пальцями", MousepadSettings.TAP_ACTIONS, settings.doubleTap) { settings.doubleTap = it; version++ }
    Choice("Торкання трьома пальцями", MousepadSettings.TAP_ACTIONS, settings.tripleTap) { settings.tripleTap = it; version++ }
    Choice("Чутливість", MousepadSettings.SENSITIVITY_NAMES, settings.sensitivity) { settings.sensitivity = it; version++ }
    Choice("Прискорення вказівника", MousepadSettings.ACCELERATION_NAMES, settings.acceleration) { settings.acceleration = it; version++ }
    Toggle("Обернена прокрутка", settings.invertScroll) { settings.invertScroll = it; version++ }
    Text("Швидкість прокрутки: ${settings.scrollSensitivity}%", style = MaterialTheme.typography.bodyMedium)
    Slider(settings.scrollSensitivity.toFloat(), { settings.scrollSensitivity = it.toInt(); version++ }, valueRange = 1f..300f)
    Toggle("Подвійне торкання й перетягування", settings.doubleTapDrag) { settings.doubleTapDrag = it; version++ }
    Toggle("Кнопки миші під панеллю", settings.mouseButtons) { settings.mouseButtons = it; version++ }
    if (hasGyro) {
        Toggle("Гіроскопічна миша", settings.gyroMouse) { settings.gyroMouse = it; version++ }
        if (settings.gyroMouse) {
            Text("Чутливість гіроскопа: ${settings.gyroSensitivity}%", style = MaterialTheme.typography.bodyMedium)
            Slider(settings.gyroSensitivity.toFloat(), { settings.gyroSensitivity = it.toInt(); version++ }, valueRange = 10f..300f)
        }
    }
}

@Composable
private fun Choice(title: String, options: Map<String, String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Box {
            TextButton(onClick = { open = true }) { Text(options[selected] ?: selected) }
            DropdownMenu(open, { open = false }) {
                options.forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(key); open = false })
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked, onChange, Modifier.semantics { contentDescription = title })
    }
}
