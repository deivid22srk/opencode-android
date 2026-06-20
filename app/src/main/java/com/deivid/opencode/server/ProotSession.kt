package com.deivid.opencode.server

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Bootstraps and launches a proot session rooted at an extracted Alpine
 * minirootfs. This lets the opencode server run inside a real Linux userland
 * where `apk add python3 nodejs ruby gcc …` works at runtime — enabling the
 * tool-call feature to spawn arbitrary Linux binaries.
 *
 *
 * ## File layout
 *
 * ```
 * jniLibs/arm64-v8a/libproot-loader.so    → /data/app/<pkg>/lib/arm64/libproot-loader.so
 *                                              (apk_data_file — execve allowed)
 * assets/proot/proot                      → filesDir/proot/bin/proot
 *                                              (bionic-linked, runs via /system/bin/linker64)
 * assets/proot/lib/libtalloc.so.2         → filesDir/proot/lib/libtalloc.so.2
 * assets/proot/lib/libandroid-shmem.so    → filesDir/proot/lib/libandroid-shmem.so
 * assets/alpine-rootfs.tar.gz             → filesDir/alpine/ (extracted)
 * ```
 *
 *
 * ## How proot bypasses the SELinux neverallow
 *
 * On Android 10+ the kernel refuses `execve()` on files in `app_data_file`
 * (i.e. anything under `/data/data/<pkg>/files/`). proot's loader trick
 * sidesteps this: every `execve` inside the proot session is rewritten (via
 * ptrace) to `execve(<loader-in-apk_data_file>)`, and the loader then
 * `mmap(PROT_EXEC)`s the real target. `mmap(PROT_EXEC)` on app_data_file
 * IS allowed, so the whole thing works without root.
 *
 * The make-or-break env var is `PROOT_LOADER` — it must point at our
 * `libproot-loader.so` in nativeLibraryDir. See:
 * https://github.com/termux/proot/issues/338
 */
class ProotSession(private val context: Context) {

    /**
     * Ensures the proot binary, its libs, and the Alpine rootfs are all
     * extracted to filesDir. Idempotent — skips work that's already done.
     *
     * Returns the directory layout that [launch] expects.
     */
    fun ensureInstalled(): Result<ProotPaths> = runCatching {
        val paths = ProotPaths(context)

        // 1. proot binary + libs (extract from assets)
        paths.prootBin.parentFile?.mkdirs()
        paths.prootLibDir.mkdirs()

        if (!paths.prootBin.exists() || paths.prootBin.length() == 0L) {
            copyAsset("proot/proot", paths.prootBin)
            if (!paths.prootBin.setExecutable(true, true)) {
                error("Failed to chmod proot binary")
            }
        }

        for ((asset, target) in listOf(
            "proot/lib/libtalloc.so.2" to File(paths.prootLibDir, "libtalloc.so.2"),
            "proot/lib/libandroid-shmem.so" to File(paths.prootLibDir, "libandroid-shmem.so"),
        )) {
            if (!target.exists() || target.length() == 0L) {
                copyAsset(asset, target)
                target.setExecutable(true, true)
            }
        }

        // 2. Alpine rootfs (only extract if not already done)
        if (!paths.alpineRootDir.isDirectory ||
            !File(paths.alpineRootDir, "etc/apk/repositories").exists()
        ) {
            extractAlpineRootfs(paths.alpineRootDir)
        }

        // 3. Rootfs fixups (mirror what proot-distro does on install)
        writeResolvConf(paths.alpineRootDir)
        ensureApkRepositories(paths.alpineRootDir)
        writePasswdEntry(paths.alpineRootDir)

        paths
    }

    /**
     * Builds the ProcessBuilder command list that launches [argv] inside the
     * proot'd Alpine environment. The caller is responsible for setting any
     * extra env vars and starting the process.
     *
     * Example: `session.launch(listOf("opencode", "serve", "--port", "4096"))`
     */
    fun launch(
        argv: List<String>,
        cwd: String = "/root",
        extraBinds: List<String> = emptyList(),
    ): ProcessBuilder {
        val paths = ensureInstalled().getOrThrow()

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
            ?: error("nativeLibraryDir is null — APK may not have been installed correctly")

        val loaderPath = File(nativeLibDir, "libproot-loader.so")
        if (!loaderPath.exists()) {
            error("libproot-loader.so missing in nativeLibraryDir=$nativeLibDir")
        }

        // proot itself is a bionic binary, so we launch it via Android's
        // own linker64. We set LD_LIBRARY_PATH so it can find libtalloc.so.2
        // and libandroid-shmem.so in our private lib dir.
        val cmd = mutableListOf(
            paths.prootBin.absolutePath,
            "--rootfs=${paths.alpineRootDir.absolutePath}",
            "--change-id=0:0",         // fake root inside the sandbox
            "--link2symlink",          // termux fork: emulate hard links
            "--kill-on-exit",          // reap children when proot exits
            "--kernel-release=6.1.0-PRoot-Android", // satisfy musl/glibc min-kernel checks
            "-L",                      // resolve symlinks outside the rootfs
            // Bind standard virtual filesystems so /proc, /dev, /sys work
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/sys",
            // Bind the app's own data dir so opencode (which lives there) is
            // visible inside the sandbox. Mount it at the same path so any
            // hardcoded /data/data/<pkg>/... paths still resolve.
            "--bind=${context.filesDir.absolutePath}:${context.filesDir.absolutePath}",
            // Bind /system so bionic-linked binaries (none in Alpine, but
            // defensive) and Android tools work.
            "--bind=/system",
            "--bind=/apex",
            "--bind=/vendor",
        )
        cmd.addAll(extraBinds)
        cmd.add("--cwd=$cwd")
        cmd.addAll(argv)

        return ProcessBuilder(cmd).apply {
            environment()["PROOT_LOADER"] = loaderPath.absolutePath
            environment()["PROOT_LOADER_32"] = loaderPath.absolutePath
            paths.prootTmpDir.mkdirs()
            environment()["PROOT_TMP_DIR"] = paths.prootTmpDir.absolutePath
            // Disable seccomp acceleration — Android often restricts seccomp
            // install for untrusted apps.
            environment()["PROOT_NO_SECCOMP"] = "1"
            // proot's deps live in our private lib dir
            environment()["LD_LIBRARY_PATH"] = paths.prootLibDir.absolutePath
            // Clean environment inside the sandbox — let /etc/profile set PATH
            environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            environment()["HOME"] = "/root"
            environment()["TERM"] = "xterm-256color"
            environment()["LANG"] = "C.UTF-8"
            // Strip Android-specific env that might confuse Linux code
            environment().remove("ANDROID_DATA")
            environment().remove("ANDROID_ROOT")
            environment().remove("BOOTCLASSPATH")
            environment().remove("CLASSPATH")
            redirectErrorStream(true)
        }
    }

    /**
     * Runs a one-shot command inside the proot'd Alpine env (e.g. to
     * `apk add python3`). Returns the combined stdout/stderr.
     */
    fun runCommand(argv: List<String>, timeoutMs: Long = 120_000L): Result<String> = runCatching {
        val proc = launch(argv).start()
        val out = proc.inputStream.bufferedReader().readText()
        val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            error("Command timed out after ${timeoutMs}ms: ${argv.joinToString(" ")}\nOutput so far:\n$out")
        }
        val code = proc.exitValue()
        if (code != 0) {
            error("Command exited with $code: ${argv.joinToString(" ")}\n$out")
        }
        out
    }

    /**
     * Convenience wrapper around `apk add` that streams output to [onLog].
     * Safe to call from a coroutine on Dispatchers.IO.
     */
    fun apkAdd(
        packages: List<String>,
        onLog: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        val argv = listOf("apk", "--no-cache", "add") + packages
        val proc = launch(argv).start()
        proc.inputStream.bufferedReader().use { r ->
            val buf = CharArray(1024)
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                val chunk = String(buf, 0, n)
                onLog(chunk)
            }
        }
        val code = proc.waitFor()
        if (code != 0) error("apk add failed with exit code $code")
    }

    // ----- Bootstrap helpers -----

    private fun copyAsset(assetPath: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun extractAlpineRootfs(targetDir: File) {
        targetDir.mkdirs()
        // Use our existing TarReader to walk the alpine-minirootfs tarball.
        // It's a regular ustar .tar.gz so the same code that extracts the
        // opencode binary works here too — we just extract EVERY entry
        // instead of stopping at a specific name.
        GZIPInputStream(context.assets.open("alpine-rootfs.tar.gz")).use { gz ->
            FullTarExtractor(gz).extractTo(targetDir)
        }
    }

    private fun writeResolvConf(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(
            """
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            nameserver 2001:4860:4860::8888
            """.trimIndent() + "\n",
        )
    }

    private fun ensureApkRepositories(rootfs: File) {
        val etc = File(rootfs, "etc")
        val apkDir = File(etc, "apk").apply { mkdirs() }
        val repos = File(apkDir, "repositories")
        // Point at the Alpine CDN so `apk update` + `apk add` work out of the box.
        repos.writeText(
            """
            https://dl-cdn.alpinelinux.org/alpine/v3.24/main
            https://dl-cdn.alpinelinux.org/alpine/v3.24/community
            """.trimIndent() + "\n",
        )
    }

    private fun writePasswdEntry(rootfs: File) {
        val etc = File(rootfs, "etc")
        // Make sure root has a home inside the rootfs
        File(etc, "passwd").takeIf { it.exists() }?.let { f ->
            val content = f.readText()
            if (!content.contains("root:")) {
                f.writeText(content + "\nroot:x:0:0:root:/root:/bin/sh\n")
            }
        }
        File(rootfs, "root").mkdirs()
    }

    companion object {
        private const val TAG = "ProotSession"
    }
}

/**
 * Layout of files managed by [ProotSession], all under filesDir.
 */
data class ProotPaths(private val ctx: Context) {
    val prootDir: File get() = File(ctx.filesDir, "proot")
    val prootBin: File get() = File(prootDir, "bin/proot")
    val prootLibDir: File get() = File(prootDir, "lib")
    val prootTmpDir: File get() = File(prootDir, "tmp")
    val alpineRootDir: File get() = File(ctx.filesDir, "alpine")

    /** True if the Alpine rootfs looks installed. */
    fun isInstalled(): Boolean =
        prootBin.exists() && prootBin.canExecute() &&
            File(alpineRootDir, "etc/apk/repositories").exists()
}
