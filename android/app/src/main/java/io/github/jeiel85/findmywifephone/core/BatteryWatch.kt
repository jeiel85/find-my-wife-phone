package io.github.jeiel85.findmywifephone.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jeiel85.findmywifephone.data.Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * "배터리가 부족하면 알리기": a dying phone can no longer be rung or located, so its peers hear about
 * it while there is still time — at [BATTERY_LOW_PCT] and once more at [BATTERY_CRITICAL_PCT], each
 * with the phone's last location when the owner allows it (Hub.lastLocationAllowed, D-13).
 * Android delivers no low-battery broadcast to a closed app, so this checks every 15 minutes
 * (WorkManager's shortest period; no network unless it has to tell). Each notice goes out once per
 * discharge, to each peer once (a retry after a partial failure skips those who have it): both are
 * due again only after the phone charges or passes [REARM_PCT].
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

    /** A check this much later than the last one (same boot) means Doze held the 15-minute work back. */
    const val LATE_GAP_MS = 60 * 60_000L
    /** How long settings keeps showing a late check or a missed critical notice. */
    const val DIAGNOSTIC_KEEP_MS = 7 * 24 * 60 * 60_000L

    /**
     * Pure decision, unit-tested: what to do at [battery]% given which notices already went out.
     * A phone first seen below the critical level gets only the critical notice (it counts for both).
     * [partial]: some peers already have a notice that has not reached all of them yet; charging must
     * clear that too, or the next discharge would skip those peers.
     */
    fun decide(enabled: Boolean, battery: Int, charging: Boolean, lowSent: Boolean, criticalSent: Boolean, partial: Boolean = false): Action = when {
        battery < 0 -> Action.NONE
        (lowSent || criticalSent || partial) && (charging || battery >= REARM_PCT) -> Action.REARM
        !enabled || charging -> Action.NONE
        battery <= BATTERY_CRITICAL_PCT && !criticalSent -> Action.NOTIFY_CRITICAL
        battery <= BATTERY_LOW_PCT && !lowSent && !criticalSent -> Action.NOTIFY_LOW
        else -> Action.NONE
    }

    /** The notice is done once every current peer has it (a peer paired later is not owed this one). */
    fun complete(peerIds: Collection<String>, notified: Set<String>) = peerIds.isNotEmpty() && peerIds.all { it in notified }

    /** Time since the previous check, when both are in the same boot (elapsedRealtime restarts at boot). */
    fun gap(prev: Store.BatteryCheck?, bootCount: Int, elapsed: Long): Long? =
        prev?.takeIf { bootCount >= 0 && it.bootCount == bootCount && elapsed >= it.elapsed }?.let { elapsed - it.elapsed }

    /**
     * The phone went down (died, most likely) low and discharging, before its ≤ 5% notice: the
     * 15-minute checks came too late. A restart by hand at that level looks the same; rare enough.
     */
    fun missedCritical(prev: Store.BatteryCheck?, bootCount: Int, criticalSent: Boolean): Boolean =
        prev != null && bootCount >= 0 && prev.bootCount >= 0 && prev.bootCount != bootCount && !prev.charging &&
            prev.battery in 0..BATTERY_LOW_PCT && !criticalSent

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
    suspend fun check(context: Context): Unit = lock.withLock {
        Hub.init(context)
        val (battery, charging) = read(context)
        val store = Hub.store
        observe(context, store, battery, charging)
        val partial = store.batteryNotified(LOW).isNotEmpty() || store.batteryNotified(CRITICAL).isNotEmpty()
        when (val a = decide(store.settings.value.batteryWarn, battery, charging, store.batteryLowSent, store.batteryCriticalSent, partial)) {
            Action.REARM -> store.rearmBattery()
            Action.NONE -> Unit
            Action.NOTIFY_LOW, Action.NOTIFY_CRITICAL -> {
                val stage = if (a == Action.NOTIFY_CRITICAL) CRITICAL else LOW
                // Only peers still owed this notice; a failed send (offline) is retried on the next check.
                val before = store.batteryNotified(stage)
                val notified = before + Hub.sendBatteryLow("bat-${store.batteryRound()}-$stage", before)
                store.setBatteryNotified(stage, notified)
                val done = complete(store.peers.value.map { it.id }, notified)
                if (done) {
                    store.batteryLowSent = true
                    if (a == Action.NOTIFY_CRITICAL) store.batteryCriticalSent = true
                }
                Log.i(TAG, "battery $battery% ($a): ${notified.size}/${store.peers.value.size} peers told${if (done) "" else ", retry later"}")
            }
        }
    }

    /**
     * Doze can hold the 15-minute work back for hours, and then the 5% notice never goes out. Nothing
     * reports that from the field, so this phone keeps the evidence and settings shows it to the owner.
     */
    private fun observe(context: Context, store: Store, battery: Int, charging: Boolean) {
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        val elapsed = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val prev = store.lastBatteryCheck
        gap(prev, boot, elapsed)?.let { g ->
            val (longest, at) = store.batteryLongestGap
            if (g > longest || now - at > DIAGNOSTIC_KEEP_MS) store.batteryLongestGap = g to now
            if (g >= LATE_GAP_MS) Log.w(TAG, "battery check ${g / 60_000} min after the last one (Doze?)")
        }
        if (missedCritical(prev, boot, store.batteryCriticalSent)) {
            Log.w(TAG, "phone went down at ${prev?.battery}% before the critical notice")
            store.batteryMissedCriticalAt = now
        }
        store.lastBatteryCheck = Store.BatteryCheck(boot, elapsed, battery, charging)
    }

    private const val TAG = "BatteryWatch"
    private const val LOW = "low"
    private const val CRITICAL = "critical"
    /** The app-start check and the periodic one can overlap; one notice must not go out twice. */
    private val lock = Mutex()
}

class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        BatteryWatch.check(applicationContext)
        return Result.success()
    }
}
