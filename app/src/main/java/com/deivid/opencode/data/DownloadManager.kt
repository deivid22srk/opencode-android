package com.deivid.opencode.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streaming HTTP downloader with progress reporting.
 *
 * GitHub release downloads redirect (`Location: https://objects.githubusercontent.com/...`)
 * so we MUST follow redirects — `HttpURLConnection.instanceFollowRedirects = true`
 * does this transparently.
 */
class DownloadManager(private val context: Context) {

    /**
     * Downloads [url] to [targetFile] and reports progress via [onProgress].
     *
     * @param onProgress called with a fraction in `0f..1f`, or `-1f` when the
     *   server didn't send a Content-Length (unknown total). The callback is
     *   dispatched on Dispatchers.Main so it's safe to update Compose state.
     */
    suspend fun download(
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "OpenCode-Android")
            conn.instanceFollowRedirects = true
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("HTTP $code downloading $url")
                }
                val total = conn.contentLengthLong.let { if (it > 0) it else -1L }
                targetFile.parentFile?.mkdirs()
                var downloaded = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            // Report at most every 256KB to avoid flooding
                            if (downloaded - lastReport >= 256 * 1024 || total < 0) {
                                val pct = if (total > 0) downloaded.toFloat() / total else -1f
                                withContext(Dispatchers.Main) { onProgress(pct) }
                                lastReport = downloaded
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { onProgress(1f) }
                targetFile
            } finally {
                conn.disconnect()
            }
        }
    }
}
