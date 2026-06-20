package com.deivid.opencode.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("assets") val assets: List<GitHubAsset>,
    @SerialName("prerelease") val prerelease: Boolean = false,
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long,
)

data class CompatibleRelease(
    val version: String,
    val publishedAt: String,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
)
