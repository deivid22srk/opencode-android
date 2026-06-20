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
 * Wraps a running `opencode serve` process launched through the bundled musl
 * dynamic linker. Streams stdout/stderr to a log file and to a callback so the
 * UI can show real-time output.
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
     * Launches `opencode serve` with the given options. Returns the bound URL.
     *
     * The opencode CLI prints `opencode server listening on http://...` to
     * stdout when it's ready. We watch stdout for that line and resolve as
     * soon as we see it (or fail if the process exits first).
     */
    fun start(
        port: Int,
        hostname: String,
        password: String?,
        onLog: (String) -> Unit,
    ): Result<String> = runCatching {
        if (isRunning()) error("Server is already running")

        BinaryManager(context).ensureRuntime().getOrThrow()
        val binary = Paths.binary(context)
        if (!binary.exists() || !binary.canExecute()) {
            error("opencode binary not imported or not executable")
        }
        val linker = Paths.linker(context)
        if (!linker.exists() || !linker.canExecute()) {
            error("musl dynamic linker is missing")
        }
        val libDir = Paths.libDir(context)

        Paths.logsDir(context).mkdirs()
        val logFile = Paths.logFile(context)
        if (logFile.exists()) logFile.delete()
        logFile.createNewFile()
        val logWriter = OutputStreamWriter(logFile.outputStream().buffered(), Charsets.UTF_8)
        writerRef.set(logWriter)

        val cmd = mutableListOf(
            linker.absolutePath,
            "--library-path",
            libDir.absolutePath,
            binary.absolutePath,
            "serve",
            "--hostname", hostname,
            "--port", port.toString(),
        )

        val pb = ProcessBuilder(cmd).apply {
            // Force a writable HOME inside app-private storage so opencode
            // doesn't try to write to /root or /home.
            val home = Paths.configDir(context).apply { mkdirs() }
            environment()["HOME"] = home.absolutePath
            environment()["TMPDIR"] = context.cacheDir.absolutePath
            environment()["OPENCODE_INSTALL_DIR"] = Paths.binDir(context).absolutePath
            environment()["XDG_DATA_HOME"] = File(home, "share").absolutePath
            environment()["XDG_CONFIG_HOME"] = File(home, "config").absolutePath
            environment()["XDG_CACHE_HOME"] = context.cacheDir.absolutePath
            // Disable opencode's auto-update path; we manage versions manually
            environment()["OPENCODE_DISABLE_UPDATE"] = "1"
            if (!password.isNullOrBlank()) {
                environment()["OPENCODE_SERVER_PASSWORD"] = password
            }
            // Strip Android-specific env that might confuse musl/Linux code
            environment().remove("ANDROID_DATA")
            environment().remove("ANDROID_ROOT")
            environment().remove("CLASSPATH")
            redirectErrorStream(true)
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
