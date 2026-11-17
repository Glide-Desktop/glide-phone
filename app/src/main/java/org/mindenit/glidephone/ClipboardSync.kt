package org.mindenit.glidephone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Android 10+ only lets the focused app (or the active keyboard) read the clipboard, so —
 * like KDE Connect's ClipboardFloatingActivity — this invisible activity takes focus for an
 * instant, reads the clipboard, sends it to the connected devices and closes.
 */
class ClipboardFloatingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            dimAmount = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // From the tile the process may be fresh: bring the connector up first.
        GlideHub.goOnline(this)
        val connector = GlideHub.connector(this)
        val sent = connector.pushClipboardNow()
        if (intent.getBooleanExtra(EXTRA_SHOW_TOAST, false)) {
            Toast.makeText(this, if (sent) "Буфер обміну надіслано" else "Немає підключених пристроїв", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val EXTRA_SHOW_TOAST = "showToast"

        fun intent(context: Context, showToast: Boolean): Intent =
            Intent(context.applicationContext, ClipboardFloatingActivity::class.java)
                .putExtra(EXTRA_SHOW_TOAST, showToast)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Below Android 10 the clipboard listener keeps working in the background. */
        fun canSyncAutomatically(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }
}

/** Quick-settings tile "Send clipboard" (ClipboardTileService). */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = if (GlideHub.connectedDevices.isNotEmpty()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = ClipboardFloatingActivity.intent(this, showToast = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/** "Send files" from the persistent notification (SendFileActivity). */
class SendFileActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val device = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (uris.isNotEmpty()) {
            val ok = GlideHub.connector(this).sendFiles(uris, device)
            Toast.makeText(this, if (ok) "Надсилання розпочато" else "Пристрій недоступний", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch("*/*")
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}
