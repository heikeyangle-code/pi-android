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
 *  3. **Close is idempotent and terminal.** `destroy()` ends the direct child, and on
 *     proot `--kill-on-exit` takes its children with it; on proroot there is no such
 *     option, so [close] reaps the tree first ([GuestTreeReaper]). Closing the
 *     streams unblocks the reader thread, which is the only way it ever exits.
 */
class PtySession private constructor(
    private val process: Process,
    private val output: OutputStream,
    private val input: InputStream,
    /**
     * Which runtime launched this terminal. It changes what stopping means: proot
     * takes its tree with it (`--kill-on-exit`), proroot has no such option and the
     * tree has to be reaped by the app ([GuestTreeReaper]).
     */
    private val engine: GuestEngine = GuestEngine.Proot,
    /**
     * Called once, after the tree is gone, with this launch's handle. `PtyLauncher`
     * uses it to delete the `.proroot-config-<pid>` table this launch left behind —
     * the same unlink [RuntimeSelection.sweepProrootConfigs] would do later, done
     * while the pid is still known. Null on proot, where there is no such table.
     */
    private val handle: ProrootLaunchHandle? = null,
    private val onStopped: (ProrootLaunchHandle?) -> Unit = {},
) {

    private val closed = AtomicBoolean(false)

    /** The launch's config handle, for callers that need the pid. */
    val launch: ProrootLaunchHandle? get() = handle

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
     *
     * On proroot this is where `--kill-on-exit` used to do its work, and it is done
     * **on its own thread**. The reason is a real constraint rather than tidiness:
     * `close()` is called from `TerminalBridge.close()`, which runs in a Compose
     * `DisposableEffect`'s `onDispose` — i.e. on the **main thread** — and
     * [GuestTreeReaper] deliberately waits (TERM, then KILL) for up to five seconds.
     * Blocking a screen teardown for that long is an ANR, so the fast parts (close the
     * streams, kill the direct child) stay synchronous and the reaping is handed to a
     * daemon thread that also deletes this launch's config table when it is done.
     *
     * TERM first, then KILL, deepest first, and a survivor count that is reported
     * instead of assumed away — [GuestTreeReaper] carries that reasoning; the pid's
     * identity is checked against the start time recorded at launch, so a recycled pid
     * is never signalled.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val launch = handle
        // Capture **first**, on this thread, while the launcher is still alive: the
        // tree is only reachable through its parent-child edges, and the
        // `process.destroy()` below reparents the children to init
        // ([GuestTreeReaper.capture]). The capture is a `/proc` read — milliseconds —
        // whereas the reaping that follows waits for TERM and then KILL, which is why
        // only that half goes to a thread.
        val captured = if (engine.usesOptInPlumbing) {
            val pid = launch?.launcherPid
            if (pid == null) {
                lastError = "停止终端时拿不到 proroot 的 launcher pid（.proroot-config 表没有出现），未回收 guest 进程树"
                runCatching { onStopped(launch) }
                null
            } else {
                runCatching { GuestTreeReaper.capture(pid, launch.launcherStartTime) }
                    .getOrNull()
                    .also { snapshot ->
                        if (snapshot == null) {
                            lastError = "停止终端时无法捕获 guest 进程树：launcher pid 已不存在或已被回收"
                            runCatching { onStopped(launch) }
                        }
                    }
            }
        } else {
            null
        }
        runCatching { output.close() }
        runCatching { process.destroy() }
        runCatching { input.close() }
        if (captured != null) {
            runCatching {
                GuestTreeReaper.reapInBackground(captured) { report ->
                    if (!report.clean) {
                        lastError = "停止终端后仍有 guest 进程存活：${report.survivors.joinToString()}"
                    }
                    runCatching { onStopped(launch) }
                }
            }.onFailure { runCatching { onStopped(launch) } }
        }
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
            /** The engine [argv] was built for; decides how [close] stops the tree. */
            engine: GuestEngine = GuestEngine.Proot,
            /**
             * proroot's scratch directory, or null on proot. Arming happens **here**,
             * around `start()`, because a handle is only valid for the process that
             * starts after it was armed.
             */
            prorootTmp: File? = null,
            /** This launch's identity token; see [ProrootLaunchHandle]. */
            launchToken: String? = null,
            /** See the constructor's `onStopped`. */
            onStopped: (ProrootLaunchHandle?) -> Unit = {},
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
            val handle = if (engine.usesOptInPlumbing && prorootTmp != null) {
                ProrootLaunchHandle.arm(prorootTmp, launchToken)
            } else {
                null
            }
            val process = builder.start()
            // Resolve after the process exists: the table is written by the launcher,
            // which is now running.
            handle?.resolveLauncherPid()
            val session = PtySession(process, process.outputStream, process.inputStream, engine, handle, onStopped)
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
            } catch (error: IOException) {
                // A closed stream is how this thread is asked to stop, and [close] is
                // the only thing that closes it — so a failure while the session is
                // still open is not the normal path. It is also the one failure this
                // thread can cause silently: nothing else drains the guest's output, so
                // the pipe fills and the program inside blocks in `write` with a screen
                // that never updates. That is rule 1 of the class KDoc ("Output can
                // never block the guest"), and `lastError` is what the key bar shows.
                if (!closed.get()) {
                    lastError = "读取终端输出失败，已停止读取：${error.message ?: error::class.java.simpleName}"
                }
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
