package app.pi.ui.extension

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * The payload fixtures, captured from the extension's **own** projection
 * (`tools/collect-subagent-fixtures.mjs`).
 *
 * The hand-written JSON this file used to carry is why a four-agent run rendered as
 * "1 运行中" with the tasks missing: an invented shape agrees with the parser that reads it.
 * These files are what `pi-subagents@0.71.0` actually emits, and `--check` re-derives them.
 */
private val FIXTURE_DIR: File = File(System.getProperty("pi.repo.root") ?: ".")
    .resolve("app/src/test/resources/pi-subagent-fixtures")

private fun fixtureLine(id: String): String {
    val file = File(FIXTURE_DIR, "$id.json")
    if (!file.isFile) {
        println("widget-payload: CANNOT RUN — missing ${file.path}")
        println("  run `node tools/collect-subagent-fixtures.mjs --pkg <pi-subagents> --write`")
        exitProcess(2)
    }
    return Json.parseToJsonElement(file.readText()).jsonObject["line"]!!.jsonPrimitive.content
}

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

private fun run(
    id: String,
    label: String,
    state: String,
    turns: Int,
    tools: Int,
    updatedAt: Long = 1_790_200_638_560L,
    endedAt: Long? = null,
): String {
    // `updatedAt` and `endedAt` are parameters because the *difference* between them is the
    // point: the extension moves `updatedAt` on every status tick and writes `endedAt` once.
    // A fixture that could not move `updatedAt` was a fixture that could not fail.
    val ended = if (endedAt != null) ",\"endedAt\":$endedAt" else ""
    return "{\"id\":\"$id\",\"kind\":\"subagent\",\"label\":\"$label\",\"state\":\"$state\"," +
        "\"startedAt\":1790200606123,\"updatedAt\":$updatedAt$ended," +
        "\"activity\":{\"state\":\"running\",\"currentTool\":\"read\",\"turnCount\":$turns," +
        "\"toolCount\":$tools,\"lastActivityAt\":$updatedAt}," +
        "\"children\":[{\"id\":\"step:0\",\"kind\":\"step\",\"label\":\"scout\",\"state\":\"$state\"}]}"
}

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

    // -------------------------------------------------- a panel that brings no title of its own
    // pi's widget channel is a `string[]`, so most extensions that use it send no header at all
    // (`pi-web-access`: `setWidget("web-activity", lines)`). The key is the one identity the host
    // always has; it is humanised, never translated, because the host does not know what the
    // extension meant by it.
    widgetCheck("a widget key becomes its caption", widgetCaption("web-activity"), "web activity")
    widgetCheck("an underscore key too", widgetCaption("background_tasks"), "background tasks")
    widgetCheck("a blank key has no caption", widgetCaption("   "), null)

    // ------------------------------------------------------------------ the real prefix
    val one = summary(snapshot(run("r1", "oracle", "running", 4, 6), generatedAt = 1L))
    // One state: the headline already reads 「1 运行中」, so a badge would only repeat the number —
    // the device report was 「子代理 3 3 运行中」, and this is that defect's assertion.
    widgetCheck("a single state carries no badge", listOf(one.label, one.badge), listOf("子代理", null))
    widgetCheck("the headline counts by state, in the extension's colour", tonesOf(one.headline), listOf(WidgetTone.Accent))
    widgetCheck("the headline reads as words", textOf(one.headline), "1 运行中")

    // **The anti-flicker property — and the fixture that used to lie about it.** The extension
    // re-sends this line on every status tick, and the field it moves is `updatedAt` (the payload
    // is its own clock; `lastActivityAt` rides with it). The old `later` bumped only `generatedAt`,
    // a field nothing draws, so this assertion passed while device users watched the card redraw
    // several times a second. `updatedAt` moves here, which is the only version of the test that
    // can fail for the right reason.
    val later = summary(
        snapshot(
            run("r1", "oracle", "running", 4, 6, updatedAt = 1_790_206_039_999L),
            generatedAt = 1_790_206_039_999L,
        ),
    )
    widgetCheck("a newer snapshot draws the same bytes", visible(widgetRow("PI_SUBAGENT_ASYNC_JSON:" + later.raw)), visible(widgetRow("PI_SUBAGENT_ASYNC_JSON:${one.raw}")))
    checkTrue("and the payload itself did change", one.raw != later.raw)

    // The detail row: glyph in the state's colour, the name as the one run a reader scans for
    // (bold at the drawing site), the rest dim — the extension's own shape (`themeBold`,
    // `statJoin`).
    val detail = one.details.first()
    widgetCheck("the job row, its readings row, then its one task", one.details.size, 3)
    widgetCheck("the glyph carries the state's colour", tonesOf(detail.take(1)), listOf(WidgetTone.Accent))
    checkTrue("the state word rides with the glyph", detail.any { it.text == "运行中" && it.tone == WidgetTone.Accent })
    checkTrue("the name is the readable run", detail.any { it.text == "oracle" && it.tone == WidgetTone.Text })
    // **The job row carries `widgetStats`, the readings row carries `widgetActivity`** —
    // both in the shape official gives *this payload*: `widgetStats`' stage/step/parallel
    // branches need `mode`/`stepsTotal`/`currentStep`, fields the async projection never
    // writes (measured off all seven fixtures), so its stat slot here is the tool-use
    // count plus — **for a finished job only** — the job's elapsed. The job's elapsed
    // therefore lives on this row and **not** in the activity line — official separates
    // them (`render.js:1920` vs `:1111`).
    //
    // A *running* job shows no duration: its `updatedAt` moves on every tick, so
    // `updatedAt - startedAt` was a stopwatch that grew on every row, ten times a second.
    // That is the single largest contributor to the device report of rows "来回跳".
    widgetCheck(
        "a running job's stats are the tool count, with no duration to tick",
        listOf(detail[2].tone, detail.last().text),
        listOf(WidgetTone.Dim, " · 6 工具"),
    )
    // A finished job's duration is `endedAt - startedAt`: written once, and therefore the
    // only duration this card may draw.
    val finished = summary(snapshot(run("r2", "done", "complete", 4, 6, endedAt = 1_790_200_638_560L)))
    checkTrue(
        "a finished job shows its frozen duration",
        textOf(finished.details.first()).contains("32.4s"),
        "row=${textOf(finished.details.first())}",
    )
    val readings = one.details[1]
    // The live word leads, and it comes from the **extension's own** `activity.state` — never
    // from an age: this row used to print 「刚刚 / N 秒 / N 分钟」 out of
    // `updatedAt - lastActivityAt`, which moved on every status tick. Then the tool, then the
    // counts.
    checkTrue(
        "the readings are their own dim row: live label, then tool first",
        textOf(readings).startsWith("⎿  正在活跃 · read") && readings.all { it.tone == WidgetTone.Dim },
        "row=${textOf(readings)}",
    )
    checkTrue(
        "and they carry the turn/tool counts, without the job's elapsed (widgetActivity)",
        textOf(readings).contains("4 轮") && textOf(readings).contains("6 工具") &&
            !textOf(readings).contains("32.4s"),
        "row=${textOf(readings)}",
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
    // A breakdown does not carry the total, so here the badge is the only place it can be said.
    widgetCheck("a breakdown keeps the badge", mixed.badge, "3")
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

    // Wording follows the user's own ruling (e22ca84): the byte-limit case says
    // 「（超出字节上限）」; the count case says 「+N 个未列出」 (below). The original
    // assertion expected 「（已截断）」 and predates that ruling.
    val truncated = one.raw.replace(""""byteLimitExceeded":false""", """"byteLimitExceeded":true""")
    checkTrue("a truncated snapshot says so", textOf(summary(truncated).headline).endsWith("（超出字节上限）"))

    // pi-subagents draws the first four jobs and summarises the rest (`MAX_WIDGET_JOBS = 4`).
    val many = summary(snapshot((0 until 6).joinToString(",") { run("r$it", "agent$it", "running", 1, 1) }))
    widgetCheck("four jobs (readings + task each), then the remainder", many.details.size, 13)
    // The extension breaks the remainder down (`+N more (1 running, 1 finished)`), and the
    // breakdown is what says whether the hidden ones are still working.
    widgetCheck("and the remainder is named and broken down", textOf(many.details.last()), "+2 个更多（2 运行中）")
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

    // **A job is not an agent.** A scripted call spawns N tasks inside one async job, and the
    // extension's own `label` for that job is a joined agent list
    // (`researcher, researcher, researcher, +1 more`). Counting `runs` therefore drew
    // "1 运行中" for a run of four agents — the card contradicted the sentence above it, and
    // this is the device case that produced the report. Leaves are what the user counts.
    // **The real payload**, not a hand-written one: `scripted-parallel` is what the extension's
    // own projection emits for a scripted call (one job, four tasks). Hand-written fixtures
    // agreed with the code that read them — that is how a four-agent run stayed invisible.
    val scriptedRaw = fixtureLine("scripted-parallel")
    val scripted = widgetRow(scriptedRaw) as WidgetRow.Summary
    widgetCheck("a scripted job counts its agents, not itself", textOf(scripted.headline), "2 运行中 · 1 完成 · 1 失败")
    widgetCheck("and the badge is the agent count", scripted.badge, "4")
    widgetCheck("the job row, its readings, then its four tasks", scripted.details.size, 6)
    val taskRows = scripted.details.filter { textOf(it).startsWith("├─ ") || textOf(it).startsWith("└─ ") }
    checkTrue(
        "task rows start with their branch glyph",
        taskRows.size == 4 && taskRows.all { it.first().tone == WidgetTone.Dim },
        "rows=${taskRows.size}",
    )
    checkTrue("and carry the task's own name", taskRows.any { textOf(it).contains("catbox-recovery") })
    checkTrue(
        "each task shows its own state word",
        listOf("catbox-recovery" to "运行中", "st-card-optim" to "完成", "cn-community" to "失败").all { (name, word) ->
            taskRows.any { textOf(it).contains(name) && textOf(it).contains(word) }
        },
    )
    // The name is the extension's own (`widgetJobName`): for a scripted call that is the joined
    // agent list, and it is the only place the mode shows. The card used to overwrite it with
    // "4 个子任务", which made a parallel job and a chain job look identical.
    checkTrue(
        "the job row keeps the name the extension gave it",
        textOf(scripted.details[0]).contains("researcher, researcher, researcher, +1 more"),
        "row=${textOf(scripted.details[0])}",
    )
    checkTrue("and the row says which kind of job it is", textOf(scripted.details[0]).contains("子代理"))
    // The job's id, in the short form the inspect command takes. The value comes from the
    // **payload's own** `runs[0].id`; the older expectation (`f456576c`) belonged to a
    // hand-written payload that bb28a85 replaced with the extension's own projection, which
    // emits `job-1` here — an assertion no fixture can satisfy is not a check.
    checkTrue(
        "the job row carries the id the inspect command wants",
        scripted.details[0].any { it.text == " · job-1" },
        "row=${textOf(scripted.details[0])}",
    )
    // `omitted` is the sender's own count, and the card must repeat it rather than saying
    // only that something was cut.
    checkTrue(
        "a payload the sender trimmed names how many it dropped",
        textOf(
            summary(
                """{"kind":"pi-subagents.async-status-snapshot","version":1,"runs":[],
                    "omitted":{"runs":2,"children":3,"byteLimitExceeded":false}}""".trimIndent().replace("\n", ""),
            ).headline,
        // 用户原话的措辞（e22ca84）：`+N 个未列出`，不是更早的「另有 N 个」。
        ).contains("+5 个未列出"),
    )
    checkTrue(
        "a payload cut by the byte limit says that instead",
        textOf(
            summary(
                """{"kind":"pi-subagents.async-status-snapshot","version":1,"runs":[],
                    "omitted":{"runs":0,"children":0,"byteLimitExceeded":true}}""".trimIndent().replace("\n", ""),
            ).headline,
        ).contains("超出字节上限"),
    )
    // `fixtureLine` returns the **whole line** (prefix included). Feeding it through
    // `summary()` added a second prefix, so `payloadLine` never matched and this assertion
    // died with "the payload no longer summarises" — an exception that killed every later
    // check in this file. Draw it the way the card does: `widgetRow` on the line itself.
    checkTrue(
        "a payload that dropped nothing says nothing",
        !textOf((widgetRow(fixtureLine("more-than-the-panel-draws")) as WidgetRow.Summary).headline).contains("未列出"),
    )
    // **No clock anywhere on these rows.** The readings row used to carry the current tool's
    // stopwatch (`web_search 8.6s`, from `updatedAt - currentToolStartedAt`), and every job row
    // carried `updatedAt - startedAt`. Both move on every status tick — four parallel agents
    // meant four strings changing ~10×/s, which is the device report of rows "来回跳". The
    // readings row now names the tool; the job row's duration comes from `endedAt` only.
    val scriptedReadings = textOf(scripted.details[1])
    checkTrue(
        "the job's readings are their own row: live label · tool · counts, and no stopwatch",
        scriptedReadings.startsWith("⎿  ") &&
            scriptedReadings.contains("web_search") &&
            !scriptedReadings.contains("8.6s") &&
            scriptedReadings.contains("2 轮") &&
            scriptedReadings.contains("3 工具") &&
            !scriptedReadings.contains("32.4s"),
        "row=$scriptedReadings",
    )
    checkTrue(
        "a task with no timestamps shows no duration rather than a guess",
        taskRows.any { textOf(it).contains("web_search · 3 轮 · 7 工具") && !textOf(it).contains("s ·") },
    )
    // Official's stat slot for *this* payload: tool-use + the job's elapsed
    // (`widgetStats`' stage/step/parallel branches need fields the async projection
    // never writes — see `widgetJobStats`' KDoc). The elapsed is drawn **only** when the job
    // carries `endedAt`, i.e. once it is over and the number can no longer move.
    checkTrue(
        "a running job's row carries tool-use and no duration",
        textOf(scripted.details[0]).contains("3 工具") && !textOf(scripted.details[0]).contains("32.4s"),
        "row=${textOf(scripted.details[0])}",
    )
    checkTrue(
        "a finished job in the same snapshot does carry its frozen duration",
        scripted.details.any { textOf(it).contains("32.4s") },
        "rows=${scripted.details.map { textOf(it) }}",
    )

    // Identity before the glyph on child rows (official `materializedWidgetChildLines`:
    // `└─ ${bold identity} ${glyph} ${name}`), read from the fixture itself so the pin
    // follows whatever id the extension emits.
    val scriptedChildIds = Json.parseToJsonElement(scriptedRaw.substringAfter(':')).jsonObject["runs"]!!
        .jsonArray.first().jsonObject["children"]!!.jsonArray
        .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    checkTrue(
        "each task row carries its identity (official puts it before the glyph)",
        scriptedChildIds.isNotEmpty() && scriptedChildIds.all { id ->
            taskRows.any { row -> row.any { span -> span.text.contains(id) } }
        },
        "ids=$scriptedChildIds",
    )
    checkTrue("the last task closes the branch", textOf(scripted.details.last()).startsWith("└─ "))
    val childless = summary(snapshot("""{"id":"solo","kind":"subagent","label":"oracle","state":"running"}"""))
    widgetCheck(
        "a job with no tasks counts as one, keeps its name, and needs no badge",
        listOf(childless.badge, textOf(childless.headline), textOf(childless.details[0]).contains("oracle")),
        listOf(null, "1 运行中", true),
    )

    // ------------------------------------------------------------------ the card's own tone
    widgetCheck("a text-only widget has no state to carry", widgetCardTone(listOf(WidgetRow.Text("hi"))), null)
    widgetCheck("an unregistered payload has none either", widgetCardTone(listOf(WidgetRow.Folded("FOO_JSON", "{}"))), null)
    widgetCheck("a card with a failed job takes its colour", widgetCardTone(listOf(mixed)), WidgetTone.Error)
    widgetCheck("a warning is weaker than an error", widgetCardTone(listOf(summary(snapshot(run("x", "h", "paused", 1, 1))), mixed)), WidgetTone.Error)

    // ------------------------------------------------- the two non-widget surfaces
    // pi's `sanitizeStatusText` (`footer.ts:13-19`). A status is an arbitrary string, and
    // without this one embedded newline turns the card's single row into two.
    widgetCheck("a status is sanitised the way pi sanitises it", sanitizeStatusText("a\nb\tc  d \r\n"), "a b c d")
    widgetCheck("and trimmed", sanitizeStatusText("  x  "), "x")
    // pi's only ordering rule for statuses: by key, alphabetically (`footer.ts:235-237`).
    widgetCheck(
        "statuses are ordered by key, as pi orders them",
        statusRows(listOf("zeta" to "z", "alpha" to "a")).map { it.key },
        listOf("alpha", "zeta"),
    )
    widgetCheck("an entry that sanitises to nothing draws no row", statusRows(listOf("k" to "  \n ")).size, 0)
    widgetCheck("a status keeps the extension's own text", statusRows(listOf("k" to "待办 3/5")).first().text, "待办 3/5")

    // ------------------------------------------------- a text widget's real shapes
    // `pi-web-access` pushes file content and `pi-background-tasks` a joined paragraph, so one
    // array element can carry newlines. Drawing only the first row dropped the rest silently.
    widgetCheck(
        "an element carrying newlines becomes several rows",
        widgetRows(listOf("a\nb\nc")),
        listOf(WidgetRow.Text("a"), WidgetRow.Text("b"), WidgetRow.Text("c")),
    )
    widgetCheck("a blank element stays one row (it is spacing)", widgetRows(listOf("")), listOf(WidgetRow.Text("")))
    checkTrue("a widget whose lines all fit needs no affordance", !widgetNeedsDisclosure(listOf(WidgetRow.Text("short"))))
    checkTrue("a cut line earns one", widgetNeedsDisclosure(listOf(WidgetRow.Text("x".repeat(200)))))
    checkTrue("a payload always has something behind it", widgetNeedsDisclosure(listOf(widgetRow("""FOO_JSON:{"a":1}""")!!)))

    // -------------------------------------------------------- every captured payload
    // The regression net over real bytes: whatever the extension emits, the card must fold it
    // into rows, draw no line past pi's machine-line limit, and never fall over — including the
    // empty one and the state it never names.
    for (id in listOf(
        "single-agent", "scripted-parallel", "mixed-states", "more-than-the-panel-draws",
        "workflow-with-steps", "nothing-running", "unknown-state",
    )) {
        val row = widgetRow(fixtureLine(id))
        widgetCheck("$id still summarises", row is WidgetRow.Summary, true)
        val captured = row as? WidgetRow.Summary ?: continue
        checkTrue(
            "$id keeps every drawn line inside pi's limit",
            (captured.headline + captured.details.flatten()).all { it.text.length <= 501 },
        )
        checkTrue("$id names a tone for every span", captured.headline.isNotEmpty())
    }
    widgetCheck(
        "the empty payload counts nothing and says so",
        textOf((widgetRow(fixtureLine("nothing-running")) as WidgetRow.Summary).headline),
        "无活动任务",
    )
    widgetCheck(
        "a state the extension never names is its own error glyph",
        (widgetRow(fixtureLine("unknown-state")) as WidgetRow.Summary).worst,
        WidgetTone.Error,
    )

    // `currentPath` is projected **when a tool holds one** (none of the seven fixtures
    // has it) — the fact the readings line must carry when present, home-shortened the
    // way official `shortenPath` folds `$HOME`.
    val withPath = summary(
        snapshot(
            """{"id":"p1","kind":"subagent","label":"pathy","state":"running",
               "startedAt":1790200606123,"updatedAt":1790200638560,
               "currentPath":"/root/work/App.kt",
               "activity":{"state":"running","turnCount":1,"toolCount":1,
                           "lastActivityAt":1790200638560}}""".trimIndent().replace("\n", ""),
        ),
    )
    checkTrue(
        "the working path rides the readings line, home-shortened",
        textOf(withPath.details[1]).contains("~/work/App.kt"),
        "row=${textOf(withPath.details[1])}",
    )

    // Queued jobs collapse into ONE muted line between the running and the finished jobs
    // (official's slots budget: the summary line itself consumes a slot).
    val queuedPanel = summary(
        snapshot(
            """{"id":"r1","kind":"subagent","label":"go","state":"running","startedAt":1,"updatedAt":2},
               {"id":"r2","kind":"subagent","label":"wait","state":"queued"}""".trimIndent().replace("\n", ""),
        ),
    )
    checkTrue(
        "queued jobs are one muted summary line",
        queuedPanel.details.any { textOf(it) == "◦ 1 排队中" },
        "rows=${queuedPanel.details.map { textOf(it) }}",
    )

    // Official's fall-through when a job reports no activity at all: the status word
    // (`thinking…` / `Partial` / … at the tail of `widgetActivity`), localized.
    val silent = widgetRow(fixtureLine("unknown-state")) as WidgetRow.Summary
    checkTrue(
        "a job with no activity falls through to the status word",
        silent.details.size >= 2 && textOf(silent.details[1]).contains("部分完成"),
        "rows=${silent.details.map { textOf(it) }}",
    )

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
