package io.github.jeiel85.findmywifephone.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto between two paired phones (design/PROTOCOL.md §암호화).
 *
 * pairKey = HKDF-SHA256(ikm = ECDH-P256(mine, theirs), salt = pairSecret, info = "fmwp pair v1")
 * envelope = base64url(0x01 ‖ nonce(12) ‖ AES-256-GCM(pairKey, nonce, json, aad = "fmwp1|from|to"))
 *
 * The pair secret only ever travels inside the QR code, so the relay — which sees both public
 * keys — still cannot derive the key or swap a key in the middle.
 */
object Crypto {
    private const val VERSION: Byte = 1
    private const val NONCE = 12
    private val random = SecureRandom()

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    /** 65-byte uncompressed point (0x04 ‖ X ‖ Y). */
    fun encodePublic(key: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    fun decodePublic(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        // KeyFactory does not check this everywhere; an off-curve point enables invalid-curve attacks on ECDH.
        val field = p256.curve.field as java.security.spec.ECFieldFp
        val p = field.p
        require(x < p && y < p) { "coordinate out of range" }
        val rhs = x.pow(3).add(p256.curve.a.multiply(x)).add(p256.curve.b).mod(p)
        require(y.pow(2).mod(p) == rhs) { "point is not on P-256" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256)) as ECPublicKey
    }

    fun encodePrivate(key: PrivateKey): ByteArray = key.encoded
    fun decodePrivate(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun pairKey(mine: PrivateKey, theirs: ECPublicKey, pairSecret: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(mine)
            doPhase(theirs, true)
            generateSecret()
        }
        return hkdf(shared, pairSecret, "fmwp pair v1".toByteArray(), 32)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun aad(from: String, to: String): ByteArray = "fmwp1|$from|$to".toByteArray()

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): String {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
        return b64(byteArrayOf(VERSION) + nonce + cipher.doFinal(plain))
    }

    /** null when the envelope is malformed, from another key, or tampered with. */
    fun open(key: ByteArray, envelope: String, aad: ByteArray): ByteArray? = try {
        val bytes = unb64(envelope)
        require(bytes.size > 1 + NONCE + 16 && bytes[0] == VERSION)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 1, NONCE))
            updateAAD(aad)
            doFinal(bytes, 1 + NONCE, bytes.size - 1 - NONCE)
        }
    } catch (_: Exception) {
        null
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Relay-side id of an invite: the relay learns the hash, never the secret. */
    fun inviteId(pairSecret: ByteArray): String = b64(sha256(pairSecret))

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}
