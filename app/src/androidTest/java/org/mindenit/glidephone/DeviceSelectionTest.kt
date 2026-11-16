package org.mindenit.glidephone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class DeviceSelectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectingAnotherDeviceUpdatesHome() {
        val devices = listOf(RemoteDevice("one", "Ноутбук"), RemoteDevice("two", "Робочий ПК"))
        compose.setContent {
            var selected by remember { mutableStateOf(devices.first()) }
            MaterialTheme {
                HomeScreen(devices, selected, { id ->
                    selected = devices.first { it.id == id }
                }, {}, {})
            }
        }

        compose.onNodeWithText("Ноутбук").assertExists()
        compose.onNodeWithText("Змінити ▾").performClick()
        compose.onNodeWithText("Робочий ПК").performClick()
        compose.onNodeWithText("Робочий ПК").assertExists()
        compose.onNodeWithText("Ноутбук").assertDoesNotExist()
    }
}
