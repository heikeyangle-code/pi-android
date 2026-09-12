package app.pi.ui.terminal

import android.content.Context
import app.pi.runtime.PtyLauncher
import app.pi.runtime.PtySession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * One Workbench terminal tab: a guest process on one side, libvterm on the
 * other, and nothing of our own in between.
 *
 * ## What this class is, and what it deliberately is not
 *
 * It is the two-way byte pump the terminal library asks for. `org.connectbot:termlib`
 * owns the emulation (libvterm over JNI) and the rendering (a Compose component);
 * it owns **no process**, and its KDoc says so: "This class provides terminal
 * emulation without PTY management. The caller is responsible for: Creating and
 * managing the PTY; Reading data from PTY and feeding to writeInput(); Handling
 * onKeyboardInput() callback and writing to PTY."
 *
 * That split is exactly this app's existing split, which is why the library
 * could be dropped in without touching the PTY:
 *
 *  - **Guest → screen** is [PtyLauncher.start]'s `onOutput`, handed straight to
 *    [TerminalEmulator.writeInput]. No interpretation happens here, so nothing
 *    can mis-parse an escape sequence on the way in.
 *  - **Keyboard → guest** is the library's `onKeyboardInput`, handed straight to
 *    [PtySession.write]. The library encoded the bytes (including the Kitty and
 *    xterm `modifyOtherKeys` variants it negotiates with the host program), so
 *    this class must not encode anything a second time.
 *
 * The previous implementation had a hand-written VT parser between these two
 * points. It is gone: the only terminal state this class keeps is the one bit
 * the library does not expose, bracketed-paste mode (see [BracketedPasteWatcher]).
 *
 * ## Threading
 *
 * `onOutput` arrives on the reader thread and is forwarded synchronously, which
 * is what keeps guest latency down and is safe because libvterm serialises its
 * own calls behind a mutex. `onKeyboardInput` arrives on the main looper, so it
 * is *not* written inline: a paste of a few hundred kilobytes would otherwise
 * block the UI thread on a full pipe. All writes go through one single-thread
 * executor instead, which is the part that matters — ordering between a
 * keystroke and a paste is preserved.
 */
class TerminalBridge private constructor(
    private val context: Context,
    /**
     * The cell grid the emulator starts at, before the view has been measured.
     *
     * A placeholder, not the real size: [start] replaces it with the grid the view
     * reports, because that is the one the guest's PTY gets pinned to.
     */
    initialRows: Int,
    initialColumns: Int,
    private val palette: TerminalPalette,
    /** Guest command; blank means the interactive shell (see `PtyLauncher.Spec`). */
    private val command: String,
    /**
     * Where an OSC 52 request from the guest goes.
     *
     * The library decodes the sequence and hands over the text, but the Android
     * clipboard is the app's to own, so the pane supplies this sink. The library
     * posts its OSC handling to the main looper, so the sink is always called on
     * the main thread and may touch the clipboard directly.
     */
    private val onClipboardCopy: (String) -> Unit,
    private val writer: ExecutorService,
) {

    /**
     * libvterm, wrapped by the library. Created here rather than taken as a
     * constructor argument so that its callbacks can close over `this`.
     *
     * It exists before the guest does, and that ordering is load-bearing: the pane
     * has to render an emulator in order to measure the area the terminal will
     * occupy, and the grid that measurement produces is what the guest's PTY is
     * pinned to at spawn. See [start].
     */
    val emulator: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = initialRows,
        initialCols = initialColumns,
        defaultForeground = palette.foreground,
        defaultBackground = palette.background,
        onKeyboardInput = { bytes -> writeToGuest(bytes) },
        onClipboardCopy = { text -> onClipboardCopy(text) },
    )

    /**
     * `(rows, cols)` once [start] has run, else null.
     *
     * This is the grid the guest's `stty` and `COLUMNS`/`LINES` were pinned to, and
     * the pane hands it to the library as `forcedSize` so the emulator cannot be
     * reflowed away from it — load-bearing rather than cosmetic, because `script(1)`
     * cannot resize the pty it created: the guest's idea of the grid is frozen when
     * the process starts, and an emulator that followed the view would paint a grid
     * the program never laid out.
     *
     * Freezing a grid is only acceptable because the grid is *measured to fit* the
     * view before the spawn (the pane's `terminalGrid`). Pinning a hard-coded
     * `80x26` is what left the terminal as a thin band on a phone: 80 columns force
     * the fitting font down to a few sp, and 26 rows of it fill less than half the
     * height.
     */
    @Volatile
    var pinnedSize: Pair<Int, Int>? = null
        private set

    private val bracketedPaste = BracketedPasteWatcher()

    @Volatile private var session: PtySession? = null

    @Volatile private var started = false

    @Volatile var exitCode: Int? = null
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile var bytesRead: Long = 0
        private set

    val isRunning: Boolean get() = session?.isRunning == true

    // ---------------------------------------------------------------------- start

    /**
     * Pin the grid and start the guest. Idempotent: only the first call starts
     * anything, so a later view resize cannot restart the user's shell.
     *
     * @param rows the measured rows, from the pane's `terminalGrid`.
     * @param columns the measured columns, same source.
     */
    fun start(rows: Int, columns: Int) {
        if (started) return
        started = true
        emulator.resize(rows, columns)
        pinnedSize = rows to columns
        connect(rows, columns)
    }

    // ----------------------------------------------------------------------- paste

    /**
     * Send clipboard text to the guest.
     *
     * Bracketed paste is the guest's choice, not ours: pi turns it on itself
     * (`packages/tui/src/terminal.ts:186` writes `ESC [ ? 2004 h`) and its editor
     * splits input on those markers (`packages/tui/src/components/editor.ts:707`),
     * while a plain `cat` never asked for them and would print the markers
     * literally. So the wrapping follows the mode the guest actually set, which
     * is what [BracketedPasteWatcher] tracks.
     *
     * Two risks are the terminal's job to inherit and the user's to know, and
     * neither is handled here: pasted text is delivered verbatim, so a newline in
     * a shell runs the next line, and an escape sequence in the pasted text is
     * interpreted by the guest as input. Real terminals behave the same way; the
     * bracketed-paste wrapper exists precisely because programs that opt in can
     * then treat the block as data.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val payload = if (bracketedPaste.enabled) "\u001b[200~$text\u001b[201~" else text
        writeToGuest(payload.toByteArray(Charsets.UTF_8))
    }

    // -------------------------------------------------------------------- lifecycle

    /** End the session. Safe to call more than once. */
    fun close() {
        session?.close()
        writer.shutdownNow()
    }

    // --------------------------------------------------------------------- internals

    private fun writeToGuest(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val current = session ?: return
        // Rejected once the executor is shut down, and a write racing a close is
        // not an error worth surfacing.
        runCatching { writer.execute { current.write(bytes, bytes.size) } }
    }

    /**
     * Start the guest process on the grid [start] pinned, and connect it to the
     * emulator.
     *
     * Called once from [start], so a failure is reported through [lastError]
     * rather than preventing the terminal from existing: an empty screen is a
     * worse answer than a message.
     */
    private fun connect(rows: Int, columns: Int) {
        val launched = runCatching {
            PtyLauncher.start(
                context = context,
                spec = PtyLauncher.Spec(
                    command = command,
                    columns = columns,
                    rows = rows,
                ),
                onOutput = { bytes, length ->
                    // Called on the reader thread, and synchronous on purpose:
                    // libvterm copies the bytes before returning, so the buffer
                    // the session reuses is safe to hand over unmodified.
                    bracketedPaste.feed(bytes, length)
                    emulator.writeInput(bytes, 0, length)
                    bytesRead += length
                },
                onExit = { code -> exitCode = code },
            )
        }
        launched.onSuccess { session = it }
        launched.onFailure { error ->
            lastError = "${error::class.java.simpleName}: ${error.message}"
        }
    }

    companion object {

        /**
         * Create a terminal: the emulator, but no guest yet.
         *
         * The process starts in [start], once the view has been measured. That
         * split exists because the guest's PTY is pinned at spawn and cannot be
         * resized afterwards, so the grid has to be known *before* the spawn — and
         * the only honest source for it is the size the terminal will be drawn at.
         *
         * The returned bridge always exists, even when the guest cannot start.
         */
        fun open(
            context: Context,
            rows: Int,
            columns: Int,
            palette: TerminalPalette,
            onClipboardCopy: (String) -> Unit,
            command: String = "",
        ): TerminalBridge {
            val writer = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "pi-pty-writer").apply { isDaemon = true }
            }
            return TerminalBridge(
                context = context,
                initialRows = rows,
                initialColumns = columns,
                palette = palette,
                command = command,
                onClipboardCopy = onClipboardCopy,
                writer = writer,
            )
        }
    }
}

/**
 * Tracks `DECSET 2004` / `DECRST 2004` in the guest's output.
 *
 * Only the app can track this: libvterm knows the mode internally, but the
 * library exposes neither a getter for it nor a `paste` entry point, so the byte
 * stream the bridge already sees is the cheapest honest source. It is a
 * byte-at-a-time matcher over `ESC [ ? 2 0 0 4` and the one byte that decides
 * `h` (set) or `l` (reset); no allocation, no buffering, and a sequence split
 * across two 64 KiB reads still matches because the partial match survives the
 * call boundary. A byte that is neither `h` nor `l` restarts the match, so a
 * truncated sequence cannot swallow the next one.
 */
private class BracketedPasteWatcher {

    private var matched = 0

    var enabled: Boolean = false
        private set

    fun feed(bytes: ByteArray, length: Int) {
        for (index in 0 until length) {
            val byte = bytes[index]
            if (matched == PREFIX.size) {
                when (byte) {
                    FINAL_SET -> enabled = true
                    FINAL_RESET -> enabled = false
                }
                matched = if (byte == PREFIX[0]) 1 else 0
                continue
            }
            matched = when {
                byte == PREFIX[matched] -> matched + 1
                byte == PREFIX[0] -> 1
                else -> 0
            }
        }
    }

    private companion object {
        /** `ESC [ ? 2 0 0 4`, with the final byte deciding set or reset. */
        val PREFIX = byteArrayOf(0x1b, '['.code.toByte(), '?'.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '4'.code.toByte())
        const val FINAL_SET = 'h'.code.toByte()
        const val FINAL_RESET = 'l'.code.toByte()
    }
}
