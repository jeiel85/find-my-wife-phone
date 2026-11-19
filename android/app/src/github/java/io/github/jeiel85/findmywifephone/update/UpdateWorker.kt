package io.github.jeiel85.findmywifephone.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.ui.Notifier
import java.util.concurrent.TimeUnit

/** Twice a day, on any network: check GitHub Releases and install a newer signed build. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hub.init(applicationContext)
        if (!Updater.supported || !Hub.store.settings.value.autoUpdate) return Result.success()
        val st = Updater.run(applicationContext, userInitiated = false)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (st !is UpdateState.Failed) {
            prefs.edit().remove(KEY_FAILING_SINCE).remove(KEY_NOTIFIED).apply()
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_FAILING_SINCE, 0).takeIf { it > 0 } ?: now.also { prefs.edit().putLong(KEY_FAILING_SINCE, it).apply() }
        // Settings is the only other place a failure shows, so after three days of nothing but failures say so once.
        if (now - since >= NOTIFY_AFTER_MS && !prefs.getBoolean(KEY_NOTIFIED, false)) {
            Notifier.updateFailing(applicationContext, st.reason)
            prefs.edit().putBoolean(KEY_NOTIFIED, true).apply()
        }
        // Network trouble or GitHub's rate limit: try again with backoff instead of waiting 12 hours.
        return if (st.transient && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "auto-update"
        private const val PREFS = "fmwp-update-worker"
        private const val KEY_FAILING_SINCE = "failingSince"
        private const val KEY_NOTIFIED = "failNotified"
        private const val MAX_RETRIES = 3
        private const val NOTIFY_AFTER_MS = 3L * 86_400_000

        /** Idempotent; called on every start and when the setting changes. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!Updater.supported || !enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
