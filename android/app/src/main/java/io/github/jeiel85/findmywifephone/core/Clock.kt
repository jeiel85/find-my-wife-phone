package io.github.jeiel85.findmywifephone.core

import android.os.SystemClock
import kotlin.math.abs

/**
 * This phone's clock corrected by the relay's. A request carries the sender's time and the receiver
 * drops it when it looks older than two minutes, so two phones whose clocks disagree by more than
 * that would drop every request. Both sides stamp and compare with [now] instead: the relay's HTTP
 * `Date` header on every call tells what time it really is.
 *
 * The correction is anchored to the monotonic clock ([SystemClock.elapsedRealtime]), not stored as
 * an offset from the wall clock: when the owner fixes the phone's clock (as settings asks) or the
 * network time jumps it, [now] does not move. The anchor is only good for one boot; after a reboot
 * there is no correction until the next relay call (Hub makes one right after start).
 *
 * Only for comparing times between phones. Times shown on this phone (history, "3분 전") stay on
 * its own clock so they agree with the status bar.
 */
object Clock {
    /** Settings warns when the phone's own clock is off by more than this. */
    const val WARN_SKEW_MS = 60_000L
    /** A slower round trip says too little about when the relay stamped its answer. */
    private const val MAX_ROUND_TRIP_MS = 5_000L

    /** Relay time minus elapsedRealtime, for this boot; null until a relay call measured it. */
    @Volatile var base: Long? = null
        private set

    val anchored get() = base != null

    fun now(): Long = base?.let { it + SystemClock.elapsedRealtime() } ?: System.currentTimeMillis()

    /** Relay time minus this phone's wall clock right now (0 when not measured). */
    fun offsetMs(): Long = base?.let { now() - System.currentTimeMillis() } ?: 0L

    fun skewed() = abs(offsetMs()) > WARN_SKEW_MS

    fun anchor(newBase: Long) { base = newBase }

    /**
     * [base] estimate from one relay call, or null when it can't be trusted. [serverDateMs] is the
     * `Date` header (whole seconds, rounded down, so +500 ms on average); the relay stamped it
     * somewhere between [sentAt] and [receivedAt] (elapsedRealtime), taken as the middle.
     */
    fun estimate(serverDateMs: Long, sentAt: Long, receivedAt: Long): Long? {
        if (serverDateMs <= 0 || receivedAt < sentAt || receivedAt - sentAt > MAX_ROUND_TRIP_MS) return null
        return serverDateMs + 500 - (sentAt + receivedAt) / 2
    }
}
