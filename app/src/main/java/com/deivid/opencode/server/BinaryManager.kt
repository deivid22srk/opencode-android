package com.deivid.opencode.server

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream

/**
 * Layout of files managed by the app:
 *
 *   filesDir/opencode/
 *     ├── bin/opencode                <- user-imported ARM64 executable
 *     ├── lib/ld-musl-aarch64.so.1    <- musl dynamic linker (bundled)
 *     ├── lib/libc.musl-aarch64.so.1  <- musl libc (symlink to ld-musl)
 *     ├── lib/libstdc++.so.6          <- GNU C++ stdlib (bundled)
 *     ├── lib/libgcc_s.so.1           <- GCC support lib (bundled)
 *     ├── config/                     <- opencode user config
 *     └── logs/server.log
 */
object Paths {
    const val SUBDIR = "opencode"

    fun root(ctx: Context) = File(ctx.filesDir, SUBDIR)
    fun binDir(ctx: Context) = File(root(ctx), "bin")
    fun libDir(ctx: Context) = File(root(ctx), "lib")
    fun configDir(ctx: Context) = File(root(ctx), "config")
    fun logsDir(ctx: Context) = File(root(ctx), "logs")
    fun binary(ctx: Context) = File(binDir(ctx), "opencode")
    fun linker(ctx: Context) = File(libDir(ctx), "ld-musl-aarch64.so.1")
    fun logFile(ctx: Context) = File(logsDir(ctx), "server.log")
}

data class BinaryInfo(
    val path: File,
    val version: String,
    val sizeBytes: Long,
    val importedAt: Long,
)

class BinaryManager(private val context: Context) {

    /**
     * Ensures the musl runtime libraries have been copied out of the APK
     * `assets/musl/` directory into the app's private files dir, where they
     * can be chmod-ed executable and loaded by the dynamic linker.
     *
     * This is required because Android's Bionic libc is incompatible with
     * Linux/glibc/musl binaries — we need to ship musl itself.
     */
    fun ensureRuntime(): Result<Unit> = runCatching {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            error("This device has no arm64-v8a ABI; opencode ARM64 cannot run here.")
        }

        val libDir = Paths.libDir(context)
        libDir.mkdirs()

        val required = listOf(
            "ld-musl-aarch64.so.1" to "ld-musl-aarch64.so.1",
            "libstdc++.so.6" to "libstdc++.so.6",
            "libgcc_s.so.1" to "libgcc_s.so.1",
        )

        for ((asset, target) in required) {
            val out = File(libDir, target)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("musl/$asset").use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (!out.setExecutable(true, true)) error("Failed to chmod $target")
        }

        // musl libc lives inside the same file as the dynamic linker — the
        // canonical libc.musl-aarch64.so.1 is a symlink to ld-musl-aarch64.so.1
        val libcSymlink = File(libDir, "libc.musl-aarch64.so.1")
        if (!libcSymlink.exists()) {
            try {
                libcSymlink.delete()
                val target = File(libDir, "ld-musl-aarch64.so.1")
                // Prefer symlink (saves space); fall back to copy on filesystems
                // that don't support them.
                val created = try {
                    @Suppress("UnsafeNewApiCall")
                    java.nio.file.Files.createSymbolicLink(
                        libcSymlink.toPath(),
                        target.toPath(),
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                if (!created) {
                    target.inputStream().use { input ->
                        FileOutputStream(libcSymlink).use { output -> input.copyTo(output) }
                    }
                    libcSymlink.setExecutable(true, true)
                }
            } catch (e: IOException) {
                // ignore — the linker may still resolve libc from the same file
            }
        }
    }

    /**
     * Imports a user-selected release file. Accepts:
     *  - opencode-linux-arm64*.tar.gz
     *  - opencode-linux-arm64*.zip
     *  - the bare `opencode` executable
     */
    fun importFromUri(uri: android.net.Uri): Result<BinaryInfo> = runCatching {
        ensureRuntime().getOrThrow()

        // Stage into a temp file because DocumentFile streams aren't seekable.
        val tmp = File(context.cacheDir, "import-${System.currentTimeMillis()}.bin")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }

        try {
            val binary = extractAndInstall(tmp)
            binary
        } finally {
            tmp.delete()
        }
    }

    private fun extractAndInstall(staged: File): BinaryInfo {
        val binDir = Paths.binDir(context).apply { mkdirs() }
        val out = Paths.binary(context)
        if (out.exists()) out.delete()

        // Read first 4 bytes of the file to sniff the format.
        // We can't use InputStream.readNBytes(int) because it requires Java 11
        // (Android API 33+); the manual read works on every API level.
        val magic = ByteArray(4)
        staged.inputStream().use { input ->
            var read = 0
            while (read < magic.size) {
                val n = input.read(magic, read, magic.size - read)
                if (n <= 0) break
                read += n
            }
        }

        when {
            // gzip magic 0x1f 0x8b
            magic.size >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() -> {
                extractTarGz(staged, out)
            }
            // ZIP magic 'PK'
            magic.size >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() -> {
                extractZip(staged, out)
            }
            // ELF magic 0x7f 'E' 'L' 'F' — treat as bare executable
            magic.size >= 4 && magic[0] == 0x7f.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte() -> {
                staged.inputStream().use { input ->
                    FileOutputStream(out).use { o -> input.copyTo(o) }
                }
            }
            else -> error("Unrecognised file format. Expected .tar.gz, .zip, or an ELF binary.")
        }

        if (!out.exists() || out.length() < 1_000_000) {
            error("Extracted file is too small or missing. The release archive may be corrupt.")
        }
        if (!out.setExecutable(true, true)) error("Failed to chmod opencode binary")

        val version = readVersion(out)
        return BinaryInfo(
            path = out,
            version = version,
            sizeBytes = out.length(),
            importedAt = System.currentTimeMillis(),
        )
    }

    private fun extractTarGz(archive: File, outBinary: File) {
        GZIPInputStream(archive.inputStream()).use { gz ->
            TarReader(gz).use { tar ->
                if (!tar.extractFile("opencode", FileOutputStream(outBinary))) {
                    error("opencode binary not found in tar.gz archive")
                }
            }
        }
    }

    private fun extractZip(archive: File, outBinary: File) {
        ZipInputStream(archive.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                if (name == "opencode" && !entry.isDirectory) {
                    FileOutputStream(outBinary).use { o -> zip.copyTo(o) }
                    return
                }
                entry = zip.nextEntry
            }
        }
        error("opencode binary not found in zip archive")
    }

    /**
     * Runs `opencode --version` through the musl dynamic linker to verify
     * the binary works on this device and to capture its version string.
     */
    private fun readVersion(binary: File): String {
        return try {
            val linker = Paths.linker(context)
            val libDir = Paths.libDir(context)
            val pb = ProcessBuilder(
                linker.absolutePath,
                "--library-path",
                libDir.absolutePath,
                binary.absolutePath,
                "--version",
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            // opencode prints its version on the first line
            out.lineSequence().firstOrNull()?.trim() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun currentBinary(): BinaryInfo? {
        val b = Paths.binary(context)
        if (!b.exists() || !b.canExecute()) return null
        return BinaryInfo(
            path = b,
            version = "imported",
            sizeBytes = b.length(),
            importedAt = b.lastModified(),
        )
    }

    fun deleteBinary() {
        Paths.binary(context).delete()
    }
}
