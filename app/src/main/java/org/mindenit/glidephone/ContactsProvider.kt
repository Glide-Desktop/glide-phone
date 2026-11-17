package org.mindenit.glidephone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Answers contact sync requests from a connected computer (kdeconnect.contacts.*). */
internal object ContactsProvider {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** uid -> last-updated timestamp (ms). */
    fun uidTimestamps(context: Context): Map<String, Long> {
        if (!hasPermission(context)) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP),
                null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                val tsIdx = c.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
                while (c.moveToNext() && out.size < 2000) {
                    val id = c.getString(idIdx) ?: continue
                    val ts = if (tsIdx >= 0) c.getLong(tsIdx) else 0L
                    out[id] = ts
                }
            }
        }
        return out
    }

    fun vcards(context: Context, uids: List<String>, deviceId: String): Map<String, String> {
        if (!hasPermission(context)) return emptyMap()
        val timestamps = uidTimestamps(context)
        val out = LinkedHashMap<String, String>()
        uids.take(500).forEach { uid ->
            runCatching {
                val lookupKey = lookupKeyFor(context, uid) ?: return@forEach
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out[uid] = withMetadata(input.readBytes().toString(Charsets.UTF_8), deviceId, uid, timestamps[uid] ?: 0L)
                }
            }
        }
        return out
    }

    /** KDE Connect tags every vCard with the source device/uid and a REV timestamp. */
    internal fun withMetadata(vcard: String, deviceId: String, uid: String, timestamp: Long): String {
        val end = vcard.lastIndexOf("END:VCARD")
        if (end < 0) return vcard
        return vcard.substring(0, end) + "X-KDECONNECT-ID-DEV-$deviceId:$uid\r\nREV:$timestamp\r\n" + vcard.substring(end)
    }

    private fun lookupKeyFor(context: Context, contactId: String): String? {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId), null
        )?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    /** Base64 contact photo for telephony's phoneThumbnail (ContactsHelper.photoId64Encoded). */
    fun photoForNumber(context: Context, number: String): String? {
        if (number.isBlank() || !hasPermission(context)) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val photo = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return null
            context.contentResolver.openInputStream(Uri.parse(photo))?.use { input ->
                android.util.Base64.encodeToString(input.readBytes(), android.util.Base64.DEFAULT)
            }
        }.getOrNull()
    }

    fun nameForNumber(context: Context, number: String): String {
        if (number.isBlank() || !hasPermission(context)) return ""
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull().orEmpty()
    }
}
