package org.mindenit.glidephone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import org.json.JSONObject

/**
 * Glide only: actions the computer can run on the phone, the counterpart of the phone running
 * the computer's commands. Offered with KDE Connect's run command packets the other way round
 * (`kdeconnect.runcommand` with the list, `kdeconnect.runcommand.request` with a key).
 */
internal object PhoneActions {
    private class Action(val key: String, val name: String, val run: (Context) -> Boolean)

    private fun global(action: Int): (Context) -> Boolean = {
        GlideAccessibilityService.instance?.performGlobalAction(action) == true
    }

    private var torchOn = false

    private fun torch(context: Context, on: Boolean): Boolean = runCatching {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return false
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        cameras.setTorchMode(id, on)
        torchOn = on
        true
    }.getOrDefault(false)

    private fun ringer(context: Context, mode: Int): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.ringerMode = mode
        true
    }.getOrDefault(false)

    private val ACTIONS = buildList {
        if (Build.VERSION.SDK_INT >= 28) {
            add(Action("lock", "Заблокувати телефон", global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)))
            add(Action("screenshot", "Знімок екрана телефону", global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)))
        }
        add(Action("notifications", "Відкрити сповіщення", global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)))
        add(Action("torch", "Ліхтарик", { torch(it, !torchOn) }))
        add(Action("vibrate", "Режим вібрації", { ringer(it, AudioManager.RINGER_MODE_VIBRATE) }))
        add(Action("sound", "Увімкнути звук дзвінка", { ringer(it, AudioManager.RINGER_MODE_NORMAL) }))
    }

    /** `kdeconnect.runcommand` with the actions, `commandList` a JSON string as KDE Connect sends it. */
    fun listPacket(): String {
        val list = JSONObject()
        ACTIONS.forEach { list.put(it.key, JSONObject().put("name", it.name).put("command", it.key)) }
        return JSONObject()
            .put("id", System.currentTimeMillis())
            .put("type", ProtocolPackets.TYPE_RUNCOMMAND)
            .put("body", JSONObject().put("commandList", list.toString()).put("canAddCommand", false))
            .toString() + "\n"
    }

    /** The computer's `kdeconnect.runcommand.request`; returns a packet to send back, if any. */
    fun handle(context: Context, body: JSONObject): String? {
        if (body.optBoolean("requestCommandList", false)) return listPacket()
        val key = body.optString("key").takeIf { it.isNotEmpty() } ?: return null
        val action = ACTIONS.firstOrNull { it.key == key } ?: return null
        android.util.Log.i("GlideLink", "the computer ran the phone action $key: ${action.run(context.applicationContext)}")
        return null
    }
}
