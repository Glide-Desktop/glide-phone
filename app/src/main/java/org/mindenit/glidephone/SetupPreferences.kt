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

    private companion object {
        const val KEY_SKIP_SENSITIVE = "clipboard_skip_sensitive"
        const val KEY_COMPLETED = "completed"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_FEATURES = "selected_features"
        val DEFAULT_FEATURES = FeatureId.entries.map { it.key }.toSet()
    }
}
