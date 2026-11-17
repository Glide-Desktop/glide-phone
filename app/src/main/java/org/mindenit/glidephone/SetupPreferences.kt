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

    /** ContactsPlugin "acceptedToTransferContacts": explicit consent before sharing contacts. */
    var contactsAccepted: Boolean
        get() = preferences.getBoolean(KEY_CONTACTS_ACCEPTED, false)
        set(value) { preferences.edit().putBoolean(KEY_CONTACTS_ACCEPTED, value).apply() }

    /** mpris_notification: show the computer's player as a media notification. */
    var mprisNotification: Boolean
        get() = preferences.getBoolean(KEY_MPRIS_NOTIFICATION, true)
        set(value) { preferences.edit().putBoolean(KEY_MPRIS_NOTIFICATION, value).apply() }

    /** pref_mpris_volume_control: volume keys change the computer's volume while it shows. */
    var mprisVolumeKeys: Boolean
        get() = preferences.getBoolean(KEY_MPRIS_VOLUME, true)
        set(value) { preferences.edit().putBoolean(KEY_MPRIS_VOLUME, value).apply() }

    /** pref_presenter_enable_volume_keys: volume keys switch slides while presenting. */
    var presenterVolumeKeys: Boolean
        get() = preferences.getBoolean(KEY_PRESENTER_VOLUME, true)
        set(value) { preferences.edit().putBoolean(KEY_PRESENTER_VOLUME, value).apply() }

    /** pref_presenter_sensitivity (0-100, default 50). */
    var presenterSensitivity: Int
        get() = preferences.getInt(KEY_PRESENTER_SENSITIVITY, 50)
        set(value) { preferences.edit().putInt(KEY_PRESENTER_SENSITIVITY, value).apply() }

    /** PresenterActivity's pointer factor for the sensitivity setting (0.036 at 50). */
    val presenterPointerFactor: Float get() = ((presenterSensitivity + 10) / 100f) / 10f * 0.6f

    /** mpris_interval_time: seek step in microseconds (10 s default). */
    var mprisSeekMicros: Long
        get() = preferences.getLong(KEY_MPRIS_SEEK, 10_000_000L)
        set(value) { preferences.edit().putLong(KEY_MPRIS_SEEK, value).apply() }

    /** share_destination_folder_uri: SAF tree for received files, null = Downloads. */
    var shareDestination: String?
        get() = preferences.getString(KEY_SHARE_DESTINATION, null)
        set(value) { preferences.edit().putString(KEY_SHARE_DESTINATION, value).apply() }

    /** share_notification_preference: sound/vibrate when a transfer finishes. */
    var shareAlert: Boolean
        get() = preferences.getBoolean(KEY_SHARE_ALERT, true)
        set(value) { preferences.edit().putBoolean(KEY_SHARE_ALERT, value).apply() }

    /** digitizer_preference_key_hide_draw_button */
    var digitizerHideDrawButton: Boolean
        get() = preferences.getBoolean(KEY_DIGITIZER_HIDE, false)
        set(value) { preferences.edit().putBoolean(KEY_DIGITIZER_HIDE, value).apply() }

    /** digitizer_preference_key_draw_button_side: top_left, top_right, bottom_left, bottom_right. */
    var digitizerDrawButtonSide: String
        get() = preferences.getString(KEY_DIGITIZER_SIDE, "bottom_left") ?: "bottom_left"
        set(value) { preferences.edit().putString(KEY_DIGITIZER_SIDE, value).apply() }

    /** mpris_keepwatching_enabled: offer to continue a web video on the phone. */
    var mprisKeepWatching: Boolean
        get() = preferences.getBoolean(KEY_MPRIS_KEEP_WATCHING, true)
        set(value) { preferences.edit().putBoolean(KEY_MPRIS_KEEP_WATCHING, value).apply() }

    /** set_runcommand_name_as_title: controls/widget show the name, the command below. */
    var runCommandNameAsTitle: Boolean
        get() = preferences.getBoolean(KEY_RUNCOMMAND_NAME_TITLE, true)
        set(value) { preferences.edit().putBoolean(KEY_RUNCOMMAND_NAME_TITLE, value).apply() }

    /** pref_sendkeystrokes_enabled: accept text/x-keystrokes shares. */
    var sendKeystrokes: Boolean
        get() = preferences.getBoolean(KEY_SEND_KEYSTROKES, true)
        set(value) { preferences.edit().putBoolean(KEY_SEND_KEYSTROKES, value).apply() }

    /** pref_send_safe_text_immediately: digits-only codes are typed without asking. */
    var sendSafeTextImmediately: Boolean
        get() = preferences.getBoolean(KEY_SAFE_TEXT, true)
        set(value) { preferences.edit().putBoolean(KEY_SAFE_TEXT, value).apply() }

    private companion object {
        const val KEY_SEND_KEYSTROKES = "pref_sendkeystrokes_enabled"
        const val KEY_SAFE_TEXT = "pref_send_safe_text_immediately"
        const val KEY_RUNCOMMAND_NAME_TITLE = "set_runcommand_name_as_title"
        const val KEY_MPRIS_KEEP_WATCHING = "mpris_keepwatching_enabled"
        const val KEY_DIGITIZER_HIDE = "digitizer_hide_draw_button"
        const val KEY_DIGITIZER_SIDE = "digitizer_draw_button_side"
        const val KEY_SHARE_DESTINATION = "share_destination_folder_uri"
        const val KEY_SHARE_ALERT = "share_notification_preference"
        const val KEY_MPRIS_SEEK = "mpris_interval_time"
        const val KEY_PRESENTER_VOLUME = "presenter_volume_keys"
        const val KEY_PRESENTER_SENSITIVITY = "presenter_sensitivity"
        const val KEY_MPRIS_NOTIFICATION = "mpris_notification"
        const val KEY_MPRIS_VOLUME = "mpris_volume_keys"
        const val KEY_CONTACTS_ACCEPTED = "contacts_accepted"
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
