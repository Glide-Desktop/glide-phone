package org.mindenit.glidephone

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * Find-my-phone alarm, after KDE Connect's FindMyPhonePlugin: loops the chosen ringtone
 * on the alarm stream at full volume (restoring the previous volume afterwards) and can
 * blink the flashlight.
 */
internal object AlarmPlayer {
    private var player: MediaPlayer? = null
    private var previousVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private var flashCamera: String? = null
    private var flashOn = false
    private var flashing = false
    private var appContext: Context? = null

    private val flashTick = object : Runnable {
        override fun run() {
            if (!flashing) return
            flashOn = !flashOn
            setTorch(flashOn)
            handler.postDelayed(this, 500)
        }
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (player?.isPlaying == true) return
        stopSound()
        val prefs = SetupPreferences(app)
        val uri = prefs.findMyPhoneRingtone?.let(Uri::parse) ?: Settings.System.DEFAULT_RINGTONE_URI
        val audio = app.getSystemService(AudioManager::class.java)
        try {
            val mp = MediaPlayer().apply {
                setDataSource(app, uri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build())
                @Suppress("DEPRECATION")
                setWakeMode(app, PowerManager.SCREEN_DIM_WAKE_LOCK)
                isLooping = true
                prepare()
            }
            if (audio != null) {
                previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
            mp.start()
            player = mp
        } catch (_: Exception) {
            restoreVolume(app)
        }
        if (prefs.findMyPhoneFlashlight) startFlashing(app)
    }

    @Synchronized
    fun stop() {
        stopSound()
        appContext?.let(::restoreVolume)
        stopFlashing()
    }

    val isPlaying: Boolean
        @Synchronized get() = player?.isPlaying == true

    private fun stopSound() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private fun restoreVolume(context: Context) {
        if (previousVolume < 0) return
        runCatching { context.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
        previousVolume = -1
    }

    private fun startFlashing(context: Context) {
        val cameras = context.getSystemService(CameraManager::class.java) ?: return
        flashCamera = runCatching {
            cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull() ?: return
        if (flashing) return
        flashing = true
        handler.post(flashTick)
    }

    private fun stopFlashing() {
        if (!flashing) return
        flashing = false
        handler.removeCallbacks(flashTick)
        flashOn = false
        setTorch(false)
    }

    private fun setTorch(on: Boolean) {
        val id = flashCamera ?: return
        runCatching { appContext?.getSystemService(CameraManager::class.java)?.setTorchMode(id, on) }
    }
}
