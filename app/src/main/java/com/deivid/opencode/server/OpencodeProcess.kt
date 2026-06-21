package com.deivid.opencode.server

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps a running `opencode serve` process.
 *
 * ## Why we use `nativeLibraryDir`
 *
 * On Android 10+ a SELinux `neverallow` rule (b/112357170) blocks any
 * `execve()` on files in `/data/data/<pkg>/files/` — they are labeled
 * `app_data_file`, and `app_data_file:file execute_no_trans` is forbidden
 * for `untrusted_app`. So we **cannot** execve the musl dynamic linker
 * from there.
 *
 * The fix is to ship the musl linker inside the APK as
 * `app/src/main/jniLibs/arm64-v8a/libopencode-musl.so`. The Android package
 * installer extracts it to `/data/app/<pkg>-<hash>/lib/arm64/` at install
 * time, where it gets labeled `apk_data_file`. SELinux DOES allow
 * `execute_no_trans` on `apk_data_file` for any appdomain. So we can
 * finally execve it.
 *
 * Once the musl linker is running, it `mmap(PROT_EXEC)`s the opencode
 * binary from `filesDir` (labeled `app_data_file`). That needs only
 * `execute` (not `execute_no_trans`), which IS allowed. So opencode itself
 * can stay in `filesDir` where we can replace it at runtime.
 *
 * Reference:
 *   https://github.com/agnostic-apollo/Android-Docs/blob/master/site/pages/en/projects/docs/apps/processes/app-data-file-execute-restrictions.md
 */
class OpencodeProcess(private val context: Context) {

    private val processRef = AtomicReference<Process?>(null)
    private val writerRef = AtomicReference<OutputStreamWriter?>(null)

    /** Pid of the running process, or -1 if not running / unavailable. */
    val pid: Int
        get() = try {
            // java.lang.Process.pid() requires Java 9+ and Android API 30+.
            // Use reflection so the call site compiles on every API level.
            val p = processRef.get() ?: return -1
            val m = p.javaClass.getMethod("pid")
            (m.invoke(p) as? Int) ?: -1
        } catch (_: Exception) { -1 }

    fun isRunning(): Boolean = processRef.get()?.isAlive == true

    /**
     * Returns the path of the musl dynamic linker, as installed by Android's
     * package installer under `/data/app/<pkg>-<hash>/lib/arm64/`. Returns
     * null if the linker is not present — which means either the device is
     * not arm64-v8a or the APK was built without the bundled musl linker.
     */
    fun muslLinkerPath(): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val linker = File(nativeDir, "libopencode-musl.so")
        return if (linker.exists() && linker.canExecute()) linker.absolutePath else null
    }

    /**
     * Launches `opencode serve` with the given options. Returns the bound URL.
     *
     * The opencode CLI prints `opencode server listening on http://...` to
     * stdout when it's ready. We watch stdout for that line and resolve as
     * soon as we see it (or fail if the process exits first).
     *
     * @param workingDirectory the directory opencode should treat as the
     *   default project root. Becomes `process.cwd()` for the child process
     *   and is what opencode uses when a request arrives without an
     *   `x-opencode-directory` header. Defaults to a sandbox dir we own.
     * @param useProot if true, launches opencode INSIDE a proot'd Alpine
     *   rootfs. This enables opencode's tool-call feature to spawn binaries
     *   (python, node, etc.) that the user installs at runtime via
     *   `apk add`. When false (default), launches opencode directly via
     *   the musl dynamic linker — lighter weight, but no tool-call support.
     */
    fun start(
        port: Int,
        hostname: String,
        password: String?,
        workingDirectory: File? = null,
        useProot: Boolean = false,
        onLog: (String) -> Unit,
    ): Result<String> = runCatching {
        if (isRunning()) error("Server is already running")

        Paths.logsDir(context).mkdirs()
        val logFile = Paths.logFile(context)
        if (logFile.exists()) logFile.delete()
        logFile.createNewFile()
        val logWriter = OutputStreamWriter(logFile.outputStream().buffered(), Charsets.UTF_8)
        writerRef.set(logWriter)

        // Build the ProcessBuilder based on the chosen launch mode.
        val pb = if (useProot) {
            buildProotLaunch(port, hostname, password, workingDirectory)
        } else {
            buildDirectLaunch(port, hostname, password, workingDirectory)
        }

        val proc = pb.start()
        processRef.set(proc)

        // Latch for the "listening on" line. We scan stdout line-by-line.
        val urlLatch = CountDownLatch(1)
        val urlRef = AtomicReference<String?>(null)
        val exitRef = AtomicReference<String?>(null)

        Thread({
            val sb = StringBuilder()
            try {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        val chunk = String(buf, 0, n)
                        sb.append(chunk)
                        synchronized(logWriter) {
                            logWriter.write(chunk)
                            logWriter.flush()
                        }
                        onLog(chunk)
                        // Scan for the listening line; opencode prints:
                        //   opencode server listening on http://127.0.0.1:4096
                        if (urlRef.get() == null) {
                            for (line in chunk.split('\n')) {
                                val marker = "listening on http://"
                                val idx = line.indexOf(marker)
                                if (idx >= 0) {
                                    val url = line.substring(idx + marker.length)
                                        .trim()
                                        .split(' ', '\t', '\r')
                                        .firstOrNull()
                                        ?.let { "http://$it" }
                                    if (url != null) {
                                        urlRef.set(url)
                                        urlLatch.countDown()
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "stdout reader ended", e)
            } finally {
                // If process exited without printing the URL, capture the tail
                // of the log so the caller can surface a meaningful error.
                if (urlRef.get() == null) {
                    val lines = sb.toString().lineSequence()
                        .filter { it.isNotBlank() }
                        .toList()
                    val tail = if (lines.size > 50) {
                        lines.takeLast(50).joinToString("\n")
                    } else {
                        lines.joinToString("\n")
                    }
                    exitRef.set(tail.ifBlank { "opencode exited without printing a URL" })
                    urlLatch.countDown()
                }
                try { synchronized(logWriter) { logWriter.close() } } catch (_: Exception) {}
                writerRef.set(null)
            }
        }, "opencode-stdout").also { it.isDaemon = true }.start()

        // Wait up to 60s for the listening line. The process keeps running
        // after the latch counts down. Increased from 30s to 60s because the
        // diagnostic shell script adds startup time.
        if (!urlLatch.await(60_000, TimeUnit.MILLISECONDS)) {
            stop()
            error("opencode did not announce a listening URL within 30s")
        }

        urlRef.get() ?: error(
            "opencode exited without printing a URL. Tail of log:\n" +
                (exitRef.get() ?: "(no output)")
        )
    }

    // ----- Launch builders -----

    /**
     * Direct launch — runs opencode via the bundled musl dynamic linker.
     *
     * **IMPORTANT**: This mode only works with PIE (ET_DYN) binaries.
     * Most opencode releases are built as ET_EXEC (non-PIE), which the
     * musl dynamic linker cannot load when invoked from the command line
     * (musl's ldso only accepts ET_DYN/PIE for command-line invocation;
     * the kernel's PT_INTERP path works for both, but we must execve the
     * linker from nativeLibraryDir due to SELinux, which forces the
     * command-line path).  When the binary is ET_EXEC, this method
     * automatically falls back to proot mode.
     */
    private fun buildDirectLaunch(
        port: Int,
        hostname: String,
        password: String?,
        workingDirectory: File?,
    ): ProcessBuilder {
        BinaryManager(context).ensureRuntime().getOrThrow()
        BinaryManager(context).ensureNetworkConfig().getOrThrow()
        val binary = Paths.binary(context)
        if (!binary.exists() || !binary.canExecute()) {
            error("opencode binary not imported or not executable")
        }

        // Check if the binary is ET_EXEC (non-PIE).  The musl dynamic
        // linker (ld-musl-aarch64.so.1) only supports loading ET_DYN (PIE)
        // executables when invoked from the command line.  ET_EXEC binaries
        // fail with "Not a valid dynamic program".  When we detect ET_EXEC,
        // fall back to proot mode where the kernel handles PT_INTERP
        // correctly (the PT_INTERP path works for both ET_EXEC and ET_DYN).
        val isPie = isPieBinary(binary)
        if (!isPie) {
            android.util.Log.w(TAG,
                "opencode binary is ET_EXEC (non-PIE); musl linker cannot " +
                    "load it from the command line. Falling back to proot mode.")
            return buildProotLaunch(port, hostname, password, workingDirectory)
        }

        val linkerPath = muslLinkerPath()
            ?: error(
                "musl dynamic linker not found in nativeLibraryDir. " +
                    "The APK was probably built without the bundled linker — " +
                    "rebuild with the latest source."
            )

        val libDir = Paths.libDir(context)

        val workDir = workingDirectory ?: Paths.workspaceDir(context)
        workDir.mkdirs()
        if (!workDir.isDirectory) {
            error("Working directory does not exist or is not a directory: ${workDir.absolutePath}")
        }

        val cmd = mutableListOf(
            linkerPath,
            "--library-path",
            libDir.absolutePath,
            binary.absolutePath,
            "serve",
            "--hostname", hostname,
            "--port", port.toString(),
            // Enable verbose logging to help diagnose startup failures.
            "--print-logs",
            "--log-level", "DEBUG",
        )

        val caBundle = Paths.caBundle(context)
        val resolvConf = Paths.resolvConf(context)

        return ProcessBuilder(cmd).apply {
            directory(workDir)
            val home = Paths.configDir(context).apply { mkdirs() }
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            environment()["OPENCODE_INSTALL_DIR"] = Paths.binDir(context).absolutePath
            environment()["XDG_DATA_HOME"] = File(home, "share").absolutePath
            environment()["XDG_CONFIG_HOME"] = File(home, "config").absolutePath
            environment()["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
            environment()["OPENCODE_DISABLE_UPDATE"] = "1"
            environment()["OPENCODE_DISABLE_PROJECT_CONFIG"] = "1"
            environment()["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
            // Network configuration — critical for HTTPS to LLM providers.
            // Bun (which opencode is built on) looks for CA certs at
            // /etc/ssl/certs/ca-certificates.crt etc., which don't exist on
            // Android. We point SSL_CERT_FILE and NODE_EXTRA_CA_CERTS at our
            // bundled CA file (concatenated from Android system CAs).
            if (caBundle.exists()) {
                environment()["SSL_CERT_FILE"] = caBundle.absolutePath
                environment()["SSL_CERT_DIR"] = home.absolutePath
                environment()["NODE_EXTRA_CA_CERTS"] = caBundle.absolutePath
                environment()["REQUESTS_CA_BUNDLE"] = caBundle.absolutePath
                environment()["CURL_CA_BUNDLE"] = caBundle.absolutePath
            }
            // DNS — musl's resolver reads /etc/resolv.conf. Android doesn't
            // have one, so we point the resolver at our synthetic file via
            // the RES_OPTIONS env var (musl respects RES_OPTIONS for options
            // but not for nameserver; we also create a symlink at /etc/resolv.conf
            // is not possible, so we rely on the proot mode for that. In
            // direct mode, musl falls back to 127.0.0.1 which doesn't work,
            // so we also set RESOLV_HOST_CONF as a fallback).
            if (resolvConf.exists()) {
                environment()["RESOLV_HOST_CONF"] = resolvConf.absolutePath
                environment()["RES_OPTIONS"] = "timeout:2 attempts:2"
            }
            if (!password.isNullOrBlank()) {
                environment()["OPENCODE_SERVER_PASSWORD"] = password
            }
            environment().remove("ANDROID_DATA")
            environment().remove("ANDROID_ROOT")
            environment().remove("CLASSPATH")
            redirectErrorStream(true)
        }
    }

    /**
     * Checks whether the given ELF binary is PIE (Position-Independent
     * Executable, ELF type ET_DYN).  Returns `true` for PIE/ET_DYN,
     * `false` for ET_EXEC (non-PIE) or unreadable files.
     *
     * The musl dynamic linker only supports loading ET_DYN binaries when
     * invoked from the command line.  ET_EXEC binaries must be loaded via
     * the kernel's PT_INTERP mechanism (which works for both types).
     */
    private fun isPieBinary(binary: File): Boolean {
        try {
            binary.inputStream().use { input ->
                // ELF header: bytes 0-3 = magic (7f 45 4c 46)
                // byte 4 = class (1=32-bit, 2=64-bit)
                // e_type is at offset 16 (2 bytes, little-endian)
                val header = ByteArray(18)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 18) return false
                // Verify ELF magic
                if (header[0] != 0x7f.toByte() ||
                    header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() ||
                    header[3] != 'F'.code.toByte()
                ) return false
                // e_type at offset 16: ET_EXEC=2, ET_DYN=3
                val eType = (header[16].toInt() and 0xFF) or
                    ((header[17].toInt() and 0xFF) shl 8)
                // PIE binaries have type ET_DYN (3)
                return eType == 3
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to check ELF type of ${binary.absolutePath}", e)
            return false
        }
    }

    /**
     * proot launch — runs opencode INSIDE a proot'd Alpine rootfs. opencode's
     * tool-call feature works because every `execve` inside the proot session
     * is rewritten (via ptrace) to `execve(libproot-loader.so)` in
     * nativeLibraryDir (apk_data_file — execve allowed). The loader then
     * `mmap(PROT_EXEC)`s the real target from app_data_file (also allowed).
     */
    private fun buildProotLaunch(
        port: Int,
        hostname: String,
        password: String?,
        workingDirectory: File?,
    ): ProcessBuilder {
        BinaryManager(context).ensureRuntime().getOrThrow()
        val binary = Paths.binary(context)
        if (!binary.exists() || !binary.canExecute()) {
            error("opencode binary not imported or not executable")
        }

        // Make sure proot + Alpine rootfs are installed.
        val proot = ProotSession(context)
        proot.ensureInstalled().getOrThrow()

        // CRITICAL: copy the opencode binary INTO the Alpine rootfs instead
        // of bind-mounting it. proot's file-bind mechanism has a known issue
        // where it creates a 0-byte mknod() placeholder at the guest path
        // inside the rootfs (path/glue.c::build_glue). Copying the binary
        // into the rootfs avoids this entirely.
        //
        // We ALWAYS overwrite — a previous proot run with file-bind may have
        // left a 0-byte placeholder at this path, and we need to replace it
        // with the real binary.
        val opencodeInRootfs = File(proot.rootfsDir(), "usr/local/bin/opencode")
        opencodeInRootfs.parentFile?.mkdirs()
        if (opencodeInRootfs.exists()) {
            opencodeInRootfs.delete()
        }
        binary.inputStream().use { input ->
            java.io.FileOutputStream(opencodeInRootfs).use { output -> input.copyTo(output) }
        }
        if (!opencodeInRootfs.setExecutable(true, true)) {
            error("Failed to chmod opencode binary in rootfs")
        }
        val rootfsBinaryLen = opencodeInRootfs.length()
        val origBinaryLen = binary.length()
        if (rootfsBinaryLen < 1_000_000) {
            error(
                "opencode binary copy to rootfs failed — file is only " +
                    "$rootfsBinaryLen bytes (expected ~160 MB). " +
                    "Original: $origBinaryLen bytes. " +
                    "Path: ${opencodeInRootfs.absolutePath}"
            )
        }
        android.util.Log.i("OpencodeProcess",
            "Copied opencode to rootfs: ${opencodeInRootfs.absolutePath} " +
                "($rootfsBinaryLen bytes, original was $origBinaryLen)")

        // Copy C++ runtime libs (libstdc++.so.6, libgcc_s.so.1) from our
        // bundled assets directly into /usr/lib/ in the rootfs.
        //
        // We do NOT use 'apk add' because:
        // 1. apk uses flock() for database locking, which returns ENOSYS
        //    ("Function not implemented") under proot on Android
        // 2. The --bind=/proc causes SELinux 'setattr' denials that cascade
        //    into apk database write failures
        // 3. apk needs network access, adding latency and a failure point
        //
        // Instead, we ship pre-built musl-linked versions of these libs
        // (extracted from Alpine v3.24 .apk packages) in assets/alpine-libs/
        // and copy them to /usr/lib/ in the rootfs. The musl linker searches
        // /usr/lib/ by default, so opencode's DT_NEEDED entries resolve
        // automatically.
        val usrLibDir = File(proot.rootfsDir(), "usr/lib")
        usrLibDir.mkdirs()
        for (libName in listOf("libstdc++.so.6", "libgcc_s.so.1")) {
            val dst = File(usrLibDir, libName)
            if (dst.exists()) dst.delete()
            try {
                context.assets.open("alpine-libs/$libName").use { input ->
                    java.io.FileOutputStream(dst).use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true, true)
                android.util.Log.i("OpencodeProcess",
                    "Copied $libName to rootfs: ${dst.absolutePath} (${dst.length()} bytes)")
            } catch (e: Exception) {
                android.util.Log.e("OpencodeProcess",
                    "Failed to copy $libName: ${e.message}")
            }
        }

        // Mount the user's working directory as /root inside the sandbox so
        // opencode's `process.cwd()` resolves to a real, writable folder
        // that the user actually cares about.
        val workDir = workingDirectory ?: Paths.workspaceDir(context)
        workDir.mkdirs()
        val workspaceBind = "--bind=${workDir.absolutePath}:/root"

        // Build the opencode command that runs inside the proot sandbox.
        //
        // opencode is a Bun --compile binary that is dynamically linked
        // against musl libc, libstdc++.so.6, and libgcc_s.so.1.  Its ELF
        // PT_INTERP points at /lib/ld-musl-aarch64.so.1.
        //
        // IMPORTANT: We invoke opencode DIRECTLY — NOT through the musl
        // linker as a command-line wrapper.  When the kernel loads the
        // binary via execve(), it reads PT_INTERP and invokes the musl
        // linker as the dynamic interpreter automatically.  That code path
        // works for both ET_EXEC and ET_DYN (PIE) binaries.
        //
        // Invoking the musl linker explicitly on the command line
        //   e.g. /lib/ld-musl-aarch64.so.1 --library-path /usr/lib /usr/local/bin/opencode
        // does NOT work for ET_EXEC binaries: musl's ldso only supports
        // loading ET_DYN (PIE) executables when invoked directly from the
        // command line, and rejects ET_EXEC with "Not a valid dynamic
        // program".  The kernel's PT_INTERP path does not have this
        // limitation because it passes the binary's metadata to the linker
        // via the auxiliary vector (AT_PHDR, AT_PHNUM, etc.), bypassing
        // the ET_DYN-only check in ldso's command-line path.
        //
        // The libraries are found via:
        //  1. DT_RPATH embedded in the opencode binary (set at build time)
        //  2. LD_LIBRARY_PATH set by ProotSession (includes /usr/lib)
        //  3. The musl linker's built-in default search paths (/usr/lib, /lib)
        val opencodeArgs = listOf(
            "serve",
            "--hostname", hostname,
            "--port", port.toString(),
            "--print-logs",
            "--log-level", "DEBUG",
        )
        val shellCmd = buildString {
            // Minimal diagnostics — keep it short so opencode starts faster.
            append("echo '=== PROOT SESSION STARTED ==='\n")
            append("echo \"whoami: \$(whoami 2>&1 || echo 'FAIL')\"\n")
            append("echo \"pwd: \$(pwd 2>&1 || echo 'FAIL')\"\n")
            append("echo '--- File checks ---'\n")
            append("ls -la /usr/local/bin/opencode /usr/lib/libstdc++.so.6 /usr/lib/libgcc_s.so.1 /lib/ld-musl-aarch64.so.1 2>&1\n")
            append("echo '=== STARTING OPENCODE ==='\n")
            // Set env vars — network config, XDG dirs, opencode flags.
            // Inside proot, /etc/resolv.conf is written by ProotSession
            // (writeResolvConf), so DNS works natively via musl's resolver.
            // CA certs are also available via /etc/ssl/certs in the rootfs.
            if (!password.isNullOrBlank()) {
                append("export OPENCODE_SERVER_PASSWORD=").append(escapeShell(password)).append("; ")
            }
            append("export OPENCODE_DISABLE_UPDATE=1 OPENCODE_DISABLE_AUTOUPDATE=1 OPENCODE_DISABLE_PROJECT_CONFIG=1; ")
            // Run opencode directly.  The kernel will invoke PT_INTERP
            // (/lib/ld-musl-aarch64.so.1) automatically and load DT_NEEDED
            // libraries (libstdc++.so.6, libgcc_s.so.1) from /usr/lib/.
            append("/usr/local/bin/opencode ")
                .append(opencodeArgs.joinToString(" ") { escapeShell(it) })
                .append(" 2>&1\n")
            append("echo \"=== OPENCODE EXITED WITH CODE \$? ===\"\n")
        }

        val pb = proot.launch(
            argv = listOf("/bin/sh", "-lc", shellCmd),
            cwd = "/root",
            extraBinds = listOf(workspaceBind),
        )

        // Pass workspace dir so ProcessBuilder.directory() doesn't matter
        // (proot ignores it anyway — it sets cwd via --cwd flag).
        pb.directory(workDir)
        return pb
    }

    /** Escape a string for use as a single shell argument. */
    private fun escapeShell(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    fun stop() {
        val proc = processRef.getAndSet(null) ?: return
        try {
            proc.destroy()
            // Give it a moment to exit cleanly
            if (!proc.waitFor(2_000, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
        }
        writerRef.getAndSet(null)?.let { w ->
            try { synchronized(w) { w.close() } } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "OpencodeProcess"
    }
}
