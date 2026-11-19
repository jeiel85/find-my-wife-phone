package io.github.jeiel85.findmywifephone.core

import kotlin.math.abs

/**
 * This phone's clock corrected by the relay's. A request carries the sender's time and the receiver
 * drops it when it looks older than two minutes, so two phones whose clocks disagree by more than
 * that would drop every request. Both sides stamp and compare with [now] instead: the relay's HTTP
 * `Date` header on every call tells how far this phone's clock is off ([offsetMs]).
 *
 * Only for comparing times between phones. Times shown on this phone (history, "3분 전") stay on
 * its own clock so they agree with the status bar.
 */
object Clock {
    /** Settings warns when the phone's own clock is off by more than this. */
    const val WARN_SKEW_MS = 60_000L
    /** A slower round trip says too little about when the relay stamped its answer. */
    private const val MAX_ROUND_TRIP_MS = 5_000L

    /** Relay time minus this phone's time; restored from Store on start. */
    @Volatile var offsetMs = 0L

    fun now(): Long = System.currentTimeMillis() + offsetMs

    val skewed get() = abs(offsetMs) > WARN_SKEW_MS

    /**
     * Offset estimate from one relay call, or null when it can't be trusted. [serverDateMs] is the
     * `Date` header (whole seconds, rounded down, so +500 ms on average); the relay stamped it
     * somewhere between [sentAt] and [receivedAt], taken as the middle.
     */
    fun estimate(serverDateMs: Long, sentAt: Long, receivedAt: Long): Long? {
        if (serverDateMs <= 0 || receivedAt < sentAt || receivedAt - sentAt > MAX_ROUND_TRIP_MS) return null
        return serverDateMs + 500 - (sentAt + receivedAt) / 2
    }
}
