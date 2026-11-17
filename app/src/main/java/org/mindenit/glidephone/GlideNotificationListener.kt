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

    fun send(packet: String, key: String? = null, icon: ByteArray? = null, iconHash: String? = null) {
        connector?.sendNotificationPacket(packet, key, icon, iconHash)
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
        runCatching { setSpotify(activeNotifications.orEmpty().any { it.packageName == SPOTIFY }) }
    }

    /** MprisMediaSession hides the computer's Spotify while Spotify itself runs here. */
    private fun setSpotify(running: Boolean) {
        if (RemoteMediaNotification.spotifyRunning == running) return
        RemoteMediaNotification.spotifyRunning = running
        handler.post { RemoteMediaNotification.update(this) }
    }

    override fun onListenerDisconnected() {
        NotificationBridge.disconnected(this)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotificationBridge.disconnected(this)
        super.onDestroy()
    }

    private val handler = Handler(Looper.getMainLooper())

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == SPOTIFY) setSpotify(true)
        active[sbn.key] = sbn
        // Coalesce rapid updates of the same notification (NOTIFICATION_SYNC_DELAY_MS).
        handler.removeCallbacksAndMessages(sbn.key)
        handler.postAtTime({ if (active[sbn.key] === sbn) publish(sbn) }, sbn.key, android.os.SystemClock.uptimeMillis() + 50)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == SPOTIFY) setSpotify(false)
        active.remove(sbn.key)
        handler.removeCallbacksAndMessages(sbn.key)
        if (announced.remove(sbn.key)) {
            NotificationBridge.send(ProtocolPackets.notificationCancel(sbn.key))
        }
    }

    internal fun sendActiveNotifications() {
        // A bulk resend must not make the computer alert for every old notification again.
        runCatching { activeNotifications?.forEach { active[it.key] = it; publish(it, forceSilent = true) } }
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

    private fun publish(sbn: StatusBarNotification, forceSilent: Boolean = false) {
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
        val filter = NotificationFilter(this)
        if (!filter.isEnabled(sbn.packageName)) return
        val hideContents = filter.blocksContents(sbn.packageName)
        // "Only alert while locked": still send, but silently while the phone is in use.
        val quiet = filter.alertOnlyWhenLocked &&
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked != true
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

        val packet = org.json.JSONObject(ProtocolPackets.notification(
            id = sbn.key,
            appName = appName,
            title = title,
            text = body,
            silent = forceSilent || quiet || (isUpdate && notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0),
            actions = actionTitles,
            requestReplyId = if (hasReply) sbn.key else null,
            isClearable = sbn.isClearable,
            time = sbn.postTime
        ))
        val packetBody = packet.getJSONObject("body")
        if (hideContents) {
            // AppDatabase BLOCK_CONTENTS: only that something arrived, from which app.
            listOf("title", "text", "ticker", "requestReplyId").forEach { packetBody.remove(it) }
        } else {
            addConversation(notification, packetBody)
        }
        val icon = if (filter.blocksImages(sbn.packageName)) null else runCatching { extractIcon(sbn) }.getOrNull()
        NotificationBridge.send(packet.toString() + "\n", sbn.key, icon?.first, icon?.second)
    }

    /** Messaging-style notifications: send the conversation, and group chats as KDE Connect does. */
    private fun addConversation(notification: Notification, body: org.json.JSONObject) {
        val style = androidx.core.app.NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification) ?: return
        val messages = style.messages
        if (messages.isEmpty()) return
        val conversation = org.json.JSONArray()
        messages.forEach { message ->
            conversation.put(org.json.JSONObject()
                .put("sender", message.person?.name?.toString() ?: "Невідомий відправник")
                .put("content", message.text?.toString().orEmpty()))
        }
        body.put("conversation", conversation)
        val group = style.conversationTitle
        if (style.isGroupConversation && group != null) {
            val last = conversation.getJSONObject(conversation.length() - 1)
            body.put("groupName", group.toString())
            body.put("title", group.toString())
            body.put("text", last.getString("sender") + ": " + last.getString("content"))
        }
    }

    /** PNG icon (large icon, else small icon) and its MD5, as NotificationsPlugin.extractIcon. */
    private fun extractIcon(sbn: StatusBarNotification): Pair<ByteArray, String>? {
        val notification = sbn.notification
        val foreign = createPackageContext(sbn.packageName, 0)
        val drawable = (notification.getLargeIcon() ?: notification.smallIcon)?.loadDrawable(foreign) ?: return null
        val size = if (drawable.intrinsicWidth in 65..128 && drawable.intrinsicHeight in 65..128)
            drawable.intrinsicWidth to drawable.intrinsicHeight else 96 to 96
        val bitmap = android.graphics.Bitmap.createBitmap(size.first, size.second, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size.first, size.second)
        drawable.draw(canvas)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val hash = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return bytes to hash
    }
}
