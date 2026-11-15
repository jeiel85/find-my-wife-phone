package io.github.jeiel85.findmywifephone.core

import io.github.jeiel85.findmywifephone.crypto.Crypto
import org.json.JSONObject

/**
 * What the QR code carries. The link opens the landing page's pairing helper when the app is not
 * installed; the payload sits in the fragment so it never reaches a server.
 */
data class PairOffer(
    val deviceId: String,
    val publicKey: ByteArray,
    val secret: ByteArray,
    val name: String,
    val expiresAt: Long,
) {
    fun toLink(): String = "$LINK_PREFIX${Crypto.b64(toJson().toString().toByteArray())}"

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("id", deviceId)
        .put("pub", Crypto.b64(publicKey))
        .put("s", Crypto.b64(secret))
        .put("n", name)
        .put("exp", expiresAt)

    override fun equals(other: Any?) = other is PairOffer && other.deviceId == deviceId &&
        other.publicKey.contentEquals(publicKey) && other.secret.contentEquals(secret) &&
        other.name == name && other.expiresAt == expiresAt

    override fun hashCode() = deviceId.hashCode()

    companion object {
        const val LINK_PREFIX = "https://jeiel85.github.io/find-my-wife-phone/pair/#"
        private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{22}$")

        /** Accepts the https link, the `fmwp://pair/…` hand-off from the web page, or the bare payload. */
        fun parse(text: String?): PairOffer? {
            val raw = text?.trim() ?: return null
            val payload = when {
                raw.startsWith(LINK_PREFIX) -> raw.removePrefix(LINK_PREFIX)
                raw.startsWith("fmwp://pair/#") -> raw.removePrefix("fmwp://pair/#")
                raw.startsWith("fmwp://pair/") -> raw.removePrefix("fmwp://pair/")
                else -> raw
            }
            return try {
                val o = JSONObject(String(Crypto.unb64(payload)))
                require(o.getInt("v") == 1)
                val id = o.getString("id").also { require(DEVICE_ID.matches(it)) }
                val pub = Crypto.unb64(o.getString("pub")).also { Crypto.decodePublic(it) }
                val secret = Crypto.unb64(o.getString("s")).also { require(it.size == 16) }
                PairOffer(id, pub, secret, o.optString("n").take(40), o.getLong("exp"))
            } catch (_: Exception) {
                null
            }
        }
    }
}
