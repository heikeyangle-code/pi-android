package app.pi.ui.extension

import app.pi.rpc.PiJson
import app.pi.ui.blocks.TOOL_LINE_MAX_CHARS
import app.pi.ui.blocks.ToolOutputParse
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
 * protocol", `docs/observability.md`).
 *
 * The App used to draw every such line verbatim. That is not merely ugly: the
 * payload is bounded by the *sender* (32 KB), pi's own widget cap counts **lines**
 * (`MAX_WIDGET_LINES = 10`, [boundedWidgetLines]), and one 32 KB line passes a
 * line-count cap untouched and then wraps — which is how a status payload came to
 * occupy half the conversation and re-lay-out on every status tick.
 *
 * ## The rules, and which of them are pi's rather than mine
 *
 *  1. **A line is text, or it is a declared payload, or it is too long to be
 *     text.** pi truncates an over-long machine line with `…` at
 *     [TOOL_LINE_MAX_CHARS] — its own grep limit, `core/tools/truncate.ts:13`,
 *     already transcribed for the tool card (`blocks/ToolOutputParse.kt`) — so the
 *     widget uses **the same number and the same ellipsis** instead of inventing a
 *     second bound. Truncation is right for text; it would be wrong for a payload,
 *     which is why a declared payload folds instead.
 *  2. **A payload the App understands is summarised, and only a payload is.**
 *     Detection is the convention above, not a list of extension names, so a
 *     *future* extension gets the fold for free. [PAYLOAD_SUMMARIES] maps a prefix
 *     to a renderer; an unregistered prefix still folds, never falls back to raw
 *     text.
 *  3. **Colour comes from the payload's own states, mapped the way the extension
 *     maps them.** In pi the extension colours its own widget
 *     (`theme.fg("accent", …)`) — and when it hands the host a JSON payload
 *     instead, the *state → token* mapping is in its renderer
 *     (`tui/render.js` `widgetStatusGlyph` / `widgetStepStatus`). [SUBAGENT_STATES]
 *     is that table, transcribed: `running` accent, `queued` muted, `complete`
 *     success, `paused`/`stopped` warning, and **everything else error** — the
 *     extension's own fall-through, including its `failed`/`partial`/`rejected`
 *     arms. The spans carry a [WidgetTone], not a `Color`, so this file stays
 *     Android-free and the harness can pin the mapping token by token.
 *
 * A prefix in [HIDDEN_PAYLOAD_PREFIXES] is a pure data channel: `pi-subagents`
 * says so itself for its inspect reply — "Hosts must not render this widget"
 * (`docs/observability.md`) — so the App drops it.
 *
 * ## What is deliberately *not* here
 *
 * No rendering, no Compose, no colour values: the renderer is [ExtensionWidgetStack]
 * and `widgetToneColor`. Nothing in the output is a timestamp or a duration: the
 * extension re-sends its payload on every status tick, and a label that changes for
 * a reason the user cannot see is the flicker this file exists to remove.
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

    /**
     * A payload the App understands: a titled row, the disclosure body, and the
     * tone that decides the card's own border and stripe.
     *
     * [headline] is the counts row; [details] are the per-job rows, already in
     * [WidgetSpan] form so the renderer only maps tones to colours.
     */
    data class Summary(
        val label: String,
        val badge: String,
        val headline: List<WidgetSpan>,
        val details: List<List<WidgetSpan>>,
        val worst: WidgetTone,
        val raw: String,
    ) : WidgetRow
}

/**
 * Classify and resolve every line of one widget, dropping the data channels.
 *
 * The single entry point the renderer calls, so the harness exercises exactly
 * what the screen draws.
 */
internal fun widgetRows(lines: List<String>): List<WidgetRow> = lines
    // **One array element is not always one row.** `pi-web-access` pushes file content and
    // `pi-background-tasks` pushes a joined remediation paragraph, so an element can carry
    // newlines. Drawing only its first row dropped the rest silently, which is the one failure
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
        is WidgetRow.Folded, is WidgetRow.Summary -> true
    }
}

/** Past this many characters a line cannot be one row on a phone, so it is cut. */
internal const val WIDGET_DISCLOSURE_CHARS = 48

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
            summary
        }
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
 * The card's own tone: the most severe state any row carries, or `null` when
 * nothing in the widget is reporting one (a text widget, or an unregistered
 * payload).
 *
 * Severity order is the one a reader uses — a failure outranks a pause, both
 * outrank progress — and it is why one card can carry a mixed set of jobs without
 * the container colour lying about them.
 */
internal fun widgetCardTone(rows: List<WidgetRow>): WidgetTone? =
    rows.mapNotNull { (it as? WidgetRow.Summary)?.worst }
        .minByOrNull { WIDGET_TONE_SEVERITY.indexOf(it) }

/**
 * Severity, **worst first** — so the most severe tone is the *smallest* index and
 * every lookup takes `minBy`. A `maxBy` over this list picks the mildest state,
 * which is how a card holding one failed job first drew itself as a success.
 */
private val WIDGET_TONE_SEVERITY = listOf(
    WidgetTone.Error, WidgetTone.Warning, WidgetTone.Accent,
    WidgetTone.Success, WidgetTone.Muted, WidgetTone.Dim, WidgetTone.Text,
)

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
private val PAYLOAD_SUMMARIES: Map<String, (String) -> WidgetRow?> = mapOf(
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

/**
 * How many of one job's tasks the card lists before summarising (`+N 个子任务`).
 *
 * The payload caps children at `maxChildrenPerNode = 8`; four keeps one job's
 * disclosure the same height as the job list it sits in.
 */
private const val SUBAGENT_DETAIL_CHILDREN = 4

/**
 * The payload's own `maxDepth` (`async-status-projection.js`), used as the bound on
 * the leaf walk so a malformed or self-referencing tree cannot recurse forever.
 */
private const val SUBAGENT_MAX_DEPTH = 3

/** One state's glyph, word and token — the extension's own choices, transcribed. */
private data class StateLook(val glyph: String, val word: String, val tone: WidgetTone)

/**
 * The job-level mapping from `tui/render.js` `widgetStatusGlyph`, plus the word
 * from `widgetStepStatus`: `running` accent, `queued` muted `◦`, `complete`
 * success `✓`, `paused`/`stopped` warning `■`, and **everything else** error `✗`.
 *
 * The fall-through is the extension's, not this file's: it draws no separate
 * `partial` or `rejected` glyph, so neither does the App. Words are Chinese
 * because they are the *host's* prose — the extension's English words never cross
 * the wire (it sends the JSON, and the summary is this App's invention).
 */
private val SUBAGENT_STATES: Map<String, StateLook> = mapOf(
    "running" to StateLook("⠋", "运行中", WidgetTone.Accent),
    "queued" to StateLook("◦", "排队中", WidgetTone.Muted),
    "complete" to StateLook("✓", "完成", WidgetTone.Success),
    "paused" to StateLook("■", "已暂停", WidgetTone.Warning),
    "stopped" to StateLook("■", "已停止", WidgetTone.Warning),
    "failed" to StateLook("✗", "失败", WidgetTone.Error),
)

/** The extension's own fall-through: anything it does not name is a failure glyph. */
private val SUBAGENT_STATE_FALLBACK = StateLook("✗", "部分完成", WidgetTone.Error)

/** The order the headline reads in, worst-last so a failure is the last word. */
private val SUBAGENT_STATE_ORDER = listOf(
    "running", "queued", "paused", "complete", "failed", "stopped", "rejected", "partial",
)

/**
 * The `subagent-async` snapshot, as one row — or `null` when the payload is not
 * the versioned shape this code was written against, in which case the caller
 * folds it. `kind` **and** `version` are both required: the extension versions
 * this payload precisely so a host can refuse to interpret a shape it does not
 * know, and a guessed render of a changed shape would be worse than a chip.
 */
private fun subagentSummary(json: String): WidgetRow? {
    val root = PiJson.parseObjectOrNull(json) ?: return null
    if (root.text("kind") != SUBAGENT_SNAPSHOT_KIND) return null
    if (root.text("version")?.toIntOrNull() != SUBAGENT_SNAPSHOT_VERSION) return null

    val runs = (root["runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    // **A run is not an agent.** A scripted call spawns N tasks inside *one* async job, and the
    // extension keeps them in `children` with the job's own `label` being a joined agent list
    // (`formatWidgetAgents` → `researcher, researcher, researcher, +1 more`). Counting `runs`
    // therefore reported "1 运行中" for a job running four agents, which reads as a bug in the
    // card. Counting **leaves** is what the user is counting: a job with children contributes
    // its children, a childless job contributes itself.
    val leaves = runs.flatMap { leafStates(it) }
    val counts = LinkedHashMap<String, Int>()
    for (state in leaves) counts[state] = (counts[state] ?: 0) + 1
    val headline = buildList {
        // Known states in the extension's order, then anything it did not name — an unknown
        // state must still be *counted*, or a payload with one would silently read as
        // "no active jobs" while its own rows are right there in the body.
        val known = SUBAGENT_STATE_ORDER.filter { counts.containsKey(it) }
        val rest = counts.keys.filter { it !in SUBAGENT_STATE_ORDER }.sorted()
        (known + rest).forEachIndexed { index, state ->
            val count = counts[state] ?: return@forEachIndexed
            val look = look(state)
            if (index > 0) add(WidgetSpan(" · ", WidgetTone.Dim))
            add(WidgetSpan("$count ${look.word}", look.tone))
        }
        if (isEmpty()) add(WidgetSpan("无活动任务", WidgetTone.Muted))
        if (root.truncated()) add(WidgetSpan("（已截断）", WidgetTone.Dim))
    }

    val details = runs.take(SUBAGENT_DETAIL_RUNS).flatMap { run ->
        // The extension's own panel draws the job row and then its tasks under it
        // (`materializedWidgetChildLines` uses `├─` / `└─`), so the card does too — otherwise a
        // four-agent job is a single opaque row whose name is a repeated agent list.
        val children = (run["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val shown = children.take(SUBAGENT_DETAIL_CHILDREN)
        // A job whose `label` is a joined agent list (`researcher, researcher, researcher,
        // +1 more`) names nothing a reader wants: its tasks are right below it with their own
        // names. The count is the useful fact for that row.
        val jobName = if (children.isEmpty()) run.text("label") else "${children.size} 个子任务"
        buildList {
            add(nodeRow(run, name = jobName))
            shown.forEachIndexed { index, child ->
                val last = index == shown.lastIndex && children.size <= SUBAGENT_DETAIL_CHILDREN
                add(
                    nodeRow(
                        child,
                        branch = if (last) "└─ " else "├─ ",
                        name = child.text("label") ?: "（未命名）",
                    ),
                )
            }
            val hidden = children.size - shown.size
            if (hidden > 0) add(listOf(WidgetSpan("└─ +$hidden 个子任务", WidgetTone.Dim)))
        }
    }.let { rendered ->
        val hidden = runs.size - SUBAGENT_DETAIL_RUNS
        if (hidden > 0) rendered + listOf(listOf(WidgetSpan("+$hidden 个更多", WidgetTone.Dim))) else rendered
    }

    return WidgetRow.Summary(
        label = "子代理",
        // The agent count, not the job count: the badge is read as "how many are out there".
        badge = leaves.size.toString(),
        headline = headline,
        details = details,
        worst = leaves.map { look(it).tone }
            .minByOrNull { WIDGET_TONE_SEVERITY.indexOf(it) } ?: WidgetTone.Accent,
        raw = json,
    )
}

/**
 * The leaf states under one node: its children's if it has any, else its own.
 *
 * Depth-bounded by the payload's own cap, so a malformed or cyclic tree cannot make
 * this walk forever.
 */
private fun leafStates(node: JsonObject, depth: Int = 0): List<String> {
    val children = (node["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (children.isEmpty() || depth >= SUBAGENT_MAX_DEPTH) return listOf(node.text("state") ?: "partial")
    return children.flatMap { leafStates(it, depth + 1) }
}

/** One job's or one task's row: state glyph and word, name, then what it is doing now. */
private fun nodeRow(node: JsonObject, branch: String = "", name: String? = null): List<WidgetSpan> {
    val look = look(node.text("state"))
    val activity = node["activity"] as? JsonObject
    // What it is doing *right now*, in the extension's own order (`widgetActivity`):
    // the current tool first, then the counts. `activity.state` ("running" / "thinking" /
    // …) is only used when there is no tool to name — the official panel does not draw it
    // either, and "思考中" is the only case where the tool slot would otherwise be empty.
    // **The extension's payload is the clock.** `updatedAt` is re-sent on every async state
    // change, so a duration derived from these fields refreshes with the panel instead of needing
    // a host-side ticker — which is also why the folded card can stay byte-stable (it draws none
    // of these) while the opened one is live. The spellings are pi's own: `widgetActivity` writes
    // `${currentTool} ${formatDuration(updatedAt - currentToolStartedAt)}`, and
    // [ToolOutputParse.formatDuration] is that same `formatDuration` (`renderers/bash.ts:32-42`).
    val updatedAt = node.long("updatedAt") ?: node.long("startedAt")
    val toolStartedAt = activity?.long("currentToolStartedAt")
    val currentTool = activity?.text("currentTool")?.let { name ->
        if (toolStartedAt != null && updatedAt != null) {
            "$name ${ToolOutputParse.formatDuration(updatedAt - toolStartedAt)}"
        } else {
            name
        }
    }
    val startedAt = node.long("startedAt")
    val endedAt = node.long("endedAt") ?: updatedAt
    val elapsed = if (startedAt != null && endedAt != null) {
        ToolOutputParse.formatDuration(endedAt - startedAt)
    } else {
        null
    }
    val doing = listOfNotNull(
        currentTool,
        // Only when there is no tool to name: the official panel does not draw this field at
        // all, and "思考中" is the one case where the slot would otherwise be empty.
        if (currentTool == null) activity?.text("state") else null,
        activity?.count("turnCount")?.let { "$it 轮" },
        activity?.count("toolCount")?.let { "$it 工具" },
        elapsed,
    )
    return buildList {
        if (branch.isNotEmpty()) add(WidgetSpan(branch, WidgetTone.Dim))
        add(WidgetSpan("${look.glyph} ", look.tone))
        // `06 §4`: the word always travels with the glyph, so the state survives colour
        // blindness — and it is the extension's own word for it (`widgetStepStatus`).
        add(WidgetSpan(look.word, look.tone))
        if (name != null) {
            add(WidgetSpan("· ", WidgetTone.Dim))
            // The name is the one run of this line a reader scans for: the extension bolds it
            // (`themeBold` in its own renderer) and so does the card, which is why it is `Text`
            // and not `Muted`.
            add(WidgetSpan(name, WidgetTone.Text))
        }
        if (doing.isNotEmpty()) {
            add(WidgetSpan(" · ", WidgetTone.Dim))
            add(WidgetSpan(doing.joinToString(" · "), WidgetTone.Dim))
        }
    }
}

private fun look(state: String?): StateLook = SUBAGENT_STATES[state] ?: SUBAGENT_STATE_FALLBACK

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

/** pi's timestamps are epoch milliseconds; a missing or malformed one is simply absent. */
private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
