package org.mindenit.glidephone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.core.content.edit
import org.mindenit.glidephone.ui.theme.GlidePhoneTheme

/** Home-screen widget listing one computer's commands (RunCommandWidgetProvider). */
class RunCommandWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        prefs(context).edit { ids.forEach { remove(it.toString()) } }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_RUN) return
        val device = intent.getStringExtra(EXTRA_DEVICE) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val ok = RunCommandStore.run(context, device, key)
        if (!ok) Toast.makeText(context, "Комп’ютер недоступний", Toast.LENGTH_SHORT).show()
    }

    companion object {
        internal const val ACTION_RUN = "org.mindenit.glidephone.RUN_COMMAND"
        internal const val EXTRA_DEVICE = "device"
        internal const val EXTRA_KEY = "key"

        internal fun prefs(context: Context) = context.applicationContext.getSharedPreferences("runcommand_widgets", Context.MODE_PRIVATE)

        internal fun deviceFor(context: Context, widgetId: Int): String? = prefs(context).getString(widgetId.toString(), null)

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, RunCommandWidget::class.java)) }.getOrNull() ?: return
            ids.forEach { update(context, manager, it) }
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        internal fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val deviceId = deviceFor(context, widgetId)
            val device = deviceId?.let { RunCommandStore.device(context, it) }
            val views = RemoteViews(context.packageName, R.layout.widget_runcommand)
            views.setTextViewText(R.id.widget_title, device?.name ?: "Команди")
            val service = Intent(context, RunCommandWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra(EXTRA_DEVICE, deviceId)
            service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME)) // one factory per widget
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_list, service)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            val template = Intent(context, RunCommandWidget::class.java).setAction(ACTION_RUN)
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            views.setPendingIntentTemplate(R.id.widget_list,
                PendingIntent.getBroadcast(context, widgetId, template, PendingIntent.FLAG_UPDATE_CURRENT or mutable))
            // Tapping the title reconfigures (picks another computer).
            views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, widgetId,
                Intent(context, RunCommandWidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(widgetId, views)
        }
    }
}

/** RunCommandWidgetDataProvider: the rows of one widget. */
class RunCommandWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private val deviceId = intent.getStringExtra(RunCommandWidget.EXTRA_DEVICE)
        private var commands = emptyList<PairingConnector.RemoteCommand>()

        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() {
            commands = deviceId?.let { RunCommandStore.device(applicationContext, it)?.commands }.orEmpty()
        }
        override fun onDestroy() = Unit
        override fun getCount() = commands.size
        override fun getViewAt(position: Int): RemoteViews {
            val command = commands[position]
            val nameAsTitle = SetupPreferences(applicationContext).runCommandNameAsTitle
            return RemoteViews(packageName, R.layout.widget_runcommand_item).apply {
                setTextViewText(R.id.widget_item_title, if (nameAsTitle) command.name else command.command)
                setTextViewText(R.id.widget_item_subtitle, if (nameAsTitle) command.command else command.name)
                setOnClickFillInIntent(R.id.widget_item, Intent()
                    .putExtra(RunCommandWidget.EXTRA_DEVICE, deviceId)
                    .putExtra(RunCommandWidget.EXTRA_KEY, command.key))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = commands[position].key.hashCode().toLong()
        override fun hasStableIds() = true
    }
}

/** RunCommandWidgetConfigActivity: choose which computer the widget controls. */
class RunCommandWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val devices = RunCommandStore.all(this)
        setContent {
            GlidePhoneTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Комп’ютер для віджета") },
                    text = {
                        if (devices.isEmpty()) {
                            Text("Підключіть комп’ютер із налаштованими командами, щоб вибрати його тут.")
                        } else Column {
                            devices.forEach { device ->
                                ListItem(
                                    headlineContent = { Text(device.name) },
                                    supportingContent = { Text("Команд: ${device.commands.size}") },
                                    modifier = Modifier.clickable { choose(widgetId, device.id) }
                                )
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Скасувати") } }
                )
            }
        }
    }

    private fun choose(widgetId: Int, deviceId: String) {
        RunCommandWidget.prefs(this).edit { putString(widgetId.toString(), deviceId) }
        RunCommandWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
