package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.core.BATTERY_LOW_PCT
import io.github.jeiel85.findmywifephone.core.BatteryWatch
import io.github.jeiel85.findmywifephone.core.BatteryWatch.Action
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.core.ShareNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "내 위치 보내기" and "배터리가 부족하면 알리기" (v0.4.0). */
class SharingTest {

    @Test fun locationShareRoundTrip() {
        val m = Msg.LocationShare(37.5, 127.0, 12f, 1_700_000_000_000, ShareNote.ARRIVED, PeerStatus(battery = 40))
        assertEquals(m, Msg.parse(m.toJson().toString()))
    }

    @Test fun unknownShareNoteReadsAsHere() {
        val o = Msg.LocationShare(1.0, 2.0, 5f, 1L, ShareNote.LEAVING, null).toJson().put("note", "from-the-future")
        assertEquals(ShareNote.HERE, (Msg.parse(o.toString()) as Msg.LocationShare).note)
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

    @Test fun batteryNoticeGoesOutOncePerDischarge() {
        // Low and not yet told: notify.
        assertEquals(Action.NOTIFY, BatteryWatch.decide(true, BATTERY_LOW_PCT, charging = false, sent = false))
        // Already told: quiet, even lower.
        assertEquals(Action.NONE, BatteryWatch.decide(true, 5, charging = false, sent = true))
        // Just above the threshold after telling: still quiet (no re-arm until well charged).
        assertEquals(Action.NONE, BatteryWatch.decide(true, BATTERY_LOW_PCT + 3, charging = false, sent = true))
        // Charging or charged past the re-arm level: due again next time.
        assertEquals(Action.REARM, BatteryWatch.decide(true, 10, charging = true, sent = true))
        assertEquals(Action.REARM, BatteryWatch.decide(true, BatteryWatch.REARM_PCT, charging = false, sent = true))
    }

    @Test fun batteryNoticeRespectsSwitchAndUnknowns() {
        assertEquals(Action.NONE, BatteryWatch.decide(false, 5, charging = false, sent = false))
        assertEquals(Action.NONE, BatteryWatch.decide(true, 5, charging = true, sent = false))
        assertEquals(Action.NONE, BatteryWatch.decide(true, -1, charging = false, sent = false))
        assertEquals(Action.NONE, BatteryWatch.decide(true, 50, charging = false, sent = false))
        // Turned off after a notice: re-arming still happens so turning it on again works.
        assertEquals(Action.REARM, BatteryWatch.decide(false, 90, charging = false, sent = true))
    }
}
