package app.pi.terminal

import android.content.Context
import app.pi.runtime.PtyLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Glue between one guest process and one emulator, with a Compose-observable
 * revision.
 *
 * Compose cannot observe a mutable grid, so the contract is deliberately blunt: a
 * single background coroutine consumes output, mutates the emulator, and bumps
 * [revision]; the composable re-reads the emulator when the revision changes.
 * Cells are not copied per frame — the view renders the emulator's own grid,
 * exactly as a terminal emulator's screen buffer is read by its display — which is
 * safe because exactly one coroutine ever mutates it, and the renderer only reads.
 *
 * The reader thread never touches the emulator. It hands chunks to a conflated
 * channel, so a burst that outruns the UI is collapsed instead of queued: the grid
 * is *state*, not a log, and painting a stale frame is worse than painting a fresh
 * one slightly later.
 */
class TerminalController private constructor(
    private val context: Context,
    val palette: TerminalPalette,
    val kind: PtyLauncher.Kind,
    val columns: Int,
    val rows: Int,
    /**
     * Scrollback ring capacity, from `app.terminal.scrollbackLines`
     * (`ui/terminal/TerminalSettings.kt`). pi caps nothing itself — this is the
     * app's own buffer, and the setting's description promises exactly this knob.
     */
    historyLimit: Int,
    /** Guest command for [PtyLauncher.Kind.Custom]; ignored otherwise. */
    val command: String,
    private val scope: CoroutineScope,
) {

    /** What the view needs to paint. */
    class Snapshot(
        val revision: Long,
        val emulator: TerminalEmulator.Snapshot,
        val bytesRead: Long,
        val exitCode: Int?,
        val error: String?,
    ) {
        val columns: Int get() = emulator.active.columns
        val rows: Int get() = emulator.active.rows
    }

    private val emulator = TerminalEmulator(columns, rows, palette, historyLimit)

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    private val chunks = Channel<ByteArray>(capacity = 96, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val clipboardRequests = Channel<String>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile private var session: PtySession? = null

    /** The guest's own title (`OSC 0/1/2`); used for the tab label when set. */
    @Volatile var guestTitle: String? = null
        private set

    @Volatile var exitCode: Int? = null
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var bytesRead: Long = 0
        private set

    fun snapshot(): Snapshot = Snapshot(
        revision = _revision.value,
        emulator = emulator.snapshot(),
        bytesRead = bytesRead,
        exitCode = exitCode,
        error = lastError,
    )

    val isRunning: Boolean get() = session?.isRunning == true

    // ------------------------------------------------------------------- input

    fun send(text: String) {
        if (text.isEmpty()) return
        session?.write(text)
    }

    /** Bracketed paste when the guest asked for it, plain paste otherwise. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        session?.write(TerminalKeys.paste(text, emulator.isBracketedPaste))
    }

    /** Take one clipboard request the guest made (OSC 52), or null. */
    fun tryClipboardRequest(): String? = clipboardRequests.tryReceive().getOrNull()

    /** Send a hard interrupt, as the toolbar's `Ctrl+C` does. */
    fun interrupt() = send(TerminalKeys.controlByte('c'.code) ?: "\u0003")

    private fun pumpReplies() {
        // `DSR` answers go back to the guest: a program that asks where the
        // cursor is and never hears back blocks on the read.
        for (reply in emulator.takeReplies()) session?.write(reply)
        // OSC 52 belongs to the app: only it owns the Android clipboard.
        for (text in emulator.takeClipboardWrites()) clipboardRequests.trySend(text)
        emulator.bell()
    }

    // -------------------------------------------------------------------- life

    fun close() {
        session?.close()
        chunks.close()
        scope.cancel()
        _revision.value++
    }

    // --------------------------------------------------------------- rendering

    /** Lines of scrollback above the viewport, oldest first. */
    fun scrollbackSize(): Int = emulator.historySize()

    /**
     * Scrollback lines, read by the view. Public like [ScreenLine] itself: the
     * alternative is copying history into a snapshot per frame, and that copy is
     * the one allocation this design cannot afford.
     */
    fun scrollbackLine(index: Int): ScreenLine? = emulator.historyLine(index)

    /** The whole active viewport as plain text; used by diagnostics and tests. */
    fun dumpText(): String = emulator.dumpText()

    companion object {

        /**
         * Create and start a terminal.
         *
         * A controller is returned even when the guest cannot start: the failure
         * is part of the state the view paints, because an empty tab is a worse
         * answer than a message.
         */
        fun open(
            context: Context,
            kind: PtyLauncher.Kind,
            columns: Int,
            rows: Int,
            palette: TerminalPalette,
            historyLimit: Int = 2000,
            command: String = "",
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): TerminalController {
            val controller =
                TerminalController(context, palette, kind, columns, rows, historyLimit, command, scope)
            controller.connect()
            return controller
        }
    }

    private fun connect() {
        val session = runCatching {
            PtyLauncher.start(
                context = context,
                spec = PtyLauncher.Spec(kind = kind, command = command, columns = columns, rows = rows),
                onOutput = { bytes, length ->
                    // Called on the reader thread. Copy before queueing: the
                    // session reuses its buffer.
                    chunks.trySend(bytes.copyOf(length))
                },
                onExit = { code ->
                    exitCode = code
                    _revision.value++
                },
            )
        }.getOrElse { error ->
            lastError = "${error::class.java.simpleName}: ${error.message}"
            _revision.value++
            return
        }
        this.session = session

        scope.launch(Dispatchers.Default) {
            for (chunk in chunks) {
                emulator.feed(chunk, chunk.size)
                bytesRead = session.bytesRead
                val title = emulator.title
                if (title.isNotEmpty()) guestTitle = title
                pumpReplies()
                _revision.value++
            }
            // The channel closes only on `close()`, but the process may exit on
            // its own; make sure the last frame is painted either way.
            _revision.value++
        }
    }
}
