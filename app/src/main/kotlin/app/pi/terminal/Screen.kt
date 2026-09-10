package app.pi.terminal

/**
 * One character cell.
 *
 * A terminal is not a string, it is a grid — because the programs we run *count*
 * on the grid. `pi`'s TUI computes its whole layout from `columns`/`rows`,
 * positions the hardware cursor through `CURSOR_MARKER`, and redraws by moving
 * to absolute rows. If cells shifted, every frame after the first would be
 * misplaced. So each cell carries its own attributes instead of being coalesced
 * into styled runs (which is what a captured log needs, and what
 * `app.pi.rpc.Ansi` does for the transcript).
 *
 * Attributes are a bitmask: they change together (one SGR sequence sets
 * several), they are compared per cell when rendering, and an index into a
 * style palette would be far more machinery for the same answer.
 */
class Cell {

    var char: Char = ' '
    var continuation: Boolean = false

    /** Packed `0xRRGGBB`, or [AnsiColors.DEFAULT] for "terminal default". */
    var fg: Int = AnsiColors.DEFAULT
    var bg: Int = AnsiColors.DEFAULT

    /** 1 or 2. Two means the next cell is [continuation] and paints nothing. */
    var width: Int = 1

    var attrs: Int = 0

    fun copyFrom(other: Cell) {
        char = other.char
        continuation = other.continuation
        fg = other.fg
        bg = other.bg
        width = other.width
        attrs = other.attrs
    }

    /** Reset to the blank cell an `ED`/`EL` with no SGR background produces. */
    fun erase(foreground: Int, background: Int) {
        char = ' '
        continuation = false
        width = 1
        fg = foreground
        bg = background
        attrs = 0
    }

    companion object {
        const val BOLD = 1
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val INVERSE = 1 shl 4
        const val HIDDEN = 1 shl 5
        const val STRIKE = 1 shl 6
    }
}

/**
 * One rendered row.
 *
 * [revision] lets the view skip re-measuring a line that did not change. That is
 * the one optimisation worth having in a terminal view: re-laying out every line
 * on a frame that only appended one at the bottom is exactly where a phone-sized
 * implementation falls over.
 */
class ScreenLine(val columns: Int) {

    val cells: Array<Cell> = Array(columns) { Cell() }
    var revision: Long = 0

    /** OSC 8 hyperlink runs on this line, in column order. */
    val links: MutableList<Link> = ArrayList(0)

    class Link(val start: Int, val url: String) {
        var end: Int = start
    }

    fun touch() {
        revision++
    }

    /** Start an OSC 8 link at [start] (the link only spans text actually written). */
    fun openLink(url: String, start: Int) {
        links += Link(start.coerceIn(0, columns - 1), url)
    }

    /** Extend the most recent link to cover [column]. No-op when none is open. */
    fun extendLink(column: Int) {
        val link = links.lastOrNull() ?: return
        val target = column.coerceIn(0, columns - 1)
        if (target >= link.start) link.end = maxOf(link.end, target)
    }

    fun linkAt(column: Int): String? {
        for (link in links) if (column in link.start..link.end) return link.url
        return null
    }

    fun linkStartAt(column: Int): Int? {
        for (link in links) if (column in link.start..link.end) return link.start
        return null
    }

    fun linkEndAt(column: Int): Int? {
        for (link in links) if (column in link.start..link.end) return link.end
        return null
    }

    /** Trim trailing blanks; the grid pads every line to [columns]. */
    fun plainText(): String {
        val sb = StringBuilder(columns)
        var i = 0
        while (i < columns) {
            val cell = cells[i]
            if (cell.continuation) {
                i++
                continue
            }
            sb.append(cell.char)
            i += cell.width.coerceAtLeast(1)
        }
        return sb.toString().trimEnd()
    }

    fun copyLine(): ScreenLine {
        val copy = ScreenLine(columns)
        for (i in 0 until columns) copy.cells[i].copyFrom(cells[i])
        copy.revision = revision
        for (link in links) copy.links += Link(link.start, link.url).also { it.end = link.end }
        return copy
    }

    companion object {
        fun blank(columns: Int): ScreenLine = ScreenLine(columns)
    }
}

/**
 * A fixed-height grid of cells with a scrollback ring.
 *
 * Fixed height, not a growing list: `pi` alternates between a main screen and an
 * alternate screen, and both are *viewports*. Lines that scroll off the top are
 * not lost, they move to [history] — which is what a terminal's scrollback is,
 * and the only history the user can select text from.
 */
class Screen(
    val columns: Int,
    val rows: Int,
    val historyLimit: Int,
) {

    val lines: Array<ScreenLine> = Array(rows) { ScreenLine.blank(columns) }

    /** Oldest first; the last element sits directly above the viewport. */
    private val history = ArrayDeque<ScreenLine>()

    /** Bumped on every mutation so the view knows to re-read. */
    var revision: Long = 0
        private set

    fun bump() {
        revision++
    }

    fun historySize(): Int = history.size

    fun historyLine(index: Int): ScreenLine? = history.getOrNull(index)

    private fun pushHistory(line: ScreenLine) {
        if (historyLimit <= 0) return
        history.addLast(line.copyLine())
        while (history.size > historyLimit) history.removeFirst()
    }

    fun clearHistory() {
        history.clear()
    }

    fun scrollUp(top: Int, bottom: Int) {
        val moved = lines[top]
        for (row in top until bottom) lines[row] = lines[row + 1]
        lines[bottom] = ScreenLine.blank(columns).also { it.revision = revision + 1 }
        // Only a scroll of the *whole* screen feeds scrollback; a partial scroll
        // region is a widget's viewport, not terminal history.
        if (top == 0) pushHistory(moved)
        revision++
    }

    fun scrollDown(top: Int, bottom: Int) {
        for (row in bottom downTo top + 1) lines[row] = lines[row - 1]
        lines[top] = ScreenLine.blank(columns).also { it.revision = revision + 1 }
        revision++
    }

    fun writeCell(line: Int, column: Int, char: Char, width: Int, fg: Int, bg: Int, attrs: Int) {
        val row = lines[line]
        val cell = row.cells[column]
        cell.char = char
        cell.width = width
        cell.fg = fg
        cell.bg = bg
        cell.attrs = attrs
        cell.continuation = false
        if (width == 2 && column + 1 < columns) {
            val next = row.cells[column + 1]
            next.char = ' '
            next.continuation = true
            next.width = 1
            next.fg = fg
            next.bg = bg
            next.attrs = attrs
        } else if (column > 0 && row.cells[column - 1].width == 2) {
            // Overwriting the second half of a wide character invalidates it.
            row.cells[column - 1].char = ' '
            row.cells[column - 1].width = 1
        }
        row.touch()
        revision++
    }

    fun eraseCell(line: Int, column: Int, fg: Int, bg: Int) {
        val row = lines[line]
        row.cells[column].erase(fg, bg)
        row.touch()
        revision++
    }

    fun eraseRange(line: Int, from: Int, toInclusive: Int, fg: Int, bg: Int) {
        val row = lines[line]
        val last = toInclusive.coerceAtMost(columns - 1)
        for (column in from.coerceAtLeast(0)..last) row.cells[column].erase(fg, bg)
        // Clearing whole lines also drops their hyperlinks: a URL that outlived
        // its text is a link the user can click on nothing.
        if (from <= 0 && toInclusive >= columns - 1) row.links.clear()
        row.touch()
        revision++
    }

    fun eraseLine(line: Int, fg: Int, bg: Int) {
        eraseRange(line, 0, columns - 1, fg, bg)
    }

    fun clear(fg: Int, bg: Int) {
        for (row in lines) {
            for (cell in row.cells) cell.erase(fg, bg)
            row.links.clear()
            row.touch()
        }
        revision++
    }

    fun lineAt(row: Int): ScreenLine = lines[row]

    fun snapshot(): BufferSnapshot = BufferSnapshot(
        columns = columns,
        rows = rows,
        revision = revision,
        historySize = history.size,
        lines = Array(rows) { lines[it] },
    )

    class BufferSnapshot(
        val columns: Int,
        val rows: Int,
        val revision: Long,
        val historySize: Int,
        val lines: Array<ScreenLine>,
    )
}
