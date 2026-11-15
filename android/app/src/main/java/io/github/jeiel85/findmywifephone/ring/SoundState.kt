package io.github.jeiel85.findmywifephone.ring

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.github.jeiel85.findmywifephone.core.Hub
import org.json.JSONObject

/**
 * Raises the alarm stream (which plays through silent/vibrate ringer modes) and, when the owner
 * granted "방해 금지 접근", lifts Do Not Disturb — then puts everything back exactly as it was.
 *
 * The saved state is persisted before anything changes, so a crash or reboot mid-ring is undone
 * on the next start ([restoreIfPending]).
 */
object SoundState {
    private const val TAG = "SoundState"

    fun raise(context: Context): Int {
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Hub.store.soundRestore() == null) {
            val saved = JSONObject()
                .put("alarmVolume", audio.getStreamVolume(AudioManager.STREAM_ALARM))
            if (nm.isNotificationPolicyAccessGranted) saved.put("filter", nm.currentInterruptionFilter)
            Hub.store.saveSoundRestore(saved)
        }
        if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                .onFailure { Log.w(TAG, "could not lift DND", it) }
        }
        return audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
    }

    fun setAlarmVolume(context: Context, level: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0) }
            .onFailure { Log.w(TAG, "could not set alarm volume", it) }
    }

    /** @return true when there was something to restore. */
    fun restore(context: Context): Boolean {
        val saved = Hub.store.soundRestore() ?: return false
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, saved.getInt("alarmVolume"), 0) }
            .onFailure { Log.w(TAG, "could not restore alarm volume", it) }
        if (saved.has("filter") && nm.isNotificationPolicyAccessGranted) {
            val filter = saved.getInt("filter")
            if (filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN && nm.currentInterruptionFilter != filter) {
                runCatching { nm.setInterruptionFilter(filter) }
                    .onFailure { Log.w(TAG, "could not restore DND", it) }
            }
        }
        Hub.store.saveSoundRestore(null)
        return true
    }

    fun restoreIfPending(context: Context) {
        if (RingService.isActive) return
        if (restore(context)) Log.i(TAG, "restored sound state left over from an interrupted ring")
    }
}
