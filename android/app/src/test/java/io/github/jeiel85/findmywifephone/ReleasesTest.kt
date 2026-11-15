package io.github.jeiel85.findmywifephone

import io.github.jeiel85.findmywifephone.update.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {

    private val sha = "ab".repeat(32)
    private val other = "cd".repeat(32)

    private fun release(
        tag: String = "v0.2.0",
        assetName: String = "find-my-wife-phone.apk",
        digest: String? = "sha256:$sha",
        body: String = "- 고친 것\n\n---\n**APK SHA-256** `$other`\n",
        url: String = "https://github.com/jeiel85/find-my-wife-phone/releases/download/v0.2.0/find-my-wife-phone.apk",
        prerelease: Boolean = false,
    ) = """
        {"tag_name":"$tag","draft":false,"prerelease":$prerelease,
         "html_url":"https://github.com/jeiel85/find-my-wife-phone/releases/tag/$tag",
         "body":${org.json.JSONObject.quote(body)},
         "assets":[{"name":"other.txt","browser_download_url":"https://github.com/x","size":1},
                   {"name":"$assetName","size":4242,"browser_download_url":"$url"${if (digest != null) ",\"digest\":\"$digest\"" else ""}}]}
    """.trimIndent()

    @Test fun parsesVersionAssetAndDigest() {
        val r = Releases.parse(release())!!
        assertEquals("0.2.0", r.version)
        assertEquals(sha, r.sha256)
        assertEquals(4242L, r.size)
        assertEquals("- 고친 것", r.notes)
        assertTrue(r.apkUrl.endsWith("/v0.2.0/find-my-wife-phone.apk"))
    }

    @Test fun fallsBackToTheChecksumInTheNotes() {
        assertEquals(other, Releases.parse(release(digest = null))!!.sha256)
    }

    @Test fun refusesReleasesItCannotVerifyOrUse() {
        assertNull("no checksum anywhere", Releases.parse(release(digest = null, body = "notes only")))
        assertNull("no APK asset", Releases.parse(release(assetName = "app.apk")))
        assertNull("pre-release", Releases.parse(release(prerelease = true)))
        assertNull("odd tag", Releases.parse(release(tag = "nightly")))
        assertNull("download outside github.com", Releases.parse(release(url = "https://evil.example/app.apk")))
        assertNull("garbage", Releases.parse("not json"))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(Releases.isNewer("0.1.10", "0.1.9"))
        assertTrue(Releases.isNewer("1.0.0", "0.9.9"))
        assertFalse(Releases.isNewer("0.1.0", "0.1.0"))
        assertFalse(Releases.isNewer("0.1.0", "0.2.0"))
        assertFalse(Releases.isNewer("0.2", "0.1.0"))
        assertFalse(Releases.isNewer("0.2.0-beta", "0.1.0"))
    }
}
