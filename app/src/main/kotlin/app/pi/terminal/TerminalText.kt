package app.pi.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * The text form of a screen line: styled runs for painting, plain text for copy.
 *
 * Both jobs belong together because they have to agree. Copy must produce what
 * the user sees (grid padding removed, each wide character once), and painting
 * must not invent characters — a terminal that copies something different from
 * what it shows is worse than one that cannot copy.
 */
internal object TerminalText {

    /** The family every terminal glyph uses; the grid depends on it being fixed. */
    val family: FontFamily = FontFamily.Monospace

    /**
     * A resolved cell style.
     *
     * `bold` and `dim` are resolved into the colours *as well as* recorded:
     * a terminal brightens system colours for bold and fades dim text, and those
     * effects have to survive into the span. [equals] is by value so consecutive
     * cells with the same style merge into one run, which is what keeps a frame
     * of ~2000 cells cheap.
     */
    class Style(
        val foreground: Color,
        val background: Color,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val invisible: Boolean,
    ) {
        fun toSpanStyle(): SpanStyle {
            val decoration = when {
                underline && strike -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough),
                )
                underline -> TextDecoration.Underline
                strike -> TextDecoration.LineThrough
                else -> null
            }
            return SpanStyle(
                color = if (invisible) background else foreground,
                background = background,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = decoration,
            )
        }

        override fun equals(other: Any?): Boolean =
            other is Style &&
                foreground == other.foreground &&
                background == other.background &&
                bold == other.bold &&
                italic == other.italic &&
                underline == other.underline &&
                strike == other.strike &&
                invisible == other.invisible

        override fun hashCode(): Int {
            var result = foreground.hashCode()
            result = 31 * result + background.hashCode()
            result = 31 * result + (if (bold) 1 else 0)
            result = 31 * result + (if (italic) 1 else 0)
            result = 31 * result + (if (underline) 1 else 0)
            result = 31 * result + (if (strike) 1 else 0)
            result = 31 * result + (if (invisible) 1 else 0)
            return result
        }
    }

    /**
     * Build one `AnnotatedString` for a line.
     *
     * Trailing blanks are part of the string: the grid holds exactly `columns`
     * cells and dropping them would move everything after them.
     *
     * @param selection the inclusive column range to highlight, if any.
     * @param cursorColumn the column to paint the cursor block on, or -1.
     */
    fun styledLine(
        line: ScreenLine,
        palette: TerminalPalette,
        selection: IntRange? = null,
        cursorColumn: Int = -1,
    ): AnnotatedString {
        val styles = Array(line.columns) { column ->
            styleOf(line.cells[column], palette, selection, cursorColumn, column)
        }
        val builder = AnnotatedString.Builder()
        var index = 0
        while (index < line.columns) {
            val style = styles[index]
            val text = StringBuilder(16)
            while (index < line.columns && styles[index] == style) {
                val cell = line.cells[index]
                // A continuation cell paints nothing: the wide glyph before it
                // already occupies the space.
                text.append(if (cell.continuation) ' ' else cell.char)
                index++
            }
            builder.pushStyle(style.toSpanStyle())
            builder.append(text.toString())
            builder.pop()
        }
        return builder.toAnnotatedString()
    }

    fun styleOf(
        cell: Cell,
        palette: TerminalPalette,
        selection: IntRange?,
        cursorColumn: Int,
        column: Int,
    ): Style {
        val bold = cell.attrs and Cell.BOLD != 0
        val dim = cell.attrs and Cell.DIM != 0
        val inverse = cell.attrs and Cell.INVERSE != 0
        var foreground = resolveForeground(cell.fg, palette, bold)
        var background = palette.resolveBackground(cell.bg)
        if (dim) {
            // Dimming blends toward the background rather than lowering alpha:
            // alpha would let the surface show through and the glyph would
            // disappear where it overlaps the cursor or a selection.
            foreground = lerp(foreground, background, 0.45f)
        }
        if (inverse) {
            val swap = foreground
            foreground = background
            background = swap
        }
        if (selection != null && column in selection) background = palette.selection
        if (column == cursorColumn) background = palette.cursor
        return Style(
            foreground = foreground,
            background = background,
            bold = bold,
            italic = cell.attrs and Cell.ITALIC != 0,
            underline = cell.attrs and Cell.UNDERLINE != 0,
            strike = cell.attrs and Cell.STRIKE != 0,
            invisible = cell.attrs and Cell.HIDDEN != 0,
        )
    }

    private fun resolveForeground(packed: Int, palette: TerminalPalette, bold: Boolean): Color {
        val slot = AnsiColors.systemIndex(packed)
        if (slot >= 0) return palette.systemColor(slot, bold)
        return palette.resolveForeground(packed)
    }

    /**
     * The line as the user would copy it: cells, not the padded grid.
     *
     * A two-cell character is emitted once (its continuation cell is skipped), so
     * copying CJK out of the terminal does not insert a space per character.
     */
    fun plainText(line: ScreenLine): String = line.plainText()

    /** The visible characters between two columns, inclusive, for copy. */
    fun textInRange(line: ScreenLine, from: Int, to: Int): String {
        val sb = StringBuilder((to - from + 1).coerceAtLeast(0))
        var column = from.coerceAtLeast(0)
        val last = to.coerceAtMost(line.columns - 1)
        while (column <= last) {
            val cell = line.cells[column]
            if (!cell.continuation) sb.append(cell.char)
            column += cell.width.coerceAtLeast(1)
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Character widths.
 *
 * Unicode's East Asian Width property is the authority; this is the subset a
 * terminal on a phone actually meets — CJK ideographs, kana, Hangul, fullwidth
 * forms and the common emoji blocks are two cells wide, combining marks are
 * zero. Getting this wrong is visible: a single mis-measured wide character
 * shifts every column after it, and the TUI's absolute cursor addressing then
 * draws into the wrong place.
 */
internal object TerminalTextWidth {
    fun width(char: Char): Int = when {
        char.code < 0x20 -> 0
        char.code == 0x7F -> 0
        char.code in 0x0300..0x036F -> 0
        char.code in 0x1AB0..0x1AFF -> 0
        char.code in 0x1DC0..0x1DFF -> 0
        char.code in 0x20D0..0x20FF -> 0
        char.code in 0xFE00..0xFE0F -> 0
        char.code in 0xFE20..0xFE2F -> 0
        isWide(char) -> 2
        else -> 1
    }

    private fun isWide(char: Char): Boolean = when (char.code) {
        in 0x1100..0x115F -> true // Hangul Jamo init
        in 0x2E80..0x303E -> true // CJK radicals, Kangxi, CJK symbols
        in 0x3041..0x33FF -> true // kana, Hangul compat, CJK compat
        in 0x3400..0x4DBF -> true // CJK ext A
        in 0x4E00..0x9FFF -> true // CJK unified
        in 0xA000..0xA4CF -> true // Yi
        in 0xAC00..0xD7A3 -> true // Hangul syllables
        in 0xF900..0xFAFF -> true // CJK compatibility ideographs
        in 0xFE30..0xFE6F -> true // CJK compatibility forms
        in 0xFF00..0xFF60 -> true // fullwidth forms
        in 0xFFE0..0xFFE6 -> true // fullwidth signs
        in 0x1F300..0x1F64F -> true // emoji, pictographs
        in 0x1F900..0x1F9FF -> true // supplemental symbols
        in 0x20000..0x3FFFD -> true // CJK ext B+
        else -> false
    }
}
