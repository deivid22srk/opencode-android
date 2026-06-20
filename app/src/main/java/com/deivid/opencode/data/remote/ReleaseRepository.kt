package com.deivid.opencode.data.remote

import com.deivid.opencode.model.CompatibleRelease
import com.deivid.opencode.model.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class ReleaseRepository {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchCompatibleReleases(): Result<List<CompatibleRelease>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/anomalyco/opencode/releases")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "opencode-android")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val response = conn.inputStream.bufferedReader().readText()
            val releases = json.decodeFromString<List<GitHubRelease>>(response)

            releases.flatMap { release ->
                release.assets
                    .filter { asset -> isCompatible(asset.name) }
                    .map { asset ->
                        CompatibleRelease(
                            version = release.tagName,
                            publishedAt = release.publishedAt,
                            downloadUrl = asset.browserDownloadUrl,
                            fileName = asset.name,
                            sizeBytes = asset.size,
                        )
                    }
            }
        }
    }

    private fun isCompatible(assetName: String): Boolean {
        val lower = assetName.lowercase()
        return lower.contains("arm64") && lower.contains("musl") && lower.endsWith(".tar.gz")
    }
}
