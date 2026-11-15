package io.github.jeiel85.findmywifephone.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jeiel85.findmywifephone.BuildConfig
import io.github.jeiel85.findmywifephone.crypto.Crypto
import io.github.jeiel85.findmywifephone.data.Direction
import io.github.jeiel85.findmywifephone.data.History
import io.github.jeiel85.findmywifephone.data.HistoryEntry
import io.github.jeiel85.findmywifephone.data.Identity
import io.github.jeiel85.findmywifephone.data.Kind
import io.github.jeiel85.findmywifephone.data.Peer
import io.github.jeiel85.findmywifephone.data.Store
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.net.Relay
import io.github.jeiel85.findmywifephone.net.RelayResult
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.ui.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

sealed class Registration {
    data object NotConfigured : Registration()   // built without google-services.json
    data object Working : Registration()
    data object Ready : Registration()
    data class Failed(val code: String) : Registration()
}

enum class RequestKind { RING, LOCATE }

/** What the requester's screen shows for the latest request to one peer. */
data class Outgoing(
    val kind: RequestKind,
    val reqId: String,
    val startedAt: Long,
    val sent: Boolean = false,
    val sendError: String? = null,
    val ring: RingState? = null,
    val locate: LocateState? = null,
    val result: Msg.LocateResult? = null,
    /** No answer at all within [NO_RESPONSE_MS] after the relay accepted the request. */
    val noResponse: Boolean = false,
) {
    val active get() = sendError == null && !noResponse && when (kind) {
        RequestKind.RING -> ring == null || !ring.terminal
        RequestKind.LOCATE -> result == null && (locate == null || !locate.terminal)
    }
}

sealed class PairEvent {
    data class Paired(val peerName: String) : PairEvent()
    data class Failed(val code: String) : PairEvent()
}

/** The one place that talks to the relay and turns pushes into actions. */
@SuppressLint("StaticFieldLeak") // application context only
object Hub {
    private const val TAG = "Hub"
    const val NO_RESPONSE_MS = 30_000L
    const val INVITE_TTL_SEC = 600
    /** Older ring/locate requests are ignored: ringing minutes later would only confuse. */
    private const val MAX_REQUEST_AGE_MS = 120_000L
    private const val MAX_CLOCK_SKEW_MS = 120_000L

    lateinit var context: Context; private set
    lateinit var store: Store; private set
    lateinit var history: History; private set
    private lateinit var relay: Relay

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registerLock = Mutex()

    private val _registration = MutableStateFlow<Registration>(Registration.Working)
    val registration: StateFlow<Registration> = _registration.asStateFlow()

    private val _outgoing = MutableStateFlow<Map<String, Outgoing>>(emptyMap())
    val outgoing: StateFlow<Map<String, Outgoing>> = _outgoing.asStateFlow()

    private val _pairEvents = MutableSharedFlow<PairEvent>(extraBufferCapacity = 4)
    val pairEvents: SharedFlow<PairEvent> = _pairEvents.asSharedFlow()

    fun init(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        store = Store(context)
        history = History(context)
        relay = Relay(BuildConfig.RELAY_URL)
        if (!BuildConfig.HAS_FIREBASE) {
            _registration.value = Registration.NotConfigured
            return
        }
        scope.launch { ensureRegistered() }
    }

    // ---- registration --------------------------------------------------------------------

    suspend fun ensureRegistered(): Identity? = registerLock.withLock {
        if (!BuildConfig.HAS_FIREBASE) return null
        _registration.value = Registration.Working
        val fcm = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.w(TAG, "FCM token unavailable", e)
            _registration.value = Registration.Failed("fcm")
            return null
        }
        store.keyPair()
        val current = store.identity()
        if (current != null) {
            if (store.registeredFcmToken() != fcm) {
                when (val r = relay.updateFcmToken(current.token, fcm)) {
                    is RelayResult.Ok -> store.setRegisteredFcmToken(fcm)
                    is RelayResult.Fail -> if (r.status == 401) {
                        store.clearRegistration()
                    } else {
                        _registration.value = Registration.Failed(r.code)
                        return current
                    }
                }
            }
            store.identity()?.let { _registration.value = Registration.Ready; return it }
        }
        return when (val r = relay.register(fcm)) {
            is RelayResult.Ok -> {
                store.saveRegistration(r.body.getString("deviceId"), r.body.getString("token"), fcm)
                _registration.value = Registration.Ready
                store.identity()
            }
            is RelayResult.Fail -> {
                _registration.value = Registration.Failed(r.code)
                null
            }
        }
    }

    fun onNewFcmToken() {
        scope.launch { ensureRegistered() }
    }

    // ---- my status -----------------------------------------------------------------------

    fun myStatus(): PeerStatus {
        val s = store.settings.value
        val bm = context.getSystemService(BatteryManager::class.java)
        return PeerStatus(
            snoozeUntil = if (s.snoozeUntil > System.currentTimeMillis()) s.snoozeUntil else 0,
            locationPolicy = s.locationPolicy,
            battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: -1,
            charging = bm?.isCharging == true,
        )
    }

    fun deviceLabel(): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Settings changed: tell every peer so their screen shows e.g. "요청 받지 않는 중". */
    fun broadcastStatus() {
        scope.launch {
            val st = myStatus()
            store.peers.value.forEach { send(it, Msg.Status(st), ttlSec = 3600) }
        }
    }

    // ---- pairing -------------------------------------------------------------------------

    suspend fun createOffer(): Result<PairOffer> {
        val me = ensureRegistered() ?: return Result.failure(IllegalStateException(registrationCode()))
        val secret = Crypto.randomBytes(16)
        val expiresAt = System.currentTimeMillis() + INVITE_TTL_SEC * 1000L
        return when (val r = relay.createInvite(me.token, Crypto.inviteId(secret), INVITE_TTL_SEC)) {
            is RelayResult.Ok -> {
                store.addInvite(secret, expiresAt)
                Result.success(PairOffer(me.deviceId, me.publicKey, secret, store.settings.value.myName, expiresAt))
            }
            is RelayResult.Fail -> Result.failure(IllegalStateException(r.code))
        }
    }

    private fun registrationCode() = (registration.value as? Registration.Failed)?.code ?: "not_registered"

    /** We scanned someone's QR. */
    suspend fun join(offer: PairOffer): Result<Peer> {
        if (offer.expiresAt < System.currentTimeMillis()) return Result.failure(IllegalStateException("invite_expired"))
        val me = ensureRegistered() ?: return Result.failure(IllegalStateException(registrationCode()))
        if (offer.deviceId == me.deviceId) return Result.failure(IllegalStateException("self_pairing"))
        val key = Crypto.pairKey(me.privateKey, Crypto.decodePublic(offer.publicKey), offer.secret)
        val hello = Msg.Hello(store.settings.value.myName, deviceLabel(), ack = false, status = myStatus())
        val env = Crypto.seal(key, hello.toJson().toString().toByteArray(), Crypto.aad(me.deviceId, offer.deviceId))
        return when (val r = relay.join(me.token, Crypto.inviteId(offer.secret), Crypto.b64(me.publicKey), env)) {
            is RelayResult.Ok -> {
                val now = System.currentTimeMillis()
                val peer = Peer(offer.deviceId, offer.name.ifBlank { "상대" }, "", key, now, null, 0)
                store.upsertPeer(peer)
                history.add(HistoryEntry(Msg.newId(), now, peer.id, peer.name, Direction.OUT, Kind.PAIR, "paired"))
                Result.success(peer)
            }
            is RelayResult.Fail -> Result.failure(IllegalStateException(r.code))
        }
    }

    fun unpair(peerId: String) {
        val peer = store.peer(peerId) ?: return
        scope.launch {
            send(peer, Msg.Unpair(), ttlSec = 86_400)
            store.identity()?.let { relay.unlink(it.token, peerId) }
        }
        store.removePeer(peerId)
        _outgoing.update { it - peerId }
        history.add(HistoryEntry(Msg.newId(), System.currentTimeMillis(), peer.id, peer.name, Direction.OUT, Kind.UNPAIR, "unpaired"))
    }

    // ---- sending -------------------------------------------------------------------------

    fun send(peer: Peer, msg: Msg, ttlSec: Int): RelayResult {
        val me = store.identity() ?: return RelayResult.Fail(0, "not_registered")
        val env = Crypto.seal(peer.pairKey, msg.toJson().toString().toByteArray(), Crypto.aad(me.deviceId, peer.id))
        val r = relay.send(me.token, peer.id, env, ttlSec)
        if (r is RelayResult.Fail && r.status == 401) {
            store.clearRegistration()
            scope.launch { ensureRegistered() }
        }
        return r
    }

    /** Fire-and-forget reply from a receiver-side component. */
    fun reply(peerId: String, msg: Msg, ttlSec: Int = 300) {
        val peer = store.peer(peerId) ?: return
        scope.launch {
            val r = send(peer, msg, ttlSec)
            if (r is RelayResult.Fail) Log.w(TAG, "reply ${msg::class.simpleName} failed: ${r.code}")
        }
    }

    fun ring(peerId: String) = request(peerId, RequestKind.RING)
    fun locate(peerId: String) = request(peerId, RequestKind.LOCATE)

    private fun request(peerId: String, kind: RequestKind) {
        val peer = store.peer(peerId) ?: return
        val reqId = Msg.newId()
        val now = System.currentTimeMillis()
        _outgoing.update { it + (peerId to Outgoing(kind, reqId, now)) }
        history.add(HistoryEntry(reqId, now, peer.id, peer.name, Direction.OUT,
            if (kind == RequestKind.RING) Kind.RING else Kind.LOCATE, "sent"))
        scope.launch {
            val msg = if (kind == RequestKind.RING) Msg.Ring(reqId, myStatus()) else Msg.Locate(reqId, myStatus())
            // Short TTL: a ring that arrives minutes later is worse than none.
            val r = send(peer, msg, ttlSec = 60)
            updateOutgoing(peerId, reqId) {
                if (r is RelayResult.Fail) it.copy(sendError = r.code) else it.copy(sent = true)
            }
            if (r is RelayResult.Fail) {
                history.update(reqId, Direction.OUT, "failed:${r.code}")
                return@launch
            }
            delay(NO_RESPONSE_MS)
            updateOutgoing(peerId, reqId) {
                if (it.ring == null && it.locate == null && it.result == null) it.copy(noResponse = true) else it
            }
            if (_outgoing.value[peerId]?.noResponse == true) history.update(reqId, Direction.OUT, "no_response")
        }
    }

    fun stopRing(peerId: String) {
        val out = _outgoing.value[peerId] ?: return
        if (out.kind != RequestKind.RING) return
        updateOutgoing(peerId, out.reqId) { it.copy(ring = RingState.STOPPED) }
        history.update(out.reqId, Direction.OUT, RingState.STOPPED.wire)
        reply(peerId, Msg.RingCancel(out.reqId, myStatus()), ttlSec = 120)
    }

    fun dismissOutgoing(peerId: String) = _outgoing.update { it - peerId }

    private fun updateOutgoing(peerId: String, reqId: String, change: (Outgoing) -> Outgoing) {
        _outgoing.update { map ->
            val cur = map[peerId]
            if (cur == null || cur.reqId != reqId) map else map + (peerId to change(cur))
        }
    }

    // ---- receiving -----------------------------------------------------------------------

    /** Entry point for every FCM data message. Runs on the FCM worker thread. */
    fun onPush(data: Map<String, String>) {
        val me = store.identity() ?: return
        val from = data["from"] ?: return
        val env = data["env"] ?: return
        when (data["k"]) {
            "pair" -> onPairPush(me, from, data["pub"] ?: return, env)
            "msg" -> onMessagePush(me, from, env)
        }
    }

    private fun onPairPush(me: Identity, from: String, pub: String, env: String) {
        val theirKey = runCatching { Crypto.decodePublic(Crypto.unb64(pub)) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        for ((secret, exp) in store.invites()) {
            if (exp + 60_000 < now) continue
            val key = Crypto.pairKey(me.privateKey, theirKey, secret)
            val plain = Crypto.open(key, env, Crypto.aad(from, me.deviceId)) ?: continue
            val hello = Msg.parse(String(plain)) as? Msg.Hello ?: continue
            store.consumeInvite(secret)
            val peer = Peer(from, hello.name.ifBlank { "상대" }, hello.model, key, now, hello.status, now)
            store.upsertPeer(peer)
            history.add(HistoryEntry(hello.id, now, peer.id, peer.name, Direction.IN, Kind.PAIR, "paired"))
            reply(peer.id, Msg.Hello(store.settings.value.myName, deviceLabel(), ack = true, status = myStatus()), ttlSec = 3600)
            _pairEvents.tryEmit(PairEvent.Paired(peer.name))
            Notifier.paired(context, peer.name)
            return
        }
        Log.w(TAG, "pair push matched no open invite")
    }

    private fun onMessagePush(me: Identity, from: String, env: String) {
        val peer = store.peer(from) ?: return
        val plain = Crypto.open(peer.pairKey, env, Crypto.aad(from, me.deviceId)) ?: run {
            Log.w(TAG, "undecryptable message from paired peer"); return
        }
        val msg = Msg.parse(String(plain)) ?: return
        val now = System.currentTimeMillis()
        if (msg.ts > now + MAX_CLOCK_SKEW_MS) return
        if (!store.markSeen(msg.id)) return
        store.updatePeer(from) { it.copy(status = msg.status ?: it.status, seenAt = now) }
        handle(store.peer(from) ?: return, msg, now)
    }

    private fun handle(peer: Peer, msg: Msg, now: Long) {
        when (msg) {
            is Msg.Hello -> store.updatePeer(peer.id) {
                it.copy(name = msg.name.ifBlank { it.name }, model = msg.model.ifBlank { it.model })
            }.also { if (msg.ack) _pairEvents.tryEmit(PairEvent.Paired(msg.name.ifBlank { peer.name })) }
            is Msg.Status -> Unit // status already applied
            is Msg.Ring -> onRingRequest(peer, msg, now)
            is Msg.RingCancel -> RingService.cancel(context, msg.reqId)
            is Msg.Locate -> onLocateRequest(peer, msg, now)
            is Msg.RingUpdate -> {
                updateOutgoing(peer.id, msg.reqId) { it.copy(ring = msg.state, noResponse = false) }
                if (msg.state.terminal || msg.state == RingState.RINGING) history.update(msg.reqId, Direction.OUT, msg.state.wire)
            }
            is Msg.LocateUpdate -> {
                updateOutgoing(peer.id, msg.reqId) { it.copy(locate = msg.state, noResponse = false) }
                if (msg.state.terminal) history.update(msg.reqId, Direction.OUT, msg.state.wire)
            }
            is Msg.LocateResult -> {
                updateOutgoing(peer.id, msg.reqId) { it.copy(result = msg, noResponse = false) }
                history.update(msg.reqId, Direction.OUT, "shared", msg.lat, msg.lng)
            }
            is Msg.Unpair -> {
                store.removePeer(peer.id)
                _outgoing.update { it - peer.id }
                history.add(HistoryEntry(msg.id, now, peer.id, peer.name, Direction.IN, Kind.UNPAIR, "unpaired"))
            }
        }
    }

    private fun onRingRequest(peer: Peer, msg: Msg.Ring, now: Long) {
        history.add(HistoryEntry(msg.reqId, now, peer.id, peer.name, Direction.IN, Kind.RING, "received"))
        if (now - msg.ts > MAX_REQUEST_AGE_MS) {
            history.update(msg.reqId, Direction.IN, "late")
            return
        }
        val status = myStatus()
        if (status.snoozed(now)) {
            history.update(msg.reqId, Direction.IN, RingState.SNOOZED.wire)
            Notifier.blockedWhileSnoozed(context, peer.name, ring = true)
            reply(peer.id, Msg.RingUpdate(msg.reqId, RingState.SNOOZED, status))
            return
        }
        RingService.start(context, peer.id, peer.name, msg.reqId)
    }

    private fun onLocateRequest(peer: Peer, msg: Msg.Locate, now: Long) {
        history.add(HistoryEntry(msg.reqId, now, peer.id, peer.name, Direction.IN, Kind.LOCATE, "received"))
        if (now - msg.ts > MAX_REQUEST_AGE_MS) {
            history.update(msg.reqId, Direction.IN, "late")
            return
        }
        val status = myStatus()
        val refusal = when {
            status.snoozed(now) -> LocateState.SNOOZED
            status.locationPolicy == LocationPolicy.OFF -> LocateState.OFF
            else -> null
        }
        if (refusal != null) {
            history.update(msg.reqId, Direction.IN, refusal.wire)
            Notifier.locateRefused(context, peer.name, refusal)
            reply(peer.id, Msg.LocateUpdate(msg.reqId, refusal, status))
            return
        }
        LocateService.start(context, peer.id, peer.name, msg.reqId, ask = status.locationPolicy == LocationPolicy.ASK)
    }
}
