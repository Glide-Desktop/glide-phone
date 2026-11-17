package org.mindenit.glidephone

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SetupFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val preferences
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("glide_setup", 0)

    private var originalCompleted: Boolean? = null
    private var originalFeatures: Set<String>? = null
    private var originalDeviceName: String? = null

    @Before
    fun setUp() {
        GlideHub.offline = true
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { GlideHub.resetForTests() }
        // The app asks for these on first launch; a system dialog would block the UI under test.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf("android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK").forEach { permission ->
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, permission)
            }
        }
        originalCompleted = if (preferences.contains("completed")) preferences.getBoolean("completed", false) else null
        originalFeatures = preferences.getStringSet("selected_features", null)?.toSet()
        originalDeviceName = preferences.getString("device_name", null)
        preferences.edit().remove("completed").remove("selected_features").remove("device_name").commit()
    }

    @After
    fun tearDown() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { GlideHub.resetForTests() }
        GlideHub.offline = false
        val editor = preferences.edit()
        if (originalCompleted == null) editor.remove("completed") else editor.putBoolean("completed", originalCompleted!!)
        if (originalFeatures == null) editor.remove("selected_features") else editor.putStringSet("selected_features", originalFeatures)
        if (originalDeviceName == null) editor.remove("device_name") else editor.putString("device_name", originalDeviceName)
        editor.commit()
    }

    @Test
    fun navigationSwitchesBetweenTopLevelScreens() {
        preferences.edit().putBoolean("completed", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Пристрої").performClick()
            compose.onNodeWithText("Поки що немає пристроїв").assertExists()
            compose.onNodeWithText("Я").performClick()
            compose.onNodeWithText("Інформація про пристрій").assertExists()
            compose.onNodeWithText("Головна").performClick()
            compose.onNodeWithText("Немає підключених пристроїв").assertExists()
        }
    }

    @Test
    fun deviceNameCanBeChangedAndSurvivesRecreation() {
        preferences.edit().putBoolean("completed", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Я").performClick()
            compose.onNodeWithText("Назва пристрою").performClick()
            compose.onNodeWithText("Назва").performTextClearance()
            compose.onNodeWithText("Назва").performTextInput("Мій Glide")
            compose.onNodeWithText("Зберегти").performClick()
            compose.onNodeWithText("Мій Glide").assertExists()

            scenario.recreate()

            compose.onNodeWithText("Мій Glide").assertExists()
        }
    }

    @Test
    fun homeDoesNotOfferDeviceActionsWhenDisconnected() {
        preferences.edit().putBoolean("completed", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Немає підключених пристроїв").assertExists()
            compose.onNodeWithText("Файли").assertDoesNotExist()
            compose.onNodeWithText("Мої пристрої").performClick()
            compose.onNodeWithText("Поки що немає пристроїв").assertExists()
        }
    }

    @Test
    fun selectedFeaturePersistsAfterSetupAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithText("Почати").performClick()
            compose.onNodeWithText("Пропустити").performClick()
            compose.onNodeWithContentDescription("Файли").assertIsOn().performClick().assertIsOff()
            compose.onNodeWithContentDescription("Назад").performClick()
            compose.onNodeWithText("Пропустити").performClick()
            compose.onNodeWithContentDescription("Файли").assertIsOff()
            compose.onNodeWithText("Завершити").performClick()

            scenario.recreate()

            compose.onNodeWithText("Я").performClick()
            compose.onNodeWithText("Можливості").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Файли").assertIsOff()
            compose.onNodeWithContentDescription("Сповіщення").assertIsOn()
        }
    }
}
