package org.mindenit.glidephone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the background service back after a reboot or an app update, like KDE Connect's
 * KdeConnectBroadcastReceiver, so paired devices reconnect without opening the app.
 */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!SetupPreferences(context).isSetupComplete) return
                try {
                    GlideHub.goOnline(context)
                } catch (_: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException: the next app launch starts it.
                }
            }
        }
    }
}
