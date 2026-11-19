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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import org.json.JSONObject

sealed class Registration {
    data object NotConfigured : Registration()   // built without google-services.json
    data object Working : Registration()
    data object Ready : Registration()
    data class Failed(val code: String) : Registration()
    /** The owner removed this phone from the relay; it registers again only when they pair. */
    data object Removed : Registration()
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
    /**
     * The request went out but its answer was lost (no HTTP response): the relay may well have
     * forwarded it, so the other phone can ring. Shown as "not sure" and the stop button stays.
     */
    val uncertain: Boolean = false,
    /** No answer at all within [NO_RESPONSE_MS] after the relay accepted the request. */
    val noResponse: Boolean = false,
    /** "그만 울리기" pressed; the cancel is on its way to the relay. */
    val stopping: Boolean = false,
    /** The cancel could not be sent, so the other phone may still ring. The stop button stays for a retry. */
    val stopError: String? = null,
    /** RING only: what the relay said about the ring itself, which a stop has to wait for (see Hub.stopRing). */
    val ringSend: Deferred<RelayResult>? = null,
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
        scope.launch { if (ensureRegistered() != null) announceVersion() }
    }

    /**
     * First start of a new version: tell the peers, so their screen stops warning that this phone
     * runs an old version (PeerStatus.appVersion) without waiting for the next request.
     */
    private fun announceVersion() {
        if (store.announcedVersion == BuildConfig.VERSION_CODE) return
        val st = myStatus()
        // Retry on the next start only when it might work then; a peer the relay refuses (4xx) won't.
        val done = store.peers.value.map { send(it, Msg.Status(st), ttlSec = 3600) }
            .all { it is RelayResult.Ok || (it is RelayResult.Fail && it.status in 400..499) }
        if (done) store.announcedVersion = BuildConfig.VERSION_CODE
    }

    // ---- registration --------------------------------------------------------------------

    /** Main-safe: the relay client is blocking, so all three suspend entry points hop to IO. */
    suspend fun ensureRegistered(): Identity? = withContext(Dispatchers.IO) { registerLocked() }

    private suspend fun registerLocked(): Identity? = registerLock.withLock {
        if (!BuildConfig.HAS_FIREBASE) return null
        if (store.relayRemoved) {
            _registration.value = Registration.Removed
            return null
        }
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
            beacon = RingService.beaconState,
            appVersion = BuildConfig.VERSION_CODE,
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

    suspend fun createOffer(): Result<PairOffer> = withContext(Dispatchers.IO) { createOfferIo() }

    private suspend fun createOfferIo(): Result<PairOffer> {
        store.relayRemoved = false // pairing again is the owner's way back after "서버에서 이 폰 지우기"
        val secret = Crypto.randomBytes(16)
        val expiresAt = System.currentTimeMillis() + INVITE_TTL_SEC * 1000L
        val (me, r) = withIdentity { relay.createInvite(it.token, Crypto.inviteId(secret), INVITE_TTL_SEC) }
            ?: return Result.failure(IllegalStateException(registrationCode()))
        return when (r) {
            is RelayResult.Ok -> {
                store.addInvite(secret, expiresAt)
                Result.success(PairOffer(me.deviceId, me.publicKey, secret, store.settings.value.myName, expiresAt))
            }
            is RelayResult.Fail -> Result.failure(IllegalStateException(r.code))
        }
    }

    private fun registrationCode() = (registration.value as? Registration.Failed)?.code ?: "not_registered"

    /**
     * Runs [call] as this phone. When the relay no longer knows us (401 — e.g. the daily cleanup
     * dropped a registration that had no pairs for months), registers anew and tries once more.
     */
    private suspend fun withIdentity(call: (Identity) -> RelayResult): Pair<Identity, RelayResult>? {
        var me = ensureRegistered() ?: return null
        var r = call(me)
        if (r is RelayResult.Fail && r.status == 401) {
            store.clearRegistration()
            me = ensureRegistered() ?: return null
            r = call(me)
        }
        return me to r
    }

    /** We scanned someone's QR. */
    suspend fun join(offer: PairOffer): Result<Peer> = withContext(Dispatchers.IO) { joinIo(offer) }

    private suspend fun joinIo(offer: PairOffer): Result<Peer> {
        if (offer.expiresAt < System.currentTimeMillis()) return Result.failure(IllegalStateException("invite_expired"))
        store.relayRemoved = false
        val current = ensureRegistered() ?: return Result.failure(IllegalStateException(registrationCode()))
        if (offer.deviceId == current.deviceId) return Result.failure(IllegalStateException("self_pairing"))
        val key = Crypto.pairKey(current.privateKey, Crypto.decodePublic(offer.publicKey), offer.secret)
        val hello = Msg.Hello(store.settings.value.myName, deviceLabel(), ack = false, status = myStatus())
        // Sealed per attempt: a re-registration after 401 changes our device id, which is in the AAD.
        val (_, r) = withIdentity { me ->
            val env = Crypto.seal(key, hello.toJson().toString().toByteArray(), Crypto.aad(me.deviceId, offer.deviceId))
            relay.join(me.token, Crypto.inviteId(offer.secret), Crypto.b64(me.publicKey), env)
        } ?: return Result.failure(IllegalStateException(registrationCode()))
        return when (r) {
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

    /**
     * 설정 → 서버에서 이 폰 지우기: tells every peer, then deletes this phone's id, push token, links
     * and invites on the relay (privacy policy §7). The phone stays unregistered until it pairs again.
     * On failure nothing local changes, so the owner can retry.
     */
    suspend fun removeFromRelay(): RelayResult = withContext(Dispatchers.IO) {
        // Set first: a 401 inside send() must not re-register us behind the owner's back.
        store.relayRemoved = true
        val me = store.identity()
        if (me != null) {
            store.peers.value.forEach { send(it, Msg.Unpair(), ttlSec = 86_400) }
            val r = relay.deleteDevice(me.token)
            if (r is RelayResult.Fail && r.status != 401) { // 401: the relay had already forgotten us
                store.relayRemoved = false
                return@withContext r
            }
        }
        val now = System.currentTimeMillis()
        store.peers.value.forEach { p ->
            store.removePeer(p.id)
            history.add(HistoryEntry(Msg.newId(), now, p.id, p.name, Direction.OUT, Kind.UNPAIR, "unpaired"))
        }
        _outgoing.value = emptyMap()
        store.clearRegistration()
        _registration.value = Registration.Removed
        RelayResult.Ok(JSONObject())
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
        val ringSend = if (kind == RequestKind.RING) CompletableDeferred<RelayResult>() else null
        _outgoing.update { it + (peerId to Outgoing(kind, reqId, now, ringSend = ringSend)) }
        history.add(HistoryEntry(reqId, now, peer.id, peer.name, Direction.OUT,
            if (kind == RequestKind.RING) Kind.RING else Kind.LOCATE, "sent"))
        scope.launch {
            val msg = if (kind == RequestKind.RING) Msg.Ring(reqId, myStatus()) else Msg.Locate(reqId, myStatus())
            // Short TTL: a ring that arrives minutes later is worse than none.
            val r = try {
                send(peer, msg, ttlSec = 60)
            } catch (e: Exception) {
                ringSend?.complete(RelayResult.Fail(0, "error")) // never leave a pending stop hanging
                throw e
            }
            ringSend?.complete(r)
            // No HTTP answer at all: the relay may have forwarded it before the answer was lost.
            val lost = r is RelayResult.Fail && r.status == 0 && r.code == "network"
            updateOutgoing(peerId, reqId) {
                when {
                    lost -> it.copy(sent = true, uncertain = true)
                    r is RelayResult.Fail -> it.copy(sendError = r.code)
                    else -> it.copy(sent = true)
                }
            }
            if (r is RelayResult.Fail && !lost) {
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

    /**
     * "그만 울리기" / "취소". The request only counts as stopped once the relay has taken the cancel;
     * if that fails the screen says so and keeps the button, because the other phone may still ring.
     */
    fun stopRing(peerId: String) {
        val out = _outgoing.value[peerId] ?: return
        if (out.kind != RequestKind.RING || !out.active || out.stopping) return
        val peer = store.peer(peerId) ?: return
        val reqId = out.reqId
        updateOutgoing(peerId, reqId) { it.copy(stopping = true, stopError = null) }
        scope.launch {
            // Pressed right after "울리기": let the ring reach the relay first so the cancel does not
            // overtake it there. FCM can still swap them, which the receiver handles (Store.wasCancelled).
            // Tied to this ring, not to the screen: "위치 보기" may replace the outgoing entry meanwhile.
            val sent = out.ringSend?.await()
            if (sent is RelayResult.Fail && sent.status != 0) {
                // The relay refused the ring, so it never went out. A transport failure (status 0) proves
                // nothing — the relay may have forwarded it before the response was lost — so cancel anyway.
                updateOutgoing(peerId, reqId) { it.copy(stopping = false) }
                return@launch
            }
            val r = send(peer, Msg.RingCancel(reqId, myStatus()), ttlSec = 120)
            if (r is RelayResult.Fail) {
                Log.w(TAG, "ring-cancel failed: ${r.code}")
                updateOutgoing(peerId, reqId) { it.copy(stopping = false, stopError = r.code) }
                return@launch
            }
            updateOutgoing(peerId, reqId) {
                // An end state may have arrived meanwhile (e.g. "찾았어요"); it stays.
                if (it.ring?.terminal == true) it.copy(stopping = false)
                else it.copy(stopping = false, stopError = null, ring = RingState.STOPPED, noResponse = false)
            }
            // Our own stop must not replace an end state the other phone reported meanwhile (e.g. FOUND).
            if (RingState.ofOrNull(history.outcome(reqId, Direction.OUT))?.terminal != true) {
                history.update(reqId, Direction.OUT, RingState.STOPPED.wire)
            }
        }
    }

    /** Records a ring outcome unless the entry already holds an end state that [state] must not undo. */
    private fun updateHistoryRing(reqId: String, state: RingState) {
        val current = RingState.ofOrNull(history.outcome(reqId, Direction.OUT))
        if (RingState.accepts(current, state)) history.update(reqId, Direction.OUT, state.wire)
    }

    fun dismissOutgoing(peerId: String) = _outgoing.update { it - peerId }

    /** Debug builds only (src/debug DemoActivity): puts the home screen into a given state for review. */
    fun debugShowOutgoing(peerId: String, o: Outgoing?) {
        if (!BuildConfig.DEBUG) return
        _outgoing.update { if (o == null) it - peerId else it + (peerId to o) }
    }

    /** Debug builds only: hides the "no push config" banner while taking design screenshots. */
    fun debugHideRegistrationBanner() {
        if (BuildConfig.DEBUG) _registration.value = Registration.Ready
    }

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
        val from = data["src"] ?: return
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
            is Msg.RingCancel -> {
                // Remember it first: the ring itself may still be on its way (see onRingRequest, RingService.begin).
                store.markCancelled(msg.reqId)
                RingService.cancel(context, msg.reqId)
            }
            is Msg.Locate -> onLocateRequest(peer, msg, now)
            is Msg.RingUpdate -> {
                updateOutgoing(peer.id, msg.reqId) {
                    if (RingState.accepts(it.ring, msg.state)) it.copy(ring = msg.state, noResponse = false) else it
                }
                if (msg.state.terminal || msg.state == RingState.RINGING) updateHistoryRing(msg.reqId, msg.state)
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
            Log.i(TAG, "ring ${msg.reqId} skipped: ${(now - msg.ts) / 1000}s old")
            history.update(msg.reqId, Direction.IN, "late")
            return
        }
        if (store.wasCancelled(msg.reqId)) {
            Log.i(TAG, "ring ${msg.reqId} skipped: its cancel arrived first")
            // Its cancel overtook it. The requester already shows "멈췄어요"; nothing to send back.
            history.update(msg.reqId, Direction.IN, RingState.STOPPED.wire)
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
