package app.pi.ui.extension

import app.pi.ui.blocks.TOOL_LINE_MAX_CHARS

/**
 * `setWidget` 一行是什么：宿主不认识发送它的扩展时，唯一能对一行做的判断。
 *
 * SDK 的 widget 内容是 `string[]`；富组件工厂只在 TUI 里有。所以这里只做通用的事：
 * 一行文字就是一行（超长按 pi 自己的方式截断），元素内含换行就拆成多行，
 * 形如 `<PREFIX>_JSON:{…}` 的机器负载折叠成一行标签 + 展开才看的原文——
 * 标签只取决于前缀，不取决于负载内容，所以扩展每个 tick 重发也不会让面板重排。
 *
 * 不认任何具体第三方扩展的负载：没有专属摘要，认不出的一律折叠。纯函数，无 Compose、无颜色值。
 */

/** The tokens a widget line may be painted with — pi's own set for this panel. */
internal enum class WidgetTone { Text, Muted, Dim, Accent, Success, Warning, Error }

/** One run of a widget line, with the token the extension's renderer would use. */
internal data class WidgetSpan(val text: String, val tone: WidgetTone)

/** One row of an extension widget, after the rules above have been applied. */
internal sealed interface WidgetRow {
    /** Verbatim text, already truncated by [widgetRow], drawn on one row. */
    data class Text(val text: String) : WidgetRow

    /**
     * A payload the App does not summarise.
     *
     * [label] depends only on the prefix, never on the payload's *contents* — an
     * extension that re-sends this line on every tick therefore cannot make the
     * panel re-lay-out.
     */
    data class Folded(val label: String, val raw: String) : WidgetRow
}

/**
 * Classify and resolve every line of one widget, dropping the data channels.
 *
 * The single entry point the renderer calls, so the harness exercises exactly
 * what the screen draws.
 */
internal fun widgetRows(lines: List<String>): List<WidgetRow> = lines
    // **One array element is not always one row.** an extension may push file content or a
    // joined paragraph as one element, so an element can carry newlines. Drawing only its first row dropped the rest silently, which is the one failure
    // mode a host must never have: the extension's own panel shows those rows.
    .flatMap { line -> if (line.contains('\n')) line.split('\n').take(MAX_SPLIT_ROWS) else listOf(line) }
    .mapNotNull(::widgetRow)

/**
 * The most rows one array element may split into.
 *
 * A bound on the split itself, not on the card: the panel's own row budget is applied
 * afterwards (`boundedWidgetRows`), and this only stops a pathological element (a
 * megabyte of newlines) from allocating before that cap can act.
 */
internal const val MAX_SPLIT_ROWS = 64

/**
 * Is there anything a tap would reveal?
 *
 * A text widget has no header of its own, so the cue has to be earned: a line that was
 * cut (the ellipsis is the evidence) or a payload row. A widget whose lines all fit
 * needs no affordance, and gets none — the alternative was a `展开` label on every panel,
 * including the ones with nothing behind it.
 */
internal fun widgetNeedsDisclosure(rows: List<WidgetRow>): Boolean = rows.any { row ->
    when (row) {
        is WidgetRow.Text -> row.text.length > WIDGET_DISCLOSURE_CHARS
        is WidgetRow.Folded -> true
    }
}

/** Past this many characters a line cannot be one row on a phone, so it is cut. */
internal const val WIDGET_DISCLOSURE_CHARS = 48

/**
 * A widget that brings no title of its own gets one from its **key**.
 *
 * pi's widget channel is a `string[]`, so an extension sends
 * `setWidget("web-activity", lines)` and the panel arrives with nothing that names it — on
 * the phone it was an unlabelled block of text. The key is the one piece of identity the
 * host always has, and pi's own multi-key widgets (`powerline-top`, `background-tasks`, …)
 * already name themselves there.
 *
 * Humanised, never translated: the host does not know what "web-activity" *means*, so it
 * must not invent a word for it. Separators become spaces and nothing else happens.
 */
internal fun widgetCaption(key: String): String? =
    key.trim().replace('-', ' ').replace('_', ' ').takeIf { it.isNotEmpty() }

/** One line, or `null` when it must not be drawn at all. */
internal fun widgetRow(line: String): WidgetRow? {
    val payload = payloadLine(line)
    if (payload != null) {
        val (prefix, json) = payload
        return WidgetRow.Folded(prefix, json)
    }
    return WidgetRow.Text(truncateWidgetLine(line))
}

/**
 * pi's own treatment of an over-long machine line: cut at
 * [TOOL_LINE_MAX_CHARS] and mark it, exactly as the tool card does
 * (`blocks/ToolOutputParse.kt`). A widget line is one row on a phone, so a
 * longer one is not text any more — and this is also what bounds
 * `Ansi.parse`'s input at the drawing site.
 */
internal fun truncateWidgetLine(line: String): String =
    if (line.length <= TOOL_LINE_MAX_CHARS) line else line.substring(0, TOOL_LINE_MAX_CHARS) + "…"

/**
 * `<PREFIX>:{…}` — a prefix token, a colon, and a JSON **object**.
 *
 * Requiring the `{` is what keeps ordinary prose out: `FOO_JSON: hello` is text,
 * not a payload that silently disappeared into a chip. A prefix that *is*
 * followed by a `{` but holds something unparsable still folds rather than
 * falling back to text — a 32 KB blob is not made safe by being malformed.
 */
private val PAYLOAD_LINE = Regex("^\\s*([A-Z][A-Z0-9_]*_JSON):\\s*(\\{.*)$")

private fun payloadLine(line: String): Pair<String, String>? {
    val match = PAYLOAD_LINE.matchEntire(line) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}
