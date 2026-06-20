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
 * Interactive terminal session that runs /bin/sh (or any other command)
 * inside the proot'd Alpine rootfs. Output is streamed to subscribers; the
 * user sends input via [write].
 *
 * Implementation: we spawn `proot ... /bin/sh -l` with stdin/stdout/stderr
 * piped. This isn't a real PTY (no termios, no window-size), but it's enough
 * for an interactive shell for basic commands. ANSI escape sequences (colors,
 * cursor movement) pass through unchanged.
 *
 * The session lives until the user closes it or the process exits.
 */
class TerminalSession(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var readJob: Job? = null

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 1024)
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

            // Launch /bin/sh as a login shell. We use +l (login) so /etc/profile
            // is sourced — sets PATH, PS1, etc.
            //
            // IMPORTANT: We use a custom PS1 prompt and force the shell to
            // echo input by setting `stty sane` and disabling canonical mode
            // issues via the `-i` (interactive) flag. Without a real PTY,
            // we need the shell to:
            //   1. Print a prompt after each command
            //   2. Echo what the user types
            //   3. Flush output immediately (no buffering)
            //
            // We set PS1 to a ReTerminal-style green prompt:
            //   root@opencode:~#
            //
            // The shell reads from stdin line-by-line. When the user sends
            // a command + "\n", the shell executes it and writes output to
            // stdout, which we stream to the UI.
            val pb = proot.launch(
                argv = listOf("/bin/sh", "-i"),
                cwd = "/root",
                extraBinds = emptyList(),
            )

            // Pipe all three streams — we read stdout, write to stdin.
            pb.redirectInput(ProcessBuilder.Redirect.PIPE)
            pb.redirectOutput(ProcessBuilder.Redirect.PIPE)
            pb.redirectError(ProcessBuilder.Redirect.PIPE)
            pb.redirectErrorStream(true) // merge stderr into stdout

            val proc = pb.start()
            process = proc
            stdin = proc.outputStream

            // Set up the prompt and environment. We send these as initial
            // commands so the shell is configured before the user types.
            val d = "${'$'}"  // literal dollar sign, avoiding Kotlin template syntax
            val initScript = buildString {
                append("export PS1='${d}{GREEN}\\u${d}{RESET}@\\h:\\w# '\n")
                append("export GREEN='\\033[01;32m'\n")
                append("export RESET='\\033[0m'\n")
                append("export TERM=xterm-256color\n")
                append("export HOME=/root\n")
                append("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
                append("stty sane 2>/dev/null || true\n")
                append("echo ''\n")
                append("echo -e '\\033[01;32mWelcome to Alpine Linux (proot)\\033[0m'\n")
                append("echo -e 'Type commands below. Try: ls, apk update, apk add python3'\n")
                append("echo ''\n")
            }
            // Send the init script line by line, each followed by newline.
            for (line in initScript.split('\n')) {
                stdin?.write((line + "\n").toByteArray())
            }
            stdin?.flush()

            // Stream output to subscribers. We read raw bytes (not buffered
            // reader) so binary data and ANSI sequences pass through intact.
            readJob = scope.launch {
                try {
                    val input = proc.inputStream
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val chunk = String(buf, 0, n, Charsets.UTF_8)
                        _output.emit(chunk)
                    }
                } catch (e: Exception) {
                    _output.emit("\r\n[terminal read error: ${e.message}]\r\n")
                } finally {
                    _output.emit("\r\n[terminal session ended]\r\n")
                }
            }

            // Wait for the process to exit in a separate coroutine.
            scope.launch {
                val code = proc.waitFor()
                _output.emit("\r\n[process exited with code $code]\r\n")
                process = null
                stdin = null
            }
        }.let { result ->
            result.map { }
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
