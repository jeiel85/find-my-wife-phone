package io.github.jeiel85.findmywifephone.ring

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.util.Log

/**
 * Blinks the camera flash while ringing. setTorchMode needs no camera permission; it simply
 * fails when another app holds the camera, and then we just don't blink.
 */
class Flash(context: Context, private val main: Handler) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val id: String? = runCatching {
        cameras?.cameraIdList?.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private var on = false
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            set(!on)
            main.postDelayed(this, BLINK_MS)
        }
    }

    fun start() {
        if (id == null || running) return
        running = true
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        if (on) set(false)
    }

    private fun set(value: Boolean) {
        val cam = id ?: return
        runCatching { cameras?.setTorchMode(cam, value) }
            .onSuccess { on = value }
            .onFailure { Log.w(TAG, "torch unavailable", it); running = false }
    }

    private companion object {
        const val TAG = "Flash"
        const val BLINK_MS = 450L
    }
}
