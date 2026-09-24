package app.pi.ui.extension

// Pure-logic predicates for two rendering decisions that no compiler can make:
//
//  1. `isPreformatted` — is this widget line a *terminal row* (box art, a
//     space-aligned table) whose column alignment survives only if the line is
//     never soft-wrapped. `ExtensionWidgetCard.kt` gives such a line the
//     "one row, no wrap, scroll sideways" treatment once the card is opened.
//  2. `selectNeedsSheet` — is this `select` option list long or wide enough that
//     the 330-wide dialog (`06 §2`) is the wrong shell for it? `ExtensionDialogs.kt`
//     switches to the v2 bottom sheet above the threshold and stays on the dialog
//     below it, so the common case keeps the shape the board specifies
//     (`06 §2` classifies the extension select as a *dialog*).
//
// Deliberately Android-free (kotlin stdlib only): `tools/run-app-pure-checks.sh`
// compiles this file with its harness `ExtensionTextLinesCheck.kt` on a bare JVM.
// Anything Compose-shaped would move the decision into a place the harness cannot
// reach — and these are exactly the rules that silently rot without a check.

/**
 * A line needs this many **visible columns** before width alone makes it
 * preformatted. Columns, not characters, and not the panel's own width.
 *
 * The widget panel's text area is ≈ 46–48 columns on the board's 412dp design
 * width: 正文列宽 384dp (`design/ui-refactor/12-color-area-and-scrollbar-plan.md:56`)
 * minus the stack's `pageHorizontal` padding (`ExtensionWidgetCard.kt:89`), the
 * card's `BlockCardRowPadding` (`ui/blocks/BlockChrome.kt:291`) and the 12dp
 * accent stripe (`ExtensionWidgetCard.kt:127`), at `monoSmall` 12sp
 * (`ui/theme/PiTheme.kt:225` ≈ 7.2dp/列).
 *
 * 80 is chosen **above** that panel width on purpose: it is pi's own default
 * terminal width (`PLACEHOLDER_COLUMNS = 80`, `ui/terminal/TerminalPane.kt:393`).
 * A prose line of 45–79 columns is still a sentence and must keep wrapping when
 * the card is opened — wrapping is how prose is read. A line at or past 80 was
 * authored as a row of a terminal the size pi itself assumes; splitting it across
 * several wrapped rows is what destroys column alignment, so it gets no wrap and
 * a horizontal scrollbar instead. The two content rules below (box-drawing
 * characters, runs of two or more spaces) are the primary detectors for narrower
 * tables; this one is the backstop for tables too wide to ever fit one row.
 */
internal const val PreformatWideColumns = 80

/**
 * Is [raw] a preformatted terminal row? True when **any** of the three rules hits:
 *
 *  1. it contains a box-drawing character (U+2500–U+257F — the whole block, which
 *     contains every character pi's own box art and the task's `─│┌┐└┘├┤` use);
 *  2. after stripping SGR escapes and trailing whitespace it contains a run of two
 *     or more spaces — the only way two independent columns can align in a
 *     monospace row (a single space between columns is just prose);
 *  3. its visible width exceeds [PreformatWideColumns].
 *
 * The escape strip matters: a widget line reaches this file *raw* (the card
 * parses it with `Ansi.parse` at `ExtensionWidgetCard.kt:162`), so without
 * stripping, the bytes of an `ESC [ … m` SGR sequence would be counted as content
 * and could fake a double space the user's screen never shows.
 */
internal fun isPreformatted(raw: String): Boolean {
    val text = stripAnsiControls(raw)
    if (text.any { it.code in BoxDrawingRange.first..BoxDrawingRange.last }) return true
    val body = text.trimEnd()
    if (body.contains("  ")) return true
    return visibleColumns(body) > PreformatWideColumns
}

/**
 * Visible width of [raw] in terminal columns: escapes gone, trailing whitespace
 * trimmed, CJK/fullwidth/emoji counted as 2, combining and zero-width marks as 0,
 * a tab as 4 (a common tab stop — the rule only needs the magnitude), everything
 * else as 1. Box-drawing characters count as 1 because that is the cell they
 * occupy in the monospace terminal the line came from.
 */
internal fun visibleColumns(raw: String): Int {
    val text = stripAnsiControls(raw).trimEnd()
    var columns = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        columns += codePointWidth(cp)
        i += Character.charCount(cp)
    }
    return columns
}

/**
 * Does this option list belong in a bottom sheet rather than the dialog?
 *
 * [optionColumns] is each option label's [visibleColumns] (the caller strips the
 * extension's colour escapes first — `chromeText`), so the rule is a pure
 * comparison and both thresholds are pinned by the harness:
 *
 *  - **≥ [SelectSheetMinOptions] options.** One option row is
 *    `padding:10px 12px` (`06 §2`, line 81; drawn at `ExtensionDialogs.kt:194`)
 *    around a 14sp/1.62 line (`06 §2`, line 59) ≈ 43dp. The dialog's list area is
 *    `DialogListMaxHeight = 360dp` (`ExtensionDialogs.kt:552`) → 8.4 rows, so the
 *    9th option forces a scroll *inside* a 330-wide dialog (`06 §2`, line 91).
 *    The sheet's body allowance is 420dp (`06 §2`, line 90 →
 *    `PiSettingsMetrics.sheetBodyMax`) → 9.8 rows: the same list fits.
 *  - **any label wider than [SelectSheetWideLabelColumns] columns.** A dialog row
 *    has 330 − 2×16 (dialog padding, `06 §2` line 91) − 2×12 (row padding,
 *    line 81) − ≈16dp for `○` + gap (`ExtensionDialogs.kt:195-207`) ≈ 254dp ≈ 36
 *    columns at 14sp (7dp/column; a CJK glyph is 2 columns = 14dp). Past that the
 *    label is ellipsized *in the shell the user must read it in*; a sheet row on
 *    the 412dp screen has ≈ 49 columns of room, so the sheet is where it fits.
 *
 * Below both thresholds nothing changes: `select` renders as the dialog
 * `06 §2` classifies it as (line 161: 扩展 select is a 对话框).
 */
internal fun selectNeedsSheet(optionColumns: List<Int>): Boolean =
    optionColumns.size >= SelectSheetMinOptions ||
        optionColumns.any { it > SelectSheetWideLabelColumns }

/** See [selectNeedsSheet]: the 9th option is the first one the dialog cannot show without scrolling. */
internal const val SelectSheetMinOptions = 9

/** See [selectNeedsSheet]: the widest label a 330-wide dialog row can show uncut. */
internal const val SelectSheetWideLabelColumns = 36

/** The box-drawing block `─│┌┐└┘├┤` lives in; the whole block is box art. */
private val BoxDrawingRange = 0x2500..0x257F

/**
 * Remove ANSI escape sequences and stray control bytes, leaving the text the user
 * actually sees. `ESC [ … final` (CSI, which every SGR colour is) and
 * `ESC ] … BEL` (OSC, hyperlinks) are skipped whole; any other `ESC x` drops two
 * units. Tabs survive — they are content, and `codePointWidth` prices them.
 */
internal fun stripAnsiControls(raw: String): String {
    val clean = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        when {
            // 0x1B is ESC, 0x07 is BEL — written as codes so no invisible byte
            // ever lands in this source file.
            ch.code == 0x1B -> {
                i++
                when {
                    i < raw.length && raw[i] == '[' -> {
                        i++
                        while (i < raw.length && raw[i].code !in 0x40..0x7E) i++
                        if (i < raw.length) i++
                    }
                    i < raw.length && raw[i] == ']' -> {
                        i++
                        while (i < raw.length && raw[i].code != 0x07 && raw[i].code != 0x1B) i++
                        if (i < raw.length && raw[i].code == 0x1B) i++ else if (i < raw.length) i++
                        if (i < raw.length && raw[i] == '\\') i++
                    }
                    i < raw.length -> i += 2
                }
            }
            ch == '\t' -> {
                clean.append(ch)
                i++
            }
            ch.code < 0x20 || ch.code == 0x7F -> i++ // stray control bytes render as nothing
            else -> {
                clean.append(ch)
                i++
            }
        }
    }
    return clean.toString()
}

/** Column width of one code point — see [visibleColumns] for the mapping. */
private fun codePointWidth(cp: Int): Int = when {
    cp == 9 -> 4 // tab
    cp < 0x20 || cp == 0x7F -> 0 // control bytes that survived (they render as nothing)
    cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F -> 0
    cp == 0xFE0E || cp == 0xFE0F -> 0 // variation selectors
    cp in 0x0300..0x036F -> 0 // combining marks (they render on the previous cell)
    else -> if (isWideCodePoint(cp)) 2 else 1
}

/**
 * East-Asian-Wide ranges worth spelling out (wcwidth's table minus the ambiguous
 * half): CJK ideographs and radicals, kana, fullwidth forms, Hangul, the CJK
 * compatibility blocks, and the emoji planes terminals render double-width.
 * Everything else — including the box-drawing block — is 1.
 */
private fun isWideCodePoint(cp: Int): Boolean = when {
    cp in 0x1100..0x115F -> true // Hangul Jamo
    cp in 0x2E80..0x303E -> true // CJK radicals, Kangxi, CJK symbols/punctuation
    cp in 0x3041..0x33FF -> true // kana, bopomofo, hangul compat jamo, CJK compat
    cp in 0x3400..0x4DBF -> true // CJK ext A
    cp in 0x4E00..0x9FFF -> true // CJK unified
    cp in 0xA000..0xA4CF -> true // Yi
    cp in 0xAC00..0xD7A3 -> true // Hangul syllables
    cp in 0xF900..0xFAFF -> true // CJK compatibility ideographs
    cp in 0xFE30..0xFE6F -> true // CJK compatibility forms / fullwidth variants
    cp in 0xFF00..0xFF60 -> true // fullwidth forms
    cp in 0xFFE0..0xFFE6 -> true // fullwidth signs
    cp in 0x1F300..0x1F5FF || cp in 0x1F600..0x1F64F -> true // emoji
    cp in 0x1F680..0x1F6FF || cp in 0x1F900..0x1F9FF -> true // transport / supplemental
    cp in 0x1FA70..0x1FAFF -> true // symbols and pictographs ext A
    cp in 0x20000..0x3FFFD -> true // CJK ext B and beyond (astral planes)
    else -> false
}