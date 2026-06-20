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
                    val tail = if (lines.size > 8) {
                        lines.takeLast(8).joinToString("\n")
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

        // Wait up to 30s for the listening line. The process keeps running
        // after the latch counts down.
        if (!urlLatch.await(30_000, TimeUnit.MILLISECONDS)) {
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
     * This is the lightweight mode: no proot, no Alpine rootfs. opencode's
     * tool-call feature (spawning python/node/etc.) will NOT work in this
     * mode because execve() of those binaries would hit the SELinux
     * neverallow.
     */
    private fun buildDirectLaunch(
        port: Int,
        hostname: String,
        password: String?,
        workingDirectory: File?,
    ): ProcessBuilder {
        val linkerPath = muslLinkerPath()
            ?: error(
                "musl dynamic linker not found in nativeLibraryDir. " +
                    "The APK was probably built without the bundled linker — " +
                    "rebuild with the latest source."
            )

        BinaryManager(context).ensureRuntime().getOrThrow()
        val binary = Paths.binary(context)
        if (!binary.exists() || !binary.canExecute()) {
            error("opencode binary not imported or not executable")
        }
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
        )

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

        // Mount the opencode binary inside the rootfs at a stable path so we
        // can invoke it as `/usr/local/bin/opencode` from inside the proot
        // session. Use --bind instead of copying.
        val opencodeBind = "--bind=${binary.absolutePath}:/usr/local/bin/opencode"

        // Mount the user's working directory as /root inside the sandbox so
        // opencode's `process.cwd()` resolves to a real, writable folder
        // that the user actually cares about.
        val workDir = workingDirectory ?: Paths.workspaceDir(context)
        workDir.mkdirs()
        val workspaceBind = "--bind=${workDir.absolutePath}:/root"

        // Build the opencode command that runs inside the proot sandbox.
        // We use `sh -lc` so /etc/profile is sourced (sets PATH etc.).
        val opencodeArgs = mutableListOf(
            "serve",
            "--hostname", hostname,
            "--port", port.toString(),
        )
        if (!password.isNullOrBlank()) {
            // The password is passed via env var so it doesn't show in `ps`.
            // We export it inline before running opencode.
        }
        val shellCmd = buildString {
            if (!password.isNullOrBlank()) {
                append("export OPENCODE_SERVER_PASSWORD=").append(escapeShell(password)).append("; ")
            }
            append("export OPENCODE_DISABLE_UPDATE=1 OPENCODE_DISABLE_AUTOUPDATE=1 OPENCODE_DISABLE_PROJECT_CONFIG=1; ")
            append("exec opencode ").append(opencodeArgs.joinToString(" ") { escapeShell(it) })
        }

        val pb = proot.launch(
            argv = listOf("/bin/sh", "-lc", shellCmd),
            cwd = "/root",
            extraBinds = listOf(opencodeBind, workspaceBind),
        )

        // CRITICAL: opencode is musl-linked and DT_NEEDEDs libstdc++.so.6 and
        // libgcc_s.so.1. The Alpine base rootfs does NOT ship these (Alpine
        // base is musl-only, no C++ stdlib). We already bundle them in
        // filesDir/opencode/lib/ for direct-launch mode — we need to make
        // them findable by the musl dynamic linker INSIDE the proot sandbox
        // too.
        //
        // Since we have --bind=<filesDir>:<filesDir>, the path
        //   /data/data/<pkg>/files/opencode/lib/
        // is valid inside the sandbox. Adding it to LD_LIBRARY_PATH lets
        // Alpine's musl linker find our bundled libstdc++.so.6 and
        // libgcc_s.so.1 when it loads opencode.
        val opencodeLibDir = Paths.libDir(context).absolutePath
        val existingLdPath = pb.environment()["LD_LIBRARY_PATH"] ?: ""
        pb.environment()["LD_LIBRARY_PATH"] = listOf(
            existingLdPath,
            opencodeLibDir,
        ).filter { it.isNotEmpty() }.joinToString(":")

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
