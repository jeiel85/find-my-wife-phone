package io.github.jeiel85.findmywifephone.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of a relay call. [status] 0 means the request never got an HTTP answer. */
sealed class RelayResult {
    data class Ok(val body: JSONObject) : RelayResult()
    data class Fail(val status: Int, val code: String) : RelayResult()

    val ok get() = this is Ok
}

/** Thin blocking client for the relay API (design/PROTOCOL.md §중계 API). Call off the main thread. */
class Relay(private val baseUrl: String) {

    fun register(fcmToken: String) = call("POST", "/v1/devices", null, JSONObject().put("fcmToken", fcmToken))
    fun updateFcmToken(token: String, fcmToken: String) = call("PUT", "/v1/devices/me", token, JSONObject().put("fcmToken", fcmToken))
    fun deleteDevice(token: String) = call("DELETE", "/v1/devices/me", token, null)
    fun createInvite(token: String, inviteId: String, ttlSec: Int) =
        call("POST", "/v1/invites", token, JSONObject().put("inviteId", inviteId).put("ttlSec", ttlSec))
    fun join(token: String, inviteId: String, pub: String, env: String) =
        call("POST", "/v1/invites/$inviteId/join", token, JSONObject().put("pub", pub).put("env", env))
    fun send(token: String, to: String, env: String, ttlSec: Int) =
        call("POST", "/v1/messages", token, JSONObject().put("to", to).put("env", env).put("ttlSec", ttlSec))
    fun unlink(token: String, peerId: String) = call("DELETE", "/v1/links/$peerId", token, null)

    private fun call(method: String, path: String, token: String?, body: JSONObject?): RelayResult {
        val conn = try {
            URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return RelayResult.Fail(0, "network")
        }
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("accept", "application/json")
            token?.let { conn.setRequestProperty("authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status in 200..299) RelayResult.Ok(json) else RelayResult.Fail(status, json.optString("error", "http_$status"))
        } catch (e: IOException) {
            RelayResult.Fail(0, "network")
        } finally {
            conn.disconnect()
        }
    }
}
