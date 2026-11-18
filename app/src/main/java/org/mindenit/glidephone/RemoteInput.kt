package org.mindenit.glidephone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Routes kdeconnect.mousepad.request and kdeconnect.shareinputdevices.request from a
 * computer to the phone, like MouseReceiverPlugin, RemoteKeyboardPlugin,
 * RemoteKeyboardIMEPlugin and InputDevicesReceiver together.
 */
internal object RemoteInput {
    private const val TOP = 0x8
    private const val LEFT = 0x4
    private const val RIGHT = 0x2
    private const val BOTTOM = 0x1
    private const val NONE = 0

    @Volatile private var enterEdge = NONE

    fun isKeyboardPacket(body: JSONObject) = body.has("key") || body.has("specialKey")

    /** "Only when editing" (default on): the IME types only while it is shown. */
    fun editingOnly(context: Context) = SetupPreferences(context).remoteKeyboardEditingOnly

    fun keyboardAvailable(context: Context): Boolean {
        val ime = GlideKeyboardService.instance
        return GlideAccessibilityService.instance != null || (ime != null && (ime.visible || !editingOnly(context)))
    }

    /** Re-announce kdeconnect.mousepad.keyboardstate to every connected device. */
    fun keyboardAvailabilityChanged(context: Context) {
        GlideHub.connectorIfCreated()?.broadcastKeyboardState(keyboardAvailable(context.applicationContext))
    }

    /** @return true if handled; [echo] receives the ack packet when one was requested. */
    fun handleKeyboard(context: Context, body: JSONObject, echo: (String) -> Unit): Boolean {
        val key = body.optString("key")
        val special = body.optInt("specialKey", 0)
        val shift = body.optBoolean("shift", false)
        val ctrl = body.optBoolean("ctrl", false)
        val ime = GlideKeyboardService.instance
        val handled = if (ime != null && (ime.visible || !editingOnly(context))) {
            ime.handleKey(key, special, shift, ctrl)
        } else {
            GlideAccessibilityService.instance?.handleKey(key, special, shift, ctrl) ?: false
        }
        if (handled && body.optBoolean("sendAck", false)) echo(ProtocolPackets.mousepadEcho(body))
        return handled
    }

    /** MouseReceiverPlugin.onPacketReceived plus InputDevicesReceiver's edge release. */
    fun handleMouse(body: JSONObject, release: (Int, Int) -> Unit): Boolean {
        val service = GlideAccessibilityService.instance ?: return false
        fun delta(name: String) = body.optDouble(name, 0.0).let { if (it < 0) floor(it) else ceil(it) }.toInt()
        val dx = delta("dx")
        val dy = delta("dy")
        val x = body.optInt("x", 0)
        val y = body.optInt("y", 0)
        when {
            body.optBoolean("singleclick") -> service.click()
            body.optBoolean("doubleclick") -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            body.optBoolean("middleclick") -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            body.optBoolean("rightclick") -> service.performGlobalAction(
                if (Build.VERSION.SDK_INT >= 36) AccessibilityService.GLOBAL_ACTION_MENU else AccessibilityService.GLOBAL_ACTION_BACK
            )
            body.optBoolean("forwardclick") -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            body.optBoolean("backclick") -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            body.optBoolean("singlehold") -> service.longClickSwipe()
            body.optBoolean("singlerelease") -> return service.stopSwipe()
            body.optBoolean("scroll") -> service.scroll(dy)
            dx != 0 || dy != 0 -> {
                service.move(dx, dy)
                checkEdgeRelease(service, release)
            }
            x != 0 || y != 0 -> service.setPos(x, y)
            !body.has("dx") && !body.has("dy") && !body.has("x") && !body.has("y") -> service.hide(0)
        }
        return true
    }

    /** kdeconnect.shareinputdevices.request: the computer's cursor entered our screen. */
    fun handleShareInputDevices(body: JSONObject, release: (Int, Int) -> Unit) {
        val service = GlideAccessibilityService.instance
        if (service == null) {
            release(body.optInt("deltax", 0), body.optInt("deltay", 0))
            return
        }
        enterEdge = body.optInt("exitEdge", NONE)
        val dx = body.optInt("deltax")
        val dy = body.optInt("deltay")
        android.util.Log.i("GlideLink", "the computer's cursor came in (edge $enterEdge at $dx,$dy)")
        when (enterEdge) {
            TOP -> service.setPos(dx, 1)
            LEFT -> service.setPos(1, dy)
            RIGHT -> service.setPos(service.width - 1, dy)
            BOTTOM -> service.setPos(dx, service.height - 1)
        }
    }

    private fun checkEdgeRelease(service: GlideAccessibilityService, release: (Int, Int) -> Unit) {
        val back = when (enterEdge) {
            TOP -> if (service.cursorY == 0) service.cursorX to 0 else null
            LEFT -> if (service.cursorX == 0) 0 to service.cursorY else null
            RIGHT -> if (service.cursorX == service.width) 0 to service.cursorY else null
            BOTTOM -> if (service.cursorY == service.height) service.cursorX to 0 else null
            else -> null
        } ?: return
        enterEdge = NONE
        android.util.Log.i("GlideLink", "giving the cursor back at ${back.first},${back.second}")
        release(back.first, back.second)
    }
}
