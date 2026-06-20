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
 *     ├── lib/libc.musl-aarch64.so.1  <- symlink to nativeLibraryDir/libopencode-musl.so
 *     ├── lib/libstdc++.so.6          <- GNU C++ stdlib (bundled in assets)
 *     ├── lib/libgcc_s.so.1           <- GCC support lib (bundled in assets)
 *     ├── config/                     <- opencode user config
 *     └── logs/server.log
 *
 *   nativeLibraryDir  (= /data/app/<pkg>-<hash>/lib/arm64/)
 *     └── libopencode-musl.so         <- musl dynamic linker (BUNDLED IN APK
 *                                        as jniLibs/arm64-v8a/libopencode-musl.so)
 *
 * On Android 10+ a SELinux `neverallow` rule (b/112357170) blocks any
 * `execve()` on files in `filesDir` — they are labeled `app_data_file`
 * and `app_data_file:file execute_no_trans` is forbidden for untrusted
 * apps. So we cannot execve the musl linker from there.
 *
 * Instead we ship the linker inside the APK as a jniLib. The package
 * installer extracts it to `nativeLibraryDir` at install time, where it
 * gets labeled `apk_data_file` and execve() works.
 *
 * The other libs (libc.musl-aarch64.so.1, libstdc++.so.6, libgcc_s.so.1)
 * only need to be `mmap(PROT_EXEC)`-ed by the linker — that uses the
 * `execute` SELinux permission, which IS allowed on `app_data_file`. So
 * they can stay in filesDir/lib.
 *
 * Reference:
 *   https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md
 */
object Paths {
    const val SUBDIR = "opencode"

    fun root(ctx: Context) = File(ctx.filesDir, SUBDIR)
    fun binDir(ctx: Context) = File(root(ctx), "bin")
    fun libDir(ctx: Context) = File(root(ctx), "lib")
    fun configDir(ctx: Context) = File(root(ctx), "config")
    fun logsDir(ctx: Context) = File(root(ctx), "logs")
    fun binary(ctx: Context) = File(binDir(ctx), "opencode")
    fun logFile(ctx: Context) = File(logsDir(ctx), "server.log")

    /**
     * Default workspace directory — the project root opencode will operate on
     * when no `x-opencode-directory` header is sent by the client.
     */
    fun workspaceDir(ctx: Context) = File(root(ctx), "workspace")

    /**
     * Absolute path of the musl dynamic linker, as installed by Android's
     * package installer. Returns null on devices where the linker is not
     * present (e.g. non-arm64 or APK built without the bundled linker).
     */
    fun muslLinker(ctx: Context): File? {
        val dir = ctx.applicationInfo.nativeLibraryDir ?: return null
        val f = File(dir, "libopencode-musl.so")
        return if (f.exists() && f.canExecute()) f else null
    }
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
     * `assets/musl/` directory into the app's private files dir.
     *
     * NOTE: We do NOT ship the musl dynamic linker (`ld-musl-aarch64.so.1`)
     * here. It is shipped as `jniLibs/arm64-v8a/libopencode-musl.so` instead,
     * so the installer extracts it into `nativeLibraryDir` where it's
     * execve()-able. See the `Paths` doc for the full rationale.
     *
     * The remaining libs (libc.musl-aarch64.so.1, libstdc++.so.6, libgcc_s.so.1)
     * only need to be `mmap(PROT_EXEC)`-ed by the linker, which uses the
     * `execute` SELinux permission — that IS allowed on `app_data_file`.
     */
    fun ensureRuntime(): Result<Unit> = runCatching {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            error("This device has no arm64-v8a ABI; opencode ARM64 cannot run here.")
        }

        val libDir = Paths.libDir(context)
        libDir.mkdirs()

        // The musl linker must already be present in nativeLibraryDir
        // (it's bundled in the APK, not extracted from assets).
        val linkerFile = Paths.muslLinker(context)
            ?: error(
                "musl dynamic linker (libopencode-musl.so) not found in " +
                    "nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}. " +
                    "Reinstall the APK — the installer should have extracted it."
            )

        // libc.musl-aarch64.so.1 → symlink to nativeLibraryDir/libopencode-musl.so
        // (musl libc lives inside the dynamic linker binary itself)
        val libcSymlink = File(libDir, "libc.musl-aarch64.so.1")
        if (!libcSymlink.exists()) {
            try {
                libcSymlink.delete()
                val created = try {
                    @Suppress("UnsafeNewApiCall")
                    java.nio.file.Files.createSymbolicLink(
                        libcSymlink.toPath(),
                        linkerFile.toPath(),
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                if (!created) {
                    // Fallback: copy the bytes (uses 2x the space but works on
                    // filesystems that don't support symlinks).
                    linkerFile.inputStream().use { input ->
                        FileOutputStream(libcSymlink).use { output -> input.copyTo(output) }
                    }
                    libcSymlink.setExecutable(true, true)
                }
            } catch (e: IOException) {
                // Best-effort; if it fails, the linker will look in
                // nativeLibraryDir as well via LD_LIBRARY_PATH fallback.
            }
        }

        // libstdc++.so.6 and libgcc_s.so.1 (bundled in assets/musl/)
        val required = listOf(
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
            extractAndInstall(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Imports a release file already staged on local disk (e.g. one we just
     * downloaded with [com.deivid.opencode.data.DownloadManager]). Same
     * extraction logic as [importFromUri], but skips the URI-to-file copy.
     */
    fun importFromFile(staged: File): Result<BinaryInfo> = runCatching {
        ensureRuntime().getOrThrow()
        try {
            extractAndInstall(staged)
        } finally {
            // The caller owns the staged file (e.g. cacheDir); clean it up.
            staged.delete()
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
            val linker = Paths.muslLinker(context) ?: return "imported"
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
            out.lineSequence().firstOrNull()?.trim() ?: "imported"
        } catch (e: Exception) {
            "imported"
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
