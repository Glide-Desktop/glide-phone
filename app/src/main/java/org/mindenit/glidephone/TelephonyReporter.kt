package org.mindenit.glidephone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Notifies connected computers about incoming/missed/ongoing calls (kdeconnect.telephony). */
internal object TelephonyReporter {
    @Volatile private var listener: PhoneStateListener? = null
    @Volatile private var lastEvent: String? = null
    @Volatile private var lastName = ""
    @Volatile private var muted = false

    private fun remember(event: String, name: String, number: String) {
        lastEvent = event; lastName = name; lastNumber = number
    }

    /** kdeconnect.telephony.request_mute: silence the ringer for the current call. */
    fun muteRinger(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        runCatching {
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_MUTE, 0)
            muted = true
        }
    }

    private fun unmuteRinger(context: Context) {
        if (!muted) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        runCatching { audio.adjustStreamVolume(android.media.AudioManager.STREAM_RING, android.media.AudioManager.ADJUST_UNMUTE, 0) }
        muted = false
    }
    @Volatile private var lastNumber = ""

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun start(context: Context, emit: (String) -> Unit) {
        if (listener != null || !hasPermission(context)) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val number = phoneNumber?.takeIf { it.isNotBlank() } ?: lastNumber
                // Without a matching contact KDE Connect shows the number as the name.
                val name = ContactsProvider.nameForNumber(context, number).ifBlank { number }
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        unmuteRinger(context)
                        remember("ringing", name, number)
                        emit(ProtocolPackets.telephony("ringing", name, number))
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        remember("talking", name, number)
                        emit(ProtocolPackets.telephony("talking", name, number))
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val event = lastEvent ?: return // nothing was reported (e.g. initial callback)
                        // Withdraw the last event, then report a missed call if nobody answered.
                        emit(ProtocolPackets.telephony(event, lastName, lastNumber, isCancel = true))
                        if (event == "ringing") emit(ProtocolPackets.telephony("missedCall", lastName, lastNumber))
                        unmuteRinger(context)
                        lastEvent = null
                        lastNumber = ""
                        lastName = ""
                    }
                }
            }
        }
        Handler(Looper.getMainLooper()).post {
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
        }
        listener = l
    }

    @Suppress("DEPRECATION")
    fun stop(context: Context) {
        val l = listener ?: return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        Handler(Looper.getMainLooper()).post {
            runCatching { tm?.listen(l, PhoneStateListener.LISTEN_NONE) }
        }
        listener = null
    }
}
