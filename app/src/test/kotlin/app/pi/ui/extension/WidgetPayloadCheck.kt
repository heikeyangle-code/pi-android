package app.pi.ui.extension

import kotlin.system.exitProcess

// A bare-JVM harness for the widget-line rules. Registered in
// `tools/run-app-pure-checks.sh` as `widget-payload`.
//
// Why this is worth pinning: pi's RPC widget contract is `widgetLines: string[]` and nothing
// else, so an extension that wants a richer panel than text sends a **prefix plus a JSON
// object on one line** and expects the host to interpret it (`pi-subagents`'
// `PI_SUBAGENT_ASYNC_JSON:` "host inspection protocol"). The host used to draw every line
// verbatim, and because pi's own cap counts *lines* (`MAX_WIDGET_LINES = 10`) a single 32 KB
// line passed it untouched and then wrapped into half the screen — re-laying-out on every
// status tick, because the payload carries a fresh `updatedAt` each time.
//
// Three of the properties below cannot be seen by a compiler and are the whole point:
//
//  1. **A folded label does not depend on the payload's volatile fields.** That, and not a
//     smaller font, is what removes the flicker: two snapshots that differ only in
//     `generatedAt`/`updatedAt` must produce byte-identical rows.
//  2. **An oversized or structured line never becomes text.** Nothing may reach the renderer
//     as a `Text` row longer than `WIDGET_LINE_MAX_CHARS` — that invariant is what bounds both
//     the layout and `Ansi.parse`'s input.
//  3. **Prose is not swallowed.** `FOO_JSON: hello` is a line of text; only `<PREFIX>:{` is a
//     payload. A rule that folded anything with a colon would silently eat an extension's own
//     status sentence.
//
// What this deliberately does not check: the rendering itself (Compose, not compiled here) and
// the two prefix registrations' *politics* — only that an unregistered prefix folds instead of
// being drawn, so a future extension gets the fold for free.

private var widgetFailures = 0

private fun widgetCheck(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        widgetFailures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun checkTrue(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        widgetFailures++
        println(if (detail.isEmpty()) "FAIL $name" else "FAIL $name\n  $detail")
    }
}

/** A snapshot in the shape the extension sends, with the fields a summary must ignore. */
private fun snapshot(runs: String, generatedAt: Long = 1_790_206_038_621L): String =
    """
    {"kind":"pi-subagents.async-status-snapshot","version":1,"generatedAt":$generatedAt,
     "caps":{"maxRuns":20,"maxChildrenPerNode":8,"maxDepth":3,"maxStringLength":160,
             "maxSerializedBytes":32768},
     "omitted":{"runs":0,"children":0,"byteLimitExceeded":false},
     "runs":[$runs]}
    """.trimIndent().replace("\n", "")

private fun run(id: String, label: String, state: String, turns: Int, tools: Int): String =
    """{"id":"$id","kind":"subagent","label":"$label","state":"$state",
        "startedAt":1790200606123,"updatedAt":1790200638560,
        "activity":{"state":"running","turnCount":$turns,"toolCount":$tools,
                    "lastActivityAt":1790200638560,"currentTool":"read"},
        "children":[{"id":"step:0","kind":"step","label":"scout","state":"$state"}]}"""
        .trimIndent().replace("\n", "")

fun main() {
    // ------------------------------------------------------------------ text stays text
    widgetCheck("a plain line is text", widgetRow("\u001b[2mhello\u001b[0m"), WidgetRow.Text("\u001b[2mhello\u001b[0m"))
    widgetCheck("an empty line is text (it is spacing)", widgetRow(""), WidgetRow.Text(""))

    // A colon is not a payload. Only `<PREFIX>:{` is, or an extension's own sentence would
    // disappear into a chip.
    widgetCheck("prose with a colon is text", widgetRow("FOO_JSON: hello"), WidgetRow.Text("FOO_JSON: hello"))
    widgetCheck(
        "a prefix over a non-object is text",
        widgetRow("FOO_JSON: [1,2]"),
        WidgetRow.Text("FOO_JSON: [1,2]"),
    )

    // ------------------------------------------------------------------ the fold, generically
    val unknown = widgetRow("""FOO_JSON:{"a":1}""")
    widgetCheck(
        "an unregistered payload folds to its prefix",
        unknown,
        WidgetRow.Folded("FOO_JSON", """{"a":1}"""),
    )
    // The label is the prefix, never the payload: this is what keeps a re-sent payload from
    // changing the panel's layout.
    checkTrue("the folded label is the prefix alone", (unknown as WidgetRow.Folded).label == "FOO_JSON")

    widgetCheck("an inspect reply is not drawn at all", widgetRow("""PI_SUBAGENT_INSPECT_JSON:{"x":1}"""), null)
    widgetCheck(
        "hidden rows vanish from the widget",
        widgetRows(listOf("a", """PI_SUBAGENT_INSPECT_JSON:{"x":1}""", "b")),
        listOf(WidgetRow.Text("a"), WidgetRow.Text("b")),
    )

    // ------------------------------------------------------------------ the real prefix
    val one = snapshot(run("r1", "oracle", "running", 4, 6), generatedAt = 1L)
    val summary = widgetRow("PI_SUBAGENT_ASYNC_JSON:$one") as? WidgetRow.Summary
    checkTrue("the snapshot is summarised", summary != null)
    widgetCheck("the headline counts by state", summary?.headline, "子代理：1 运行中")
    widgetCheck("one detail line per run", summary?.details?.size, 1)
    checkTrue(
        "the detail carries pi-subagents' own glyph, label and stats",
        summary?.details?.firstOrNull() == "⠋ oracle · 4 轮 · 6 工具",
        "detail=${summary?.details?.firstOrNull()}",
    )
    widgetCheck("a summary keeps the raw payload for the viewer", summary?.raw, one)

    // **The anti-flicker property.** The extension re-sends this line on every status tick and
    // only `generatedAt`/`lastActivityAt`/`updatedAt` move between them.
    val later = snapshot(run("r1", "oracle", "running", 4, 6), generatedAt = 1_790_206_039_999L)
    checkTrue("a newer snapshot renders byte-identically", widgetRow("PI_SUBAGENT_ASYNC_JSON:$later") == summary)

    // Counts *do* move, and must: that is a fact the user can see.
    widgetCheck(
        "finished runs are counted separately",
        (widgetRow("PI_SUBAGENT_ASYNC_JSON:" + snapshot(run("a", "x", "running", 1, 1) + "," + run("b", "y", "complete", 2, 3))) as WidgetRow.Summary).headline,
        "子代理：1 运行中 · 1 完成",
    )

    // The shape from the device: three parallel agents, all running.
    val three = snapshot(
        run("a", "scout", "running", 3, 7) + "," + run("b", "reviewer", "running", 3, 7) + "," +
            run("c", "oracle", "running", 4, 6),
    )
    widgetCheck(
        "three parallel agents read as one line",
        (widgetRow("PI_SUBAGENT_ASYNC_JSON:$three") as WidgetRow.Summary).headline,
        "子代理：3 运行中",
    )

    widgetCheck("no runs is not an error", (widgetRow("PI_SUBAGENT_ASYNC_JSON:" + snapshot("")) as WidgetRow.Summary).headline, "子代理：无活动任务")

    // `omitted` is part of the payload's contract; a summary that ignored it would print a
    // count the user can see is short.
    val truncated = one.replace(""""byteLimitExceeded":false""", """"byteLimitExceeded":true""")
    checkTrue(
        "a truncated snapshot says so",
        (widgetRow("PI_SUBAGENT_ASYNC_JSON:$truncated") as? WidgetRow.Summary)?.headline?.endsWith("（已截断）") == true,
    )

    // pi-subagents draws the first four jobs and summarises the rest (`MAX_WIDGET_JOBS = 4`).
    val many = snapshot((0 until 6).joinToString(",") { run("r$it", "agent$it", "running", 1, 1) })
    val manySummary = widgetRow("PI_SUBAGENT_ASYNC_JSON:$many") as? WidgetRow.Summary
    widgetCheck("details stop at the panel's own cap", manySummary?.details?.size, 5)
    widgetCheck("and the remainder is named", manySummary?.details?.lastOrNull(), "+2 个更多")

    // ------------------------------------------------------- version/kind guards, and refusing
    checkTrue(
        "a future version folds instead of being guessed at",
        widgetRow("PI_SUBAGENT_ASYNC_JSON:" + one.replace(""""version":1""", """"version":2""")) is WidgetRow.Folded,
    )
    checkTrue(
        "another kind of payload folds",
        widgetRow("PI_SUBAGENT_ASYNC_JSON:" + one.replace("pi-subagents.async-status-snapshot", "something.else")) is WidgetRow.Folded,
    )
    // A payload-shaped line whose body is not JSON must still not become text: a 32 KB blob is
    // not made safe by being malformed.
    checkTrue("a malformed body folds", widgetRow("""PI_SUBAGENT_ASYNC_JSON:{oops}""") is WidgetRow.Folded)

    // ------------------------------------------------------------------ the size bound
    val atLimit = "x".repeat(WIDGET_LINE_MAX_CHARS)
    widgetCheck("a line at the limit is still text", widgetRow(atLimit), WidgetRow.Text(atLimit))
    val over = widgetRow("x".repeat(WIDGET_LINE_MAX_CHARS + 1)) as? WidgetRow.Folded
    checkTrue("a line over the limit folds", over != null)
    checkTrue(
        "and its chip says how much there is",
        over?.label == "长文本 ${WIDGET_LINE_MAX_CHARS + 1} 字符",
        "label=${over?.label}",
    )
    widgetCheck("the raw text is kept for the viewer", over?.raw?.length, WIDGET_LINE_MAX_CHARS + 1)

    // The invariant the renderer relies on, over every shape above.
    val everyShape = listOf(
        "hello",
        "",
        "FOO_JSON: hello",
        """FOO_JSON:{"a":1}""",
        """PI_SUBAGENT_INSPECT_JSON:{"x":1}""",
        "PI_SUBAGENT_ASYNC_JSON:$three",
        "PI_SUBAGENT_ASYNC_JSON:$many",
        "PI_SUBAGENT_ASYNC_JSON:{oops}",
        atLimit,
        "x".repeat(32 * 1024),
    )
    checkTrue(
        "no text row ever exceeds the bound",
        widgetRows(everyShape).filterIsInstance<WidgetRow.Text>().all { it.text.length <= WIDGET_LINE_MAX_CHARS },
    )
    checkTrue(
        "a 32 KB line is one row, not a screenful",
        widgetRows(listOf("x".repeat(32 * 1024))).size == 1,
    )
    checkTrue(
        "every payload line folds into exactly one row",
        everyShape
            .filter { it.contains("_JSON:") && widgetRow(it) != null }
            .all { widgetRows(listOf(it)).let { rows -> rows.size == 1 && rows.first() !is WidgetRow.Text } },
    )

    println(if (widgetFailures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($widgetFailures)")
    if (widgetFailures != 0) exitProcess(1)
}
