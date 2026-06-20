package com.deivid.opencode.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data classes representing a GitHub release and its downloadable assets.
 */
data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val isMusl: Boolean,
) {
    val sizeMB: Float get() = sizeBytes / 1_000_000f
    val isCompatible: Boolean
        get() = name.contains("linux-arm64", ignoreCase = true)
}

data class GithubRelease(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val body: String?,
    val assets: List<ReleaseAsset>,
) {
    /** Only assets that are compatible with Android ARM64 (linux-arm64). */
    val compatibleAssets: List<ReleaseAsset>
        get() = assets.filter { it.isCompatible }

    /** The recommended musl asset, or the first compatible one. */
    val recommendedAsset: ReleaseAsset?
        get() = compatibleAssets.find { it.isMusl } ?: compatibleAssets.firstOrNull()
}

/**
 * Fetches opencode releases from the GitHub API and optionally downloads
 * a release asset to a local file.
 *
 * All network I/O runs on [Dispatchers.IO].
 */
class ReleaseFetcher {

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/anomalyco/opencode/releases?per_page=10"
    }

    /**
     * Returns a list of [GithubRelease] objects, each pre-filtered to
     * contain only ARM64-compatible assets.
     */
    suspend fun fetchReleases(): Result<List<GithubRelease>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                error("GitHub API returned $code: $err")
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            parseReleases(body)
        }
    }

    /**
     * Downloads a release asset from [downloadUrl] into [targetFile].
     * Calls [onProgress] periodically with (bytesDownloaded, totalBytes).
     * Total bytes may be -1 if the server doesn't report Content-Length.
     */
    suspend fun downloadRelease(
        downloadUrl: String,
        targetFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var redirectCount = 0
            var currentUrl = downloadUrl

            // GitHub release downloads redirect through several CDN URLs
            while (redirectCount < 10) {
                val conn = URL(currentUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = false

                when (conn.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val loc = conn.getHeaderField("Location")
                            ?: error("Redirect without Location header")
                        conn.disconnect()
                        currentUrl = loc
                        redirectCount++
                        continue
                    }
                    200 -> {
                        val totalBytes = conn.contentLength.toLong()
                        val input = conn.inputStream
                        targetFile.outputStream().buffered(8192).use { output ->
                            val buffer = ByteArray(8192)
                            var downloaded = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                downloaded += n
                                onProgress(downloaded, if (totalBytes > 0) totalBytes else -1L)
                            }
                        }
                        input.close()
                        conn.disconnect()
                        return@withContext
                    }
                    else -> {
                        val err = conn.errorStream?.bufferedReader()?.readText()
                            ?: "HTTP ${conn.responseCode}"
                        conn.disconnect()
                        error("Download failed: $err")
                    }
                }
            }
            error("Too many redirects")
        }
    }

    // ------------------------------------------------------------------
    // JSON parsing (minimal — no external dependency)
    // ------------------------------------------------------------------

    private fun parseReleases(json: String): List<GithubRelease> {
        val releases = mutableListOf<GithubRelease>()
        val arr = org.json.JSONArray(json)

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val tagName = obj.getString("tag_name")
            val name = obj.optString("name", tagName)
            val publishedAt = obj.getString("published_at")
            val body = if (obj.has("body") && !obj.isNull("body")) obj.getString("body") else null

            val assetsArr = obj.getJSONArray("assets")
            val assets = mutableListOf<ReleaseAsset>()
            for (j in 0 until assetsArr.length()) {
                val a = assetsArr.getJSONObject(j)
                val assetName = a.getString("name")
                assets.add(
                    ReleaseAsset(
                        name = assetName,
                        sizeBytes = a.getLong("size"),
                        downloadUrl = a.getString("browser_download_url"),
                        isMusl = assetName.contains("musl", ignoreCase = true),
                    )
                )
            }

            // Only include releases that have at least one ARM64 asset
            val compatibleAssets = assets.filter { it.isCompatible }
            if (compatibleAssets.isNotEmpty()) {
                releases.add(
                    GithubRelease(
                        tagName = tagName,
                        name = name,
                        publishedAt = publishedAt,
                        body = body,
                        assets = assets,
                    )
                )
            }
        }
        return releases
    }
}