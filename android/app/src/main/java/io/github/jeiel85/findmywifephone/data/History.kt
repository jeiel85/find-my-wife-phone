package io.github.jeiel85.findmywifephone.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class Direction { IN, OUT }
enum class Kind { RING, LOCATE, PAIR, UNPAIR }

/**
 * One line in the "기록" screen. Incoming location requests are always recorded and can't be
 * cleared one by one: the point is that nobody can look you up without you being able to see it.
 */
data class HistoryEntry(
    val reqId: String,
    val at: Long,
    val peerId: String,
    val peerName: String,
    val direction: Direction,
    val kind: Kind,
    val outcome: String,
    val lat: Double? = null,
    val lng: Double? = null,
)

class History(context: Context) {
    private val prefs = context.getSharedPreferences("fmwp-history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private fun load(): List<HistoryEntry> {
        val arr = JSONArray(prefs.getString("entries", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    reqId = o.getString("reqId"), at = o.getLong("at"),
                    peerId = o.getString("peerId"), peerName = o.optString("peerName"),
                    direction = Direction.valueOf(o.getString("dir")), kind = Kind.valueOf(o.getString("kind")),
                    outcome = o.optString("outcome"),
                    lat = if (o.has("lat")) o.getDouble("lat") else null,
                    lng = if (o.has("lng")) o.getDouble("lng") else null,
                )
            }.getOrNull()
        }
    }

    private fun save(list: List<HistoryEntry>) {
        val trimmed = list.sortedByDescending { it.at }.take(MAX)
        val arr = JSONArray()
        trimmed.forEach { e ->
            arr.put(JSONObject().put("reqId", e.reqId).put("at", e.at).put("peerId", e.peerId)
                .put("peerName", e.peerName).put("dir", e.direction.name).put("kind", e.kind.name)
                .put("outcome", e.outcome).apply { e.lat?.let { put("lat", it) }; e.lng?.let { put("lng", it) } })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
        _entries.value = trimmed
    }

    @Synchronized
    fun add(entry: HistoryEntry) = save(_entries.value.filterNot { it.reqId == entry.reqId && it.direction == entry.direction } + entry)

    /** Updates the outcome of an existing request; no-op when the request was never recorded. */
    @Synchronized
    fun update(reqId: String, direction: Direction, outcome: String, lat: Double? = null, lng: Double? = null) {
        val list = _entries.value
        if (list.none { it.reqId == reqId && it.direction == direction }) return
        save(list.map {
            if (it.reqId == reqId && it.direction == direction) it.copy(outcome = outcome, lat = lat ?: it.lat, lng = lng ?: it.lng) else it
        })
    }

    fun outcome(reqId: String, direction: Direction): String? =
        _entries.value.firstOrNull { it.reqId == reqId && it.direction == direction }?.outcome

    @Synchronized
    fun clearOutgoing() = save(_entries.value.filter { it.direction == Direction.IN })

    private companion object { const val MAX = 300 }
}
