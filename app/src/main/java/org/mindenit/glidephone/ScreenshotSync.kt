package org.mindenit.glidephone

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Glide-only: every new screenshot goes straight to the connected computers, so a capture
 * on the phone is ready to paste or attach on the desktop.
 */
internal object ScreenshotSync {
    private var observer: ContentObserver? = null
    @Volatile private var newestSeen = 0L
    private val sent = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops watching to match the setting. */
    @Synchronized
    fun apply(context: Context) {
        val app = context.applicationContext
        val wanted = SetupPreferences(app).sendScreenshots && hasPermission(app)
        if (wanted && observer == null) {
            newestSeen = System.currentTimeMillis() / 1000 // only screenshots taken from now on
            val watcher = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) = Thread { check(app) }.start()
            }
            runCatching {
                app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, watcher)
                observer = watcher
            }
        } else if (!wanted) {
            observer?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
            observer = null
        }
    }

    /** Whether a MediaStore path names a screenshot folder. */
    fun isScreenshotPath(path: String?): Boolean =
        path != null && path.split('/').any { it.equals("Screenshots", ignoreCase = true) || it.equals("Знімки екрана", ignoreCase = true) }

    private fun check(context: Context) {
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, pathColumn)
        val found = mutableListOf<Pair<Long, Long>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(newestSeen.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && found.size < 5) {
                    if (isScreenshotPath(c.getString(2))) found += c.getLong(0) to c.getLong(1)
                }
            }
        }
        val fresh = found.filter { sent.add(it.first) }
        if (fresh.isEmpty()) return
        newestSeen = maxOf(newestSeen, fresh.maxOf { it.second })
        // Let the screenshot app finish writing the file.
        Thread.sleep(1500)
        val uris = fresh.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it.first) }
        val connector = GlideHub.connectorIfCreated() ?: return
        GlideHub.connectedDevices.forEach { device -> connector.sendFiles(uris, device.id) }
    }
}
