package org.mindenit.glidephone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.controls.Control
import android.service.controls.ControlsProviderService
import android.service.controls.DeviceTypes
import android.service.controls.actions.CommandAction
import android.service.controls.actions.ControlAction
import android.service.controls.templates.StatelessTemplate
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Flow
import java.util.function.Consumer

/**
 * Remote commands remembered per device (RunCommandPlugin.KEY_COMMANDS_PREFERENCE), so
 * device controls and the widget still list them while the computer is away.
 */
internal object RunCommandStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("runcommand_cache", Context.MODE_PRIVATE)

    data class Device(val id: String, val name: String, val commands: List<PairingConnector.RemoteCommand>)

    fun save(context: Context, deviceId: String, deviceName: String, commands: List<PairingConnector.RemoteCommand>) {
        val array = JSONArray()
        commands.forEach { array.put(JSONObject().put("key", it.key).put("name", it.name).put("command", it.command)) }
        prefs(context).edit { putString(deviceId, JSONObject().put("name", deviceName).put("commands", array).toString()) }
        RunCommandWidget.refreshAll(context)
    }

    fun forget(context: Context, deviceId: String) {
        prefs(context).edit { remove(deviceId) }
        RunCommandWidget.refreshAll(context)
    }

    fun all(context: Context): List<Device> = prefs(context).all.mapNotNull { (id, raw) ->
        runCatching {
            val obj = JSONObject(raw as String)
            val array = obj.getJSONArray("commands")
            Device(id, obj.optString("name", id), (0 until array.length()).map { i ->
                val c = array.getJSONObject(i)
                PairingConnector.RemoteCommand(c.getString("key"), c.optString("name"), c.optString("command"))
            })
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    fun device(context: Context, id: String): Device? = all(context).firstOrNull { it.id == id }

    /** Run [key] on [deviceId] if it is connected; brings the app online first. */
    fun run(context: Context, deviceId: String, key: String): Boolean {
        GlideHub.goOnline(context)
        val connector = GlideHub.connector(context)
        if (GlideHub.connectedDevices.none { it.id == deviceId }) return false
        return connector.sendPacketTo(deviceId, ProtocolPackets.runCommandRequest(key))
    }
}

/** Device controls for remote commands (RunCommandControlsProviderService), Android 11+. */
@RequiresApi(Build.VERSION_CODES.R)
class RunCommandControlsService : ControlsProviderService() {
    private val subscribers = CopyOnWriteArrayList<Flow.Subscriber<in Control>>()

    private fun controlId(device: String, key: String) = "$device:$key"

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0x7263,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DETAIL, "COMMANDS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stateful(device: RunCommandStore.Device, command: PairingConnector.RemoteCommand): Control {
        val nameAsTitle = SetupPreferences(this).runCommandNameAsTitle
        val online = GlideHub.connectedDevices.any { it.id == device.id }
        return Control.StatefulBuilder(controlId(device.id, command.key), openApp())
            .setTitle(if (nameAsTitle) command.name else command.command)
            .setSubtitle(if (nameAsTitle) command.command else command.name)
            .setStructure(device.name)
            .setControlTemplate(StatelessTemplate(command.key))
            .setDeviceType(DeviceTypes.TYPE_ROUTINE)
            .setCustomIcon(Icon.createWithResource(this, android.R.drawable.ic_media_play))
            .setStatus(if (online) Control.STATUS_OK else Control.STATUS_DISABLED)
            .setStatusText(if (online) "Торкніться, щоб виконати" else "Комп’ютер недоступний")
            .build()
    }

    override fun createPublisherForAllAvailable(): Flow.Publisher<Control> {
        val controls = RunCommandStore.all(this).flatMap { device ->
            device.commands.map { command ->
                Control.StatelessBuilder(controlId(device.id, command.key), openApp())
                    .setTitle(command.name)
                    .setSubtitle(command.command)
                    .setStructure(device.name)
                    .setDeviceType(DeviceTypes.TYPE_ROUTINE)
                    .setCustomIcon(Icon.createWithResource(this, android.R.drawable.ic_media_play))
                    .build()
            }
        }
        return Flow.Publisher { subscriber ->
            subscriber.onSubscribe(object : Flow.Subscription {
                override fun request(n: Long) = Unit
                override fun cancel() = Unit
            })
            controls.forEach(subscriber::onNext)
            subscriber.onComplete()
        }
    }

    override fun createPublisherFor(controlIds: MutableList<String>): Flow.Publisher<Control> {
        GlideHub.goOnline(this)
        return Flow.Publisher { subscriber ->
            subscribers += subscriber
            subscriber.onSubscribe(object : Flow.Subscription {
                override fun request(n: Long) = Unit
                override fun cancel() { subscribers.remove(subscriber) }
            })
            controlIds.forEach { id -> subscriber.onNext(controlFor(id)) }
        }
    }

    private fun controlFor(id: String): Control {
        val device = RunCommandStore.device(this, id.substringBefore(':'))
        val command = device?.commands?.firstOrNull { it.key == id.substringAfter(':') }
        return if (device != null && command != null) stateful(device, command)
        else Control.StatefulBuilder(id, openApp()).setStatus(Control.STATUS_NOT_FOUND).build()
    }

    override fun performControlAction(controlId: String, action: ControlAction, consumer: Consumer<Int>) {
        if (action !is CommandAction) { consumer.accept(ControlAction.RESPONSE_FAIL); return }
        val ok = RunCommandStore.run(this, controlId.substringBefore(':'), controlId.substringAfter(':'))
        consumer.accept(if (ok) ControlAction.RESPONSE_OK else ControlAction.RESPONSE_FAIL)
        val updated = controlFor(controlId)
        subscribers.forEach { it.onNext(updated) }
    }
}
