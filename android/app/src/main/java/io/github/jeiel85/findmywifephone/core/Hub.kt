package io.github.jeiel85.findmywifephone.core

import android.annotation.SuppressLint
import android.content.Context
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
import io.github.jeiel85.findmywifephone.data.SharedLocation
import io.github.jeiel85.findmywifephone.data.Store
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.locate.NoFix
import io.github.jeiel85.findmywifephone.locate.NoFixException
import io.github.jeiel85.findmywifephone.locate.OneFix
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

/** "내 위치 보내기" as the sender's screen shows it. */
sealed class ShareOut {
    data object Locating : ShareOut()
    data object Sending : ShareOut()
    data class Sent(val note: ShareNote, val at: Long) : ShareOut()
    /** [note] is kept so the card can offer "다시 보내기" with the same words. */
    data class NoLocation(val reason: NoFix, val note: ShareNote) : ShareOut()
    data class Failed(val code: String, val note: ShareNote) : ShareOut()

    val busy get() = this is Locating || this is Sending
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
    /** A dying phone may have no fresh fix to give; an hour-old one still says where it was (its time is shown). */
    private const val LAST_FIX_MAX_AGE_MS = 60 * 60_000L
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

    private val _shareOut = MutableStateFlow<Map<String, ShareOut>>(emptyMap())
    val shareOut: StateFlow<Map<String, ShareOut>> = _shareOut.asStateFlow()

    private val _pairEvents = MutableSharedFlow<PairEvent>(extraBufferCapacity = 4)
    val pairEvents: SharedFlow<PairEvent> = _pairEvents.asSharedFlow()

    fun init(appContext: Context) {
        if (::context.isInitialized) return
        context = appContext.applicationContext
        store = Store(context)
        history = History(context)
        relay = Relay(BuildConfig.RELAY_URL)
        // The monotonic anchor restarts at boot; an older one would be off by the time the phone was down.
        val boot = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        if (boot >= 0 && store.clockBoot == boot) store.clockBase?.let { Clock.anchor(it) }
        relay.onServerDate = { date, sent, received ->
            Clock.estimate(date, sent, received)?.let { b ->
                // Whole-second Date headers jitter by a second; only a real change is worth a write.
                if (kotlin.math.abs(b - (Clock.base ?: Long.MIN_VALUE / 2)) > 1_000) {
                    Clock.anchor(b)
                    store.setClockAnchor(b, boot)
                }
            }
        }
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
            .all { settled(it) }
        if (done) store.announcedVersion = BuildConfig.VERSION_CODE
    }

    // ---- registration --------------------------------------------------------------------

    /** Main-safe: the relay client is blocking, so all three suspend entry points hop to IO. */
    suspend fun ensureRegistered(): Identity? = withContext(Dispatchers.IO) { registerLocked() }

    private suspend fun registerLocked(): Identity? = registerLock.withLock {
        if (!BuildConfig.HAS_FIREBASE) return null
        if (store.relayRemoved) {
            // A removal whose relay delete failed earlier: finish it now that we may be online.
            store.identity()?.let { finishRelayRemoval(it) }
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
            // Also once per boot with the same token: the call's Date header anchors Clock.
            if (store.registeredFcmToken() != fcm || !Clock.anchored) {
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
        val (battery, charging) = BatteryWatch.read(context)
        return PeerStatus(
            snoozeUntil = if (s.snoozeUntil > System.currentTimeMillis()) s.snoozeUntil else 0,
            locationPolicy = s.locationPolicy,
            battery = battery,
            charging = charging,
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
        _shareOut.update { it - peerId }
        history.add(HistoryEntry(Msg.newId(), System.currentTimeMillis(), peer.id, peer.name, Direction.OUT, Kind.UNPAIR, "unpaired"))
    }

    /**
     * 설정 → 서버에서 이 폰 지우기: tells every peer, then deletes this phone's id, push token, links
     * and invites on the relay (privacy policy §7). The phone stays unregistered until it pairs again.
     *
     * Once the unpair notices are out the peers have dropped us (and our pair keys), so the local
     * pairs go too, whatever the relay says next. A failed relay delete stays pending
     * ([removalPending]) and is retried on the next start or tap.
     */
    suspend fun removeFromRelay(): RelayResult = withContext(Dispatchers.IO) {
        // Set first: a 401 inside send() must not re-register us behind the owner's back.
        store.relayRemoved = true
        val me = store.identity()
        val peers = store.peers.value
        if (me != null) peers.forEach { send(it, Msg.Unpair(), ttlSec = 86_400) }
        val now = System.currentTimeMillis()
        peers.forEach { p ->
            store.removePeer(p.id)
            history.add(HistoryEntry(Msg.newId(), now, p.id, p.name, Direction.OUT, Kind.UNPAIR, "unpaired"))
        }
        _outgoing.value = emptyMap()
        _shareOut.value = emptyMap()
        _registration.value = Registration.Removed
        // send() may have cleared the registration on a 401, so read it again.
        store.identity()?.let { finishRelayRemoval(it) } ?: RelayResult.Ok(JSONObject())
    }

    /** The relay delete of a removal; on success (or 401: already gone) the local registration goes too. */
    private fun finishRelayRemoval(me: Identity): RelayResult {
        val r = relay.deleteDevice(me.token)
        if (r is RelayResult.Ok || (r is RelayResult.Fail && r.status == 401)) {
            store.clearRegistration()
            return RelayResult.Ok(JSONObject())
        }
        Log.w(TAG, "relay removal still pending: ${(r as RelayResult.Fail).code}")
        return r
    }

    /** Removed locally, but the relay still has this phone (its delete failed). */
    fun removalPending(): Boolean = store.relayRemoved && store.identity() != null

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

    /**
     * "내 위치 보내기": one fix, sent to [peerId] with a preset [note]. The owner starts it in the app,
     * so it needs only foreground location; the receiver keeps it as that peer's latest location.
     */
    fun shareLocation(peerId: String, note: ShareNote) {
        val peer = store.peer(peerId) ?: return
        if (_shareOut.value[peerId]?.busy == true) return
        _shareOut.update { it + (peerId to ShareOut.Locating) }
        scope.launch {
            val fix = OneFix.take(context)
            val loc = fix.getOrElse { e ->
                val reason = (e as? NoFixException)?.reason ?: NoFix.NO_FIX
                _shareOut.update { it + (peerId to ShareOut.NoLocation(reason, note)) }
                return@launch
            }
            _shareOut.update { it + (peerId to ShareOut.Sending) }
            val msg = Msg.LocationShare(loc.latitude, loc.longitude, loc.accuracy, loc.time, note, myStatus())
            val r = send(peer, msg, ttlSec = 3600)
            val outcome = if (r is RelayResult.Ok) "shared" else "failed:${(r as RelayResult.Fail).code}"
            history.add(HistoryEntry(msg.id, System.currentTimeMillis(), peer.id, peer.name, Direction.OUT, Kind.SHARE,
                outcome, loc.latitude, loc.longitude))
            _shareOut.update {
                it + (peerId to if (r is RelayResult.Ok) ShareOut.Sent(note, System.currentTimeMillis()) else ShareOut.Failed((r as RelayResult.Fail).code, note))
            }
        }
    }

    fun dismissShareOut(peerId: String) = _shareOut.update { it - peerId }

    /**
     * BatteryWatch: this phone's battery just ran low. Tells every peer not in [done], so they can still
     * ring or locate it before it dies. Returns the peers this send settled (delivered, or refused for
     * good); the rest are tried again on the next check. [historyKey] names this notice: a retry updates
     * its history line instead of adding another.
     */
    suspend fun sendBatteryLow(historyKey: String, done: Set<String> = emptySet()): Set<String> {
        if (store.peers.value.none { it.id !in done } || store.identity() == null) return emptySet()
        // The last location goes along only on the owner's terms (D-13): the switch, the "auto" policy, not snoozed.
        val taken = if (lastLocationAllowed()) {
            OneFix.take(context, maxAgeMs = LAST_FIX_MAX_AGE_MS).getOrNull()
                ?.let { Fix(it.latitude, it.longitude, it.accuracy, it.time) }
        } else null
        // Taking a fix can take 25 s: the owner may have withdrawn consent (or unpaired) meanwhile, so read both again.
        val st = myStatus()
        val fix = taken?.takeIf { lastLocationAllowed(st) }
        val peers = store.peers.value.filter { it.id !in done }
        if (peers.isEmpty()) return emptySet()
        val now = System.currentTimeMillis()
        val results = peers.map { p ->
            val msg = Msg.BatteryLow(st, fix)
            val r = send(p, msg, ttlSec = 3600)
            history.add(HistoryEntry("$historyKey:${p.id}", now, p.id, p.name, Direction.OUT, Kind.BATTERY,
                if (r is RelayResult.Ok) (if (fix != null) "shared" else "sent") else "failed:${(r as RelayResult.Fail).code}",
                fix?.lat, fix?.lng))
            p to r
        }
        // Like every location this phone sends, the owner is told who got it.
        val told = results.filter { it.second is RelayResult.Ok }.map { it.first.name }
        if (fix != null && told.isNotEmpty()) Notifier.batteryLocationSent(context, told)
        return results.filter { settled(it.second) }.map { it.first.id }.toSet()
    }

    /** Why a low-battery notice could not carry this phone's location right now; null when it can. */
    enum class LastLocationBlock { OFF, POLICY, SNOOZED, PERMISSION, LOCATION_OFF }

    fun lastLocationBlock(st: PeerStatus = myStatus()): LastLocationBlock? {
        val s = store.settings.value
        return when {
            !s.batteryWarn || !s.batteryLocation -> LastLocationBlock.OFF
            st.locationPolicy != LocationPolicy.AUTO -> LastLocationBlock.POLICY
            st.snoozed(System.currentTimeMillis()) -> LastLocationBlock.SNOOZED
            !OneFix.hasBackgroundPermission(context) -> LastLocationBlock.PERMISSION
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled != true -> LastLocationBlock.LOCATION_OFF
            else -> null
        }
    }

    /** May a low-battery notice carry this phone's location (settings, policy, snooze, background permission)? */
    fun lastLocationAllowed(st: PeerStatus = myStatus()): Boolean {
        val s = store.settings.value
        return s.batteryWarn && s.batteryLocation && st.locationPolicy == LocationPolicy.AUTO &&
            !st.snoozed(System.currentTimeMillis()) && OneFix.hasBackgroundPermission(context)
    }

    /**
     * A send that need not be retried: delivered, or refused for good (not paired, peer gone…).
     * 429 is only a rate-limit window (relay /v1/messages) and network/5xx failures may pass, so those retry.
     */
    private fun settled(r: RelayResult) = r is RelayResult.Ok || (r is RelayResult.Fail && r.status in 400..499 && r.status != 429)

    fun ring(peerId: String) = request(peerId, RequestKind.RING)
    fun locate(peerId: String) = request(peerId, RequestKind.LOCATE)

    /** A ring or locate to [peerId] is still under way (a second tap would only replace it). */
    fun requestActive(peerId: String, kind: RequestKind) = _outgoing.value[peerId]?.let { it.kind == kind && it.active } == true

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

    /** Debug builds only (DemoActivity): shows a "내 위치 보내기" result card. */
    fun debugShowShareOut(peerId: String, s: ShareOut?) {
        if (!BuildConfig.DEBUG) return
        _shareOut.update { if (s == null) it - peerId else it + (peerId to s) }
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
        if (msg.ts > Clock.now() + MAX_CLOCK_SKEW_MS) return
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
            is Msg.LocationShare -> {
                store.putShared(peer.id, SharedLocation(msg.lat, msg.lng, msg.accuracyM, msg.fixAt, msg.note, now))
                history.add(HistoryEntry(msg.id, now, peer.id, peer.name, Direction.IN, Kind.SHARE, "shared", msg.lat, msg.lng))
                Notifier.locationReceived(context, peer.id, peer.name, msg.note)
            }
            is Msg.BatteryLow -> {
                val fix = msg.fix
                if (fix != null) store.putShared(peer.id, SharedLocation(fix.lat, fix.lng, fix.accuracyM, fix.fixAt, ShareNote.BATTERY, now))
                history.add(HistoryEntry(msg.id, now, peer.id, peer.name, Direction.IN, Kind.BATTERY,
                    if (fix != null) "shared" else "received", fix?.lat, fix?.lng))
                Notifier.peerBatteryLow(context, peer.id, peer.name, msg.status.battery, withLocation = fix != null)
            }
            is Msg.Unpair -> {
                store.removePeer(peer.id)
                _outgoing.update { it - peer.id }
                _shareOut.update { it - peer.id }
                history.add(HistoryEntry(msg.id, now, peer.id, peer.name, Direction.IN, Kind.UNPAIR, "unpaired"))
            }
        }
    }

    private fun onRingRequest(peer: Peer, msg: Msg.Ring, now: Long) {
        history.add(HistoryEntry(msg.reqId, now, peer.id, peer.name, Direction.IN, Kind.RING, "received"))
        // Both clocks relay-corrected (Clock); an older sender's own clock may still be off.
        val age = Clock.now() - msg.ts
        if (age > MAX_REQUEST_AGE_MS) {
            Log.i(TAG, "ring ${msg.reqId} skipped: ${age / 1000}s old (clock offset ${Clock.offsetMs() / 1000}s)")
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
        if (Clock.now() - msg.ts > MAX_REQUEST_AGE_MS) {
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
