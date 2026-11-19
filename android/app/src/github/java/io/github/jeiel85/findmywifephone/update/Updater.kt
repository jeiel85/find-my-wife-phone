package io.github.jeiel85.findmywifephone.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.ui.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    /** Handed to the system installer; on success the process is replaced. */
    data class Installing(val release: Release) : UpdateState
    /** Android wants the owner's tap (first self-update, or the system does not allow silent ones). */
    data class NeedsConfirm(val release: Release, val confirm: Intent) : UpdateState
    /** [transient] = worth retrying soon (network, GitHub rate limit or outage). */
    data class Failed(val reason: String, val transient: Boolean = false) : UpdateState
}

/**
 * Updates the sideloaded app from this repository's GitHub Releases (DECISIONS.md D-7).
 *
 * An APK is installed only when its SHA-256 matches the release and it is signed with the same
 * key as the installed app — Android enforces the signature too, so a hijacked release page
 * cannot push a foreign build. Debug builds never update themselves.
 */
object Updater {
    private const val TAG = "Updater"
    private const val LATEST = "https://api.github.com/repos/jeiel85/find-my-wife-phone/releases/latest"
    private const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val ACTION_STATUS = "fmwp.update.STATUS"

    val supported: Boolean get() = !BuildConfig.DEBUG

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val lock = Mutex()

    /** MainActivity keeps this current; a silent background install would kill a visible app. */
    @Volatile var appVisible = false

    private fun prefs(context: Context) = context.getSharedPreferences("fmwp-update", Context.MODE_PRIVATE)
    fun lastCheckedAt(context: Context): Long = prefs(context).getLong("checkedAt", 0)

    /** Ring or location request in progress: replacing the app now would cut it off. */
    private fun busy() = RingService.isActive || LocateService.isActive

    /**
     * Check, and when a newer verified build exists download and install it.
     * [userInitiated] = the owner pressed a button, so it may install while the app is open.
     */
    suspend fun run(context: Context, userInitiated: Boolean): UpdateState = withContext(Dispatchers.IO) {
        if (!supported) return@withContext UpdateState.Idle
        lock.withLock {
            val release = check(context) ?: return@withLock _state.value
            fun mustWait() = busy() || (!userInitiated && appVisible)
            if (mustWait()) return@withLock UpdateState.Available(release).also { _state.value = it }
            val apk = download(context, release) ?: return@withLock _state.value
            // The download can take a while; a ring may have started or the app come to the front.
            if (mustWait()) return@withLock UpdateState.Available(release).also { _state.value = it }
            install(context, apk, release)
            _state.value
        }
    }

    private fun check(context: Context): Release? {
        _state.value = UpdateState.Checking
        return try {
            val conn = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "find-my-wife-phone-android")
            }
            val code = conn.responseCode
            if (code == 404) { // no release published yet
                prefs(context).edit().putLong("checkedAt", System.currentTimeMillis()).apply()
                _state.value = UpdateState.UpToDate
                return null
            }
            if (code != 200) {
                // 403/429: unauthenticated API limit (60 an hour per IP); 5xx: GitHub is having trouble.
                _state.value = if (code == 403 || code == 429) UpdateState.Failed("GitHub 요청 한도에 걸렸어요. 잠시 뒤 다시 확인해요", transient = true)
                else UpdateState.Failed("GitHub 응답 $code", transient = code >= 500)
                return null
            }
            val release = Releases.parse(conn.inputStream.bufferedReader().use { it.readText() })
            prefs(context).edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            when {
                release == null -> { _state.value = UpdateState.Failed("확인할 수 없는 릴리스예요"); null }
                !Releases.isNewer(release.version, BuildConfig.VERSION_NAME) -> { _state.value = UpdateState.UpToDate; null }
                else -> release.also { _state.value = UpdateState.Available(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
            _state.value = UpdateState.Failed("인터넷 연결을 확인해 주세요", transient = true)
            null
        }
    }

    private fun download(context: Context, release: Release): File? {
        val dir = File(context.filesDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "${release.version}.apk")
        return try {
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "find-my-wife-phone-android")
            }
            if (conn.responseCode != 200) error("download HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > MAX_APK_BYTES) error("APK too large")
                        digest.update(buf, 0, n)
                        output.write(buf, 0, n)
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != shown) { shown = pct; _state.value = UpdateState.Downloading(release, pct) }
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != release.sha256) error("checksum mismatch")
            verifyArchive(context, out)
            out
        } catch (e: Exception) {
            Log.w(TAG, "download/verify failed", e)
            out.delete()
            _state.value = UpdateState.Failed(
                if (e.message?.startsWith("checksum") == true || e is SecurityException) "받은 파일을 검증하지 못해 설치하지 않았어요"
                else "내려받지 못했어요",
                transient = e is java.io.IOException)
            null
        }
    }

    /** Same package, higher version code, same signing key as the installed app. */
    private fun verifyArchive(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive: PackageInfo = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("not an APK")
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.packageName != context.packageName) throw SecurityException("other package")
        if (archive.longVersionCode <= installed.longVersionCode) throw SecurityException("not newer")
        val theirs = archive.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val mine = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (theirs.isNullOrEmpty() || theirs != mine) throw SecurityException("different signer")
    }

    private fun install(context: Context, apk: File, release: Release) {
        _state.value = UpdateState.Installing(release)
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Silent when Android allows it (we installed the previous version ourselves);
            // otherwise the system asks once and we surface that as a notification/button.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        try {
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                prefs(context).edit().putString("installing", release.version).apply()
                val status = PendingIntent.getBroadcast(context, 0,
                    Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS).putExtra("version", release.version),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "install session failed", e)
            _state.value = UpdateState.Failed("설치를 시작하지 못했어요")
        }
    }

    // ---- callbacks from UpdateReceiver ----------------------------------------------------

    internal fun onNeedsConfirm(context: Context, version: String, confirm: Intent) {
        val release = (state.value as? UpdateState.Installing)?.release?.takeIf { it.version == version }
        if (release != null) _state.value = UpdateState.NeedsConfirm(release, confirm)
        if (appVisible) {
            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Notifier.updateReady(context, version, confirm) }
        } else {
            Notifier.updateReady(context, version, confirm)
        }
    }

    internal fun onFailed(context: Context, status: Int, message: String?) {
        Log.w(TAG, "install failed: $status $message")
        prefs(context).edit().remove("installing").apply()
        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdateState.Idle
        else UpdateState.Failed("설치하지 못했어요")
    }

    /** First start after the package was replaced. */
    internal fun onReplaced(context: Context) {
        val p = prefs(context)
        val expected = p.getString("installing", null) ?: return
        p.edit().remove("installing").apply()
        File(context.filesDir, "update").listFiles()?.forEach { it.delete() }
        if (expected == BuildConfig.VERSION_NAME) Notifier.updated(context, BuildConfig.VERSION_NAME)
    }
}
