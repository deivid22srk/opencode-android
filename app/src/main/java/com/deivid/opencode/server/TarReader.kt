package com.deivid.opencode.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal ustar/POSIX tar reader — enough to extract a single regular file
 * entry from a `.tar` stream. We avoid Apache Commons Compress to keep the
 * APK small; tar's on-disk format is simple enough that 100 lines do the job.
 *
 * Header layout (512 bytes):
 *   name        [0..100]
 *   mode        [100..108]
 *   uid         [108..116]
 *   gid         [116..124]
 *   size        [124..136]   (octal, ASCII)
 *   mtime       [136..148]
 *   checksum    [148..156]
 *   typeflag    [156..157]
 *   ...
 *   magic       [257..263]   ("ustar\0")
 */
internal class TarReader(private val stream: InputStream) : AutoCloseable {

    private val header = ByteArray(512)
    private val block = ByteArray(512)

    /** Iterate over entries; copy `name == targetName` to `out`. */
    fun extractFile(targetName: String, out: OutputStream): Boolean {
        while (true) {
            if (!readFully(header)) return false

            // Two consecutive zero blocks = end of archive
            if (header.all { it == 0.toByte() }) return false

            val name = readString(header, 0, 100)
            val size = readOctal(header, 124, 12)
            val typeflag = header[156]

            // Skip directories / extended headers / PAX records
            val isRegular = typeflag == '0'.code.toByte() ||
                typeflag == 0.toByte() ||
                typeflag == '\u0000'.code.toByte()

            val baseName = name.substringAfterLast('/')
            if (isRegular && (baseName == targetName || name == targetName)) {
                copyN(stream, out, size)
                skipPadding(size)
                return true
            }

            // Skip entry data
            skipPadding(size, skipData = size)
        }
    }

    override fun close() = stream.close()

    private fun readFully(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        var s = readString(buf, off, len).trim()
        if (s.isEmpty()) return 0
        // Some tar writers pad with spaces instead of NULs
        s = s.takeWhile { it in '0'..'7' }
        return s.toLongOrNull(8) ?: 0L
    }

    private fun copyN(input: InputStream, output: OutputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val toRead = if (remaining > block.size) block.size.toLong() else remaining
            val n = input.read(block, 0, toRead.toInt())
            if (n <= 0) throw IOException("Unexpected EOF in tar entry")
            output.write(block, 0, n)
            remaining -= n
        }
    }

    private fun skipPadding(entrySize: Long, skipData: Long = 0L) {
        var toSkip = skipData
        // Already-skipped data still needs to be padded to 512 boundary
        val total = entrySize
        val padding = (512 - (total % 512)) % 512
        toSkip += padding
        while (toSkip > 0) {
            val n = stream.skip(toSkip)
            if (n <= 0) break
            toSkip -= n
        }
    }
}
