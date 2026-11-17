package org.mindenit.glidephone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colors that the Material [androidx.compose.material3.ColorScheme] does not model. */
@Immutable
data class GlideColors(
    val tonal: Color,
    val cardBorder: Color,
    val connectedContainer: Color,
    val onConnected: Color,
    val connectedDot: Color,
    val alertContainer: Color,
    val onAlert: Color,
    val deepAccent: Color,
)

private val LightGlideColors = GlideColors(
    tonal = TonalLight,
    cardBorder = CardBorderLight,
    connectedContainer = ConnectedContainerLight,
    onConnected = OnConnectedLight,
    connectedDot = ConnectedDotLight,
    alertContainer = AlertContainerLight,
    onAlert = OnAlertLight,
    deepAccent = DeepAccentLight,
)

private val DarkGlideColors = GlideColors(
    tonal = TonalDark,
    cardBorder = CardBorderDark,
    connectedContainer = ConnectedContainerDark,
    onConnected = OnConnectedDark,
    connectedDot = ConnectedDotDark,
    alertContainer = AlertContainerDark,
    onAlert = OnAlertDark,
    deepAccent = DeepAccentDark,
)

val LocalGlideColors = staticCompositionLocalOf { LightGlideColors }

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

@Composable
fun GlidePhoneTheme(
    darkTheme: Boolean = when (AppTheme.current(androidx.compose.ui.platform.LocalContext.current)) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        else -> isSystemInDarkTheme()
    },
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val glideColors = if (darkTheme) DarkGlideColors else LightGlideColors
    androidx.compose.runtime.CompositionLocalProvider(LocalGlideColors provides glideColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content,
        )
    }
}


/** KDE Connect's app theme setting (KEY_APP_THEME): light, dark or follow the system. */
object AppTheme {
    const val SYSTEM = "follow_system"
    const val LIGHT = "light"
    const val DARK = "dark"
    private val state = androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences("glide_setup", android.content.Context.MODE_PRIVATE)

    fun current(context: android.content.Context): String =
        state.value ?: (prefs(context).getString("theme_pref", SYSTEM) ?: SYSTEM).also { state.value = it }

    fun set(context: android.content.Context, value: String) {
        prefs(context).edit().putString("theme_pref", value).apply()
        state.value = value
    }
}
