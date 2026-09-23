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
// Four of the properties below cannot be seen by a compiler:
//
//  1. **The panel's visible bytes do not depend on the payload's volatile fields.** Two
//     snapshots that differ only in `generatedAt`/`updatedAt` must produce the same label,
//     badge, headline and details. (`raw` does differ, and must: it is the opened viewer's
//     content, not the panel's layout.)
//  2. **The state mapping is the extension's own**, not this App's taste: `running` accent,
//     `queued` muted, `complete` success, `paused`/`stopped` warning, and **everything else**
//     error — including the `partial`/`rejected` states `pi-subagents` never gives a glyph of
//     its own.
//  3. **Nothing is drawn wider than pi's machine-line limit**, which is `TOOL_LINE_MAX_CHARS`
//     (pi's own grep limit, `core/tools/truncate.ts:13`) — the same number and the same `…`
//     the tool card uses, so the two cards cannot disagree about what "too long" means.
//  4. **Prose is not swallowed.** `FOO_JSON: hello` is a line of text; only `<PREFIX>:{` is a
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

private fun textOf(spans: List<WidgetSpan>): String = spans.joinToString("") { it.text }
private fun tonesOf(spans: List<WidgetSpan>): List<WidgetTone> = spans.map { it.tone }

/** The parts of a summary the panel actually draws. `raw` is deliberately not one of them. */
private fun visible(row: WidgetRow?): List<Any?>? = (row as? WidgetRow.Summary)?.let {
    listOf(it.label, it.badge, it.headline, it.details, it.worst)
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
        "activity":{"state":"running","currentTool":"read","turnCount":$turns,
                    "toolCount":$tools,"lastActivityAt":1790200638560},
        "children":[{"id":"step:0","kind":"step","label":"scout","state":"$state"}]}"""
        .trimIndent().replace("\n", "")

private fun summary(json: String): WidgetRow.Summary =
    widgetRow("PI_SUBAGENT_ASYNC_JSON:$json") as? WidgetRow.Summary
        ?: error("the payload no longer summarises: ${json.take(120)}")

fun main() {
    // ------------------------------------------------------------------ text stays text
    val ansi = "\u001b[2mhello\u001b[0m"
    widgetCheck("a plain line is text", widgetRow(ansi), WidgetRow.Text(ansi))
    widgetCheck("an empty line is text (it is spacing)", widgetRow(""), WidgetRow.Text(""))

    // A colon is not a payload. Only `<PREFIX>:{` is, or an extension's own sentence would
    // disappear into a chip.
    widgetCheck("prose with a colon is text", widgetRow("FOO_JSON: hello"), WidgetRow.Text("FOO_JSON: hello"))
    widgetCheck("a prefix over a non-object is text", widgetRow("FOO_JSON: [1,2]"), WidgetRow.Text("FOO_JSON: [1,2]"))

    // ------------------------------------------------------------------ pi's machine-line limit
    val atLimit = "x".repeat(500)
    widgetCheck("a line at the limit is untouched", widgetRow(atLimit), WidgetRow.Text(atLimit))
    val over = widgetRow("x".repeat(501)) as WidgetRow.Text
    widgetCheck("a line one over the limit is cut, not folded", over.text.length, 501)
    checkTrue("and it is marked with pi's own ellipsis", over.text.endsWith("…"), "text=${over.text.takeLast(4)}")
    checkTrue("the cut is at TOOL_LINE_MAX_CHARS (pi's grep limit)", over.text.dropLast(1) == atLimit)

    // ------------------------------------------------------------------ the fold, generically
    val unknown = widgetRow("""FOO_JSON:{"a":1}""")
    widgetCheck("an unregistered payload folds to its prefix", unknown, WidgetRow.Folded("FOO_JSON", """{"a":1}"""))

    widgetCheck("an inspect reply is not drawn at all", widgetRow("""PI_SUBAGENT_INSPECT_JSON:{"x":1}"""), null)
    widgetCheck(
        "hidden rows vanish from the widget",
        widgetRows(listOf("a", """PI_SUBAGENT_INSPECT_JSON:{"x":1}""", "b")),
        listOf(WidgetRow.Text("a"), WidgetRow.Text("b")),
    )

    // ------------------------------------------------------------------ the real prefix
    val one = summary(snapshot(run("r1", "oracle", "running", 4, 6), generatedAt = 1L))
    widgetCheck("the title is the host's own label plus the job count", listOf(one.label, one.badge), listOf("子代理", "1"))
    widgetCheck("the headline counts by state, in the extension's colour", tonesOf(one.headline), listOf(WidgetTone.Accent))
    widgetCheck("the headline reads as words", textOf(one.headline), "1 运行中")

    // **The anti-flicker property.** The extension re-sends this line on every status tick and
    // only `generatedAt`/`lastActivityAt`/`updatedAt` move between them.
    val later = summary(snapshot(run("r1", "oracle", "running", 4, 6), generatedAt = 1_790_206_039_999L))
    widgetCheck("a newer snapshot draws the same bytes", visible(widgetRow("PI_SUBAGENT_ASYNC_JSON:" + later.raw)), visible(widgetRow("PI_SUBAGENT_ASYNC_JSON:${one.raw}")))
    checkTrue("and the payload itself did change", one.raw != later.raw)

    // The detail row: glyph in the state's colour, the name as the one run a reader scans for
    // (bold at the drawing site), the rest dim — the extension's own shape (`themeBold`,
    // `statJoin`).
    val detail = one.details.first()
    widgetCheck("one detail row per job", one.details.size, 1)
    widgetCheck("the glyph carries the state's colour", tonesOf(detail.take(1)), listOf(WidgetTone.Accent))
    widgetCheck("the name is the readable run", listOf(detail[1].text, detail[1].tone), listOf("oracle", WidgetTone.Text))
    widgetCheck(
        "stats are dim and start with the current tool (the extension's widgetActivity)",
        listOf(detail[2].tone, detail.last().text),
        listOf(WidgetTone.Dim, "read · 4 轮 · 6 工具"),
    )

    // The extension's own fall-through: `widgetStatusGlyph` draws ✗ in error for every state it
    // does not name, so `partial` and `rejected` must not get a glyph this App invented.
    for (state in listOf("partial", "rejected", "something-new")) {
        val odd = summary(snapshot(run("x", "odd", state, 1, 1)))
        widgetCheck("a state the extension does not name is an error glyph ($state)", tonesOf(odd.headline), listOf(WidgetTone.Error))
        checkTrue("and it draws the extension's ✗ ($state)", textOf(odd.details.first()).startsWith("✗ "), "row=${textOf(odd.details.first())}")
    }
    // The two states it *does* name with a warning glyph.
    for (state in listOf("paused", "stopped")) {
        val held = summary(snapshot(run("x", "held", state, 1, 1)))
        widgetCheck("$state is a warning, as the extension draws it", tonesOf(held.headline), listOf(WidgetTone.Warning))
    }

    // Counts *do* move, and must: that is a fact the user can see.
    val mixed = summary(snapshot(run("a", "x", "running", 1, 1) + "," + run("b", "y", "complete", 2, 3) + "," + run("c", "z", "failed", 1, 1)))
    widgetCheck("the headline reads in the extension's own state order", textOf(mixed.headline), "1 运行中 · 1 完成 · 1 失败")
    widgetCheck("the card's tone is the most severe job", mixed.worst, WidgetTone.Error)
    widgetCheck("the headline's tones are per state", tonesOf(mixed.headline), listOf(WidgetTone.Accent, WidgetTone.Dim, WidgetTone.Success, WidgetTone.Dim, WidgetTone.Error))

    // The shape from the device: three parallel agents, all running.
    val three = summary(
        snapshot(
            run("a", "scout", "running", 3, 7) + "," + run("b", "reviewer", "running", 3, 7) + "," +
                run("c", "oracle", "running", 4, 6),
        ),
    )
    widgetCheck("three parallel agents read as one line", textOf(three.headline), "3 运行中")
    widgetCheck("no runs is not an error", textOf(summary(snapshot("")).headline), "无活动任务")

    val truncated = one.raw.replace(""""byteLimitExceeded":false""", """"byteLimitExceeded":true""")
    checkTrue("a truncated snapshot says so", textOf(summary(truncated).headline).endsWith("（已截断）"))

    // pi-subagents draws the first four jobs and summarises the rest (`MAX_WIDGET_JOBS = 4`).
    val many = summary(snapshot((0 until 6).joinToString(",") { run("r$it", "agent$it", "running", 1, 1) }))
    widgetCheck("details stop at the panel's own cap", many.details.size, 5)
    widgetCheck("and the remainder is named", textOf(many.details.last()), "+2 个更多")
    widgetCheck("in the dim token", tonesOf(many.details.last()), listOf(WidgetTone.Dim))

    // ------------------------------------------------------- version/kind guards, and refusing
    checkTrue(
        "a future version folds instead of being guessed at",
        widgetRow("PI_SUBAGENT_ASYNC_JSON:" + one.raw.replace(""""version":1""", """"version":2""")) is WidgetRow.Folded,
    )
    checkTrue(
        "another kind of payload folds",
        widgetRow("PI_SUBAGENT_ASYNC_JSON:" + one.raw.replace("pi-subagents.async-status-snapshot", "something.else")) is WidgetRow.Folded,
    )
    checkTrue("a malformed body folds", widgetRow("""PI_SUBAGENT_ASYNC_JSON:{oops}""") is WidgetRow.Folded)

    // ------------------------------------------------------------------ the card's own tone
    widgetCheck("a text-only widget has no state to carry", widgetCardTone(listOf(WidgetRow.Text("hi"))), null)
    widgetCheck("an unregistered payload has none either", widgetCardTone(listOf(WidgetRow.Folded("FOO_JSON", "{}"))), null)
    widgetCheck("a card with a failed job takes its colour", widgetCardTone(listOf(mixed)), WidgetTone.Error)
    widgetCheck("a warning is weaker than an error", widgetCardTone(listOf(summary(snapshot(run("x", "h", "paused", 1, 1))), mixed)), WidgetTone.Error)

    // ------------------------------------------------------------------ the invariants
    val everyShape = listOf(
        "hello",
        "",
        "FOO_JSON: hello",
        """FOO_JSON:{"a":1}""",
        """PI_SUBAGENT_INSPECT_JSON:{"x":1}""",
        "PI_SUBAGENT_ASYNC_JSON:${three.raw}",
        "PI_SUBAGENT_ASYNC_JSON:${many.raw}",
        "PI_SUBAGENT_ASYNC_JSON:{oops}",
        atLimit,
        "x".repeat(32 * 1024),
    )
    checkTrue(
        "no drawn line exceeds pi's machine-line limit (plus the ellipsis)",
        widgetRows(everyShape).filterIsInstance<WidgetRow.Text>().all { it.text.length <= 501 },
    )
    checkTrue("a 32 KB line is one row, not a screenful", widgetRows(listOf("x".repeat(32 * 1024))).size == 1)
    checkTrue(
        "every declared payload folds into exactly one row",
        everyShape
            .filter { widgetRow(it) is WidgetRow.Folded || widgetRow(it) is WidgetRow.Summary }
            .all { widgetRows(listOf(it)).let { rows -> rows.size == 1 && rows.first() !is WidgetRow.Text } },
    )

    println(if (widgetFailures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($widgetFailures)")
    if (widgetFailures != 0) exitProcess(1)
}
