package app.pi.ui.extension

/**
 * The two surfaces that are **not** widgets but belong on the same card: `setStatus`
 * entries, and the extensions whose UI this App cannot show at all.
 *
 * They are the same kind of thing as a widget line — *a set of keyed rows the host
 * must display* — which is why they reuse the card's furniture instead of growing a
 * second, differently-shaped shell. The deleted `ExtensionStatusRow` is the
 * cautionary tale: it invented its own `·` separator, painted every entry in the
 * accent, and drew them in registration order, none of which is what pi does.
 *
 * Android-free on purpose, so the bare-JVM harness can execute the rules
 * (`tools/run-app-pure-checks.sh`, `widget-payload`).
 */

/**
 * pi's `sanitizeStatusText` (`components/footer.ts:13-19`), transcribed: newlines,
 * tabs and carriage returns become spaces, runs of spaces collapse, and the result is
 * trimmed.
 *
 * It is not cosmetic: an extension status is an arbitrary string, and without this a
 * single `\n` in one turns the card's one row into two.
 */
internal fun sanitizeStatusText(text: String): String =
    text.replace(STATUS_WHITESPACE, " ").replace(STATUS_SPACE_RUN, " ").trim()

private val STATUS_WHITESPACE = Regex("[\\r\\n\\t]")
private val STATUS_SPACE_RUN = Regex(" +")

/** One `setStatus` entry, ready to draw. */
internal data class StatusRow(val key: String, val text: String)

/**
 * pi's status order, and pi's only ordering rule: **by key, alphabetically**
 * (`footer.ts:235-237`, `sort(([a], [b]) => a.localeCompare(b))`).
 *
 * An entry that sanitises to nothing draws no row: an empty string is not a status,
 * and a blank row would read as a key with a missing value.
 */
internal fun statusRows(entries: List<Pair<String, String>>): List<StatusRow> =
    entries
        .map { (key, text) -> StatusRow(key, sanitizeStatusText(text)) }
        .filter { it.text.isNotEmpty() }
        .sortedBy { it.key }
