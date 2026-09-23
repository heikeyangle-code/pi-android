package app.pi.ui.extension

import app.pi.rpc.PiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a `setWidget` line **is** — the one thing a host can decide about a widget
 * line without knowing the extension that sent it.
 *
 * ## Why this exists
 *
 * pi's RPC widget contract is `widgetLines: string[]` and nothing else: a rich
 * `setWidget` component factory is a TUI-only object and is ignored in RPC mode
 * (`docs/rpc.md`). So an extension that wants an RPC host to draw something
 * richer than text has only one channel — a string — and the convention that grew
 * out of that is a **prefix plus a JSON object on one line**
 * (`PI_SUBAGENT_ASYNC_JSON:{"kind":…}`, `pi-subagents`' "host inspection
 * protocol", `docs/observability.md` §"Host inspection protocol (RPC)").
 *
 * The App used to draw every such line verbatim. That is not merely ugly: the
 * payload is bounded by the *sender* (32 KB for `pi-subagents`), pi's own widget
 * cap counts **lines** (`MAX_WIDGET_LINES = 10`, `boundedWidgetLines`), and one
 * 32 KB line passes a line-count cap untouched and then wraps — which is how a
 * status payload came to occupy half the conversation and re-lay-out on every
 * status tick.
 *
 * ## The two rules, and why both are extension-agnostic
 *
 *  1. **A line is text or it is too big to be text.** pi's own TUI draws a widget
 *     line truncated to the panel width and never wraps it (`truncLine`,
 *     `tui/render.js`), and a line past [WIDGET_LINE_MAX_CHARS] cannot be shown
 *     as one row on a phone at all. Such a line is folded into a chip: nothing is
 *     lost (the raw text is one tap away), the panel cannot grow past its budget,
 *     and the folded label does not depend on the payload's contents — so it does
 *     not change — and therefore cannot flicker.
 *  2. **A line that declares itself structured is not drawn as text.** The
 *     detection is the convention above, not a list of extension names, so a
 *     *future* extension gets the fold for free. What the App *understands* is a
 *     separate, smaller thing: [PAYLOAD_SUMMARIES] maps a prefix to a renderer,
 *     and an unregistered prefix still folds (never falls back to raw text).
 *
 * A prefix in [HIDDEN_PAYLOAD_PREFIXES] is a pure data channel: `pi-subagents`
 * says so itself for its inspect reply — "Hosts must not render this widget"
 * (`docs/observability.md`) — so the App drops it instead of showing a chip.
 *
 * ## What is deliberately *not* here
 *
 * No rendering, no Compose, no colour: this file is Android-free on purpose so a
 * bare-JVM harness can execute every rule above (`tools/run-app-pure-checks.sh`,
 * `widget-payload`). The renderer is [ExtensionWidgetStack].
 */

/**
 * The longest line still treated as text.
 *
 * Not a style choice: 1024 characters cannot fit one row of a phone-width panel,
 * and a widget line is *by contract* one row. Chosen well above any legitimate
 * decorated line (a coloured 46-column line is a few hundred bytes of ANSI) and
 * far below the 32 KB a status payload can reach.
 */
internal const val WIDGET_LINE_MAX_CHARS = 1024

/** One row of an extension widget, after the rules above have been applied. */
internal sealed interface WidgetRow {
    /** Verbatim text, one row, ellipsized by the renderer. */
    data class Text(val text: String) : WidgetRow

    /**
     * A payload the App does not summarise (or a line too long to be text).
     *
     * [label] depends only on the prefix (or the character count), never on the
     * payload's *contents* — an extension that re-sends this line on every tick
     * therefore cannot make the panel re-lay-out.
     */
    data class Folded(val label: String, val raw: String) : WidgetRow

    /** A payload the App understands: a stable headline, plus lines when opened. */
    data class Summary(val headline: String, val details: List<String>, val raw: String) : WidgetRow
}

/**
 * Classify and resolve every line of one widget, dropping the data channels.
 *
 * The single entry point the renderer calls, so the harness exercises exactly
 * what the screen draws.
 */
internal fun widgetRows(lines: List<String>): List<WidgetRow> = lines.mapNotNull(::widgetRow)

/** One line, or `null` when it must not be drawn at all. */
internal fun widgetRow(line: String): WidgetRow? {
    val payload = payloadLine(line)
    if (payload != null) {
        val (prefix, json) = payload
        if (prefix in HIDDEN_PAYLOAD_PREFIXES) return null
        val summary = PAYLOAD_SUMMARIES[prefix]?.invoke(json)
        return if (summary == null) {
            WidgetRow.Folded(prefix, json)
        } else {
            WidgetRow.Summary(summary.headline, summary.details, json)
        }
    }
    if (line.length > WIDGET_LINE_MAX_CHARS) {
        return WidgetRow.Folded("长文本 ${line.length} 字符", line)
    }
    return WidgetRow.Text(line)
}

/** A payload's compact form. `headline` is one row; nothing here carries a clock. */
internal data class WidgetSummary(val headline: String, val details: List<String>)

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

/**
 * Prefixes the App consumes and never draws: request/response channels whose
 * reply is addressed to the host's own bookkeeping, not to the user's eye.
 */
private val HIDDEN_PAYLOAD_PREFIXES = setOf("PI_SUBAGENT_INSPECT_JSON")

/**
 * The prefixes the App can summarise. Unregistered prefixes still fold, so adding
 * an extension is an improvement here, never a prerequisite for not breaking the
 * panel.
 */
private val PAYLOAD_SUMMARIES: Map<String, (String) -> WidgetSummary?> = mapOf(
    "PI_SUBAGENT_ASYNC_JSON" to ::subagentSummary,
)

// ---------------------------------------------------------------- pi-subagents

private const val SUBAGENT_SNAPSHOT_KIND = "pi-subagents.async-status-snapshot"
private const val SUBAGENT_SNAPSHOT_VERSION = 1

/**
 * `pi-subagents`' own cap on the jobs its terminal panel draws
 * (`MAX_WIDGET_JOBS = 4`, `src/shared/types.js`). Borrowed rather than chosen:
 * the phone panel shows the same first four.
 */
private const val SUBAGENT_DETAIL_RUNS = 4

private val SUBAGENT_STATE_ORDER = listOf(
    "running", "queued", "paused", "complete", "failed", "partial", "stopped", "rejected",
)

private val SUBAGENT_STATE_LABELS = mapOf(
    "running" to "运行中",
    "queued" to "排队中",
    "paused" to "已暂停",
    "complete" to "完成",
    "failed" to "失败",
    "partial" to "部分完成",
    "stopped" to "已停止",
    "rejected" to "已拒绝",
)

/** The same glyphs the extension's terminal panel uses (`widgetStatusGlyph`). */
private val SUBAGENT_STATE_GLYPHS = mapOf(
    "running" to "⠋",
    "queued" to "◦",
    "complete" to "✓",
    "failed" to "✗",
    "paused" to "■",
    "partial" to "◐",
    "stopped" to "■",
    "rejected" to "✗",
)

/**
 * The `subagent-async` snapshot, as one row — or `null` when the payload is not
 * the versioned shape this code was written against, in which case the caller
 * folds it. `kind` **and** `version` are both required: the extension versions
 * this payload (`ASYNC_STATUS_SNAPSHOT_VERSION`) precisely so a host can refuse
 * to interpret a shape it does not know, and a guessed render of a changed shape
 * would be worse than a chip.
 *
 * Nothing in the output is a timestamp or a duration: the extension re-sends this
 * line on every status tick, and a headline that changes for a reason the user
 * cannot see is the flicker this file exists to remove.
 */
private fun subagentSummary(json: String): WidgetSummary? {
    val root = PiJson.parseObjectOrNull(json) ?: return null
    if (root.text("kind") != SUBAGENT_SNAPSHOT_KIND) return null
    if (root.text("version")?.toIntOrNull() != SUBAGENT_SNAPSHOT_VERSION) return null

    val runs = (root["runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val counts = LinkedHashMap<String, Int>()
    for (run in runs) {
        val state = run.text("state") ?: "partial"
        counts[state] = (counts[state] ?: 0) + 1
    }
    val parts = SUBAGENT_STATE_ORDER.mapNotNull { state ->
        counts[state]?.let { "$it ${SUBAGENT_STATE_LABELS[state] ?: state}" }
    }
    val headline = buildString {
        append("子代理：")
        append(if (parts.isEmpty()) "无活动任务" else parts.joinToString(" · "))
        if (root.truncated()) append("（已截断）")
    }

    val details = runs.take(SUBAGENT_DETAIL_RUNS).map { run ->
        val activity = run["activity"] as? JsonObject
        val stats = listOfNotNull(
            activity?.count("turnCount")?.let { "$it 轮" },
            activity?.count("toolCount")?.let { "$it 工具" },
        )
        val glyph = SUBAGENT_STATE_GLYPHS[run.text("state")] ?: "·"
        // The glyph belongs to the name, not between names: pi's panel draws
        // `${glyph} ${name}` and the rest of the row as `· `-separated stats.
        (listOf("$glyph ${run.text("label") ?: "（未命名）"}") + stats).joinToString(" · ")
    }
    val hidden = runs.size - SUBAGENT_DETAIL_RUNS
    return WidgetSummary(
        headline = headline,
        details = if (hidden > 0) details + "+$hidden 个更多" else details,
    )
}

/**
 * Did the sender say it dropped anything? `omitted` is part of the snapshot's own
 * contract (`runs`, `children`, `byteLimitExceeded`), so a summary that silently
 * ignored it would report a count the user can see is wrong.
 */
private fun JsonObject.truncated(): Boolean {
    val omitted = this["omitted"] as? JsonObject ?: return false
    if (omitted.text("byteLimitExceeded") == "true") return true
    return (omitted.text("runs")?.toIntOrNull() ?: 0) > 0 ||
        (omitted.text("children")?.toIntOrNull() ?: 0) > 0
}

/** A count that is worth printing: `0 轮` is noise. */
private fun JsonObject.count(key: String): String? =
    text(key)?.toIntOrNull()?.takeIf { it > 0 }?.toString()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
