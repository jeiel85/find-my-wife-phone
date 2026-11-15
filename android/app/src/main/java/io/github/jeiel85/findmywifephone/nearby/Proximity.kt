package io.github.jeiel85.findmywifephone.nearby

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What the ringing phone broadcasts over Bluetooth: an 8-byte token that changes every minute and
 * is derived from the pair key, so only the paired phone can recognise it and nobody nearby can
 * follow the phone around from one ring to the next (DECISIONS.md D-8).
 */
object BeaconToken {
    /** Service-data UUID of the advertisement; random, owned by this app. */
    val SERVICE: UUID = UUID.fromString("6d2b9a3e-61c4-4f0e-9c5a-3f1b7e8d2c41")
    const val WINDOW_MS = 60_000L
    const val SIZE = 8

    fun window(now: Long) = now / WINDOW_MS

    fun token(pairKey: ByteArray, window: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairKey, "HmacSHA256"))
        return mac.doFinal("fmwp beacon v1|$window".toByteArray()).copyOf(SIZE)
    }

    /** Accepts the neighbouring windows too: the two phones' clocks are never exactly equal. */
    fun matches(pairKey: ByteArray, data: ByteArray?, now: Long): Boolean {
        if (data == null || data.size != SIZE) return false
        val w = window(now)
        return (w - 1..w + 1).any { token(pairKey, it).contentEquals(data) }
    }
}

/** How close the other phone seems. Bluetooth strength is rough: these are bands, not metres. */
enum class Closeness(val label: String, val hint: String) {
    RIGHT_HERE("바로 옆이에요", "쿠션 밑, 가방 안을 살펴보세요"),
    CLOSE("아주 가까워요", "1~2걸음 안쪽이에요"),
    NEAR("이 근처예요", "같은 방이거나 바로 옆방이에요"),
    FAR("조금 멀어요", "다른 방이나 층일 수 있어요"),
    NONE("신호가 안 잡혀요", "멀리 있거나 상대 폰 블루투스가 꺼져 있어요"),
}

enum class Trend { WARMER, COLDER, STEADY }

/**
 * Smooths raw RSSI (it jumps by ±10 dB when a hand or a cushion moves) and turns it into a
 * closeness band and a warmer/colder trend. Pure, unit-tested in ProximityTest.
 */
class ProximityMeter(private val alpha: Double = 0.3, private val staleMs: Long = 6_000) {
    private var ema: Double? = null
    private var lastAt = 0L
    private val history = ArrayDeque<Pair<Long, Double>>()

    fun add(now: Long, rssi: Int) {
        val next = ema?.let { it + alpha * (rssi - it) } ?: rssi.toDouble()
        ema = next
        lastAt = now
        history.addLast(now to next)
        while (history.isNotEmpty() && now - history.first().first > 8_000) history.removeFirst()
    }

    /** Smoothed dBm, or null when nothing was heard recently. */
    fun rssi(now: Long): Double? = ema.takeIf { now - lastAt <= staleMs }

    fun closeness(now: Long): Closeness = when (val r = rssi(now)) {
        null -> Closeness.NONE
        else -> when {
            r >= -55 -> Closeness.RIGHT_HERE
            r >= -67 -> Closeness.CLOSE
            r >= -80 -> Closeness.NEAR
            else -> Closeness.FAR
        }
    }

    /** 0 (cold) … 1 (hot) for the gauge; -100 dBm and below is cold, -50 and above is hot. */
    fun warmth(now: Long): Float = rssi(now)?.let { ((it + 100) / 50).toFloat().coerceIn(0f, 1f) } ?: 0f

    /** Compares now with about three seconds ago; needs a 3 dB change to call it. */
    fun trend(now: Long): Trend {
        val current = rssi(now) ?: return Trend.STEADY
        val past = history.lastOrNull { now - it.first >= 2_500 }?.second ?: return Trend.STEADY
        return when {
            current - past >= 3 -> Trend.WARMER
            past - current >= 3 -> Trend.COLDER
            else -> Trend.STEADY
        }
    }
}
