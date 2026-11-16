package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.core.Outgoing
import io.github.jeiel85.findmywifephone.core.RequestKind
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.ui.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "그만 울리기" right after "울리기": late or reordered updates must not undo the stop. */
class RingStopTest {

    @Test fun lateProgressDoesNotReviveAnEndedRequest() {
        // The other phone sent COUNTDOWN/RINGING before our cancel reached it; they arrive after we stopped.
        assertFalse(RingState.accepts(RingState.STOPPED, RingState.COUNTDOWN))
        assertFalse(RingState.accepts(RingState.STOPPED, RingState.RINGING))
        assertFalse(RingState.accepts(RingState.FOUND, RingState.RINGING))
        assertFalse(RingState.accepts(RingState.TIMEOUT, RingState.COUNTDOWN))
    }

    @Test fun progressAndEndStatesStillApply() {
        assertTrue(RingState.accepts(null, RingState.COUNTDOWN))
        assertTrue(RingState.accepts(RingState.COUNTDOWN, RingState.RINGING))
        // FCM can also deliver RINGING before COUNTDOWN; both are "in progress".
        assertTrue(RingState.accepts(RingState.RINGING, RingState.COUNTDOWN))
        assertTrue(RingState.accepts(RingState.RINGING, RingState.FOUND))
        // An end state reported by the phone itself still wins over our own STOPPED (e.g. 찾았어요 pressed first).
        assertTrue(RingState.accepts(RingState.STOPPED, RingState.FOUND))
    }

    @Test fun historyOutcomesThatAreNotRingStatesDoNotBlockUpdates() {
        assertEquals(null, RingState.ofOrNull("sent"))
        assertTrue(RingState.accepts(RingState.ofOrNull("sent"), RingState.RINGING))
        assertFalse(RingState.accepts(RingState.ofOrNull("stopped"), RingState.RINGING))
    }

    private val ring = Outgoing(RequestKind.RING, "r1", 0, sent = true, ring = RingState.COUNTDOWN)

    @Test fun stoppingKeepsTheRequestOpenUntilTheRelayTookTheCancel() {
        val stopping = ring.copy(stopping = true)
        assertTrue(stopping.active)
        assertEquals("멈추는 중…", describe(stopping, "아내"))
    }

    @Test fun aFailedCancelSaysSoAndKeepsTheStopButton() {
        val failed = ring.copy(stopError = "network")
        assertTrue(failed.active)
        assertTrue(describe(failed, "아내").startsWith("멈추지 못했어요"))
    }

    @Test fun aStoppedRequestIsDone() {
        val stopped = ring.copy(ring = RingState.STOPPED)
        assertFalse(stopped.active)
        assertEquals("울림을 멈췄어요", describe(stopped, "아내"))
    }
}
