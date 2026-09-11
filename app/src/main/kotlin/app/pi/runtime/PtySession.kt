package app.pi.runtime

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One running terminal: a process and its pipes.
 *
 * The PTY itself is allocated *inside the guest* by `script(1)`, so from this
 * side there is nothing terminal-specific at all — just a process with pipes.
 * That is the whole point of the approach (see [PtyLauncher]): Android never has
 * to fork a pty, and this class stays ordinary I/O. It is deliberately **not**
 * part of the terminal emulator: the VT parsing and rendering belong to
 * `org.connectbot:termlib`, which is handed these bytes by
 * `app.pi.ui.terminal.TerminalBridge`.
 *
 * Three rules it exists to enforce:
 *
 *  1. **Output can never block the guest.** A reader thread drains stdout for the
 *     life of the process. A terminal that stops reading is a terminal where the
 *     program hangs mid-escape-sequence — the same failure mode as an undrained
 *     `stderr` in the engine session.
 *  2. **Input is echoed by the pty, not by the app.** Nothing here writes back
 *     what the user typed: `script`'s pty has the line discipline, so `bash`
 *     echoes in canonical mode and full-screen programs disable echo in raw mode.
 *     An app that also echoed would double every character.
 *  3. **Close is idempotent and terminal.** `destroy()` kills the proot tree
 *     (`--kill-on-exit` makes proot take its children with it) and closing the
 *     streams unblocks the reader thread, which is the only way it ever exits.
 */
class PtySession private constructor(
    private val process: Process,
    private val output: OutputStream,
    private val input: InputStream,
) {

    private val closed = AtomicBoolean(false)

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean get() = !closed.get() && exitCode == null

    /** Bytes decoded so far, for diagnostics. */
    @Volatile
    var bytesRead: Long = 0
        private set

    fun write(text: String) {
        if (closed.get()) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        write(bytes, bytes.size)
    }

    fun write(bytes: ByteArray, length: Int) {
        if (closed.get() || length <= 0) return
        try {
            output.write(bytes, 0, length)
            output.flush()
        } catch (error: IOException) {
            lastError = "写入终端失败: ${error.message}"
        }
    }

    /**
     * End the session. Safe to call from any thread and more than once; the
     * readers stop because the streams close, not because a flag is checked.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { process.destroy() }
        runCatching { input.close() }
        if (exitCode == null) exitCode = process.exitValueOrNull()
    }

    private fun Process.exitValueOrNull(): Int? = runCatching { exitValue() }.getOrNull()

    companion object {

        /**
         * Read buffer. 64 KiB is large enough that a full-screen repaint of a
         * verbose TUI arrives in a handful of reads, and small enough that the
         * emulator's incremental UTF-8 decoder never has to hold much.
         */
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * @param greeting text to paint before the guest's first byte, so a tab
         *        that cannot work explains itself. Null for the normal case.
         * @param onOutput called on the reader thread for each chunk, in order.
         * @param onExit called once, after the final output chunk.
         */
        fun spawn(
            argv: List<String>,
            environment: Map<String, String>,
            workingDirectory: File,
            greeting: String? = null,
            onOutput: (ByteArray, Int) -> Unit = { _, _ -> },
            onExit: (Int) -> Unit = {},
        ): PtySession {
            val builder = ProcessBuilder(argv)
                .directory(workingDirectory)
                // stderr joins stdout: on a terminal there is one stream, and
                // keeping it that way is what makes an interleaved error appear
                // where the program wrote it.
                .redirectErrorStream(true)
            builder.environment().putAll(environment)
            val process = builder.start()
            val session = PtySession(process, process.outputStream, process.inputStream)
            if (!greeting.isNullOrEmpty()) {
                // Paint first, then read: the greeting is our own text and must
                // not race the guest's.
                val bytes = greeting.toByteArray(Charsets.UTF_8)
                onOutput(bytes, bytes.size)
            }
            session.startReaders(onOutput, onExit)
            return session
        }
    }

    private fun startReaders(onOutput: (ByteArray, Int) -> Unit, onExit: (Int) -> Unit) {
        val reader = Thread({
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    bytesRead += read
                    onOutput(buffer, read)
                }
            } catch (_: IOException) {
                // A closed stream is how this thread is asked to stop.
            }
        }, "pi-pty-reader")
        reader.isDaemon = true
        reader.start()

        val waiter = Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            exitCode = code
            // Let the reader drain whatever the process wrote before it exited:
            // the last frame of a TUI *is* the output, and dropping it is the
            // difference between a clean exit and a torn screen.
            runCatching { reader.join(1000) }
            onExit(code)
        }, "pi-pty-waiter")
        waiter.isDaemon = true
        waiter.start()
    }
}
