package io.github.jeiel85.findmywifephone.ring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.jeiel85.findmywifephone.R
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.data.Direction
import io.github.jeiel85.findmywifephone.nearby.BeaconAdvertiser
import io.github.jeiel85.findmywifephone.ui.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the alert screen shows while a ring request is in progress. */
data class RingUi(val reqId: String, val peerName: String, val ringing: Boolean, val secondsLeft: Int, val test: Boolean = false)

/**
 * Receiver side of "찾아줘": [COUNTDOWN_SEC] seconds in which the owner can decline, then the
 * phone rings on the alarm stream for the length chosen in settings or until "찾았어요".
 * [test] rings for [TEST_SEC] seconds without a countdown or a requester (설정 → 내 폰 울려 보기).
 */
class RingService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var peerId = ""
    private var player: MediaPlayer? = null
    private var maxVolume = 0
    private var ticks = 0
    private var flash: Flash? = null
    private var beacon: BeaconAdvertiser? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_TEST -> beginTest()
            ACTION_DECLINE -> finish(RingState.DECLINED)
            ACTION_FOUND -> finish(RingState.FOUND)
            else -> if (_ui.value == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        val reqId = intent.getStringExtra(EXTRA_REQ) ?: return stopSelf()
        val from = intent.getStringExtra(EXTRA_PEER) ?: return stopSelf()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (_ui.value != null) {
            // Already handling one; the second requester learns it is busy.
            Hub.reply(from, Msg.RingUpdate(reqId, RingState.BUSY, Hub.myStatus()))
            Hub.history.update(reqId, Direction.IN, RingState.BUSY.wire)
            return
        }
        peerId = from
        val ui = RingUi(reqId, name, ringing = false, secondsLeft = COUNTDOWN_SEC)
        _ui.value = ui
        val notification = Notifier.ringCountdown(this, ui)
        try {
            ServiceCompat.startForeground(this, Notifier.ID_RING, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            // Background start refused (e.g. FCM delivered as normal priority): ring is impossible.
            Log.e(TAG, "cannot start foreground", e)
            _ui.value = null
            Hub.reply(from, Msg.RingUpdate(reqId, RingState.ERROR, Hub.myStatus()))
            Hub.history.update(reqId, Direction.IN, RingState.ERROR.wire)
            stopSelf()
            return
        }
        Hub.reply(from, Msg.RingUpdate(reqId, RingState.COUNTDOWN, Hub.myStatus()))
        Hub.history.update(reqId, Direction.IN, RingState.COUNTDOWN.wire)
        main.postDelayed(::countdownTick, 1000)
    }

    private fun beginTest() {
        if (_ui.value != null) return
        peerId = ""
        val ui = RingUi(TEST_REQ, "", ringing = true, secondsLeft = TEST_SEC, test = true)
        _ui.value = ui
        try {
            ServiceCompat.startForeground(this, Notifier.ID_RING, Notifier.ringing(this, ui),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        } catch (e: Exception) {
            Log.e(TAG, "cannot start foreground for test", e)
            _ui.value = null
            stopSelf()
            return
        }
        _ui.value = ui.copy(ringing = false)
        startRinging()
    }

    private fun countdownTick() {
        val ui = _ui.value ?: return
        if (ui.ringing) return
        val left = ui.secondsLeft - 1
        if (left <= 0) {
            startRinging()
        } else {
            _ui.value = ui.copy(secondsLeft = left)
            Notifier.update(this, Notifier.ID_RING, Notifier.ringCountdown(this, _ui.value!!))
            main.postDelayed(::countdownTick, 1000)
        }
    }

    private fun startRinging() {
        val ui = _ui.value ?: return
        val settings = Hub.store.settings.value
        _ui.value = ui.copy(ringing = true, secondsLeft = if (ui.test) TEST_SEC else settings.ringSeconds)
        maxVolume = SoundState.raise(this)
        SoundState.setAlarmVolume(this, (maxVolume * 0.6f).toInt().coerceAtLeast(1))
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                val afd = resources.openRawResourceFd(R.raw.findme)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
        }
        if (settings.ringVibrate) vibrate()
        if (settings.ringFlash) flash = Flash(this).also { it.start() }
        if (!ui.test) {
            // Lets the requester's phone show how close this one is (nearby gauge).
            beaconState = Hub.store.peer(peerId)?.let { p -> BeaconAdvertiser(this, p.pairKey).also { beacon = it }.start() } ?: false
        }
        Notifier.update(this, Notifier.ID_RING, Notifier.ringing(this, _ui.value!!))
        if (!ui.test) {
            Hub.reply(peerId, Msg.RingUpdate(ui.reqId, RingState.RINGING, Hub.myStatus()))
            Hub.history.update(ui.reqId, Direction.IN, RingState.RINGING.wire)
        }
        ticks = 0
        main.postDelayed(::ringTick, 1000)
    }

    private fun ringTick() {
        val ui = _ui.value ?: return
        ticks++
        // Gentle start, then full volume after a few seconds.
        if (ticks <= 4) SoundState.setAlarmVolume(this, (maxVolume * (0.6f + 0.1f * ticks)).toInt().coerceAtMost(maxVolume))
        val left = ui.secondsLeft - 1
        if (left <= 0) return finish(RingState.TIMEOUT)
        _ui.value = ui.copy(secondsLeft = left)
        main.postDelayed(::ringTick, 1000)
    }

    private fun vibrate() {
        val vm = getSystemService(VibratorManager::class.java) ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vm.defaultVibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vm.defaultVibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun finish(state: RingState) {
        val ui = _ui.value ?: return stopSelf()
        main.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
        flash?.stop()
        flash = null
        beacon?.stop()
        beacon = null
        beaconState = null
        SoundState.restore(this)
        _ui.value = null
        if (!ui.test) {
            if (state != RingState.STOPPED) Hub.reply(peerId, Msg.RingUpdate(ui.reqId, state, Hub.myStatus()))
            Hub.history.update(ui.reqId, Direction.IN, state.wire)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (_ui.value != null) finish(RingState.ERROR)
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RingService"
        const val COUNTDOWN_SEC = 10
        const val TEST_SEC = 5
        private const val TEST_REQ = "local-test"
        const val ACTION_START = "fmwp.ring.START"
        const val ACTION_TEST = "fmwp.ring.TEST"
        const val ACTION_DECLINE = "fmwp.ring.DECLINE"
        const val ACTION_FOUND = "fmwp.ring.FOUND"
        private const val EXTRA_REQ = "req"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_NAME = "name"

        private val _ui = MutableStateFlow<RingUi?>(null)
        val ui: StateFlow<RingUi?> = _ui.asStateFlow()
        private var instance: RingService? = null
        val isActive get() = _ui.value != null

        /** Reported in [io.github.jeiel85.findmywifephone.core.PeerStatus.beacon] while ringing. */
        @Volatile var beaconState: Boolean? = null
            private set

        fun start(context: Context, peerId: String, peerName: String, reqId: String) {
            val intent = Intent(context, RingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_REQ, reqId).putExtra(EXTRA_PEER, peerId).putExtra(EXTRA_NAME, peerName)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService refused", e)
                Hub.reply(peerId, Msg.RingUpdate(reqId, RingState.ERROR, Hub.myStatus()))
                Hub.history.update(reqId, Direction.IN, RingState.ERROR.wire)
            }
        }

        /** 설정 → 내 폰 울려 보기: same path as a real ring (alarm stream, DND, restore), 5 seconds. */
        fun test(context: Context) {
            if (isActive) return
            runCatching { context.startForegroundService(Intent(context, RingService::class.java).setAction(ACTION_TEST)) }
                .onFailure { Log.e(TAG, "test ring refused", it) }
        }

        /** Requester pressed "그만 울리기". */
        fun cancel(context: Context, reqId: String) {
            Handler(Looper.getMainLooper()).post {
                val svc = instance ?: return@post
                if (_ui.value?.reqId == reqId) svc.finish(RingState.STOPPED)
            }
        }

        /** Debug builds only: shows the alert screen without a real request (no sound, no service). */
        fun debugPreview(ui: RingUi?) {
            if (io.github.jeiel85.findmywifephone.BuildConfig.DEBUG) _ui.value = ui
        }

        fun decline() = instance?.let { svc -> svc.main.post { svc.finish(RingState.DECLINED) } }
        fun found() = instance?.let { svc -> svc.main.post { svc.finish(RingState.FOUND) } }

        fun intent(context: Context, action: String) = Intent(context, RingService::class.java).setAction(action)
    }
}
