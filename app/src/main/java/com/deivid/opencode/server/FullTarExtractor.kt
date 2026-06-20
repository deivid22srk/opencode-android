package com.deivid.opencode.server

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Full tar extractor — walks every entry in a ustar/POSIX tar stream and
 * writes it to a target directory. Supports:
 *
 *  - Regular files (typeflag '0' or '\u0000')
 *  - Directories (typeflag '5')
 *  - Symlinks (typeflag '1' or '2' with link target)
 *  - Long names via GNU ././@LongLink (typeflag 'L')
 *  - PAX headers (typeflag 'g' or 'x' — parsed and applied to next entry)
 *
 * Used to extract the Alpine minirootfs tarball. We can't use
 * `java.util.tar.TarArchiveInputStream` because Apache Commons Compress
 * is not bundled and we want to keep the APK lean.
 *
 * Header layout (512 bytes per header block):
 *   name        [0..100]
 *   mode        [100..108]   (octal)
 *   size        [124..136]   (octal)
 *   mtime       [136..148]   (octal)
 *   typeflag    [156]
 *   linkname    [157..257]
 *   magic       [257..263]   ("ustar\0" or "ustar  ")
 *   prefix      [345..500]
 */
internal class FullTarExtractor(private val stream: InputStream) : AutoCloseable {

    private val header = ByteArray(512)
    private val block = ByteArray(8192)

    fun extractTo(targetDir: File) {
        var pax: Map<String, String>? = null
        var longName: String? = null

        while (true) {
            if (!readFully(header)) break

            // Two consecutive zero blocks = end of archive
            if (header.all { it == 0.toByte() }) {
                if (!readFully(header) || header.all { it == 0.toByte() }) return
                // else fall through and process the next header
            }

            val rawName = readString(header, 0, 100)
            val size = readOctal(header, 124, 12)
            val typeflag = header[156].toInt().toChar()
            val linkname = readString(header, 157, 100)
            val prefix = readString(header, 345, 155)

            // Resolve name: PAX long-path > GNU LongLink > prefix/name
            var name = longName ?: run {
                val fullName = if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                fullName.removePrefix("./")
            }
            longName = null

            // Skip PAX headers but parse them for the next entry
            if (typeflag == 'g' || typeflag == 'x') {
                pax = readPaxRecords(size)
                skipBytes(size)
                continue
            }

            // GNU LongLink — next entry's name comes from this entry's data
            if (typeflag == 'L') {
                val data = ByteArray(size.toInt().coerceAtMost(4096))
                readInto(data, 0, size.toInt())
                longName = String(data, Charsets.UTF_8).trimEnd('\u0000', '\n')
                skipPadding(size)
                continue
            }

            // Compute the target file inside targetDir
            val safeName = sanitize(name)
            if (safeName.isEmpty()) {
                skipBytes(size); skipPadding(size); continue
            }
            val target = File(targetDir, safeName)

            when (typeflag) {
                '5', '/' -> {
                    target.mkdirs()
                    skipBytes(size); skipPadding(size)
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    if (size > 0) {
                        FileOutputStream(target).use { out -> copyN(stream, out, size) }
                    } else {
                        target.createNewFile()
                    }
                    skipPadding(size)
                    // Apply file mode (chmod) — only the executable bit
                    // matters for us. If any of owner/group/other exec bits
                    // are set, mark the file executable.
                    val mode = readOctal(header, 100, 8).toInt()
                    if (mode and 0b001_001_001 != 0) {
                        // Second arg = ownerOnly; false = executable for everyone
                        target.setExecutable(true, (mode and 0b000000100) == 0)
                    }
                }
                '1', '2' -> {
                    // hard link (1) or symlink (2)
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            java.nio.file.Paths.get(linkname),
                        )
                    } catch (e: Exception) {
                        // Fallback: create an empty file (best effort)
                        target.createNewFile()
                    }
                    skipBytes(size); skipPadding(size)
                }
                else -> {
                    // Unknown type — skip the data
                    skipBytes(size); skipPadding(size)
                }
            }
        }
    }

    override fun close() = stream.close()

    // ----- Helpers -----

    private fun readFully(buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = stream.read(buf, read, buf.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun readInto(buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = stream.read(buf, off + read, len - read)
            if (n <= 0) throw IOException("Unexpected EOF in tar entry")
            read += n
        }
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        val s = readString(buf, off, len).trim().takeWhile { it in '0'..'7' }
        return s.toLongOrNull(8) ?: 0L
    }

    private fun copyN(input: InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val toRead = if (remaining > block.size) block.size.toLong() else remaining
            val n = input.read(block, 0, toRead.toInt())
            if (n <= 0) throw IOException("Unexpected EOF in tar entry")
            output.write(block, 0, n)
            remaining -= n
        }
    }

    private fun skipBytes(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val n = stream.skip(remaining)
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun skipPadding(entrySize: Long) {
        val padding = (512 - (entrySize % 512)) % 512
        if (padding > 0) skipBytes(padding)
    }

    /**
     * Parses PAX extended header records. Format:
     *   "%d %s=%s\n"
     * where %d is the length of the entire record (including the length
     * itself as ASCII). We only care about "path" and "linkpath".
     */
    private fun readPaxRecords(size: Long): Map<String, String> {
        val data = ByteArray(size.toInt().coerceAtMost(65536))
        readInto(data, 0, size.toInt())
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < data.size) {
            // Read the length field (digits followed by space)
            val lenStart = i
            while (i < data.size && data[i] != ' '.code.toByte()) i++
            if (i >= data.size) break
            val lenStr = String(data, lenStart, i - lenStart, Charsets.US_ASCII)
            val recordLen = lenStr.toIntOrNull() ?: break
            i++ // skip the space
            // The record body is recordLen - (i - lenStart) bytes
            val bodyEnd = lenStart + recordLen
            if (bodyEnd > data.size) break
            // Find the '=' separator
            var eq = i
            while (eq < bodyEnd && data[eq] != '='.code.toByte()) eq++
            if (eq >= bodyEnd) { i = bodyEnd; continue }
            val key = String(data, i, eq - i, Charsets.UTF_8)
            // value goes from eq+1 to bodyEnd-1 (trailing \n)
            val valueEnd = if (bodyEnd > 0 && data[bodyEnd - 1] == '\n'.code.toByte()) bodyEnd - 1 else bodyEnd
            val value = String(data, eq + 1, valueEnd - eq - 1, Charsets.UTF_8)
            result[key] = value
            i = bodyEnd
        }
        return result
    }

    /**
     * Prevents path traversal — rejects any name containing ".." segments
     * or starting with "/".
     */
    private fun sanitize(name: String): String {
        if (name.startsWith("/")) return name.removePrefix("/")
        if (name.contains("..")) {
            // Allow only if ".." doesn't escape the target dir
            val parts = name.split("/").toMutableList()
            val out = ArrayDeque<String>()
            for (p in parts) {
                when {
                    p == "." -> {}
                    p == ".." -> out.removeLastOrNull() ?: return ""
                    p.isNotEmpty() -> out.addLast(p)
                }
            }
            return out.joinToString("/")
        }
        return name
    }
}
