package io.github.jeiel85.findmywifephone.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.ui.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class StoreUpdate(val version: String) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Play build: Google Play installs updates; the app only says a newer version exists
 * (DECISIONS.md D-10, D-11). The Play In-App Updates API reports whether a higher version is open
 * to this user on any track they can see (closed testing included); if so the settings row and a
 * notification send them to the app's Play Store page. Still no self-updater, install permission or
 * APK download in this build.
 */
object SelfUpdate {
    private const val TAG = "StoreUpdate"
    private const val RECHECK_MS = 6L * 3_600_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var lastCheckAt = 0L
    @Volatile private var appContext: Context? = null

    private val backgroundCheck = AtomicBoolean(false)

    /** Play's update API; device tests put a FakeAppUpdateManager here (src/androidTestPlay). */
    @Volatile internal var managerFor: (Context) -> AppUpdateManager = { AppUpdateManagerFactory.create(it) }

    fun onAppStart(context: Context) {
        appContext = context.applicationContext
        StoreUpdateWorker.schedule(context, Hub.store.settings.value.updateNotice)
        checkInBackground(context.applicationContext)
    }

    /** Re-check when the app comes back after six hours. */
    fun onAppVisible(visible: Boolean) {
        val ctx = appContext ?: return
        if (visible && System.currentTimeMillis() - lastCheckAt > RECHECK_MS) checkInBackground(ctx)
    }

    /**
     * Quiet checks honor the "새 버전 알림" switch (README and privacy policy say it can be turned
     * off); only the user's own "확인" tap asks Play when it is off. App start and the first onStart
     * arrive together, so one quiet check at a time is enough.
     */
    private fun checkInBackground(context: Context) {
        if (!Hub.store.settings.value.updateNotice) return
        if (!backgroundCheck.compareAndSet(false, true)) return
        scope.launch { try { check(context) } finally { backgroundCheck.set(false) } }
    }

    /**
     * Asks Play whether this user can get a higher version. Installs that did not come from Play
     * (a sideloaded play build, debug) can't be asked: quiet in the background, a reason when the
     * user tapped "확인".
     */
    suspend fun check(context: Context, userInitiated: Boolean = false): UpdateState {
        if (userInitiated) _state.value = UpdateState.Checking
        val next = runCatching {
            val info = managerFor(context.applicationContext).requestAppUpdateInfo()
            stateOf(info.updateAvailability(), info.availableVersionCode())
        }.getOrElse { e ->
            Log.i(TAG, "update check unavailable: ${e.message}")
            if (userInitiated) UpdateState.Failed(failureReason((e as? InstallException)?.errorCode)) else UpdateState.Idle
        }
        lastCheckAt = System.currentTimeMillis()
        _state.value = next
        return next
    }

    /** The app's page in the Play Store app, or the web page when there is no Play Store app. */
    fun openStore(context: Context) {
        try {
            context.startActivity(storeIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun storeIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).setPackage("com.android.vending")

    /** versionCode (major×10000 + minor×100 + patch, app/build.gradle.kts) back to "0.3.4". */
    fun versionName(code: Int): String = io.github.jeiel85.findmywifephone.core.versionName(code)

    /** What Play's answer means for the settings row. */
    fun stateOf(availability: Int, availableVersionCode: Int): UpdateState =
        if (availability == UpdateAvailability.UPDATE_AVAILABLE) UpdateState.StoreUpdate(versionName(availableVersionCode))
        else UpdateState.UpToDate

    /**
     * Play's InstallErrorCode for a failed check (null = not a Play error). Only "not installed from
     * Play" means it can never work here; the rest are worth another try later.
     */
    fun failureReason(errorCode: Int?): String = when (errorCode) {
        InstallErrorCode.ERROR_APP_NOT_OWNED, InstallErrorCode.ERROR_API_NOT_AVAILABLE ->
            "Play 스토어에서 받은 앱에서만 확인할 수 있어요"
        InstallErrorCode.ERROR_PLAY_STORE_NOT_FOUND -> "Play 스토어 앱이 없어 확인할 수 없어요"
        else -> "Play 스토어에 연결하지 못했어요. 잠시 뒤 다시 확인해 주세요"
    }
}

/** Twice a day: ask Play for a newer version and notify once per version (setting "새 버전 알림"). */
class StoreUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hub.init(applicationContext)
        if (!Hub.store.settings.value.updateNotice) return Result.success()
        val st = SelfUpdate.check(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (st is UpdateState.StoreUpdate && prefs.getString(KEY_NOTIFIED, null) != st.version) {
            Notifier.storeUpdate(applicationContext, st.version, SelfUpdate.storeIntent(applicationContext))
            prefs.edit().putString(KEY_NOTIFIED, st.version).apply()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "store-update"
        private const val PREFS = "store_update"
        private const val KEY_NOTIFIED = "notified"

        /** Idempotent; called on every start and when the setting changes. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<StoreUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
