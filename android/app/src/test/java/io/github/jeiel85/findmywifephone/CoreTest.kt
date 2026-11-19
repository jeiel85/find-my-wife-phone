package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.core.LocateState
import io.github.jeiel85.findmywifephone.core.LocationPolicy
import io.github.jeiel85.findmywifephone.core.Msg
import io.github.jeiel85.findmywifephone.core.PairOffer
import io.github.jeiel85.findmywifephone.core.PeerStatus
import io.github.jeiel85.findmywifephone.core.RingState
import io.github.jeiel85.findmywifephone.core.versionName
import io.github.jeiel85.findmywifephone.crypto.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey

class CoreTest {

    private fun pub(kp: java.security.KeyPair) = Crypto.encodePublic(kp.public as ECPublicKey)

    @Test fun bothSidesDeriveTheSamePairKey() {
        val a = Crypto.newKeyPair(); val b = Crypto.newKeyPair()
        val secret = Crypto.randomBytes(16)
        val ka = Crypto.pairKey(a.private, Crypto.decodePublic(pub(b)), secret)
        val kb = Crypto.pairKey(b.private, Crypto.decodePublic(pub(a)), secret)
        assertArrayEquals(ka, kb)
        assertEquals(32, ka.size)
    }

    @Test fun wrongSecretGivesADifferentKey() {
        val a = Crypto.newKeyPair(); val b = Crypto.newKeyPair()
        val k1 = Crypto.pairKey(a.private, Crypto.decodePublic(pub(b)), Crypto.randomBytes(16))
        val k2 = Crypto.pairKey(a.private, Crypto.decodePublic(pub(b)), Crypto.randomBytes(16))
        assertTrue(!k1.contentEquals(k2))
    }

    @Test fun envelopeRoundTripAndTamperDetection() {
        val key = Crypto.randomBytes(32)
        val aad = Crypto.aad("A", "B")
        val env = Crypto.seal(key, "hello".toByteArray(), aad)
        assertEquals("hello", String(Crypto.open(key, env, aad)!!))
        // direction is bound: a message from A to B can't be replayed as B to A
        assertNull(Crypto.open(key, env, Crypto.aad("B", "A")))
        assertNull(Crypto.open(Crypto.randomBytes(32), env, aad))
        val bytes = Crypto.unb64(env); bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        assertNull(Crypto.open(key, Crypto.b64(bytes), aad))
        assertNull(Crypto.open(key, "not-base64!!", aad))
    }

    @Test fun publicKeyEncodingIsStableAndValidated() {
        val kp = Crypto.newKeyPair()
        val enc = pub(kp)
        assertEquals(65, enc.size)
        assertEquals(87, Crypto.b64(enc).length) // matches the relay's PUBKEY regex
        assertArrayEquals(enc, Crypto.encodePublic(Crypto.decodePublic(enc)))
        val offCurve = enc.copyOf().also { it[64] = (it[64] + 1).toByte() }
        assertTrue(runCatching { Crypto.decodePublic(offCurve) }.isFailure)
    }

    @Test fun hkdfMatchesRfc5869TestCase1() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val okm = Crypto.hkdf(hex("0b".repeat(22)), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test fun inviteIdIsRelayCompatible() {
        assertEquals(43, Crypto.inviteId(Crypto.randomBytes(16)).length)
    }

    @Test fun pairLinkRoundTrip() {
        val kp = Crypto.newKeyPair()
        val offer = PairOffer("a".repeat(22), pub(kp), Crypto.randomBytes(16), "아내", 123L)
        val link = offer.toLink()
        assertTrue(link.startsWith("https://jeiel85.github.io/find-my-wife-phone/pair/#"))
        assertEquals(offer, PairOffer.parse(link))
        assertEquals(offer, PairOffer.parse("fmwp://pair/#" + link.substringAfter('#')))
        assertEquals(offer, PairOffer.parse("fmwp://pair/" + link.substringAfter('#')))
        assertNull(PairOffer.parse("https://example.com"))
        assertNull(PairOffer.parse(null))
    }

    @Test fun messagesRoundTrip() {
        val st = PeerStatus(snoozeUntil = 99, locationPolicy = LocationPolicy.ASK, battery = 42, charging = true, appVersion = 305)
        val all = listOf(
            Msg.Hello("남편", "Galaxy S24", ack = false, status = st),
            Msg.Hello("아내", "", ack = true, status = null),
            Msg.Status(st),
            Msg.Ring("r1", st),
            Msg.RingCancel("r1"),
            Msg.RingUpdate("r1", RingState.FOUND, st),
            Msg.Locate("l1"),
            Msg.LocateUpdate("l1", LocateState.ASKING, null),
            Msg.LocateResult("l1", 37.5665, 126.978, 12.5f, 1000L, st),
            Msg.Unpair(),
        )
        all.forEach { m -> assertEquals(m, Msg.parse(m.toJson().toString())) }
    }

    @Test fun unknownOrBrokenMessagesAreIgnored() {
        assertNull(Msg.parse("""{"v":2,"id":"x","ts":1,"type":"ring","reqId":"r"}"""))
        assertNull(Msg.parse("""{"v":1,"id":"x","ts":1,"type":"teleport"}"""))
        assertNull(Msg.parse("""{"v":1,"id":"x","ts":1,"type":"locate-result","reqId":"r","lat":99,"lng":0,"fixAt":1}"""))
        assertNull(Msg.parse("not json"))
        val unknownState = Msg.parse("""{"v":1,"id":"x","ts":1,"type":"ring-state","reqId":"r","state":"???"}""")
        assertNotNull(unknownState)
        assertEquals(RingState.ERROR, (unknownState as Msg.RingUpdate).state)
    }

    /** 0.3.4 and older send no "ver": they read as 0, which the home screen shows as an old version. */
    @Test fun appVersionIsOptionalOnTheWire() {
        assertEquals(0, PeerStatus.fromJson(PeerStatus(battery = 50).toJson())!!.appVersion)
        assertTrue(!PeerStatus(appVersion = 0).toJson().has("ver"))
        assertEquals(305, PeerStatus.fromJson(PeerStatus(appVersion = 305).toJson())!!.appVersion)
        assertEquals("0.3.5", versionName(305))
    }

    @Test fun snoozeForeverIsSnoozed() {
        assertTrue(PeerStatus(snoozeUntil = PeerStatus.FOREVER).snoozed(System.currentTimeMillis()))
        assertTrue(!PeerStatus(snoozeUntil = 0).snoozed(System.currentTimeMillis()))
    }
}
