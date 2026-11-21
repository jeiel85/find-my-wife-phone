package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.core.BATTERY_CRITICAL_PCT
import io.github.jeiel85.findmywifephone.core.BATTERY_LOW_PCT
import io.github.jeiel85.findmywifephone.core.BatteryWatch
import io.github.jeiel85.findmywifephone.core.BatteryWatch.Action
import io.github.jeiel85.findmywifephone.core.Fix
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.core.ShareNote
import io.github.jeiel85.findmywifephone.data.Store
import io.github.jeiel85.findmywifephone.ui.fixAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "내 위치 보내기" and "배터리가 부족하면 알리기" (v0.4.0), with the last location (v0.5.0). */
class SharingTest {

    @Test fun locationShareRoundTrip() {
        val m = Msg.LocationShare(37.5, 127.0, 12f, 1_700_000_000_000, ShareNote.ARRIVED, PeerStatus(battery = 40))
        assertEquals(m, Msg.parse(m.toJson().toString()))
    }

    @Test fun unknownShareNoteReadsAsHere() {
        val o = Msg.LocationShare(1.0, 2.0, 5f, 1L, ShareNote.LEAVING, null).toJson().put("note", "from-the-future")
        assertEquals(ShareNote.HERE, (Msg.parse(o.toString()) as Msg.LocationShare).note)
    }

    @Test fun batteryNoteIsNotPickable() {
        assertFalse(ShareNote.BATTERY in ShareNote.pickable)
    }

    @Test fun locationShareOutOfRangeIsDropped() {
        val o = Msg.LocationShare(1.0, 2.0, 5f, 1L, ShareNote.HERE, null).toJson().put("lat", 91.0)
        assertNull(Msg.parse(o.toString()))
    }

    @Test fun batteryLowRoundTripAndNeedsStatus() {
        val m = Msg.BatteryLow(PeerStatus(battery = 12, charging = false))
        assertEquals(m, Msg.parse(m.toJson().toString()))
        // The level is the point of the message: without `st` there is nothing to tell.
        val bare = m.toJson().apply { remove("st") }
        assertNull(Msg.parse(bare.toString()))
    }

    @Test fun batteryLowCarriesTheLastLocation() {
        val m = Msg.BatteryLow(PeerStatus(battery = 4), Fix(37.5, 127.0, 20f, 1_700_000_000_000))
        assertEquals(m, Msg.parse(m.toJson().toString()))
        // Out-of-range coordinates spoil the whole message rather than putting a pin in the wrong place.
        assertNull(Msg.parse(m.toJson().put("lat", -91.0).toString()))
        // A 0.4.0 notice (no coordinates) still reads, without a location.
        assertNull((Msg.parse(Msg.BatteryLow(PeerStatus(battery = 4)).toJson().toString()) as Msg.BatteryLow).fix)
    }

    private fun decide(enabled: Boolean = true, battery: Int, charging: Boolean = false, low: Boolean = false, critical: Boolean = false) =
        BatteryWatch.decide(enabled, battery, charging, low, critical)

    @Test fun twoNoticesPerDischarge() {
        assertEquals(Action.NOTIFY_LOW, decide(battery = BATTERY_LOW_PCT))
        // Told at 15%: quiet until the critical level, then once more.
        assertEquals(Action.NONE, decide(battery = 10, low = true))
        assertEquals(Action.NOTIFY_CRITICAL, decide(battery = BATTERY_CRITICAL_PCT, low = true))
        assertEquals(Action.NONE, decide(battery = 2, low = true, critical = true))
        // First seen already below the critical level: only the critical notice.
        assertEquals(Action.NOTIFY_CRITICAL, decide(battery = 3))
        assertEquals(Action.NONE, decide(battery = 10, critical = true))
    }

    @Test fun noticesRearmOnChargeOrRecovery() {
        assertEquals(Action.NONE, decide(battery = BATTERY_LOW_PCT + 3, low = true))
        assertEquals(Action.REARM, decide(battery = 10, charging = true, low = true))
        assertEquals(Action.REARM, decide(battery = 3, charging = true, critical = true))
        assertEquals(Action.REARM, decide(battery = BatteryWatch.REARM_PCT, low = true, critical = true))
    }

    @Test fun noticesRespectSwitchAndUnknowns() {
        assertEquals(Action.NONE, decide(enabled = false, battery = 5))
        assertEquals(Action.NONE, decide(battery = 5, charging = true))
        assertEquals(Action.NONE, decide(battery = -1))
        assertEquals(Action.NONE, decide(battery = 50))
        // Turned off after a notice: re-arming still happens so turning it on again works.
        assertEquals(Action.REARM, decide(enabled = false, battery = 90, low = true))
    }

    @Test fun partialNoticeIsClearedByCharging() {
        // One peer got the 15% notice, the other did not yet: charging must forget both, or the next
        // discharge would skip the peer that already had it.
        assertEquals(Action.REARM, decide(battery = 12, charging = true, partial = true))
        assertEquals(Action.REARM, BatteryWatch.decide(true, BatteryWatch.REARM_PCT, false, false, false, partial = true))
        // Still discharging: the stage is not done, so the retry goes on.
        assertEquals(Action.NOTIFY_LOW, BatteryWatch.decide(true, 12, false, false, false, partial = true))
    }

    private fun decide(battery: Int, charging: Boolean, partial: Boolean) =
        BatteryWatch.decide(true, battery, charging, false, false, partial)

    @Test fun noticeIsDoneOnlyWhenEveryPeerHasIt() {
        assertFalse(BatteryWatch.complete(listOf("a", "b"), setOf("a")))
        assertTrue(BatteryWatch.complete(listOf("a", "b"), setOf("a", "b")))
        // Unpaired since: the one left counts. Nobody paired: nothing was told.
        assertTrue(BatteryWatch.complete(listOf("b"), setOf("a", "b")))
        assertFalse(BatteryWatch.complete(emptyList(), setOf("a")))
    }

    @Test fun checkGapOnlyWithinOneBoot() {
        val prev = Store.BatteryCheck(bootCount = 7, elapsed = 1_000_000, battery = 40, charging = false)
        assertEquals(900_000L, BatteryWatch.gap(prev, 7, 1_900_000))
        assertNull(BatteryWatch.gap(prev, 8, 5_000_000)) // rebooted: elapsedRealtime started over
        assertNull(BatteryWatch.gap(prev, -1, 5_000_000)) // boot count unknown
        assertNull(BatteryWatch.gap(null, 7, 5_000))
    }

    @Test fun missedCriticalNoticeIsSeenAfterTheRestart() {
        val low = Store.BatteryCheck(bootCount = 7, elapsed = 1, battery = 9, charging = false)
        assertTrue(BatteryWatch.missedCritical(low, 8, criticalSent = false))
        assertFalse(BatteryWatch.missedCritical(low, 8, criticalSent = true))
        assertFalse(BatteryWatch.missedCritical(low, 7, criticalSent = false)) // same boot: still running
        assertFalse(BatteryWatch.missedCritical(low.copy(charging = true), 8, criticalSent = false))
        assertFalse(BatteryWatch.missedCritical(low.copy(battery = 60), 8, criticalSent = false))
    }

    @Test fun fixAgeSaysHowOld() {
        val now = 1_700_000_000_000
        assertEquals("방금 잡은 위치", fixAge(now - 30_000, now))
        assertEquals("12분 전 위치", fixAge(now - 12 * 60_000, now))
        assertEquals("3시간 전 위치", fixAge(now - 3 * 3_600_000, now))
    }
}
