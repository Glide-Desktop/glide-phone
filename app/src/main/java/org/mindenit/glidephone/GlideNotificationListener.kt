package org.mindenit.glidephone

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Bridges the system listener to the currently authenticated foreground connection. */
internal object NotificationBridge {
    @Volatile private var connector: PairingConnector? = null
    @Volatile private var listener: GlideNotificationListener? = null

    fun attach(connection: PairingConnector) {
        connector = connection
        refresh()
    }

    fun detach(connection: PairingConnector) {
        if (connector === connection) connector = null
    }

    fun connected(service: GlideNotificationListener) {
        listener = service
        refresh()
    }

    fun disconnected(service: GlideNotificationListener) {
        if (listener === service) listener = null
    }

    fun refresh() {
        Handler(Looper.getMainLooper()).post { listener?.sendActiveNotifications() }
    }

    fun send(packet: String) {
        connector?.sendNotificationPacket(packet)
    }

    /** Handle an incoming notification.action / notification.reply / notification.request packet. */
    fun handleIncoming(type: String, body: JSONObject) {
        val service = listener ?: return
        when (type) {
            ProtocolPackets.TYPE_NOTIFICATION_ACTION ->
                service.fireAction(body.optString("key"), body.optString("action"))
            ProtocolPackets.TYPE_NOTIFICATION_REPLY ->
                service.fireReply(body.optString("requestReplyId"), body.optString("message"))
            ProtocolPackets.TYPE_NOTIFICATION_REQUEST ->
                if (body.optBoolean("cancel", false) || body.has("cancel"))
                    service.cancelByKey(body.optString("cancel").ifBlank { body.optString("key") })
        }
    }
}

class GlideNotificationListener : NotificationListenerService() {
    private val active = ConcurrentHashMap<String, StatusBarNotification>()
    private val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationBridge.connected(this)
    }

    override fun onListenerDisconnected() {
        NotificationBridge.disconnected(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotificationBridge.disconnected(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        active[sbn.key] = sbn
        publish(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        active.remove(sbn.key)
        if (announced.remove(sbn.key)) {
            NotificationBridge.send(ProtocolPackets.notificationCancel(sbn.key))
        }
    }

    internal fun sendActiveNotifications() {
        runCatching { activeNotifications?.forEach { active[it.key] = it; publish(it) } }
    }

    /** Fire a non-reply action button a computer tapped. */
    internal fun fireAction(key: String, actionTitle: String) {
        val sbn = active[key] ?: return
        val action = sbn.notification.actions?.firstOrNull {
            it.title?.toString() == actionTitle && it.remoteInputs.isNullOrEmpty()
        } ?: return
        runCatching { action.actionIntent?.send() }
    }

    /** Send an inline reply typed on a computer. */
    internal fun fireReply(key: String, message: String) {
        val sbn = active[key] ?: return
        val action = sbn.notification.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val remoteInputs = action.remoteInputs ?: return
        val intent = Intent()
        val bundle = Bundle()
        remoteInputs.forEach { bundle.putCharSequence(it.resultKey, message) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
        runCatching { action.actionIntent?.send(this, 0, intent) }
    }

    internal fun cancelByKey(key: String) {
        if (key.isNotBlank()) runCatching { cancelNotification(key) }
    }

    private fun publish(sbn: StatusBarNotification) {
        val notification = sbn.notification
        // Same exclusions as KDE Connect: nothing persistent, local-only or media-related.
        val unwanted = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_LOCAL_ONLY or Notification.FLAG_GROUP_SUMMARY
        if (sbn.packageName == packageName ||
            notification.visibility == Notification.VISIBILITY_SECRET ||
            notification.flags and unwanted != 0 ||
            notification.category == Notification.CATEGORY_TRANSPORT) return
        if (sbn.packageName == "com.android.systemui" &&
            (sbn.tag == "low_battery" || (android.os.Build.VERSION.SDK_INT >= 26 && notification.channelId == "MediaOngoingActivity"))) return
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            runCatching { packageManager.isPackageSuspended(sbn.packageName) }.getOrDefault(false)) return
        val isUpdate = !announced.add(sbn.key)
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(1000).orEmpty()
        val body = (notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(4000).orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName).take(200)

        val actionTitles = notification.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() }
            .mapNotNull { it.title?.toString() }
        val hasReply = notification.actions.orEmpty().any { !it.remoteInputs.isNullOrEmpty() }

        NotificationBridge.send(
            ProtocolPackets.notification(
                id = sbn.key,
                appName = appName,
                title = title,
                text = body,
                silent = isUpdate && notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
                actions = actionTitles,
                requestReplyId = if (hasReply) sbn.key else null,
                isClearable = sbn.isClearable,
                time = sbn.postTime
            )
        )
    }
}
