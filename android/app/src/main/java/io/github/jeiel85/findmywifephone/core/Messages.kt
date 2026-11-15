package io.github.jeiel85.findmywifephone.core

import org.json.JSONObject
import java.util.UUID

/** How a phone answers location requests. The owner of the phone decides. */
enum class LocationPolicy(val wire: String) {
    /** Tell the owner, share after a short countdown unless they decline. Works when the phone is lost. */
    AUTO("auto"),
    /** Share only when the owner taps "허용". */
    ASK("ask"),
    OFF("off");

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

/** What a phone tells its peers about itself; piggybacks on every message as `st`. */
data class PeerStatus(
    /** 0 = accepting requests; otherwise not accepting until this unix ms ([FOREVER] = until turned off). */
    val snoozeUntil: Long = 0,
    val locationPolicy: LocationPolicy = LocationPolicy.AUTO,
    val battery: Int = -1,
    val charging: Boolean = false,
    /** While ringing: true = broadcasting the nearby beacon, false = cannot (Bluetooth off/denied), null = n/a. */
    val beacon: Boolean? = null,
) {
    fun snoozed(now: Long) = snoozeUntil > now

    fun toJson(): JSONObject = JSONObject()
        .put("snoozeUntil", snoozeUntil)
        .put("loc", locationPolicy.wire)
        .put("battery", battery)
        .put("charging", charging)
        .apply { beacon?.let { put("beacon", it) } }

    companion object {
        const val FOREVER = Long.MAX_VALUE

        fun fromJson(o: JSONObject?): PeerStatus? = o?.let {
            PeerStatus(
                snoozeUntil = it.optLong("snoozeUntil", 0),
                locationPolicy = LocationPolicy.of(it.optString("loc")),
                battery = it.optInt("battery", -1).coerceIn(-1, 100),
                charging = it.optBoolean("charging", false),
                beacon = if (it.has("beacon")) it.optBoolean("beacon") else null,
            )
        }
    }
}

/** Life cycle of a ring request as seen by both sides. */
enum class RingState(val wire: String) {
    COUNTDOWN("countdown"),   // received; owner may decline before it rings
    RINGING("ringing"),
    DECLINED("declined"),
    SNOOZED("snoozed"),       // owner is not accepting requests right now
    FOUND("found"),           // owner tapped "찾았어요" on the ringing phone
    STOPPED("stopped"),       // requester stopped it
    TIMEOUT("timeout"),       // rang for the full duration
    BUSY("busy"),             // already ringing for someone else
    ERROR("error");

    val terminal get() = this != COUNTDOWN && this != RINGING

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: ERROR
    }
}

enum class LocateState(val wire: String) {
    COUNTDOWN("countdown"),   // auto policy: shares unless declined
    ASKING("asking"),         // ask policy: waiting for the owner
    LOCATING("locating"),
    DECLINED("declined"),
    SNOOZED("snoozed"),
    OFF("off"),               // owner disabled location sharing
    UNAVAILABLE("unavailable"), // no permission / location services off / no fix
    ERROR("error");

    val terminal get() = this != COUNTDOWN && this != ASKING && this != LOCATING

    companion object {
        fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: ERROR
    }
}

/** Every end-to-end message. `id`/`ts` guard against replays; see design/PROTOCOL.md. */
sealed class Msg {
    abstract val id: String
    abstract val ts: Long
    abstract val status: PeerStatus?

    data class Hello(val name: String, val model: String, val ack: Boolean,
                     override val status: PeerStatus?, override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class Status(override val status: PeerStatus, override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class Ring(val reqId: String, override val status: PeerStatus? = null,
                    override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class RingCancel(val reqId: String, override val status: PeerStatus? = null,
                          override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class RingUpdate(val reqId: String, val state: RingState, override val status: PeerStatus?,
                          override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class Locate(val reqId: String, override val status: PeerStatus? = null,
                      override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class LocateUpdate(val reqId: String, val state: LocateState, override val status: PeerStatus?,
                            override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class LocateResult(val reqId: String, val lat: Double, val lng: Double, val accuracyM: Float, val fixAt: Long,
                            override val status: PeerStatus?, override val id: String = newId(), override val ts: Long = now()) : Msg()
    data class Unpair(override val status: PeerStatus? = null, override val id: String = newId(), override val ts: Long = now()) : Msg()

    fun toJson(): JSONObject {
        val o = JSONObject().put("v", 1).put("id", id).put("ts", ts)
        status?.let { o.put("st", it.toJson()) }
        when (this) {
            is Hello -> o.put("type", if (ack) "hello-ack" else "hello").put("name", name).put("model", model)
            is Status -> o.put("type", "status")
            is Ring -> o.put("type", "ring").put("reqId", reqId)
            is RingCancel -> o.put("type", "ring-cancel").put("reqId", reqId)
            is RingUpdate -> o.put("type", "ring-state").put("reqId", reqId).put("state", state.wire)
            is Locate -> o.put("type", "locate").put("reqId", reqId)
            is LocateUpdate -> o.put("type", "locate-state").put("reqId", reqId).put("state", state.wire)
            is LocateResult -> o.put("type", "locate-result").put("reqId", reqId)
                .put("lat", lat).put("lng", lng).put("acc", accuracyM.toDouble()).put("fixAt", fixAt)
            is Unpair -> o.put("type", "unpair")
        }
        return o
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
        fun now(): Long = System.currentTimeMillis()

        /** null for unknown versions/types or missing fields — newer peers must not crash older apps. */
        fun parse(text: String): Msg? = try {
            val o = JSONObject(text)
            if (o.optInt("v") != 1) null else {
                val id = o.getString("id")
                val ts = o.getLong("ts")
                val st = PeerStatus.fromJson(o.optJSONObject("st"))
                fun req() = o.getString("reqId").also { require(it.length in 1..64) }
                when (o.getString("type")) {
                    "hello", "hello-ack" -> Hello(
                        name = o.getString("name").take(40), model = o.optString("model").take(60),
                        ack = o.getString("type") == "hello-ack", status = st, id = id, ts = ts)
                    "status" -> st?.let { Status(it, id, ts) }
                    "ring" -> Ring(req(), st, id, ts)
                    "ring-cancel" -> RingCancel(req(), st, id, ts)
                    "ring-state" -> RingUpdate(req(), RingState.of(o.getString("state")), st, id, ts)
                    "locate" -> Locate(req(), st, id, ts)
                    "locate-state" -> LocateUpdate(req(), LocateState.of(o.getString("state")), st, id, ts)
                    "locate-result" -> {
                        val lat = o.getDouble("lat"); val lng = o.getDouble("lng")
                        require(lat in -90.0..90.0 && lng in -180.0..180.0)
                        LocateResult(req(), lat, lng, o.optDouble("acc", 0.0).toFloat(), o.getLong("fixAt"), st, id, ts)
                    }
                    "unpair" -> Unpair(st, id, ts)
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
