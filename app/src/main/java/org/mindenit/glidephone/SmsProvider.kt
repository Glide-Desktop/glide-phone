package org.mindenit.glidephone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * kdeconnect.sms.* after KDE Connect's SMSHelper / SmsMmsUtils, for plain SMS (MMS is
 * deliberately not supported). Messages use packet version 2: addresses list, sub_id, event.
 */
internal object SmsProvider {
    private val conversationsUri: Uri = Uri.parse("content://mms-sms/conversations?simple=true")

    const val EVENT_TEXT_MESSAGE = 0x1
    const val EVENT_MULTI_TARGET = 0x2

    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasSendPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Uri to watch for new messages (SMSHelper.mConversationUri). */
    val observedUri: Uri get() = conversationsUri

    /**
     * SMSHelper.getConversations: newest message of every thread, newest thread first.
     * Returned lazily so each can go out as its own packet, as KDE Connect does.
     */
    fun conversations(context: Context): Sequence<JSONObject> {
        if (!hasReadPermission(context)) return emptySequence()
        val threads = mutableListOf<Pair<Long, Long>>()
        runCatching {
            context.contentResolver.query(conversationsUri, null, null, null, null)?.use { c ->
                val idColumn = c.getColumnIndex("_id")
                val dateColumn = c.getColumnIndex("date")
                while (c.moveToNext()) {
                    if (idColumn < 0 || c.isNull(idColumn)) continue
                    val date = if (dateColumn >= 0 && !c.isNull(dateColumn)) c.getLong(dateColumn) else -1
                    threads += c.getLong(idColumn) to date
                }
            }
        }
        val ordered = threads.sortedByDescending { it.second }.map { it.first }
        return sequence {
            for (thread in ordered) {
                val first = runCatching { messagesInRange(context, thread, Long.MAX_VALUE, 1, older = true) }.getOrNull()
                first?.firstOrNull()?.let { yield(it) }
            }
        }
    }

    /** SMSHelper.getMessagesInRange over SMS, newest first. */
    fun messagesInRange(context: Context, threadId: Long?, startTimestamp: Long, numberToGet: Long?, older: Boolean): List<JSONObject> {
        if (!hasReadPermission(context)) return emptyList()
        val columns = mutableListOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.THREAD_ID
        )
        if (subscriptionColumnSupported(context)) columns += Telephony.Sms.SUBSCRIPTION_ID
        var selection = if (older) "${Telephony.Sms.DATE} <= ?" else "${Telephony.Sms.DATE} >= ?"
        val args = mutableListOf(startTimestamp.toString())
        if (threadId != null) {
            selection += " AND ${Telephony.Sms.THREAD_ID} = ?"
            args += threadId.toString()
        }
        val out = mutableListOf<JSONObject>()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, columns.toTypedArray(), selection, args.toTypedArray(),
            "${Telephony.Sms.DATE} DESC")?.use { c ->
            while ((numberToGet == null || out.size < numberToGet) && c.moveToNext()) out += toMessage(c)
        }
        return out
    }

    fun newestTimestamp(context: Context): Long =
        runCatching { messagesInRange(context, null, Long.MAX_VALUE, 1, older = true).firstOrNull()?.optLong("date") }
            .getOrNull() ?: Long.MIN_VALUE

    private var subscriptionSupport: Boolean? = null

    /** SMSHelper.getSubscriptionIdSupport: some ROMs lack the sub_id column. */
    private fun subscriptionColumnSupported(context: Context): Boolean = subscriptionSupport ?: runCatching {
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.SUBSCRIPTION_ID), null, null, null)
            ?.use { true } ?: false
    }.getOrDefault(false).also { subscriptionSupport = it }

    private fun toMessage(c: android.database.Cursor): JSONObject {
        fun str(column: String) = c.getColumnIndex(column).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
        val address = str(Telephony.Sms.ADDRESS)
        return JSONObject()
            .put("addresses", JSONArray().apply { if (address != null) put(JSONObject().put("address", address)) })
            .put("body", str(Telephony.Sms.BODY).orEmpty())
            .put("date", str(Telephony.Sms.DATE)?.toLongOrNull() ?: 0L)
            .put("type", str(Telephony.Sms.TYPE)?.toIntOrNull() ?: 0)
            .put("read", str(Telephony.Sms.READ)?.toIntOrNull() ?: 0)
            .put("thread_id", str(Telephony.Sms.THREAD_ID)?.toLongOrNull() ?: -1L)
            .put("_id", str(Telephony.Sms._ID)?.toLongOrNull() ?: 0L)
            .put("sub_id", str(Telephony.Sms.SUBSCRIPTION_ID)?.toIntOrNull() ?: 0)
            .put("event", EVENT_TEXT_MESSAGE)
    }

    /** TelephonyHelper.getAllPhoneNumbers: (number, subscription id) of each SIM. */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun localNumbers(context: Context): List<Pair<String, Int>> = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        manager.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
            val number = if (android.os.Build.VERSION.SDK_INT >= 33) manager.getPhoneNumber(info.subscriptionId) else info.number
            number?.takeIf { it.isNotBlank() }?.let { it to info.subscriptionId }
        }
    }.getOrDefault(emptyList())

    /**
     * SmsMmsUtils.sendMessage without MMS: our own number is dropped from group recipients,
     * the chosen SIM ([subId], -1 = default) is used and each recipient gets the text.
     */
    fun send(context: Context, addresses: List<String>, body: String, subId: Int = -1): Boolean {
        if (!hasSendPermission(context) || body.isBlank()) return false
        var targets = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (targets.size > 1) {
            val own = localNumbers(context).firstOrNull { it.second == subId } ?: localNumbers(context).firstOrNull()
            if (own != null) targets = targets.filterNot { PhoneNumberUtils.compare(it, own.first) }
        }
        if (targets.isEmpty()) return false
        return runCatching {
            val sms = smsManager(context, subId)
            targets.forEach { address -> sms.sendMultipartTextMessage(address, null, sms.divideMessage(body), null, null) }
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, subId: Int): SmsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
        val base = context.getSystemService(SmsManager::class.java)
        if (subId < 0) base else base.createForSubscriptionId(subId)
    } else {
        if (subId < 0) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subId)
    }
}
