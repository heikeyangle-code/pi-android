package app.pi.terminal

import androidx.compose.ui.graphics.Color

/**
 * A VT/xterm emulator: enough of one to be *correct* for `pi`, on purpose.
 *
 * ## Why this exists
 *
 * `pi`'s TUI is the app's escape hatch for the extension APIs that draw terminal
 * cells (`ctx.ui.custom()`, overlays, custom footers, `registerMessageRenderer`,
 * `renderCall`/`renderResult`). Those are inert outside a real terminal, so the
 * Workbench runs the *original* TUI and has to paint what it emits.
 * docs/pi-android-app-design.md §20.4 planned to reuse termux-app's
 * `terminal-emulator` and its `forkpty` JNI; this project does not add a JNI
 * dependency, so the emulator is Kotlin and the PTY is allocated *inside the
 * guest* (see `PtyLauncher`).
 *
 * ## What is implemented
 *
 *  - Cursor movement `A B C D E F G H f d e a \`` and tabs (`I`, `HT`, HTS).
 *  - Erase: `ED` (0/1/2/3), `EL` (0/1/2), `ECH`, `DCH`, `IL`, `DL`.
 *  - SGR: reset, bold, dim, italic, underline, inverse, hidden, strike, 16/256/
 *    true colour on foreground and background, and the "no opinion" resets
 *    (22/23/24/25/27/28/29/39/49). Blink is parsed and ignored.
 *  - Modes: `?25` cursor visibility, `?7` autowrap, `?6` origin, `?47`/`?1047`/
 *    `?1049` alternate screen, `?2004` bracketed paste, `?2026` synchronised
 *    output, the mouse/focus-reporting set, and the modifyOtherKeys /
 *    kitty-keyboard *enable* forms (`>4;Nm`, `>Nu`).
 *  - Scroll regions (`DECSTBM`), `IND`/`NEL`/`RI`, `DECSC`/`DECRC`, `s`/`u`.
 *  - `DSR 5`/`6` are **answered** — a program that asks where the cursor is and
 *    never hears back blocks on the read. `DA1` is deliberately *not* answered,
 *    because pi uses the DA reply as the sentinel that ends its kitty-keyboard
 *    negotiation (see below).
 *  - OSC 0/1/2 title, OSC 8 hyperlinks, OSC 52 clipboard, OSC 9;4 progress.
 *  - APC (`ESC _ … BEL|ST`), which is where pi hides `CURSOR_MARKER`; it is
 *    consumed and recorded as a *cursor intent* rather than printed.
 *  - xterm window ops `14t`/`16t` (cell size) and `18t` (text-area size).
 *
 * ## Kitty keyboard protocol: answering nothing is the correct answer
 *
 * pi's `ProcessTerminal.start()` writes `ESC [ > 1 u ESC [ ? u ESC [ c` and then
 * decides: a kitty *flag report* means kitty mode, anything else — specifically
 * the `DA1` reply it asked for as a sentinel — means fall back to xterm
 * `modifyOtherKeys` (`packages/tui/src/terminal.ts:255-320`). So this emulator
 * answers the DA with `ESC [ ? 1 ; 2 c` and leaves the kitty query unanswered:
 * pi falls back to `modifyOtherKeys`, immediately, with no startup timeout. The
 * app then honours that fallback on the input side by sending `ESC [ 27 ; … ~`
 * for modified keys (see `TerminalKeys`).
 *
 * ## What is deliberately NOT implemented
 *
 *  - **Inline images** (kitty `ESC_G`, iTerm2 `OSC 1337`). pi is told
 *    `PI_IMAGE_PROTOCOL=none`; images in the GUI transcript are native, and a
 *    graphics protocol would be a lot of code for a diagnostic surface. The
 *    sequences are consumed so they cannot corrupt the grid.
 *  - **Mouse reporting.** The tracking modes are accepted and recorded, but no
 *    pointer events are ever reported. pi's fullscreen mode uses the mouse for
 *    wheel scrolling; the view scrolls its own scrollback instead, which is the
 *    better touch interaction anyway.
 *  - **Colon-parameter SGR** (`38:2::R:G:B`): consumed, not interpreted.
 *  - **Sixel, ReGIS, DRCS, double-width/height lines, DECALN.**
 *  - **Bidirectional text.** Cells are laid out left to right, so an RTL run is
 *    rendered in logical order. pi's TUI is LTR.
 *  - **Application keypad modes and cursor-shape (`DECSCUSR`)**: accepted, and
 *    the canvas draws one cursor shape.
 *  - **Resizing the guest's PTY.** `script`(1) has no way to set the inner
 *    terminal's window size, so the session declares `COLUMNS`/`LINES` once and
 *    the view lays out for exactly that grid. See `PtyLauncher`.
 *
 * The parser is driven by [Input] events rather than a byte stream, so it is
 * testable without a PTY and the UTF-8 decoding lives in exactly one place.
 */
class TerminalEmulator(
    columns: Int,
    rows: Int,
    val palette: TerminalPalette,
    historyLimit: Int = 2000,
) {

    internal val primary = Screen(columns, rows, historyLimit)
    internal val alternate = Screen(columns, rows, 0)

    private val scanner = TerminalInput.Scanner()
    private var usingAlternate = false

    internal val screen: Screen get() = if (usingAlternate) alternate else primary

    var columns: Int = columns
        private set
    var rows: Int = rows
        private set

    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedCursor = intArrayOf(0, 0, AnsiColors.DEFAULT, AnsiColors.DEFAULT, 0)

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private var autowrap = true
    private var cursorVisible = true
    private var originMode = false
    private var wrapPending = false
    private var cursorIntent: CursorIntent = CursorIntent.None

    private var bracketPaste = false
    private var progress: Progress? = null
    var title: String = ""
        private set

    /** Packed `0xRRGGBB` or [AnsiColors.DEFAULT]. */
    private var fg = AnsiColors.DEFAULT
    private var bg = AnsiColors.DEFAULT
    private var attrs = 0

    private val clipboardWrites = ArrayList<String>(2)
    private val replies = ArrayList<String>(4)
    private var bellPending = false

    private var openLink: String? = null
    private var openLinkRow = 0

    private var tabStops: BooleanArray = BooleanArray(columns) { it % 8 == 0 && it != 0 }

    private var updateRevision: Long = 0

    /** Pixel size of one cell, reported for `CSI 14t`/`16t`. Set by the view. */
    var cellWidthPx: Int = 8
    var cellHeightPx: Int = 16

    /** Where the hardware cursor belongs, as last expressed by an APC program. */
    enum class CursorIntent { None, LineStart, Free }

    data class Progress(val state: Int, val percent: Int)

    class CursorPosition(
        val row: Int,
        val column: Int,
        val visible: Boolean,
        val intent: CursorIntent,
    )

    class Snapshot(
        val columns: Int,
        val rows: Int,
        val updateRevision: Long,
        val screenRevision: Long,
        /** True while the alternate screen is showing. */
        val isAlternate: Boolean,
        val cursor: CursorPosition,
        val bracketPaste: Boolean,
        val autowrap: Boolean,
        val progress: Progress?,
        val primaryScreen: Screen.BufferSnapshot,
        val alternateScreen: Screen.BufferSnapshot,
        val scrollbackSize: Int,
    ) {
        val active: Screen.BufferSnapshot get() = if (isAlternate) alternateScreen else primaryScreen
    }

    // ------------------------------------------------------------------ reading

    val isAlternateScreen: Boolean get() = usingAlternate

    val isBracketedPaste: Boolean get() = bracketPaste

    /**
     * Where the hardware cursor currently is, plus the intent a program expressed
     * through an APC marker. Reading is how the view decides whether to paint it.
     */
    val cursorPosition: CursorPosition
        get() = CursorPosition(cursorRow, cursorColumn, cursorVisible, cursorIntent)

    fun progressValue(): Progress? = progress

    /** Painted background for the whole view, so the canvas and grid agree. */
    val background: Color get() = palette.background

    fun snapshot(): Snapshot {
        val revision = updateRevision
        val screenRevision = screen.revision
        return Snapshot(
            columns = columns,
            rows = rows,
            updateRevision = revision,
            screenRevision = screenRevision,
            isAlternate = usingAlternate,
            cursor = cursorPosition,
            bracketPaste = bracketPaste,
            autowrap = autowrap,
            progress = progress,
            primaryScreen = primary.snapshot(),
            alternateScreen = alternate.snapshot(),
            scrollbackSize = primary.historySize(),
        )
    }

    fun isDirtySince(revision: Long): Boolean = updateRevision != revision

    /** Drain writes the guest addressed to the system clipboard (OSC 52). */
    fun takeClipboardWrites(): List<String> {
        if (clipboardWrites.isEmpty()) return emptyList()
        val out = ArrayList<String>(clipboardWrites)
        clipboardWrites.clear()
        return out
    }

    /** Responses the emulator owes the guest (DSR, window reports). */
    fun takeReplies(): List<String> {
        if (replies.isEmpty()) return emptyList()
        val out = ArrayList<String>(replies)
        replies.clear()
        return out
    }

    /** True once per BEL the guest emitted, and clears it. */
    fun bell(): Boolean {
        val rang = bellPending
        bellPending = false
        return rang
    }

    // ------------------------------------------------------------------ feeding

    /** Feed raw bytes from the guest, decoding UTF-8 incrementally. */
    fun feed(bytes: ByteArray, length: Int, endOfInput: Boolean = false) {
        dispatch(TerminalInput.decode(bytes, length, endOfInput), endOfInput)
    }

    /** Feed already-decoded text; used by tests and by the local echo path. */
    fun feedText(text: String) {
        dispatch(text, false)
    }

    private fun dispatch(text: String, endOfInput: Boolean) {
        if (text.isEmpty() && !endOfInput) return
        for (input in scanner.feed(text, endOfInput)) {
            when (input) {
                is Input.Print -> print(input.text)
                is Input.Control -> control(input.code)
                is Input.Escape -> escape(input.final)
                is Input.Csi -> csi(input)
                is Input.Osc -> oscCommand(input.payload)
                is Input.Apc -> apc(input.payload)
                else -> Unit
            }
        }
        updateRevision++
    }

    // ---------------------------------------------------------------- printing

    private fun print(text: String) {
        for (char in text) printChar(char)
    }

    private fun printChar(char: Char) {
        val width = TerminalTextWidth.width(char)
        if (width == 0) {
            // A combining mark: attach it to the character it modifies instead of
            // consuming a cell. A second mark on the same base is dropped.
            val row = screen.lineAt(cursorRow)
            val column = if (cursorColumn > 0) cursorColumn - 1 else 0
            val cell = row.cells[column]
            if (cell.char != ' ' && !cell.continuation) cell.char = char
            return
        }
        if (wrapPending) {
            wrapPending = false
            cursorColumn = 0
            lineFeed()
        } else if (cursorColumn >= columns) {
            cursorColumn = columns - 1
        }
        if (width == 2 && cursorColumn >= columns - 1) {
            // A two-cell character cannot straddle the right edge; xterm moves it
            // to the next line rather than drawing half of it.
            cursorColumn = 0
            lineFeed()
        }
        screen.writeCell(cursorRow, cursorColumn, char, width, fg, bg, attrs)
        if (openLink != null) screen.lineAt(cursorRow).extendLink(cursorColumn)
        cursorColumn += width
        cursorIntent = CursorIntent.Free
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            wrapPending = autowrap
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            screen.scrollUp(scrollTop, scrollBottom)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            screen.scrollDown(scrollTop, scrollBottom)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun carriageReturn() {
        cursorColumn = 0
        wrapPending = false
    }

    private fun tab() {
        cursorColumn = (((cursorColumn / 8) + 1) * 8).coerceAtMost(columns - 1)
        wrapPending = false
    }

    private fun backspace() {
        if (cursorColumn > 0) cursorColumn--
        wrapPending = false
    }

    /**
     * Blank [from]..[to] of [row].
     *
     * Erasing keeps the *current* background — that is what "clear the screen"
     * means when a program has set a background colour — but never the current
     * foreground or attributes.
     */
    private fun eraseDefault(row: Int, from: Int, to: Int) {
        val line = screen.lineAt(row)
        val last = to.coerceAtMost(columns - 1)
        for (column in from.coerceAtLeast(0)..last) {
            line.cells[column].erase(AnsiColors.DEFAULT, bg)
        }
        line.touch()
        screen.bump()
    }

    // ------------------------------------------------------------ input events

    private fun control(code: Int) {
        when (code) {
            0x07 -> bellPending = true
            0x08 -> backspace()
            0x09 -> tab()
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> carriageReturn()
            else -> Unit
        }
    }

    private fun escape(final: Char) {
        when (final) {
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> {
                carriageReturn()
                lineFeed()
            }
            'M' -> reverseIndex()
            'H' -> setTabStop()
            'c' -> fullReset()
            else -> Unit
        }
    }

    private fun saveCursor() {
        savedCursor = intArrayOf(cursorRow, cursorColumn, fg, bg, attrs)
    }

    private fun restoreCursor() {
        cursorRow = savedCursor[0].coerceIn(0, rows - 1)
        cursorColumn = savedCursor[1].coerceIn(0, columns - 1)
        fg = savedCursor[2]
        bg = savedCursor[3]
        attrs = savedCursor[4]
    }

    private fun setTabStop() {
        if (cursorColumn in tabStops.indices) tabStops[cursorColumn] = true
    }

    private fun fullReset() {
        fg = AnsiColors.DEFAULT
        bg = AnsiColors.DEFAULT
        attrs = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = 0
        cursorColumn = 0
        autowrap = true
        cursorVisible = true
        originMode = false
        wrapPending = false
        cursorIntent = CursorIntent.None
        openLink = null
        primary.clear(AnsiColors.DEFAULT, AnsiColors.DEFAULT)
        alternate.clear(AnsiColors.DEFAULT, AnsiColors.DEFAULT)
    }

    // --------------------------------------------------------------------- CSI

    private fun param(index: Int, fallback: Int = 0): Int {
        if (index >= params.size) return fallback
        val value = params[index]
        return if (value == 0) fallback else value
    }

    private val params = ArrayList<Int>(8)

    private fun csi(input: Input.Csi) {
        params.clear()
        params.addAll(input.params)
        val final = input.final

        if (input.privateMarker == '?') {
            if (final == 'h' || final == 'l') setModes(final == 'h')
            return
        }
        if (input.privateMarker == '>' || input.intermediate == '>') return
        if (input.privateMarker == '<') return
        if (input.privateMarker != ' ') return

        when (final) {
            'A' -> moveCursor(-param(0, 1), 0)
            'B' -> moveCursor(param(0, 1), 0)
            'C' -> moveCursor(0, param(0, 1))
            'D' -> moveCursor(0, -param(0, 1))
            'E' -> {
                moveCursor(param(0, 1), 0)
                carriageReturn()
            }
            'F' -> {
                moveCursor(-param(0, 1), 0)
                carriageReturn()
            }
            'G', '`' -> {
                cursorColumn = (param(0, 1) - 1).coerceIn(0, columns - 1)
                wrapPending = false
            }
            'H', 'f' -> {
                val base = if (originMode) scrollTop else 0
                val limit = if (originMode) scrollBottom else rows - 1
                cursorRow = (base + param(0, 1) - 1).coerceIn(base, limit)
                cursorColumn = (param(1, 1) - 1).coerceIn(0, columns - 1)
                wrapPending = false
            }
            'd' -> {
                val base = if (originMode) scrollTop else 0
                val limit = if (originMode) scrollBottom else rows - 1
                cursorRow = (base + param(0, 1) - 1).coerceIn(base, limit)
                wrapPending = false
            }
            'a' -> moveCursor(0, param(0, 1))
            'e' -> moveCursor(param(0, 1), 0)
            'I' -> repeat(param(0, 1)) { tab() }
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            'X' -> eraseDefault(cursorRow, cursorColumn, cursorColumn + param(0, 1) - 1)
            '@' -> insertChars(param(0, 1))
            'S' -> repeat(param(0, 1)) { screen.scrollUp(scrollTop, scrollBottom) }
            'T' -> repeat(param(0, 1)) { screen.scrollDown(scrollTop, scrollBottom) }
            'Z' -> repeat(param(0, 1)) { backspace() }
            'm' -> applySgr(params)
            'r' -> setScrollRegion()
            's' -> saveCursor()
            'u' -> restoreCursor()
            'g' -> tabStops = BooleanArray(columns) { it % 8 == 0 && it != 0 }
            'n' -> answerDeviceStatus(param(0, 0))
            'c' -> replies += "\u001b[?1;2c"
            't' -> windowOperation(params)
            else -> Unit
        }
        wrapPending = false
    }

    private inline fun repeat(count: Int, action: () -> Unit) {
        for (i in 0 until count.coerceIn(0, 4096)) action()
    }

    private fun moveCursor(deltaRow: Int, deltaColumn: Int) {
        val base = if (originMode) scrollTop else 0
        val limit = if (originMode) scrollBottom else rows - 1
        cursorRow = (cursorRow + deltaRow).coerceIn(base, limit)
        cursorColumn = (cursorColumn + deltaColumn).coerceIn(0, columns - 1)
    }

    private fun setScrollRegion() {
        val top = (param(0, 1) - 1).coerceIn(0, rows - 1)
        val bottom =
            if (params.size >= 2 && params[1] != 0) (params[1] - 1).coerceIn(0, rows - 1) else rows - 1
        if (top >= bottom) return
        scrollTop = top
        scrollBottom = bottom
        cursorRow = if (originMode) top else 0
        cursorColumn = 0
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseDefault(cursorRow, cursorColumn, columns - 1)
                for (row in cursorRow + 1 until rows) eraseDefault(row, 0, columns - 1)
            }
            1 -> {
                for (row in 0 until cursorRow) eraseDefault(row, 0, columns - 1)
                eraseDefault(cursorRow, 0, cursorColumn)
            }
            2 -> for (row in 0 until rows) eraseDefault(row, 0, columns - 1)
            3 -> screen.clearHistory()
            else -> Unit
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> eraseDefault(cursorRow, cursorColumn, columns - 1)
            1 -> eraseDefault(cursorRow, 0, cursorColumn)
            2 -> eraseDefault(cursorRow, 0, columns - 1)
            else -> Unit
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count) { screen.scrollDown(cursorRow, scrollBottom) }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow !in scrollTop..scrollBottom) return
        repeat(count) { screen.scrollUp(cursorRow, scrollBottom) }
    }

    private fun insertChars(count: Int) {
        val line = screen.lineAt(cursorRow)
        val n = count.coerceAtMost(columns)
        for (i in columns - 1 downTo cursorColumn + n) line.cells[i].copyFrom(line.cells[i - n])
        for (i in cursorColumn until (cursorColumn + n).coerceAtMost(columns)) {
            line.cells[i].erase(AnsiColors.DEFAULT, bg)
        }
        line.touch()
        screen.bump()
    }

    private fun deleteChars(count: Int) {
        val line = screen.lineAt(cursorRow)
        val n = count.coerceAtMost(columns)
        for (i in cursorColumn until columns - n) line.cells[i].copyFrom(line.cells[i + n])
        for (i in (columns - n).coerceAtLeast(cursorColumn) until columns) {
            line.cells[i].erase(AnsiColors.DEFAULT, bg)
        }
        line.touch()
        screen.bump()
    }

    private fun setModes(enabled: Boolean) {
        for (mode in params) when (mode) {
            7 -> autowrap = enabled
            25 -> cursorVisible = enabled
            6 -> originMode = enabled
            47, 1047, 1049 -> switchAlternate(enabled)
            2004 -> bracketPaste = enabled
            // Accepted and recorded, never acted on: see the class KDoc.
            1000, 1001, 1002, 1003, 1004, 1005, 1006, 1015, 1016, 2026, 2031 -> Unit
            else -> Unit
        }
    }

    private fun switchAlternate(enabled: Boolean) {
        if (enabled == usingAlternate) return
        usingAlternate = enabled
        if (enabled) {
            savedCursor = intArrayOf(cursorRow, cursorColumn, fg, bg, attrs)
            alternate.clear(AnsiColors.DEFAULT, AnsiColors.DEFAULT)
            cursorRow = 0
            cursorColumn = 0
            scrollTop = 0
            scrollBottom = rows - 1
            wrapPending = false
            openLink = null
        } else {
            cursorRow = savedCursor[0].coerceIn(0, rows - 1)
            cursorColumn = savedCursor[1].coerceIn(0, columns - 1)
            fg = savedCursor[2]
            bg = savedCursor[3]
            attrs = savedCursor[4]
            openLink = null
        }
        screen.bump()
    }

    private fun answerDeviceStatus(kind: Int) {
        when (kind) {
            5 -> replies += "\u001b[0n"
            6 -> replies += "\u001b[${cursorRow + 1};${cursorColumn + 1}R"
            else -> Unit
        }
    }

    private fun windowOperation(args: List<Int>) {
        when (args.getOrNull(0)) {
            14 -> replies += "\u001b[4;${cellHeightPx};${cellWidthPx}t"
            16 -> replies += "\u001b[6;${cellHeightPx};${cellWidthPx}t"
            18 -> replies += "\u001b[8;${rows};${columns}t"
            else -> Unit
        }
    }

    // --------------------------------------------------------------------- SGR

    private fun applySgr(codes: List<Int>) {
        val list = if (codes.isEmpty()) listOf(0) else codes
        var i = 0
        while (i < list.size) {
            when (val code = list[i]) {
                0 -> {
                    fg = AnsiColors.DEFAULT
                    bg = AnsiColors.DEFAULT
                    attrs = 0
                }
                1 -> attrs = attrs or Cell.BOLD
                2 -> attrs = attrs or Cell.DIM
                3 -> attrs = attrs or Cell.ITALIC
                4, 21 -> attrs = attrs or Cell.UNDERLINE
                5, 6 -> Unit // blink: nothing here blinks
                7 -> attrs = attrs or Cell.INVERSE
                8 -> attrs = attrs or Cell.HIDDEN
                9 -> attrs = attrs or Cell.STRIKE
                22 -> attrs = attrs and Cell.BOLD.inv() and Cell.DIM.inv()
                23 -> attrs = attrs and Cell.ITALIC.inv()
                24 -> attrs = attrs and Cell.UNDERLINE.inv()
                25, 27 -> attrs = attrs and Cell.INVERSE.inv()
                28 -> attrs = attrs and Cell.HIDDEN.inv()
                29 -> attrs = attrs and Cell.STRIKE.inv()
                39 -> fg = AnsiColors.DEFAULT
                49 -> bg = AnsiColors.DEFAULT
                in 30..37 -> fg = AnsiColors.SYSTEM[code - 30]
                in 90..97 -> fg = AnsiColors.SYSTEM[code - 90 + 8]
                in 40..47 -> bg = AnsiColors.SYSTEM[code - 40]
                in 100..107 -> bg = AnsiColors.SYSTEM[code - 100 + 8]
                38, 48 -> {
                    val extended = extendedColor(list, i)
                    if (extended.color != null) {
                        if (code == 38) fg = extended.color else bg = extended.color
                    }
                    i += extended.consumed
                }
                else -> Unit
            }
            i++
        }
    }

    private class Extended(val color: Int?, val consumed: Int)

    /**
     * `38;5;N` / `38;2;R;G;B` (and the `48` variants).
     *
     * A system index is kept as an index (`SYSTEM[n]`), not flattened to RGB, so
     * the palette can still brighten it for bold text — which is what every
     * terminal does, and why `ls --color` distinguishes executables from
     * directories.
     */
    private fun extendedColor(list: List<Int>, index: Int): Extended = when (list.getOrNull(index + 1)) {
        5 -> {
            val n = list.getOrNull(index + 2)
            if (n == null) Extended(null, 1)
            else Extended(if (n < 16) AnsiColors.SYSTEM[n] else AnsiColors.indexed(n), 2)
        }
        2 -> {
            val r = list.getOrNull(index + 2)
            val g = list.getOrNull(index + 3)
            val b = list.getOrNull(index + 4)
            if (r == null || g == null || b == null) {
                Extended(null, (list.size - index - 1).coerceAtLeast(1))
            } else {
                Extended(AnsiColors.pack(r, g, b), 4)
            }
        }
        else -> Extended(null, 0)
    }

    // -------------------------------------------------------- OSC / APC / other

    private fun oscCommand(payload: String) {
        val separator = payload.indexOf(';')
        val command = if (separator < 0) payload else payload.substring(0, separator)
        val body = if (separator < 0) "" else payload.substring(separator + 1)
        when (command) {
            "0", "1", "2" -> title = body
            "8" -> handleHyperlink(body)
            "9" -> if (body.startsWith("4;")) parseProgress(body.removePrefix("4;"))
            "52" -> handleClipboard(body)
            else -> Unit
        }
    }

    private fun parseProgress(spec: String) {
        val parts = spec.split(';')
        val state = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val percent = parts.getOrNull(1)?.toIntOrNull() ?: 0
        progress = if (state == 0 || state == 3) null else Progress(state, percent)
    }

    /**
     * `OSC 8 ; params ; URI ST`. An empty URI closes the current link.
     *
     * Real OSC 8 opens *before* the linked text and closes after it, so the run
     * is built as the characters are written: [openLinkRow] remembers the row the
     * link started on, and [Print] extends it.
     */
    private fun handleHyperlink(body: String) {
        val secondSeparator = body.indexOf(';')
        if (secondSeparator < 0) return
        val uri = body.substring(secondSeparator + 1)
        if (uri.isEmpty()) {
            val row = screen.lineAt(openLinkRow.coerceIn(0, rows - 1))
            val link = row.links.lastOrNull()
            if (link != null && link.end < link.start) row.links.removeAt(row.links.size - 1)
            openLink = null
            return
        }
        openLink = uri
        openLinkRow = cursorRow
        screen.lineAt(cursorRow).links += ScreenLine.Link(cursorColumn, uri)
    }

    private fun handleClipboard(body: String) {
        val separator = body.indexOf(';')
        if (separator < 0) return
        val selection = body.substring(0, separator)
        val data = body.substring(separator + 1)
        // `c` is the clipboard; `p`/`s` are the primary/secondary selections. A
        // terminal has only one clipboard, so all three land there. pi writes `c`.
        if (selection != "c" && selection != "p" && selection != "s") return
        if (data.isEmpty() || data == "?") return
        val decoded = runCatching {
            String(java.util.Base64.getMimeDecoder().decode(data), Charsets.UTF_8)
        }.getOrNull() ?: return
        if (decoded.isNotEmpty()) clipboardWrites += decoded
    }

    /**
     * APC payloads. pi writes `CURSOR_MARKER` (`ESC _ pi:c BEL`) immediately
     * before the character the hardware cursor belongs on, which is how a TUI
     * that paints its own cursor still tells the terminal where it is — and what
     * an IME needs in order to put its candidate window in the right place.
     */
    private fun apc(payload: String) {
        cursorIntent = when (payload.trim()) {
            "pi:c" -> CursorIntent.LineStart
            "pi:free" -> CursorIntent.Free
            "pi:none" -> CursorIntent.None
            else -> cursorIntent
        }
    }

    // ----------------------------------------------------------------- resizing

    /**
     * Re-declare the grid size.
     *
     * The guest's PTY size is fixed for a session (`script`(1) cannot change the
     * inner terminal's window size), so this only exists for tests and for a
     * future native PTY. Callers that do use it must also arrange for the guest
     * to learn the new size, or the TUI and the view will disagree.
     */
    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        if (newColumns < 2 || newRows < 1) return
        columns = newColumns
        rows = newRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorColumn = cursorColumn.coerceIn(0, columns - 1)
        tabStops = BooleanArray(columns) { it % 8 == 0 && it != 0 }
        wrapPending = false
        updateRevision++
    }

    /** The size the guest was told, which is what the view must lay out for. */
    fun declaredSize(): Pair<Int, Int> = columns to rows

    /**
     * Scrollback access for the view. Deliberately not part of [Snapshot]: the
     * history is large and copying it per frame would be the one allocation this
     * design cannot afford, so the view reads the lines it is about to paint.
     */
    fun historyLine(index: Int): ScreenLine? = primary.historyLine(index)

    fun historySize(): Int = primary.historySize()

    /** The whole active viewport as plain text; used by tests and diagnostics. */
    fun dumpText(): String =
        screen.snapshot().lines.joinToString("\n") { it.plainText() }
}
