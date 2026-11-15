package io.github.jeiel85.findmywifephone.locate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.data.Direction
import io.github.jeiel85.findmywifephone.ui.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocateUi(val reqId: String, val peerName: String, val ask: Boolean, val locating: Boolean, val secondsLeft: Int)

/**
 * Receiver side of "위치 보기". The owner is always told: with the AUTO policy the location is
 * shared after a countdown unless declined, with ASK only after "허용". Either way the result is
 * recorded in 기록 and announced with a notification that names who asked.
 */
class LocateService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var peerId = ""
    private var cts: CancellationTokenSource? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_DECLINE -> finish(LocateState.DECLINED)
            ACTION_ALLOW -> locateNow()
            else -> if (_ui.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val reqId = intent.getStringExtra(EXTRA_REQ) ?: return stopSelf()
        val from = intent.getStringExtra(EXTRA_PEER) ?: return stopSelf()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val ask = intent.getBooleanExtra(EXTRA_ASK, false)
        if (_ui.value != null) {
            Hub.reply(from, Msg.LocateUpdate(reqId, LocateState.ERROR, Hub.myStatus()))
            Hub.history.update(reqId, Direction.IN, "busy")
            return
        }
        peerId = from
        val ui = LocateUi(reqId, name, ask, locating = false, secondsLeft = if (ask) ASK_SEC else COUNTDOWN_SEC)
        _ui.value = ui
        val hasBackground = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val type = if (hasBackground) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        try {
            ServiceCompat.startForeground(this, Notifier.ID_LOCATE, Notifier.locateRequest(this, ui), type)
        } catch (e: Exception) {
            Log.e(TAG, "cannot start foreground", e)
            _ui.value = null
            Hub.reply(from, Msg.LocateUpdate(reqId, LocateState.UNAVAILABLE, Hub.myStatus()))
            Hub.history.update(reqId, Direction.IN, LocateState.UNAVAILABLE.wire)
            stopSelf()
            return
        }
        val state = if (ask) LocateState.ASKING else LocateState.COUNTDOWN
        Hub.reply(from, Msg.LocateUpdate(reqId, state, Hub.myStatus()))
        Hub.history.update(reqId, Direction.IN, state.wire)
        main.postDelayed(::tick, 1000)
    }

    private fun tick() {
        val ui = _ui.value ?: return
        if (ui.locating) return
        val left = ui.secondsLeft - 1
        if (left <= 0) {
            // ASK: silence is a "no". AUTO: silence means "go ahead" (the phone may be lost).
            if (ui.ask) finish(LocateState.DECLINED) else locateNow()
            return
        }
        _ui.value = ui.copy(secondsLeft = left)
        Notifier.update(this, Notifier.ID_LOCATE, Notifier.locateRequest(this, _ui.value!!))
        main.postDelayed(::tick, 1000)
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun locateNow() {
        val ui = _ui.value ?: return
        if (ui.locating) return
        main.removeCallbacksAndMessages(null)
        val granted = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val lm = getSystemService(LocationManager::class.java)
        if (!granted || lm?.isLocationEnabled != true) return finish(LocateState.UNAVAILABLE)
        _ui.value = ui.copy(locating = true)
        Notifier.update(this, Notifier.ID_LOCATE, Notifier.locateRequest(this, _ui.value!!))
        Hub.reply(peerId, Msg.LocateUpdate(ui.reqId, LocateState.LOCATING, Hub.myStatus()))
        val client = LocationServices.getFusedLocationProviderClient(this)
        val token = CancellationTokenSource().also { cts = it }
        main.postDelayed({ token.cancel() }, FIX_TIMEOUT_MS)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { loc ->
                if (loc != null) deliver(loc) else client.lastLocation
                    .addOnSuccessListener { last -> if (last != null) deliver(last) else finish(LocateState.UNAVAILABLE) }
                    .addOnFailureListener { finish(LocateState.UNAVAILABLE) }
            }
            .addOnFailureListener {
                Log.w(TAG, "location failed", it)
                finish(LocateState.UNAVAILABLE)
            }
    }

    private fun deliver(loc: Location) {
        val ui = _ui.value ?: return
        val msg = Msg.LocateResult(ui.reqId, loc.latitude, loc.longitude, loc.accuracy, loc.time, Hub.myStatus())
        Hub.reply(peerId, msg, ttlSec = 600)
        Hub.history.update(ui.reqId, Direction.IN, "shared", loc.latitude, loc.longitude)
        Notifier.locationShared(this, ui.peerName)
        end()
    }

    private fun finish(state: LocateState) {
        val ui = _ui.value ?: return stopSelf()
        Hub.reply(peerId, Msg.LocateUpdate(ui.reqId, state, Hub.myStatus()))
        Hub.history.update(ui.reqId, Direction.IN, state.wire)
        if (state == LocateState.UNAVAILABLE) Notifier.locateRefused(this, ui.peerName, state)
        end()
    }

    private fun end() {
        main.removeCallbacksAndMessages(null)
        cts?.cancel()
        cts = null
        _ui.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (_ui.value != null) finish(LocateState.ERROR)
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocateService"
        const val COUNTDOWN_SEC = 10
        const val ASK_SEC = 60
        private const val FIX_TIMEOUT_MS = 25_000L
        const val ACTION_START = "fmwp.locate.START"
        const val ACTION_DECLINE = "fmwp.locate.DECLINE"
        const val ACTION_ALLOW = "fmwp.locate.ALLOW"
        private const val EXTRA_REQ = "req"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ASK = "ask"

        private val _ui = MutableStateFlow<LocateUi?>(null)
        val ui: StateFlow<LocateUi?> = _ui.asStateFlow()
        private var instance: LocateService? = null

        fun start(context: Context, peerId: String, peerName: String, reqId: String, ask: Boolean) {
            val intent = Intent(context, LocateService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_REQ, reqId).putExtra(EXTRA_PEER, peerId).putExtra(EXTRA_NAME, peerName).putExtra(EXTRA_ASK, ask)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService refused", e)
                Hub.reply(peerId, Msg.LocateUpdate(reqId, LocateState.UNAVAILABLE, Hub.myStatus()))
                Hub.history.update(reqId, Direction.IN, LocateState.UNAVAILABLE.wire)
            }
        }

        /** Debug builds only: shows the alert screen without a real request. */
        fun debugPreview(ui: LocateUi?) {
            if (io.github.jeiel85.findmywifephone.BuildConfig.DEBUG) _ui.value = ui
        }

        fun decline() = instance?.let { s -> s.main.post { s.finish(LocateState.DECLINED) } }
        fun allow() = instance?.let { s -> s.main.post { s.locateNow() } }

        fun intent(context: Context, action: String) = Intent(context, LocateService::class.java).setAction(action)
    }
}
