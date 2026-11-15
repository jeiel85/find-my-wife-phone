package io.github.jeiel85.findmywifephone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.jeiel85.findmywifephone.core.Hub
import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.Outgoing
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.core.RequestKind
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.crypto.Crypto
import io.github.jeiel85.findmywifephone.data.Direction
import io.github.jeiel85.findmywifephone.data.HistoryEntry
import io.github.jeiel85.findmywifephone.data.Kind
import io.github.jeiel85.findmywifephone.data.Peer
import io.github.jeiel85.findmywifephone.locate.LocateService
import io.github.jeiel85.findmywifephone.locate.LocateUi
import io.github.jeiel85.findmywifephone.ring.RingService
import io.github.jeiel85.findmywifephone.ring.RingUi
import io.github.jeiel85.findmywifephone.ui.AlertActivity
import io.github.jeiel85.findmywifephone.ui.MainActivity

/**
 * DEBUG BUILD ONLY. Puts the UI into a named state for design review and screenshots:
 *   adb shell am start -n io.github.jeiel85.findmywifephone/.DemoActivity --es state ringing
 * States: empty, idle, countdown, ringing, found, declined, snoozed, located,
 *         alert-countdown, alert-ringing, alert-locate, alert-ask, history
 * It never talks to the relay; the demo peer has a random key and a fake id.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)
        val state = intent.getStringExtra("state") ?: "idle"
        val now = System.currentTimeMillis()
        Hub.store.updateSettings { it.copy(myName = it.myName.ifBlank { "남편" }, onboarded = true) }
        RingService.debugPreview(null)
        LocateService.debugPreview(null)
        Hub.debugShowOutgoing(PEER, null)
        Hub.debugHideRegistrationBanner()

        if (state == "empty") {
            Hub.store.removePeer(PEER)
        } else {
            Hub.store.upsertPeer(Peer(PEER, "아내", "Galaxy S23", Crypto.randomBytes(32), now - 86_400_000L * 3,
                PeerStatus(snoozeUntil = if (state == "snoozed") now + 3 * 3_600_000L else 0, battery = 64), now - 3 * 60_000))
        }
        fun ring(s: RingState?) = Hub.debugShowOutgoing(PEER, Outgoing(RequestKind.RING, "demo", now, sent = true, ring = s))
        when (state) {
            "countdown" -> ring(RingState.COUNTDOWN)
            "ringing" -> ring(RingState.RINGING)
            "found" -> ring(RingState.FOUND)
            "declined" -> ring(RingState.DECLINED)
            "located" -> Hub.debugShowOutgoing(PEER, Outgoing(RequestKind.LOCATE, "demo-l", now, sent = true, locate = LocateState.LOCATING,
                result = Msg.LocateResult("demo-l", 37.5512, 126.9882, 35f, now - 40_000, null)))
            "history" -> seedHistory(now)
        }
        val alert = when (state) {
            "alert-countdown" -> { RingService.debugPreview(RingUi("demo", "남편", ringing = false, secondsLeft = 7)); true }
            "alert-ringing" -> { RingService.debugPreview(RingUi("demo", "남편", ringing = true, secondsLeft = 94)); true }
            "alert-locate" -> { LocateService.debugPreview(LocateUi("demo", "남편", ask = false, locating = false, secondsLeft = 6)); true }
            "alert-ask" -> { LocateService.debugPreview(LocateUi("demo", "남편", ask = true, locating = false, secondsLeft = 48)); true }
            else -> false
        }
        val target = if (alert) AlertActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (state == "history") putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_HISTORY) })
        finish()
    }

    private fun seedHistory(now: Long) {
        val h = Hub.history
        h.add(HistoryEntry("d1", now - 5 * 60_000, PEER, "아내", Direction.OUT, Kind.RING, "found"))
        h.add(HistoryEntry("d2", now - 2 * 3_600_000, PEER, "아내", Direction.IN, Kind.LOCATE, "shared", 37.5512, 126.9882))
        h.add(HistoryEntry("d3", now - 5 * 3_600_000, PEER, "아내", Direction.IN, Kind.RING, "declined"))
        h.add(HistoryEntry("d4", now - 26 * 3_600_000, PEER, "아내", Direction.OUT, Kind.LOCATE, "snoozed"))
        h.add(HistoryEntry("d5", now - 3 * 86_400_000L, PEER, "아내", Direction.IN, Kind.PAIR, "paired"))
    }

    private companion object { const val PEER = "demo_peer_aaaaaaaaaaaa" }
}
