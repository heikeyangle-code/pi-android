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
        root.omittedLabel()?.let { add(WidgetSpan(it, WidgetTone.Dim)) }
    }

    // Official ordering and budget (multi-job builder, `render.js:2838-2899`): running
    // first, then **one** muted line standing in for every queued job, then the finished
    // — four slots in total (`MAX_WIDGET_JOBS = 4`, `shared/types.js:127`), counting the
    // queued line itself. Children draw under their job (`materializedWidgetChildLines`),
    // ordered the same way and capped at [SUBAGENT_DETAIL_CHILDREN]; child rows do **not**
    // consume slots. The terminal-tier shapes (single-line / progressive, driven by
    // `fitAdaptiveWidgetLines`' terminal row budget) have no phone analog: this card's
    // folded state and its label/headline chrome play that role instead.
    fun stateRank(state: String?): Int = when (state) {
        "running" -> 0
        "queued" -> 1
        else -> 2
    }
    val ordered = runs.sortedWith(compareBy { stateRank(it.text("state")) })
    fun jobRows(run: JsonObject): List<List<WidgetSpan>> {
        val children = (run["children"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .sortedWith(compareBy { stateRank(it.text("state")) })
        val shown = children.take(SUBAGENT_DETAIL_CHILDREN)
        // **The name is the extension's, not mine.** `widgetJobName` gives a job one of
        // `parallel`, `chain`, a single agent's name, or the joined agent list — those
        // four are the only place the mode shows up. `kind` distinguishes the panel's two
        // species (both words, per e22ca84); the id's short form is the handle the
        // extension's own inspect command takes — the interaction this surface has.
        val jobName = run.text("label")
        val jobKind = if (run.text("kind") == "workflow") "工作流" else "子代理"
        return buildList {
            add(
                nodeRow(
                    run,
                    name = jobName,
                    kindWord = jobKind,
                    ref = run.text("id")?.take(8),
                    stat = widgetJobStats(run),
                ),
            )
            activityRow(run)?.let { add(it) }
            shown.forEachIndexed { index, child ->
                // Official marks `└─` only when the drawn child **is** the last child
                // (`index === children.length - 1`): a truncated list is all `├─`.
                add(
                    nodeRow(
                        child,
                        branch = if (index == children.lastIndex) "└─ " else "├─ ",
                        identity = child.text("id"),
                        name = child.text("label") ?: "（未命名）",
                        inlineActivity = activityLine(child).takeIf { it.isNotEmpty() },
                    ),
                )
            }
            val hidden = children.size - shown.size
            // Branch-less, like official's `+N more workflow children`.
            if (hidden > 0) add(listOf(WidgetSpan("+$hidden 个更多子任务", WidgetTone.Dim)))
        }
    }
    var slots = SUBAGENT_DETAIL_RUNS
    val drawn = mutableListOf<List<WidgetSpan>>()
    val hiddenRuns = mutableListOf<JsonObject>()
    for (run in ordered.filter { it.text("state") == "running" }) {
        if (slots <= 0) {
            hiddenRuns += run
            continue
        }
        drawn += jobRows(run)
        slots--
    }
    val queuedRuns = ordered.filter { it.text("state") == "queued" }
    if (queuedRuns.isNotEmpty()) {
        if (slots > 0) {
            drawn += listOf(listOf(WidgetSpan("◦ ${queuedRuns.size} 排队中", WidgetTone.Muted)))
            slots--
        } else {
            hiddenRuns += queuedRuns
        }
    }
    for (run in ordered.filter { stateRank(it.text("state")) == 2 }) {
        if (slots <= 0) {
            hiddenRuns += run
            continue
        }
        drawn += jobRows(run)
        slots--
    }
    val details = if (hiddenRuns.isEmpty()) {
        drawn
    } else {
        // Official's tail (`+N more (…)`). The words are the extension's own state table
        // (finer than official's three categories, same shape), which is the only part
        // that says whether the hidden ones are still working.
        val hiddenStates = hiddenRuns.flatMap { leafStates(it) }
        val parts = SUBAGENT_STATE_ORDER.mapNotNull { state ->
            hiddenStates.count { it == state }.takeIf { it > 0 }?.let { "$it ${look(state).word}" }
        }
        val tail = if (parts.isEmpty()) "" else "（${parts.joinToString("、")}）"
        drawn + listOf(listOf(WidgetSpan("+${hiddenRuns.size} 个更多$tail", WidgetTone.Dim)))
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

/**
 * One job's or one task's row: branch and **identity** first — official
 * `materializedWidgetChildLines` puts a child's id before its glyph, bold
 * (`Text` is this card's bold) — then the state glyph and word, the name, and
 * the row's own stat slot.
 */
private fun nodeRow(
    node: JsonObject,
    branch: String = "",
    identity: String? = null,
    name: String? = null,
    kindWord: String? = null,
    ref: String? = null,
    stat: String? = null,
    /** The official child row keeps its readings on a `│`-prefixed second line; this card
     *  inlines them after the name (phone density — same content, one row instead of two). */
    inlineActivity: String? = null,
): List<WidgetSpan> {
    val look = look(node.text("state"))
    return buildList {
        if (branch.isNotEmpty()) add(WidgetSpan(branch, WidgetTone.Dim))
        if (identity != null) add(WidgetSpan("$identity ", WidgetTone.Text))
        add(WidgetSpan("${look.glyph} ", look.tone))
        // `06 §4`: the word always travels with the glyph, so the state survives colour
        // blindness — and it is the extension's own word for it (`widgetStepStatus`).
        // Official draws the word nowhere here; this is the documented a11y deviation.
        add(WidgetSpan(look.word, look.tone))
        if (kindWord != null) {
            add(WidgetSpan(" · $kindWord", WidgetTone.Dim))
        }
        if (name != null) {
            add(WidgetSpan("· ", WidgetTone.Dim))
            // The name is the one run of this line a reader scans for: the extension bolds it
            // (`themeBold` in its own renderer) and so does the card.
            add(WidgetSpan(name, WidgetTone.Text))
        }
        if (ref != null) {
            add(WidgetSpan(" · $ref", WidgetTone.Dim))
        }
        if (stat != null) {
            add(WidgetSpan(" · $stat", WidgetTone.Dim))
        }
        if (inlineActivity != null) {
            add(WidgetSpan(" · ", WidgetTone.Dim))
            add(WidgetSpan(inlineActivity, WidgetTone.Dim))
        }
    }
}

/**
 * The job's readings, transcribed from official `widgetActivity` (`render.js:1111`) —
 * the **second line** of the panel (`  ⎿  …`), never part of the name row:
 *
 *  1. the live-status label (`buildLiveStatusLine` → `formatActivityLabel`/
 *     `formatActivityAge`, both localized): the **snapshot's own freshness**
 *     (`lastActivityAt` vs `updatedAt` — the payload is the clock, the host adds no
 *     ticker), with the special activity states keeping their own phrases;
 *  2. the current tool with how long *it* has run (`updatedAt - currentToolStartedAt`);
 *  3. the working path (`currentPath` on the node, home-shortened like `shortenPath`) —
 *     the0.71 projection writes it **when a tool holds one**; the fixtures carry none,
 *     so it is drawn only when present and never invented;
 *  4. `N 轮`, `N 工具`.
 *
 * There is deliberately **no job-elapsed here**: official puts the job's own duration on
 * the name row's stats (`widgetStats`), not in the activity line. When nothing at all is
 * known, official's fall-through words are used (`thinking…` / `queued…` / `Paused` …).
 */
private fun activityLine(node: JsonObject): String {
    val activity = node["activity"] as? JsonObject
    val updatedAt = node.long("updatedAt")
    // The payload is the clock: `updatedAt` moves on every status tick, so this
    // duration refreshes with the panel instead of needing a host-side ticker.
    val tool = activity?.text("currentTool")?.let { name ->
        val since = activity.long("currentToolStartedAt")
        if (since != null && updatedAt != null) {
            "$name ${ToolOutputParse.formatDuration((updatedAt - since).coerceAtLeast(0))}"
        } else {
            name
        }
    }
    val facts = listOfNotNull(
        tool,
        node.text("currentPath")?.let { shortenHomePath(it) },
        activity?.count("turnCount")?.let { "$it 轮" },
        activity?.count("toolCount")?.let { "$it 工具" },
    )
    val live = liveStatusLabel(activity, updatedAt)
    return when {
        live != null && facts.isNotEmpty() -> "$live · ${facts.joinToString(" · ")}"
        live != null -> live
        facts.isNotEmpty() -> facts.joinToString(" · ")
        else -> idleFallbackWord(node.text("state")).orEmpty()
    }
}

/**
 * `buildLiveStatusLine` + `formatActivityLabel`, localized. The age buckets are the
 * extension's own (`status-format.js`: `<1s now / <60s Ns / else Nm`), and the "now"
 * passed in is the snapshot's `updatedAt`, not wall time.
 */
private fun liveStatusLabel(activity: JsonObject?, now: Long?): String? {
    val state = activity?.text("state")
    val last = activity?.long("lastActivityAt")
    if (last == null || now == null) {
        return when (state) {
            "needs_attention" -> "需要处理"
            "active_long_running" -> "长时间任务运行中"
            else -> null
        }
    }
    val ageMs = (now - last).coerceAtLeast(0)
    val age = when {
        ageMs < 1_000 -> "刚刚"
        ageMs < 60_000 -> "${ageMs / 1_000} 秒"
        else -> "${ageMs / 60_000} 分钟"
    }
    return when (state) {
        "needs_attention" -> if (age == "刚刚") "刚刚需要处理" else "已 $age 没有活动，需要处理"
        "active_long_running" -> "长时间任务运行中 · 最近活动 $age 前"
        else -> if (age == "刚刚") "正在活跃" else "$age 前有活动"
    }
}

/** Official's no-fact fall-through (`widgetActivity` tail), localized. */
private fun idleFallbackWord(state: String?): String? = when (state) {
    "running" -> "思考中…"
    "queued" -> "排队中…"
    "paused" -> "已暂停"
    "stopped" -> "已停止"
    "partial" -> "部分完成"
    "failed" -> "失败"
    null -> null
    else -> look(state).word
}

/** `shortenPath` (`formatters.js:136`): the guest's home (`/root/`) folds to `~`. */
private fun shortenHomePath(path: String): String =
    if (path.startsWith("/root/")) "~${path.substring(5)}" else path

/**
 * The name row's own stats — official `widgetStats` **for this payload**: the tool-use
 * count (nested under `activity` here; the TUI's own job objects carry it top-level) and
 * the job's elapsed (`updatedAt - startedAt`, skipped while queued).
 *
 * The stage / step / parallel-group / checklist branches of `widgetStats` need `mode`,
 * `stepsTotal`, `currentStep`, `parallelGroups` — fields the async projection never
 * writes (measured off all seven fixtures: keys are activity,id,kind,label,startedAt,
 * state[,updatedAt][,children][,endedAt]) — so they are unreachable by construction, not
 * omitted by choice.
 */
private fun widgetJobStats(node: JsonObject): String? {
    val activity = node["activity"] as? JsonObject
    val toolUse = activity?.count("toolCount")?.let { "$it 工具" }
    val elapsed = if (node.text("state") != "queued") {
        val started = node.long("startedAt")
        val end = node.long("updatedAt")
        if (started != null && end != null) {
            ToolOutputParse.formatDuration((end - started).coerceAtLeast(0))
        } else {
            null
        }
    } else {
        null
    }
    return listOfNotNull(toolUse, elapsed).joinToString(" · ").takeIf { it.isNotEmpty() }
}

/** The `⎿` row: dim, indented, and only when there is something to say. */
private fun activityRow(node: JsonObject): List<WidgetSpan>? =
    activityLine(node).takeIf { it.isNotEmpty() }?.let { listOf(WidgetSpan("⎿  $it", WidgetTone.Dim)) }

private fun look(state: String?): StateLook = SUBAGENT_STATES[state] ?: SUBAGENT_STATE_FALLBACK

/**
 * What the sender says it dropped, in **its own numbers**.
 *
 * `omitted` is part of the snapshot's contract (`runs`, `children`,
 * `byteLimitExceeded`), and a sentence that only said "已截断" threw away the count the
 * extension had just reported — the user could see four tasks on screen and be told
 * nothing about the fifth. The byte limit is a different statement (nothing was counted,
 * the payload was cut), so it gets its own words.
 */
private fun JsonObject.omittedLabel(): String? {
    val omitted = this["omitted"] as? JsonObject ?: return null
    val dropped = (omitted.text("runs")?.toIntOrNull() ?: 0) +
        (omitted.text("children")?.toIntOrNull() ?: 0)
    return when {
        dropped > 0 -> "+$dropped 个未列出"
        omitted.text("byteLimitExceeded") == "true" -> "（超出字节上限）"
        else -> null
    }
}

/** A count that is worth printing: `0 轮` is noise. */
private fun JsonObject.count(key: String): String? =
    text(key)?.toIntOrNull()?.takeIf { it > 0 }?.toString()

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

/** pi's timestamps are epoch milliseconds; a missing or malformed one is simply absent. */
private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()
