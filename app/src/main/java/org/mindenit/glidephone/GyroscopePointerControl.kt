package org.mindenit.glidephone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
internal fun GyroscopePointerControl(connector: PairingConnector?, featureEnabled: Boolean) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val gyroscope = remember(manager) { manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    var enabled by remember { mutableStateOf(false) }
    var registrationFailed by remember { mutableStateOf(false) }
    val canActivate = gyroscope != null && activity != null && connector?.isConnected == true && connector.supportsPresenter && featureEnabled

    LaunchedEffect(canActivate) {
        if (!canActivate) enabled = false
    }

    DisposableEffect(enabled, manager, gyroscope, activity, connector) {
        if (!enabled || manager == null || gyroscope == null || activity == null || connector == null) {
            onDispose { }
        } else {
            val motion = GyroscopeMotion()
            var registered = false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
                    if (!connector.isConnected) {
                        enabled = false
                        return
                    }
                    motion.sample(event.timestamp, event.values[0], event.values[2])?.let { (dx, dy) ->
                        connector.sendPresenterMove(dx, dy)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            fun stop() {
                if (registered) {
                    manager.unregisterListener(listener)
                    registered = false
                    motion.reset()
                    connector.sendPresenterStop()
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    stop()
                    enabled = false
                }
            }
            activity.lifecycle.addObserver(observer)
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                registered = manager.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
                registrationFailed = !registered
                if (!registered) enabled = false
            } else {
                enabled = false
            }
            onDispose {
                activity.lifecycle.removeObserver(observer)
                stop()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f))) {
        Text("Указка гіроскопом", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                gyroscope == null -> "На цьому пристрої немає гіроскопа."
                registrationFailed -> "Не вдалося запустити гіроскоп. Спробуйте ще раз."
                !featureEnabled -> "Увімкніть керування презентаціями в налаштуваннях."
                connector?.isConnected != true -> "Підключіть комп’ютер, щоб увімкнути указку."
                !connector.supportsPresenter -> "Підключений пристрій не підтримує указку."
                enabled -> "Поверніть телефон, щоб перемістити указку."
                else -> "Увімкніть указку та повертайте телефон."
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { registrationFailed = false; enabled = !enabled },
            enabled = canActivate,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (enabled) "Вимкнути указку" else "Увімкнути указку")
        }
    }
}
