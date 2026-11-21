package io.github.jeiel85.findmywifephone.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * "배터리가 부족하면 알리기": a dying phone can no longer be rung or located, so its peers hear about
 * it while there is still time — at [BATTERY_LOW_PCT] and once more at [BATTERY_CRITICAL_PCT], each
 * with the phone's last location when the owner allows it (Hub.lastLocationAllowed, D-13).
 * Android delivers no low-battery broadcast to a closed app, so this checks every 15 minutes
 * (WorkManager's shortest period; no network unless it has to tell). Each notice goes out once per
 * discharge: both are due again only after the phone charges or passes [REARM_PCT].
 */
object BatteryWatch {
    private const val WORK = "battery-watch"
    /** Hysteresis: hovering around the threshold must not notify again and again. */
    const val REARM_PCT = BATTERY_LOW_PCT + 10

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BatteryWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    enum class Action { NOTIFY_LOW, NOTIFY_CRITICAL, REARM, NONE }

    /**
     * Pure decision, unit-tested: what to do at [battery]% given which notices already went out.
     * A phone first seen below the critical level gets only the critical notice (it counts for both).
     */
    fun decide(enabled: Boolean, battery: Int, charging: Boolean, lowSent: Boolean, criticalSent: Boolean): Action = when {
        battery < 0 -> Action.NONE
        (lowSent || criticalSent) && (charging || battery >= REARM_PCT) -> Action.REARM
        !enabled || charging -> Action.NONE
        battery <= BATTERY_CRITICAL_PCT && !criticalSent -> Action.NOTIFY_CRITICAL
        battery <= BATTERY_LOW_PCT && !lowSent && !criticalSent -> Action.NOTIFY_LOW
        else -> Action.NONE
    }

    /**
     * Level (0..100, -1 unknown) and whether a charger is plugged in, from the sticky
     * ACTION_BATTERY_CHANGED broadcast — what the system itself shows and announces. (BatteryManager's
     * capacity property reads the hardware directly and can disagree, e.g. on emulators.)
     */
    fun read(context: Context): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        return pct to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }

    /** Runs one check; also called when the app starts, so a phone already low is not missed. */
    suspend fun check(context: Context) {
        Hub.init(context)
        val (battery, charging) = read(context)
        val store = Hub.store
        when (val a = decide(store.settings.value.batteryWarn, battery, charging, store.batteryLowSent, store.batteryCriticalSent)) {
            Action.REARM -> { store.batteryLowSent = false; store.batteryCriticalSent = false }
            Action.NONE -> Unit
            Action.NOTIFY_LOW, Action.NOTIFY_CRITICAL -> {
                // Marked only on success: a failed send (offline) is retried on the next check.
                val ok = Hub.sendBatteryLow()
                if (ok) {
                    store.batteryLowSent = true
                    if (a == Action.NOTIFY_CRITICAL) store.batteryCriticalSent = true
                }
                Log.i("BatteryWatch", "battery $battery% ($a): notice ${if (ok) "sent" else "not sent, retry later"}")
            }
        }
    }
}

class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BatteryWatch.check(applicationContext)
        return Result.success()
    }
}
