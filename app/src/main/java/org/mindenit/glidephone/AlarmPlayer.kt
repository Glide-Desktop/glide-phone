package org.mindenit.glidephone

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

internal object AlarmPlayer {
    private var ringtone: Ringtone? = null

    @Synchronized
    fun start(context: Context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(context.applicationContext, uri)
            if (r != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    r.isLooping = true
                }
                r.play()
                ringtone = r
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {}
        ringtone = null
    }

    val isPlaying: Boolean
        @Synchronized
        get() = ringtone?.isPlaying == true
}
