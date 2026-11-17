package org.mindenit.glidephone

import android.content.Context
import androidx.core.content.edit

/**
 * Which phone notifications are forwarded and how much of them, after KDE Connect's
 * AppDatabase: a global switch, per-app switches (Google Now off by default because it
 * re-posts constantly) and per-app privacy options that strip the contents or images.
 */
internal class NotificationFilter(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("notification_filter", Context.MODE_PRIVATE)

    var allEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALL, true)
        set(value) = prefs.edit {
            putBoolean(KEY_ALL, value)
            // Like AppDatabase.allEnabled: flipping it resets every per-app choice.
            prefs.all.keys.filter { it.startsWith(PREFIX_APP) }.forEach { putBoolean(it, value) }
        }

    fun isEnabled(packageName: String): Boolean =
        prefs.getBoolean(PREFIX_APP + packageName, if (packageName in DISABLED_BY_DEFAULT) false else allEnabled)

    fun setEnabled(packageName: String, enabled: Boolean) = prefs.edit { putBoolean(PREFIX_APP + packageName, enabled) }

    fun blocksContents(packageName: String) = prefs.getBoolean(PREFIX_CONTENTS + packageName, false)
    fun setBlocksContents(packageName: String, blocked: Boolean) = prefs.edit { putBoolean(PREFIX_CONTENTS + packageName, blocked) }

    fun blocksImages(packageName: String) = prefs.getBoolean(PREFIX_IMAGES + packageName, false)
    fun setBlocksImages(packageName: String, blocked: Boolean) = prefs.edit { putBoolean(PREFIX_IMAGES + packageName, blocked) }

    /** pref_notification_screen_off: alert on the computer only while the phone is locked. */
    var alertOnlyWhenLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCKED_ONLY, value) }

    private companion object {
        const val KEY_ALL = "all_enabled"
        const val KEY_LOCKED_ONLY = "alert_only_when_locked"
        const val PREFIX_APP = "app:"
        const val PREFIX_CONTENTS = "block_contents:"
        const val PREFIX_IMAGES = "block_images:"
        val DISABLED_BY_DEFAULT = setOf("com.google.android.googlequicksearchbox")
    }
}
