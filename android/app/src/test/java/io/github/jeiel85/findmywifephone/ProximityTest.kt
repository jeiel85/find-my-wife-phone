package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.nearby.BeaconToken
import io.github.jeiel85.findmywifephone.nearby.Closeness
import io.github.jeiel85.findmywifephone.nearby.ProximityMeter
import io.github.jeiel85.findmywifephone.nearby.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {

    private val key = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }

    @Test fun tokenIsRecognisedOnlyWithTheSamePairKey() {
        val now = 1_800_000_000_000L
        val t = BeaconToken.token(key, BeaconToken.window(now))
        assertEquals(BeaconToken.SIZE, t.size)
        assertTrue(BeaconToken.matches(key, t, now))
        assertFalse(BeaconToken.matches(other, t, now))
        assertFalse(BeaconToken.matches(key, null, now))
        assertFalse(BeaconToken.matches(key, t.copyOf(7), now))
    }

    @Test fun tokenRotatesEveryMinuteButToleratesClockSkew() {
        val now = 1_800_000_000_000L
        val t = BeaconToken.token(key, BeaconToken.window(now))
        assertFalse(t.contentEquals(BeaconToken.token(key, BeaconToken.window(now) + 1)))
        assertTrue("phone clock one minute ahead", BeaconToken.matches(key, t, now + 60_000))
        assertFalse("two minutes off", BeaconToken.matches(key, t, now + 120_000))
    }

    @Test fun beaconFlagRoundTripsAndOlderPeersOmitIt() {
        val on = io.github.jeiel85.findmywifephone.core.PeerStatus(beacon = true)
        assertEquals(true, io.github.jeiel85.findmywifephone.core.PeerStatus.fromJson(on.toJson())!!.beacon)
        val old = io.github.jeiel85.findmywifephone.core.PeerStatus()
        assertFalse(old.toJson().has("beacon"))
        assertNull(io.github.jeiel85.findmywifephone.core.PeerStatus.fromJson(old.toJson())!!.beacon)
    }

    @Test fun bandsFollowSmoothedSignal() {
        val m = ProximityMeter()
        assertEquals(Closeness.NONE, m.closeness(0))
        m.add(0, -50); assertEquals(Closeness.RIGHT_HERE, m.closeness(0))
        val far = ProximityMeter().apply { add(0, -90) }
        assertEquals(Closeness.FAR, far.closeness(0))
        assertEquals(0.2f, far.warmth(0), 0.001f)
    }

    @Test fun oneSpikeDoesNotClaimTheirPhoneIsRightHere() {
        val m = ProximityMeter()
        repeat(10) { m.add(it * 300L, -85) }
        m.add(3_000, -50) // a single reflection; real phones are still far
        val c = m.closeness(3_000)
        assertTrue("got $c", c == Closeness.FAR || c == Closeness.NEAR)
    }

    @Test fun signalGoesStaleAfterSixSeconds() {
        val m = ProximityMeter().apply { add(0, -60) }
        assertEquals(Closeness.CLOSE, m.closeness(6_000))
        assertNull(m.rssi(6_001))
        assertEquals(Closeness.NONE, m.closeness(6_001))
    }

    @Test fun trendShowsWalkingTowardsAndAway() {
        val towards = ProximityMeter()
        (0..12).forEach { towards.add(it * 300L, -90 + it * 3) }
        assertEquals(Trend.WARMER, towards.trend(3_600))

        val away = ProximityMeter()
        (0..12).forEach { away.add(it * 300L, -55 - it * 3) }
        assertEquals(Trend.COLDER, away.trend(3_600))

        val still = ProximityMeter()
        (0..12).forEach { still.add(it * 300L, -70 + (it % 2)) }
        assertEquals(Trend.STEADY, still.trend(3_600))
    }
}
