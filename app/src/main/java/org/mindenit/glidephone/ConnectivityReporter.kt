package org.mindenit.glidephone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * kdeconnect.connectivity_report, after KDE Connect's ConnectivityListener: one entry per
 * active SIM (keyed by subscription id) with its data network type and signal level,
 * re-sent whenever either changes.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal object ConnectivityReporter {
    private data class State(var signalStrength: Int = 0, var networkType: String = "Unknown")

    private val main = Handler(Looper.getMainLooper())
    private val states = LinkedHashMap<Int, State>()
    private val listeners = HashMap<Int, PhoneStateListener>()
    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null
    @Volatile private var emit: ((String) -> Unit)? = null

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Current report, or null when there is nothing to report. */
    fun reportPacket(context: Context): String? = synchronized(states) {
        if (!hasPermission(context) || states.isEmpty()) return null
        val signals = JSONObject()
        states.forEach { (id, state) ->
            signals.put(id.toString(), JSONObject().put("networkType", state.networkType).put("signalStrength", state.signalStrength))
        }
        ProtocolPackets.connectivityReport(signals)
    }

    /** Start watching all SIMs; [send] receives a packet after every change. Idempotent. */
    fun start(context: Context, send: (String) -> Unit) {
        emit = send
        val app = context.applicationContext
        if (!hasPermission(app)) return
        main.post {
            if (subscriptionsListener != null) return@post
            val sm = app.getSystemService(SubscriptionManager::class.java) ?: return@post
            val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() = refreshSubscriptions(app)
            }
            subscriptionsListener = listener
            runCatching { sm.addOnSubscriptionsChangedListener(listener) }
            refreshSubscriptions(app)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        main.post {
            val tm = app.getSystemService(TelephonyManager::class.java)
            subscriptionsListener?.let { l -> runCatching { app.getSystemService(SubscriptionManager::class.java)?.removeOnSubscriptionsChangedListener(l) } }
            subscriptionsListener = null
            listeners.values.forEach { runCatching { tm?.listen(it, PhoneStateListener.LISTEN_NONE) } }
            listeners.clear()
            synchronized(states) { states.clear() }
        }
    }

    private fun refreshSubscriptions(context: Context) {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val active = runCatching {
            context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }
        }.getOrNull().orEmpty().toSet()
        (listeners.keys - active).forEach { id ->
            runCatching { tm.listen(listeners.remove(id), PhoneStateListener.LISTEN_NONE) }
            synchronized(states) { states.remove(id) }
        }
        (active - listeners.keys).forEach { id ->
            val subTm = tm.createForSubscriptionId(id)
            synchronized(states) {
                states[id] = State(
                    if (Build.VERSION.SDK_INT >= 28) runCatching { subTm.signalStrength?.level }.getOrNull() ?: 0 else 0,
                    networkTypeName(runCatching { subTm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN))
                )
            }
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    val level = signalStrength?.level ?: 0
                    if (update(id) { if (it.signalStrength != level) { it.signalStrength = level; true } else false }) push(context)
                }
                override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                    val name = networkTypeName(networkType)
                    if (update(id) { if (it.networkType != name) { it.networkType = name; true } else false }) push(context)
                }
            }
            listeners[id] = listener
            runCatching {
                subTm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
            }
        }
        push(context)
    }

    private fun update(id: Int, change: (State) -> Boolean): Boolean = synchronized(states) {
        states[id]?.let(change) ?: false
    }

    private fun push(context: Context) {
        reportPacket(context)?.let { packet -> emit?.invoke(packet) }
    }

    /** Same names as KDE Connect's ASUUtils.networkTypeToString. */
    fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA2000"
        TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
        else -> "Unknown"
    }
}
