package org.mindenit.glidephone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-pass SMS bridge (kdeconnect.sms.*): returns conversation summaries / threads and
 * can send a text. Full MMS/attachment sync is intentionally out of scope for now.
 */
internal object SmsProvider {
    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasSendPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Latest message of each conversation. */
    fun conversations(context: Context): JSONArray {
        val messages = JSONArray()
        if (!hasReadPermission(context)) return messages
        val seenThreads = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && messages.length() < 200) {
                    val thread = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)) ?: continue
                    if (!seenThreads.add(thread)) continue
                    messages.put(toMessage(c, thread))
                }
            }
        }
        return messages
    }

    /** All messages of one conversation (newest first). */
    fun conversation(context: Context, threadId: String): JSONArray {
        val messages = JSONArray()
        if (!hasReadPermission(context)) return messages
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId), "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && messages.length() < 500) {
                    messages.put(toMessage(c, threadId))
                }
            }
        }
        return messages
    }

    fun send(context: Context, address: String, body: String): Boolean {
        if (!hasSendPermission(context) || address.isBlank() || body.isBlank()) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java)
            else android.telephony.SmsManager.getDefault()
            sms.sendMultipartTextMessage(address, null, sms.divideMessage(body), null, null)
            true
        }.getOrDefault(false)
    }

    private fun toMessage(c: android.database.Cursor, thread: String): JSONObject {
        val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty()
        val addresses = JSONArray().put(JSONObject().put("address", address))
        return JSONObject()
            .put("_id", c.getLong(c.getColumnIndexOrThrow(Telephony.Sms._ID)))
            .put("thread_id", thread.toLongOrNull() ?: 0L)
            .put("body", c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty())
            .put("address", address)
            .put("addresses", addresses)
            .put("date", c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE)))
            .put("read", c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.READ)))
            .put("type", c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE)))
            .put("event", 1)
    }

    @Suppress("unused")
    private val inboxUri: Uri = Telephony.Sms.Inbox.CONTENT_URI
}
