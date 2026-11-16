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
    @Volatile private var lastState = TelephonyManager.CALL_STATE_IDLE
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
                val name = ContactsProvider.nameForNumber(context, number)
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        lastNumber = number
                        emit(ProtocolPackets.telephony("ringing", name, number))
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> emit(ProtocolPackets.telephony("talking", name, number))
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (lastState == TelephonyManager.CALL_STATE_IDLE) {
                            // Initial callback on registration: nothing happened.
                        } else if (lastState == TelephonyManager.CALL_STATE_RINGING) {
                            emit(ProtocolPackets.telephony("missedCall", name, number))
                        } else {
                            emit(ProtocolPackets.telephony("ringing", name, number, isCancel = true))
                        }
                        lastNumber = ""
                    }
                }
                lastState = state
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
