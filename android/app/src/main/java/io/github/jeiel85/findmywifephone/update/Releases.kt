package io.github.jeiel85.findmywifephone.update

import org.json.JSONObject

/** One GitHub release that carries an installable APK. */
data class Release(
    val version: String,
    val apkUrl: String,
    /** Lower-case hex SHA-256 of the APK; an update without one is never installed. */
    val sha256: String,
    val size: Long,
    val notes: String,
    val pageUrl: String,
)

/** Pure helpers, unit-tested without Android (see ReleasesTest). */
object Releases {
    const val ASSET = "find-my-wife-phone.apk"
    private val NOTES_SHA = Regex("""APK SHA-256\*\*\s*`([0-9a-fA-F]{64})`""")
    private val DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    /**
     * Parses `GET /repos/{owner}/{repo}/releases/latest`. The checksum comes from the asset's
     * `digest` (GitHub computes it on upload) or, for older releases, from the line release.sh
     * writes into the notes. Drafts, pre-releases and releases without the APK are ignored.
     */
    fun parse(json: String): Release? = try {
        val o = JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) null else {
            val version = o.getString("tag_name").removePrefix("v")
            require(parseVersion(version) != null)
            val assets = o.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name") == ASSET }
            val notes = o.optString("body")
            val sha = DIGEST.find(asset.optString("digest"))?.groupValues?.get(1)
                ?: NOTES_SHA.find(notes)?.groupValues?.get(1)
            val url = asset.getString("browser_download_url")
            require(url.startsWith("https://github.com/"))
            if (sha == null) null else Release(
                version = version,
                apkUrl = url,
                sha256 = sha.lowercase(),
                size = asset.optLong("size"),
                notes = notes.substringBefore("\n---").trim(),
                pageUrl = o.optString("html_url"),
            )
        }
    } catch (_: Exception) {
        null
    }

    /** "1.2.3" → [1, 2, 3]; anything else (suffixes, missing parts) → null. */
    fun parseVersion(v: String): List<Int>? =
        v.split('.').takeIf { it.size == 3 }?.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }

    /** true when [candidate] is a strictly newer x.y.z than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
