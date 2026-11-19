package io.github.jeiel85.findmywifephone.data

import android.content.Context
import android.content.SharedPreferences
import io.github.jeiel85.findmywifephone.core.LocationPolicy
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.crypto.Crypto
import io.github.jeiel85.findmywifephone.crypto.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.PrivateKey

data class Identity(val deviceId: String, val token: String, val privateKey: PrivateKey, val publicKey: ByteArray)

data class Peer(
    val id: String,
    val name: String,
    val model: String,
    val pairKey: ByteArray,
    val pairedAt: Long,
    val status: PeerStatus?,
    /** When we last heard anything from this peer (unix ms, 0 = never since pairing). */
    val seenAt: Long,
) {
    override fun equals(other: Any?) = other is Peer && other.id == id && other.name == name &&
        other.model == model && other.pairedAt == pairedAt && other.status == status && other.seenAt == seenAt
    override fun hashCode() = id.hashCode()
}

data class Settings(
    val myName: String = "",
    val snoozeUntil: Long = 0,
    val locationPolicy: LocationPolicy = LocationPolicy.AUTO,
    val onboarded: Boolean = false,
    /** How long a ring lasts before it stops by itself. */
    val ringSeconds: Int = 120,
    val ringVibrate: Boolean = true,
    /** Camera flash on while ringing — helps when the phone is under a cushion in the dark. */
    val ringFlash: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** GitHub release builds check GitHub Releases twice a day and install signed updates. Unused on Play. */
    val autoUpdate: Boolean = true,
)

enum class ThemeMode(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object { fun of(v: String?) = entries.firstOrNull { it.wire == v } ?: SYSTEM }
}

/** Allowed ring lengths; anything else read from disk falls back to the default. */
val RING_LENGTHS = listOf(60, 120, 300)

/** Everything the app persists. Secrets go through the Keystore; plain prefs hold the rest. */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("fmwp", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("fmwp-store-v1")

    private val _peers = MutableStateFlow(loadPeers())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // ---- identity ------------------------------------------------------------------------

    /** Long-lived key pair, created once per install, independent of relay registration. */
    @Synchronized
    fun keyPair(): Pair<PrivateKey, ByteArray> {
        val priv = cipher.decrypt(prefs.getString("privEnc", null))
        val pub = prefs.getString("pub", null)
        if (priv != null && pub != null) return Crypto.decodePrivate(priv) to Crypto.unb64(pub)
        val kp = Crypto.newKeyPair()
        val pubBytes = Crypto.encodePublic(kp.public as java.security.interfaces.ECPublicKey)
        prefs.edit()
            .putString("privEnc", cipher.encrypt(Crypto.encodePrivate(kp.private)))
            .putString("pub", Crypto.b64(pubBytes))
            .apply()
        return kp.private to pubBytes
    }

    @Synchronized
    fun identity(): Identity? {
        val id = prefs.getString("deviceId", null) ?: return null
        val token = cipher.decrypt(prefs.getString("tokenEnc", null))?.let(::String) ?: return null
        val (priv, pub) = keyPair()
        return Identity(id, token, priv, pub)
    }

    @Synchronized
    fun saveRegistration(deviceId: String, token: String, fcmToken: String) {
        prefs.edit()
            .putString("deviceId", deviceId)
            .putString("tokenEnc", cipher.encrypt(token.toByteArray()))
            .putString("fcmToken", fcmToken)
            .apply()
    }

    fun registeredFcmToken(): String? = prefs.getString("fcmToken", null)
    fun setRegisteredFcmToken(token: String) = prefs.edit().putString("fcmToken", token).apply()

    /**
     * The owner removed this phone from the relay (설정 → 서버에서 이 폰 지우기): stay unregistered
     * until they pair again, so nothing is re-created behind their back.
     */
    var relayRemoved: Boolean
        get() = prefs.getBoolean("relayRemoved", false)
        set(v) = prefs.edit().putBoolean("relayRemoved", v).apply()

    /** App version last told to the peers; a newer install announces itself once (Hub.init). */
    var announcedVersion: Int
        get() = prefs.getInt("announcedVer", 0)
        set(v) = prefs.edit().putInt("announcedVer", v).apply()

    /** Relay forgot us (401). Peers keep their keys but are unreachable until paired again. */
    @Synchronized
    fun clearRegistration() {
        prefs.edit().remove("deviceId").remove("tokenEnc").remove("fcmToken").apply()
    }

    // ---- settings ------------------------------------------------------------------------

    private fun loadSettings() = Settings(
        myName = prefs.getString("myName", "") ?: "",
        snoozeUntil = prefs.getLong("snoozeUntil", 0),
        locationPolicy = LocationPolicy.of(prefs.getString("locPolicy", null)),
        onboarded = prefs.getBoolean("onboarded", false),
        ringSeconds = prefs.getInt("ringSec", 120).takeIf { it in RING_LENGTHS } ?: 120,
        ringVibrate = prefs.getBoolean("ringVibrate", true),
        ringFlash = prefs.getBoolean("ringFlash", true),
        theme = ThemeMode.of(prefs.getString("theme", null)),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
    )

    @Synchronized
    fun updateSettings(change: (Settings) -> Settings): Settings {
        val next = change(_settings.value)
        prefs.edit()
            .putString("myName", next.myName)
            .putLong("snoozeUntil", next.snoozeUntil)
            .putString("locPolicy", next.locationPolicy.wire)
            .putBoolean("onboarded", next.onboarded)
            .putInt("ringSec", next.ringSeconds)
            .putBoolean("ringVibrate", next.ringVibrate)
            .putBoolean("ringFlash", next.ringFlash)
            .putString("theme", next.theme.wire)
            .putBoolean("autoUpdate", next.autoUpdate)
            .apply()
        _settings.value = next
        return next
    }

    // ---- peers ---------------------------------------------------------------------------

    private fun loadPeers(): List<Peer> {
        val arr = JSONArray(prefs.getString("peers", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val key = cipher.decrypt(o.optString("keyEnc")) ?: return@mapNotNull null
            Peer(
                id = o.getString("id"),
                name = o.optString("name"),
                model = o.optString("model"),
                pairKey = key,
                pairedAt = o.optLong("pairedAt"),
                status = PeerStatus.fromJson(o.optJSONObject("status")),
                seenAt = o.optLong("seenAt"),
            )
        }
    }

    private fun savePeers(list: List<Peer>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id).put("name", p.name).put("model", p.model)
                .put("keyEnc", cipher.encrypt(p.pairKey)).put("pairedAt", p.pairedAt)
                .put("status", p.status?.toJson()).put("seenAt", p.seenAt))
        }
        prefs.edit().putString("peers", arr.toString()).apply()
        _peers.value = list
    }

    fun peer(id: String): Peer? = _peers.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertPeer(peer: Peer) = savePeers(_peers.value.filterNot { it.id == peer.id } + peer)

    @Synchronized
    fun updatePeer(id: String, change: (Peer) -> Peer) {
        val list = _peers.value
        if (list.none { it.id == id }) return
        savePeers(list.map { if (it.id == id) change(it) else it })
    }

    @Synchronized
    fun removePeer(id: String) = savePeers(_peers.value.filterNot { it.id == id })

    // ---- pairing invites (we showed a QR; we must remember its secret) --------------------

    @Synchronized
    fun addInvite(secret: ByteArray, expiresAt: Long) {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        invites().filter { it.second > now }.takeLast(4).forEach { (s, exp) ->
            arr.put(JSONObject().put("sEnc", cipher.encrypt(s)).put("exp", exp))
        }
        arr.put(JSONObject().put("sEnc", cipher.encrypt(secret)).put("exp", expiresAt))
        prefs.edit().putString("invites", arr.toString()).apply()
    }

    /** (secret, expiresAt) for every remembered invite. */
    @Synchronized
    fun invites(): List<Pair<ByteArray, Long>> {
        val arr = JSONArray(prefs.getString("invites", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            cipher.decrypt(o.optString("sEnc"))?.let { it to o.optLong("exp") }
        }
    }

    @Synchronized
    fun consumeInvite(secret: ByteArray) {
        val arr = JSONArray()
        invites().filterNot { it.first.contentEquals(secret) }.forEach { (s, exp) ->
            arr.put(JSONObject().put("sEnc", cipher.encrypt(s)).put("exp", exp))
        }
        prefs.edit().putString("invites", arr.toString()).apply()
    }

    // ---- replay guard --------------------------------------------------------------------

    /** true the first time a message id is seen. Keeps the last 300 ids. */
    @Synchronized
    fun markSeen(id: String): Boolean {
        val arr = JSONArray(prefs.getString("seen", "[]"))
        for (i in 0 until arr.length()) if (arr.getString(i) == id) return false
        val next = JSONArray()
        val start = maxOf(0, arr.length() - 299)
        for (i in start until arr.length()) next.put(arr.getString(i))
        next.put(id)
        prefs.edit().putString("seen", next.toString()).commit()
        return true
    }

    // ---- cancelled ring requests ---------------------------------------------------------

    /**
     * A ring-cancel can reach this phone before its ring (FCM keeps no order), and the ring must then
     * not start. Persisted in case the process is killed between the two pushes. Keeps the last 50 ids.
     */
    @Synchronized
    fun markCancelled(reqId: String) {
        val arr = JSONArray(prefs.getString("cancelled", "[]"))
        for (i in 0 until arr.length()) if (arr.getString(i) == reqId) return
        val next = JSONArray()
        for (i in maxOf(0, arr.length() - 49) until arr.length()) next.put(arr.getString(i))
        next.put(reqId)
        prefs.edit().putString("cancelled", next.toString()).commit()
    }

    @Synchronized
    fun wasCancelled(reqId: String): Boolean {
        val arr = JSONArray(prefs.getString("cancelled", "[]"))
        return (0 until arr.length()).any { arr.getString(it) == reqId }
    }

    // ---- sound state to restore after ringing --------------------------------------------

    fun saveSoundRestore(json: JSONObject?) {
        prefs.edit().apply { if (json == null) remove("soundRestore") else putString("soundRestore", json.toString()) }.commit()
    }

    fun soundRestore(): JSONObject? = prefs.getString("soundRestore", null)?.let { JSONObject(it) }
}
