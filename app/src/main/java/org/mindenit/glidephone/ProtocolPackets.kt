package org.mindenit.glidephone

import org.json.JSONArray
import org.json.JSONObject

internal object ProtocolPackets {
    const val TYPE_PAIR = "kdeconnect.pair"
    const val TYPE_BATTERY = "kdeconnect.battery"
    const val TYPE_NOTIFICATION = "kdeconnect.notification"
    const val TYPE_NOTIFICATION_REPLY = "kdeconnect.notification.reply"
    const val TYPE_NOTIFICATION_ACTION = "kdeconnect.notification.action"
    const val TYPE_NOTIFICATION_REQUEST = "kdeconnect.notification.request"
    const val TYPE_FINDMYPHONE_REQUEST = "kdeconnect.findmyphone.request"
    const val TYPE_PING = "kdeconnect.ping"

    // ---- Glide-only extensions (other KDE Connect apps ignore them) ----

    /** The desktop's network card addresses, for Wake-on-LAN. */
    const val TYPE_GLIDE_WOL = "glide.wol"
    const val TYPE_CLIPBOARD = "kdeconnect.clipboard"
    const val TYPE_CLIPBOARD_CONNECT = "kdeconnect.clipboard.connect"
    const val TYPE_PRESENTER = "kdeconnect.presenter"
    const val TYPE_MOUSEPAD_REQUEST = "kdeconnect.mousepad.request"
    const val TYPE_MOUSEPAD_ECHO = "kdeconnect.mousepad.echo"
    const val TYPE_MOUSEPAD_KEYBOARDSTATE = "kdeconnect.mousepad.keyboardstate"
    const val TYPE_SHAREINPUTDEVICES = "kdeconnect.shareinputdevices"
    const val TYPE_SFTP = "kdeconnect.sftp"
    const val TYPE_SFTP_REQUEST = "kdeconnect.sftp.request"
    const val TYPE_SHAREINPUTDEVICES_REQUEST = "kdeconnect.shareinputdevices.request"
    const val TYPE_SHARE_REQUEST = "kdeconnect.share.request"
    const val TYPE_SHARE_REQUEST_UPDATE = "kdeconnect.share.request.update"
    const val TYPE_MPRIS = "kdeconnect.mpris"
    const val TYPE_MPRIS_REQUEST = "kdeconnect.mpris.request"
    const val TYPE_SYSTEMVOLUME = "kdeconnect.systemvolume"
    const val TYPE_SYSTEMVOLUME_REQUEST = "kdeconnect.systemvolume.request"
    const val TYPE_RUNCOMMAND = "kdeconnect.runcommand"
    const val TYPE_RUNCOMMAND_REQUEST = "kdeconnect.runcommand.request"
    const val TYPE_RUNCOMMAND_OUTPUT = "kdeconnect.runcommand.output"
    const val TYPE_CONNECTIVITY_REPORT = "kdeconnect.connectivity_report"
    const val TYPE_TELEPHONY = "kdeconnect.telephony"
    const val TYPE_TELEPHONY_REQUEST_MUTE = "kdeconnect.telephony.request_mute"

    // Presenter & mousepad special keys (matches glide-phone presenter.rs)
    const val KEY_PREV_SLIDE = 8    // Page Up -> 104
    const val KEY_NEXT_SLIDE = 9    // Page Down -> 109
    const val KEY_EXIT = 14         // Esc -> 1
    const val KEY_START = 25        // F5 -> 63

    fun presenterSpecialKey(key: Int): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("specialKey", key))
        .toString() + "\n"

    fun presenterKey(key: String, ctrl: Boolean = false): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("key", key).put("ctrl", ctrl))
        .toString() + "\n"

    fun presenterMove(dx: Float, dy: Float): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_PRESENTER)
        .put("body", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        .toString() + "\n"

    fun presenterStop(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_PRESENTER)
        .put("body", JSONObject().put("stop", true))
        .toString() + "\n"

    fun findMyPhone(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_FINDMYPHONE_REQUEST)
        .put("body", JSONObject())
        .toString() + "\n"

    fun clipboard(content: String, timestamp: Long = System.currentTimeMillis()): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_CLIPBOARD)
        .put("body", JSONObject().put("content", content).put("timestamp", timestamp))
        .toString() + "\n"

    fun clipboardConnect(content: String, timestamp: Long = System.currentTimeMillis()): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_CLIPBOARD_CONNECT)
        .put("body", JSONObject().put("content", content).put("timestamp", timestamp))
        .toString() + "\n"

    // ---- Remote input (mousepad) ----

    fun mouseMove(dx: Float, dy: Float): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        .toString() + "\n"

    fun mouseClick(button: String): String {
        val body = JSONObject()
        when (button) {
            "right" -> body.put("rightclick", true)
            "middle" -> body.put("middleclick", true)
            "double" -> body.put("doubleclick", true)
            "singlehold" -> body.put("singlehold", true)
            "singlerelease" -> body.put("singlerelease", true)
            else -> body.put("singleclick", true)
        }
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_MOUSEPAD_REQUEST).put("body", body).toString() + "\n"
    }

    fun mouseScroll(dx: Float, dy: Float): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("scroll", true).put("dx", dx.toDouble()).put("dy", dy.toDouble()))
        .toString() + "\n"

    fun mouseType(text: String): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("key", text).put("sendAck", false))
        .toString() + "\n"

    // ---- MPRIS / media ----

    fun mprisRequest(body: JSONObject): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MPRIS_REQUEST)
        .put("body", body)
        .toString() + "\n"

    fun mprisList(players: List<String>): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MPRIS)
        .put("body", JSONObject().put("playerList", JSONArray(players)).put("supportAlbumArtPayload", true))
        .toString() + "\n"

    fun mprisState(
        player: String,
        title: String,
        artist: String,
        album: String,
        isPlaying: Boolean,
        canPlay: Boolean = true,
        canPause: Boolean = true,
        canGoNext: Boolean = true,
        canGoPrevious: Boolean = true,
        volume: Int = 100,
        length: Long = 0,
        pos: Long = 0,
        canSeek: Boolean = length > 0,
        albumArtUrl: String = ""
    ): String {
        val body = JSONObject()
            .put("player", player)
            .put("title", title)
            .put("artist", artist)
            .put("nowPlaying", title) // older GSConnect only reads this field
            .put("album", album)
            .put("albumArtUrl", albumArtUrl)
            .put("isPlaying", isPlaying)
            .put("canPlay", canPlay)
            .put("canPause", canPause)
            .put("canGoNext", canGoNext)
            .put("canGoPrevious", canGoPrevious)
            .put("canSeek", canSeek)
            .put("volume", volume)
            .put("length", length)
            .put("pos", pos)
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_MPRIS).put("body", body).toString() + "\n"
    }

    /** Album art reply: the JPEG itself travels as the packet's payload. */
    fun mprisAlbumArt(player: String, url: String): JSONObject = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MPRIS)
        .put("body", JSONObject().put("player", player).put("transferringAlbumArt", true).put("albumArtUrl", url))

    // ---- System volume ----

    fun systemVolumeRequest(body: JSONObject): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SYSTEMVOLUME_REQUEST)
        .put("body", body)
        .toString() + "\n"


    // ---- Run command ----

    fun runCommandRequest(key: String): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_RUNCOMMAND_REQUEST)
        .put("body", JSONObject().put("key", key))
        .toString() + "\n"

    fun runCommandSetup(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_RUNCOMMAND_REQUEST)
        .put("body", JSONObject().put("setup", true))
        .toString() + "\n"

    fun runCommandStop(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_RUNCOMMAND_REQUEST)
        .put("body", JSONObject().put("stop", true))
        .toString() + "\n"

    fun runCommandRequestList(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_RUNCOMMAND_REQUEST)
        .put("body", JSONObject().put("requestCommandList", true))
        .toString() + "\n"

    // ---- Connectivity report ----

    fun connectivityReport(signals: JSONObject): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_CONNECTIVITY_REPORT)
        .put("body", JSONObject().put("signalStrengths", signals))
        .toString() + "\n"

    // ---- Telephony ----

    fun telephony(event: String, name: String, number: String, isCancel: Boolean = false, thumbnail: String? = null): String {
        val body = JSONObject().put("event", event)
        if (name.isNotBlank()) body.put("contactName", name)
        if (!thumbnail.isNullOrEmpty()) body.put("phoneThumbnail", thumbnail)
        if (number.isNotBlank()) body.put("phoneNumber", number)
        if (isCancel) body.put("isCancel", true)
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_TELEPHONY).put("body", body).toString() + "\n"
    }

    // ---- Contacts (vCard sync) ----

    const val TYPE_CONTACTS_REQUEST_UIDS = "kdeconnect.contacts.request_all_uids_timestamps"
    const val TYPE_CONTACTS_REQUEST_VCARDS = "kdeconnect.contacts.request_vcards_by_uid"
    const val TYPE_CONTACTS_RESPONSE_UIDS = "kdeconnect.contacts.response_uids_timestamps"
    const val TYPE_CONTACTS_RESPONSE_VCARDS = "kdeconnect.contacts.response_vcards"

    fun contactsUids(uidToTimestamp: Map<String, Long>): String {
        val body = JSONObject()
        val uids = JSONArray()
        uidToTimestamp.forEach { (uid, ts) -> uids.put(uid); body.put(uid, ts) }
        body.put("uids", uids)
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_CONTACTS_RESPONSE_UIDS).put("body", body).toString() + "\n"
    }

    fun contactsVcards(uidToVcard: Map<String, String>): String {
        val body = JSONObject()
        val uids = JSONArray()
        uidToVcard.forEach { (uid, vcard) -> uids.put(uid); body.put(uid, vcard) }
        body.put("uids", uids)
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_CONTACTS_RESPONSE_VCARDS).put("body", body).toString() + "\n"
    }

    // ---- SMS ----

    const val TYPE_SMS_REQUEST = "kdeconnect.sms.request"
    const val TYPE_SMS_REQUEST_CONVERSATIONS = "kdeconnect.sms.request_conversations"
    const val TYPE_SMS_REQUEST_CONVERSATION = "kdeconnect.sms.request_conversation"
    const val TYPE_SMS_MESSAGES = "kdeconnect.sms.messages"

    fun smsMessages(messages: List<JSONObject>): String = smsMessages(JSONArray().apply { messages.forEach { put(it) } })

    fun smsMessages(messages: JSONArray): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SMS_MESSAGES)
        .put("body", JSONObject().put("messages", messages).put("version", 2))
        .toString() + "\n"

    // ---- File share (payload channel) ----

    fun shareFile(
        filename: String,
        size: Long,
        port: Int,
        numberOfFiles: Int = 1,
        totalPayloadSize: Long = size,
        lastModified: Long? = null,
        open: Boolean = false
    ): String {
        val body = JSONObject()
            .put("filename", filename)
            .put("numberOfFiles", numberOfFiles)
            .put("totalPayloadSize", totalPayloadSize)
        if (lastModified != null && lastModified > 0) body.put("lastModified", lastModified)
        if (open) body.put("open", true)
        return JSONObject()
            .put("id", System.currentTimeMillis())
            .put("type", TYPE_SHARE_REQUEST)
            .put("body", body)
            .put("payloadSize", size)
            .put("payloadTransferInfo", JSONObject().put("port", port))
            .toString() + "\n"
    }

    fun shareUpdate(numberOfFiles: Int, totalPayloadSize: Long): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SHARE_REQUEST_UPDATE)
        .put("body", JSONObject().put("numberOfFiles", numberOfFiles).put("totalPayloadSize", totalPayloadSize))
        .toString() + "\n"

    // Keys under which TrustedConnection exposes top-level payload metadata to handlers.
    const val KEY_PAYLOAD_SIZE = "_payloadSize"
    const val KEY_PAYLOAD_PORT = "_payloadPort"

    // ---- Digitizer (drawing tablet) ----

    const val TYPE_DIGITIZER = "kdeconnect.digitizer"
    const val TYPE_DIGITIZER_SESSION = "kdeconnect.digitizer.session"

    fun digitizerStart(width: Int, height: Int, resolutionX: Int, resolutionY: Int): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_DIGITIZER_SESSION)
        .put("body", JSONObject().put("action", "start").put("width", width).put("height", height)
            .put("resolutionX", resolutionX).put("resolutionY", resolutionY))
        .toString() + "\n"

    fun digitizerEnd(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_DIGITIZER_SESSION)
        .put("body", JSONObject().put("action", "end"))
        .toString() + "\n"

    /** DigitizerPlugin.reportEvent: only the fields that are set; tool is "Pen" or "Rubber". */
    fun digitizerEvent(active: Boolean?, touching: Boolean?, x: Int?, y: Int?, pressure: Double?, rubber: Boolean? = false): String {
        val body = JSONObject()
        active?.let { body.put("active", it) }
        touching?.let { body.put("touching", it) }
        rubber?.let { body.put("tool", if (it) "Rubber" else "Pen") }
        x?.let { body.put("x", it) }
        y?.let { body.put("y", it) }
        pressure?.let { body.put("pressure", it) }
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_DIGITIZER).put("body", body).toString() + "\n"
    }

    fun sftp(body: JSONObject): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SFTP)
        .put("body", body)
        .toString() + "\n"

    /** Ack for a key the computer sent with sendAck (RemoteKeyboardPlugin.sendAck). */
    fun mousepadEcho(request: JSONObject): String {
        val body = JSONObject().put("key", request.optString("key")).put("isAck", true)
        listOf("specialKey", "shift", "ctrl", "alt", "super").forEach { key -> if (request.has(key)) body.put(key, request.get(key)) }
        return JSONObject().put("id", System.currentTimeMillis())
            .put("type", TYPE_MOUSEPAD_ECHO).put("body", body).toString() + "\n"
    }

    fun keyboardState(state: Boolean): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_KEYBOARDSTATE)
        .put("body", JSONObject().put("state", state))
        .toString() + "\n"

    /** InputDevicesReceiver.release: hand the cursor back to the computer. */
    fun shareInputDevicesRelease(dx: Int, dy: Int): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SHAREINPUTDEVICES)
        .put("body", JSONObject().put("releaseDeltax", dx).put("releaseDeltay", dy))
        .toString() + "\n"

    // Mousepad special keys (KDE Connect SpecialKeysMap)
    const val KEY_BACKSPACE = 1
    const val KEY_TAB = 2
    const val KEY_ENTER = 12
    const val KEY_LEFT = 4
    const val KEY_UP = 5
    const val KEY_RIGHT = 6
    const val KEY_DOWN = 7
    const val KEY_ESCAPE = 14
    const val KEY_SUPER = 20

    /** MousePadPlugin.sendSuper: META_LEFT plus the "super" flag for desktops lacking the key. */
    fun superKey(): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_MOUSEPAD_REQUEST)
        .put("body", JSONObject().put("specialKey", KEY_SUPER).put("super", true))
        .toString() + "\n"

    fun shareText(text: String): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SHARE_REQUEST)
        .put("body", JSONObject().put("text", text))
        .toString() + "\n"

    fun shareUrl(url: String): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_SHARE_REQUEST)
        .put("body", JSONObject().put("url", url))
        .toString() + "\n"

    fun notification(
        id: String,
        appName: String,
        title: String,
        text: String,
        silent: Boolean = false,
        actions: List<String> = emptyList(),
        requestReplyId: String? = null,
        isClearable: Boolean = true,
        time: Long = System.currentTimeMillis()
    ): String {
        val body = JSONObject()
            .put("id", id)
            .put("appName", appName)
            .put("title", title)
            .put("text", text)
            .put("ticker", if (title.isNotEmpty() && text.isNotEmpty()) "$title: $text" else title + text)
            .put("isCancel", false)
            .put("isClearable", isClearable)
            .put("time", time.toString())
            .put("silent", silent)
        if (actions.isNotEmpty()) {
            val actArr = JSONArray()
            actions.forEach { actArr.put(it) }
            body.put("actions", actArr)
        }
        if (!requestReplyId.isNullOrBlank()) {
            body.put("requestReplyId", requestReplyId)
        }
        return JSONObject()
            .put("id", System.currentTimeMillis())
            .put("type", TYPE_NOTIFICATION)
            .put("body", body)
            .toString() + "\n"
    }

    fun notificationCancel(id: String): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_NOTIFICATION)
        .put("body", JSONObject().put("id", id).put("isCancel", true))
        .toString() + "\n"

    fun ping(message: String = "Ping!"): String = JSONObject()
        .put("id", System.currentTimeMillis())
        .put("type", TYPE_PING)
        .put("body", JSONObject().put("message", message))
        .toString() + "\n"
}
