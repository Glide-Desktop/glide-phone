package org.mindenit.glidephone

import android.content.Context
import android.os.Build

/**
 * Stores setup choices independently of the UI. These choices are preferences for
 * future integrations; they do not grant permissions or start any services.
 */
internal class SetupPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("glide_setup", Context.MODE_PRIVATE)

    val isSetupComplete: Boolean
        get() = preferences.getBoolean(KEY_COMPLETED, false)

    fun completeSetup() {
        preferences.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    val deviceName: String
        get() = preferences.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    fun setDeviceName(name: String) {
        require(name.isNotBlank() && name.length <= 50)
        preferences.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun selectedFeatures(): Set<String> =
        preferences.getStringSet(KEY_FEATURES, DEFAULT_FEATURES)?.toSet() ?: DEFAULT_FEATURES

    fun setFeatureSelected(id: String, selected: Boolean): Set<String> {
        require(id in DEFAULT_FEATURES) { "Unknown feature: $id" }
        val current = selectedFeatures()
        val updated = if (selected) current + id else current - id
        preferences.edit().putStringSet(KEY_FEATURES, updated).apply()
        return updated
    }

    /** Don't sync clipboard content Android marks as sensitive (passwords); off by default. */
    var skipSensitiveClipboard: Boolean
        get() = preferences.getBoolean(KEY_SKIP_SENSITIVE, false)
        set(value) { preferences.edit().putBoolean(KEY_SKIP_SENSITIVE, value).apply() }

    /** Ringtone URI for find-my-phone; null means the default ringtone. */
    var findMyPhoneRingtone: String?
        get() = preferences.getString(KEY_RINGTONE, null)?.takeIf { it.isNotEmpty() }
        set(value) { preferences.edit().putString(KEY_RINGTONE, value).apply() }

    var findMyPhoneFlashlight: Boolean
        get() = preferences.getBoolean(KEY_FLASHLIGHT, false)
        set(value) { preferences.edit().putBoolean(KEY_FLASHLIGHT, value).apply() }

    /** RemoteKeyboardIMEPlugin "editing only": type through our IME only while it is shown. */
    var remoteKeyboardEditingOnly: Boolean
        get() = preferences.getBoolean(KEY_EDITING_ONLY, true)
        set(value) { preferences.edit().putBoolean(KEY_EDITING_ONLY, value).apply() }

    private companion object {
        const val KEY_EDITING_ONLY = "remotekeyboard_editing_only"
        const val KEY_RINGTONE = "findmyphone_ringtone"
        const val KEY_FLASHLIGHT = "findmyphone_flashlight"
        const val KEY_SKIP_SENSITIVE = "clipboard_skip_sensitive"
        const val KEY_COMPLETED = "completed"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_FEATURES = "selected_features"
        val DEFAULT_FEATURES = FeatureId.entries.map { it.key }.toSet()
    }
}
