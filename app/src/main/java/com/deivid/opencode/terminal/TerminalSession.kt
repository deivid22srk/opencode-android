package com.deivid.opencode.terminal

import android.content.Context
import com.deivid.opencode.server.ProotSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Interactive PTY-like terminal session that runs /bin/sh (or any other
 * command) inside the proot'd Alpine rootfs. Output is streamed to
 * subscribers; the user sends input via [write].
 *
 * Implementation: we spawn `proot ... /bin/sh -l` with stdin/stdout/stderr
 * piped. This isn't a real PTY (no termios, no window-size), but it's enough
 * for an interactive shell. ANSI escape sequences (colors, cursor movement)
 * pass through unchanged.
 *
 * The session lives until the user closes it or the process exits. Multiple
 * UIs can subscribe to [output] simultaneously.
 */
class TerminalSession(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var readJob: Job? = null

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output.asSharedFlow()

    val isAlive: Boolean get() = process?.isAlive == true

    /**
     * Launches an interactive shell inside the proot'd Alpine rootfs.
     * Idempotent: if a session is already running, returns immediately.
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isAlive) return@withContext Result.success(Unit)

        runCatching {
            val proot = ProotSession(context)
            proot.ensureInstalled().getOrThrow()

            // Launch /bin/sh as a login shell. -l sources /etc/profile so
            // PATH, prompt, etc. are set up properly.
            val pb = proot.launch(
                argv = listOf("/bin/sh", "-l"),
                cwd = "/root",
                extraBinds = emptyList(),
            )

            // Make sure stdin is a pipe (not inherited) so we can write to it.
            pb.redirectInput(ProcessBuilder.Redirect.PIPE)
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            // Don't merge stderr — we want to capture it separately but
            // display it in the same terminal.
            pb.redirectErrorStream(true)

            val proc = pb.start()
            process = proc
            stdin = proc.outputStream

            // Stream output to subscribers
            readJob = scope.launch {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(2048)
                        while (true) {
                            val n = r.read(buf)
                            if (n <= 0) break
                            _output.emit(String(buf, 0, n))
                        }
                    }
                } catch (e: Exception) {
                    _output.emit("\r\n[terminal read error: ${e.message}]\r\n")
                } finally {
                    _output.emit("\r\n[terminal session ended]\r\n")
                }
            }

            // Wait for the process to exit (in another coroutine so we don't
            // block the IO dispatcher's thread pool).
            scope.launch {
                val code = proc.waitFor()
                _output.emit("\r\n[process exited with code $code]\r\n")
                process = null
                stdin = null
            }
        }
    }

    /**
     * Sends bytes to the shell's stdin. The user's keystrokes go here.
     * Returns false if the session isn't running.
     */
    fun write(data: String): Boolean {
        val s = stdin ?: return false
        val p = process ?: return false
        if (!p.isAlive) return false
        return try {
            s.write(data.toByteArray(Charsets.UTF_8))
            s.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends Ctrl-C (SIGINT) to the foreground process. We can't actually
     * send a signal via Process, so we send the Ctrl-C byte (0x03) which
     * the shell interprets as SIGINT for the foreground job.
     */
    fun sendCtrlC(): Boolean = write("\u0003")

    /** Sends Ctrl-D (EOF) — closes the shell. */
    fun sendCtrlD(): Boolean = write("\u0004")

    /**
     * Forces the session to end. Sends SIGHUP via destroy() and then
     * destroyForcibly() if it doesn't exit within 2s.
     */
    fun stop() {
        readJob?.cancel()
        process?.let { p ->
            try {
                p.destroy()
                if (!p.waitFor(2_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }
        process = null
        stdin = null
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
