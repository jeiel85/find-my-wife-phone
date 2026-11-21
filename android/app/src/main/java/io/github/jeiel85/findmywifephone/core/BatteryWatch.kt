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
 * it once while there is still time. Android delivers no low-battery broadcast to a closed app, so
 * this checks every 15 minutes (WorkManager's shortest period; no network unless it has to tell).
 * One notice per discharge: it is due again only after the phone charged past [REARM_PCT].
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

    enum class Action { NOTIFY, REARM, NONE }

    /** Pure decision, unit-tested: what to do at [battery]% given whether the notice already went out. */
    fun decide(enabled: Boolean, battery: Int, charging: Boolean, sent: Boolean): Action = when {
        battery < 0 -> Action.NONE
        sent && (charging || battery >= REARM_PCT) -> Action.REARM
        !enabled || sent || charging -> Action.NONE
        battery <= BATTERY_LOW_PCT -> Action.NOTIFY
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
    fun check(context: Context) {
        Hub.init(context)
        val (battery, charging) = read(context)
        val store = Hub.store
        when (decide(store.settings.value.batteryWarn, battery, charging, store.batteryLowSent)) {
            Action.REARM -> store.batteryLowSent = false
            Action.NONE -> Unit
            Action.NOTIFY -> {
                // Marked first only on success: a failed send (offline) is retried on the next check.
                val ok = Hub.sendBatteryLow()
                if (ok) store.batteryLowSent = true
                Log.i("BatteryWatch", "low battery $battery%: notice ${if (ok) "sent" else "not sent, retry later"}")
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
