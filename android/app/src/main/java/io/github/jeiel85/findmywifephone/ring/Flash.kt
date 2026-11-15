package io.github.jeiel85.findmywifephone.ring

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Keeps the camera flash on while ringing so light leaks out from under a cushion.
 *
 * It stays on instead of blinking: on Samsung phones every torch on/off re-posts the system's
 * flashlight notification, which then kept jumping above ours and made "찾았어요" hard to tap.
 * setTorchMode needs no camera permission; when another app holds the camera it just fails.
 */
class Flash(context: Context) {
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val id: String? = runCatching {
        cameras?.cameraIdList?.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private var on = false

    fun start() {
        val cam = id ?: return
        if (on) return
        runCatching { cameras?.setTorchMode(cam, true) }
            .onSuccess { on = true }
            .onFailure { Log.w(TAG, "torch unavailable", it) }
    }

    fun stop() {
        val cam = id ?: return
        if (!on) return
        runCatching { cameras?.setTorchMode(cam, false) }
        on = false
    }

    private companion object {
        const val TAG = "Flash"
    }
}
