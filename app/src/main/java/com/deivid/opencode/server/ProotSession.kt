package com.deivid.opencode.server

import android.content.Context
import java.io.File
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
     *
     * NOTE: The proot binary itself, the proot loader, and proot's dynamic
     * deps (libtalloc, libandroid-shmem) all live in nativeLibraryDir —
     * shipped as jniLibs so the installer extracts them to apk_data_file
     * (execve allowed). Only the Alpine rootfs is extracted from assets at
     * runtime.
     */
    fun ensureInstalled(): Result<ProotPaths> = runCatching {
        val paths = ProotPaths(context)

        // 1. Verify proot binary exists in nativeLibraryDir (apk_data_file)
        if (!paths.prootBin.exists() || !paths.prootBin.canExecute()) {
            error(
                "proot binary not found in nativeLibraryDir " +
                    "(${context.applicationInfo.nativeLibraryDir}). " +
                    "Reinstall the APK — the installer should have extracted it."
            )
        }

        // 2. Verify proot loader (also in nativeLibraryDir)
        if (!paths.prootLoader.exists() || !paths.prootLoader.canExecute()) {
            error(
                "proot loader not found in nativeLibraryDir. " +
                    "Reinstall the APK."
            )
        }

        // 3. Create the libtalloc.so.2 symlink that proot's DT_NEEDED expects.
        // AGP only packages files matching `*.so` in jniLibs (no version
        // suffixes), so we shipped the actual libtalloc bytes as `libtalloc.so`.
        // But proot's ELF has DT_NEEDED = libtalloc.so.2, so bionic's linker64
        // looks for that exact filename at runtime. We create a symlink in a
        // writable dir (filesDir/proot/lib) and add it to LD_LIBRARY_PATH.
        val tallocActual = File(paths.prootLibDir, "libtalloc.so") // nativeLibDir/libtalloc.so
        val tallocSymlinkDir = File(context.filesDir, "proot/lib")
        tallocSymlinkDir.mkdirs()
        val tallocSymlink = File(tallocSymlinkDir, "libtalloc.so.2")
        if (!tallocSymlink.exists() && tallocActual.exists()) {
            try {
                tallocSymlink.delete()
                java.nio.file.Files.createSymbolicLink(
                    tallocSymlink.toPath(),
                    tallocActual.toPath(),
                )
            } catch (e: Exception) {
                // Fallback: copy the bytes (uses 2x space but works on
                // filesystems that don't support symlinks).
                tallocActual.inputStream().use { input ->
                    java.io.FileOutputStream(tallocSymlink).use { output -> input.copyTo(output) }
                }
                tallocSymlink.setExecutable(true, true)
            }
        }

        // 4. Alpine rootfs (only extract if not already done)
        if (!paths.alpineRootDir.isDirectory ||
            !File(paths.alpineRootDir, "etc/apk/repositories").exists()
        ) {
            extractAlpineRootfs(paths.alpineRootDir)
        }

        // 5. Rootfs fixups (mirror what proot-distro does on install)
        writeResolvConf(paths.alpineRootDir)
        ensureApkRepositories(paths.alpineRootDir)
        writePasswdEntry(paths.alpineRootDir)

        paths
    }

    /** Public accessor for the Alpine rootfs directory. */
    fun rootfsDir(): File = ProotPaths(context).alpineRootDir

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

        // All proot-related binaries live in nativeLibraryDir (apk_data_file):
        //   - libproot.so         = the proot binary itself (execve target)
        //   - libproot-loader.so  = the loader that proot uses to mmap targets
        //   - libtalloc.so.2      = proot's only dynamic dep
        //   - libandroid-shmem.so = Android shmem emulation for proot
        //
        // We must set LD_LIBRARY_PATH to nativeLibDir so the bionic dynamic
        // linker can find libtalloc.so.2 + libandroid-shmem.so when proot
        // loads. The proot binary itself is invoked by absolute path.

        val cmd = mutableListOf(
            paths.prootBin.absolutePath,
            "--rootfs=${paths.alpineRootDir.absolutePath}",
            "--change-id=0:0",         // fake root inside the sandbox (= -0)
            "--link2symlink",          // termux fork: emulate hard links
            "--kill-on-exit",          // reap children when proot exits
            "--sysvipc",               // termux fork: SysV IPC emulation
            "--kernel-release=6.1.0-PRoot-Android", // satisfy musl/glibc min-kernel checks
            // Bind standard virtual filesystems so /dev, /proc, /sys work.
            // /proc is needed by opencode (reads /proc/overcommit_memory,
            // /proc/self/exe, etc.) and by many Linux tools. The SELinux
            // 'setattr' denials on /proc during bind sanitization only
            // affect apk (which writes to its database via /proc walks) —
            // but we install apk packages in a separate proot invocation
            // and skip it when libs are already installed, so this is fine.
            "--bind=/dev",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc",
            "--bind=/sys",
            // Bind the app's own data dir so opencode (which lives there) is
            // visible inside the sandbox. Mount it at the same path so any
            // hardcoded /data/data/<pkg>/... paths still resolve.
            "--bind=${context.filesDir.absolutePath}:${context.filesDir.absolutePath}",
            // Bind nativeLibraryDir so the bundled musl linker
            // (libopencode-musl.so) and proot loader (libproot-loader.so)
            // are visible inside the sandbox.
            "--bind=$nativeLibDir:$nativeLibDir",
            // Bind /system so bionic-linked binaries (none in Alpine, but
            // defensive) and Android tools work.
            "--bind=/system",
            "--bind=/apex",
            "--bind=/vendor",
        )

        // Conditional binds — only add if the source path exists. proot
        // prints a warning and refuses to start if a --bind source doesn't
        // exist ("can't sanitize binding ... No such file or directory").
        // This matches ReTerminal's init-host.sh pattern.
        //
        // NOTE: We deliberately do NOT bind /proc/self/fd → /dev/fd or
        // /proc/self/fd/{0,1,2} → /dev/std{in,out,err} here. Even when the
        // source paths exist, proot's bind mechanism calls chmod() on the
        // target during sanitization, which triggers SELinux 'setattr'
        // denials on /proc (labeled 'proc:s0') and /proc/self/fd (labeled
        // 'untrusted_app:s0'). These denials cause apk to fail with
        // "failed to write database: Function not implemented" because apk
        // walks /proc and aborts on the chmod errors.
        //
        // The Alpine rootfs already has /dev/fd, /dev/stdin, /dev/stdout,
        // /dev/stderr as symlinks to /proc/self/fd/N (set up by the
        // alpine-minirootfs), so these binds aren't strictly necessary
        // for most tools.

        // /dev/shm — needed by some musl/glibc shared-memory users. The
        // tmp dir inside the rootfs must exist.
        val shmDir = java.io.File(paths.alpineRootDir, "tmp")
        if (shmDir.isDirectory) {
            cmd.add("--bind=${shmDir.absolutePath}:/dev/shm")
        }
        cmd.addAll(extraBinds)
        cmd.add("--cwd=$cwd")
        cmd.addAll(argv)

        return ProcessBuilder(cmd).apply {
            // THE KEY: proot uses this env var to find its loader. Without it,
            // proot writes a temp loader to /data/data/.../files/ (app_data_file)
            // and the kernel refuses to execve it — see termux/proot#338.
            environment()["PROOT_LOADER"] = paths.prootLoader.absolutePath
            // PROOT_LOADER32 is used for 32-bit guest binaries. We don't ship
            // a 32-bit loader (the app is arm64-v8a only), but setting the
            // env var to the same value matches ReTerminal's pattern and is
            // harmless — proot falls back to PROOT_LOADER for 64-bit guests.
            environment()["PROOT_LOADER_32"] = paths.prootLoader.absolutePath
            paths.prootTmpDir.mkdirs()
            environment()["PROOT_TMP_DIR"] = paths.prootTmpDir.absolutePath
            // Disable seccomp acceleration — Android often restricts seccomp
            // install for untrusted apps.
            environment()["PROOT_NO_SECCOMP"] = "1"
            // Don't let proot create 0-byte mknod() placeholders inside the
            // Alpine rootfs for file binds whose guest target doesn't exist.
            // These placeholders confuse the musl linker (it opens the empty
            // file instead of the real bound source → "Not a valid dynamic
            // program"). We copy files into the rootfs directly instead of
            // relying on file binds for binaries.
            environment()["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
            // Don't fail proot startup if a --bind source doesn't exist or
            // can't be sanitized. proot prints a warning and continues.
            environment()["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
            // proot's deps (libandroid-shmem.so, libtalloc.so.2) are in
            // nativeLibDir — but libtalloc needs a .so.2 symlink which we
            // can't create in nativeLibDir (read-only). So we ship the actual
            // bytes as libtalloc.so in nativeLibDir, and create a symlink
            // libtalloc.so.2 → libtalloc.so in filesDir/proot/lib at runtime.
            //
            // We also include filesDir/opencode/lib/ — that's where opencode's
            // C++ deps (libstdc++.so.6, libgcc_s.so.1) live. The Alpine base
            // rootfs doesn't ship them, so any musl-linked binary DT_NEEDEDing
            // libstdc++ (like opencode) would fail to load inside the proot
            // sandbox without this entry.
            //
            // LD_LIBRARY_PATH must include ALL THREE dirs:
            //   - nativeLibDir             → libandroid-shmem.so, libtalloc.so
            //   - filesDir/proot/lib       → libtalloc.so.2 symlink
            //   - filesDir/opencode/lib    → libstdc++.so.6, libgcc_s.so.1
            val ldLibPath = listOfNotNull(
                nativeLibDir,
                File(context.filesDir, "proot/lib").takeIf { it.exists() }?.absolutePath,
                File(context.filesDir, "opencode/lib").takeIf { it.exists() }?.absolutePath,
            ).joinToString(":")
            environment()["LD_LIBRARY_PATH"] = ldLibPath
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

    private fun extractAlpineRootfs(targetDir: File) {
        targetDir.mkdirs()
        // We ship the rootfs as `alpine-rootfs.bin` to dodge AAPT2's habit of
        // transparently decompressing *.gz assets during APK assembly (which
        // strips the .gz suffix and confuses our code). The file is a regular
        // gzip-compressed ustar tarball — we just don't tell AAPT that.
        val assetName = "alpine-rootfs.bin"
        GZIPInputStream(context.assets.open(assetName)).use { gz ->
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
}

/**
 * Layout of files managed by [ProotSession].
 *
 * - All proot-related binaries live in `nativeLibraryDir` (= the dir the
 *   package installer extracts jniLibs to, labeled `apk_data_file`). This
 *   is the ONLY location where execve() is allowed on Android 10+.
 * - The Alpine rootfs is extracted at runtime from `assets/alpine-rootfs.bin`
 *   into `filesDir/alpine/` (labeled `app_data_file`). Files inside the
 *   rootfs only need to be mmap(PROT_EXEC)'d by proot's loader, which is
 *   allowed on `app_data_file`.
 * - `prootTmpDir` is a writable scratch dir for proot's internal use.
 */
data class ProotPaths(private val ctx: Context) {
    private val nativeLibDir: String?
        get() = ctx.applicationInfo.nativeLibraryDir

    /** proot binary (renamed to libproot.so in jniLibs, apk_data_file). */
    val prootBin: File
        get() = File(nativeLibDir ?: "", "libproot.so")

    /** proot loader (libproot-loader.so in jniLibs, apk_data_file). */
    val prootLoader: File
        get() = File(nativeLibDir ?: "", "libproot-loader.so")

    /** Directory where proot's deps (libtalloc, libandroid-shmem) live. */
    val prootLibDir: File
        get() = File(nativeLibDir ?: "")

    /** Writable scratch dir for proot's internal temp files. */
    val prootTmpDir: File
        get() = File(ctx.filesDir, "proot/tmp")

    /** Extracted Alpine rootfs (app_data_file). */
    val alpineRootDir: File
        get() = File(ctx.filesDir, "alpine")

    /** True if the proot binary is installed AND the rootfs is extracted. */
    fun isInstalled(): Boolean =
        prootBin.exists() && prootBin.canExecute() &&
            prootLoader.exists() && prootLoader.canExecute() &&
            File(alpineRootDir, "etc/apk/repositories").exists()
}
