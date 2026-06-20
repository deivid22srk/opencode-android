package com.deivid.opencode.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the opencode release catalog from the GitHub releases API and
 * keeps only the assets that this app can actually install — i.e. ARM64
 * Linux builds that the bundled musl dynamic linker can run.
 *
 * API docs: https://docs.github.com/rest/releases/releases#list-releases
 */
class ReleasesRepository {

    suspend fun fetchReleases(): Result<List<GithubRelease>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/anomalyco/opencode/releases?per_page=15")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OpenCode-Android")
            try {
                val code = conn.responseCode
                if (code != 200) {
                    error("GitHub API returned HTTP $code")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseReleases(body)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseReleases(body: String): List<GithubRelease> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val assetsArr = obj.getJSONArray("assets")
            val assets = (0 until assetsArr.length()).map { j ->
                val a = assetsArr.getJSONObject(j)
                GithubAsset(
                    name = a.getString("name"),
                    size = a.getLong("size"),
                    downloadUrl = a.getString("browser_download_url"),
                    contentType = a.optString("content_type", "application/octet-stream"),
                )
            }
            GithubRelease(
                tagName = obj.getString("tag_name"),
                name = obj.optString("name", obj.getString("tag_name")),
                publishedAt = obj.getString("published_at"),
                body = obj.optString("body", ""),
                assets = assets,
            )
        }
    }
}

data class GithubRelease(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val body: String,
    val assets: List<GithubAsset>,
) {
    /**
     * The asset we can install on this device, in order of preference:
     *   1. opencode-linux-arm64-musl.tar.gz  — smallest, fewest external deps
     *   2. opencode-linux-arm64.tar.gz       — glibc-ish but still works with our musl linker
     *   3. opencode-linux-arm64*.zip         — same binary, different container
     *
     * Returns null if the release has no ARM64 build at all (e.g. a desktop-only release).
     */
    val compatibleAsset: GithubAsset?
        get() = assets.firstOrNull { it.name == "opencode-linux-arm64-musl.tar.gz" }
            ?: assets.firstOrNull { it.name == "opencode-linux-arm64.tar.gz" }
            ?: assets.firstOrNull { it.name.matches(Regex("opencode-linux-arm64.*\\.tar\\.gz")) }
            ?: assets.firstOrNull { it.name.matches(Regex("opencode-linux-arm64.*\\.zip")) }
}

data class GithubAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val contentType: String,
)
