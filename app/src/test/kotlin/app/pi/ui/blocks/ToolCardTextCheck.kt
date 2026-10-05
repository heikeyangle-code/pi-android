package app.pi.ui.blocks

// A bare-JVM harness for the tool card's **words**, its **run plan**, and the transcript's
// **block rhythm** — compiled and run by `tools/run-app-pure-checks.sh`, whose closure is
// Android-free (kotlin stdlib + kotlinx.serialization through `:rpc`) because the Compose UI
// these three serve cannot be compiled on this machine at all (`tools/typecheck.sh` runs no
// Compose compiler plugin).
//
// Why it exists, in one line each:
//
//  1. **The footer sentence must stay byte-for-byte what it was.** `差异表` §2 第 3 行 moves
//     `状态词 · 退出码 N · 耗时 · N 行 · 已截断 · 无输出` out of the collapsed card and onto the
//     expanded card's last line, and says 「措辞与 ` · ` 顺序逐字」. A move plus a promise is not
//     a check; `toolFooterText` / `toolRejectedFooter` are now in a file this harness compiles,
//     so the exact strings and their order are asserted here.
//  2. **The row's folded cells have an exact vocabulary** (`成功`, `成功 · 已截断`, `被拒` +
//     `没有执行`) and an exact reading rule (`ms` for the file tools, pi's `6.4s` / `2m 49s` for
//     the two shells, *nothing* numeric for a blocked call). Same reason.
//  3. **A run's shell may not invent a state.** `toolRunPlan` decides, in one pass, whether a run
//     is uniform (its own state colour) or mixed (the neutral ring), and `railStateOf` decides
//     which rows are even on the rail. The v5 board's `.run[data-ring=…]` is the design; this is
//     the arithmetic under it.
//  4. **The gap a row is padded with and the gap the rail overdraws must be one number.** That
//     was the defect: `RAIL_BRIDGE` was a compile-time 8 while the spacing was a runtime setting
//     (4 / 8 / 16), equal in the default tier only — so under 宽松 the rail's line stopped short
//     of the next card and the shell would have shown a seam. The three tiers are asserted here,
//     *and* the source is read to assert there is still exactly **one** place that gives the
//     spacing (the F11 shape: block padding + list `spacedBy` = twice the gap).
//  5. **The two "1:1" geometry values the review found are asserted, not promised.** 审查 #19:
//     the folded row's reading track is one fixed 44 dp, and the cell is laid out on *every* row
//     (so the verdict's right edge cannot move). 审查 #13: `RAIL_NODE_LEFT` + `RAIL_NODE_SIZE`/2
//     equals `RAIL_LINE_LEFT` + ½ — a 15 dp node needs `left 2` to stay concentric with the
//     `left 9`/1 dp line, which is what `left 1` was arithmetic for at 17 dp.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** A settled tool call with only the fields a card's text reads. */
private fun call(
    name: String = "read",
    status: String = "success",
    output: String = "line",
    exitCode: Int? = null,
    elapsedMs: Long? = null,
    truncated: Boolean = false,
) = app.pi.rpc.ToolCall(
    key = "k",
    ts = 0L,
    toolCallId = "k",
    toolName = name,
    status = when (status) {
        "pending" -> app.pi.rpc.ToolStatus.Pending
        "error" -> app.pi.rpc.ToolStatus.Error
        else -> app.pi.rpc.ToolStatus.Success
    },
    output = output,
    exitCode = exitCode,
    outputTruncated = truncated,
    // `ToolCall.elapsedMs` is `endedAt - ts`, and `ts` is 0 here, so this is the reading the
    // card would compute for a settled call.
    endedAt = elapsedMs,
)

fun main() {
    // ------------------------------------------------------------------ 1. footer wording
    //
    // The field order and the separator are pi's own (`06 §2` 工具卡 + `差异表` §1 第 6/7/8 行):
    // 状态词 → `退出码 N` → 耗时 → `N 行` → `已截断` → `无输出`, joined by ` · `.
    check(
        "F1 state + exit code + duration + lines, in pi's order",
        toolFooterText(
            call(exitCode = 0, elapsedMs = 700L),
            ToolState.Success,
            12,
        ),
        "成功 · 退出码 0 · 700ms · 12 行",
    )
    check(
        "F2 every optional part absent leaves the word alone",
        toolFooterText(call(elapsedMs = null, output = ""), ToolState.Success, 0),
        "成功 · 无输出",
    )
    check(
        "F3 已截断 comes after the count and before 无输出's slot",
        toolFooterText(call(elapsedMs = 18L, truncated = true), ToolState.Success, 1000),
        "成功 · 18ms · 1000 行 · 已截断",
    )
    check(
        "F4 无输出 is the last part, and only for an empty result",
        toolFooterText(call(elapsedMs = null, output = ""), ToolState.Failed, 0),
        "失败 · 无输出",
    )
    check(
        "F5 a blocked call replaces the whole sentence",
        toolFooterText(call(status = "error", output = "设备策略拒绝这条 Shell 命令"), ToolState.Rejected, 3),
        "被拒 · 没有执行",
    )
    check("F6 and that sentence is spelled once", toolRejectedFooter(), "被拒 · 没有执行")

    // ------------------------------------------------------------------ 2. the folded cells
    check("R1 a file tool's reading is milliseconds", toolHeaderReading(call(elapsedMs = 132L)), "132ms")
    check(
        "R2 a shell's reading is pi's own spelling (0.86.1's minute branch)",
        toolHeaderReading(call(name = "bash", elapsedMs = 6400L)),
        "6.4s",
    )
    check(
        "R3 a two-minute shell reads 2m 49s, not 169.0s",
        toolHeaderReading(call(name = "bash", elapsedMs = 169_000L)),
        "2m 49s",
    )
    check(
        "R4 the live override is what a pending shell passes, and it is formatted the same way",
        toolHeaderReading(call(name = "bash", status = "pending"), 169_000L),
        "2m 49s",
    )
    check("R5 no measured duration, no reading", toolHeaderReading(call(elapsedMs = null)), null)
    check(
        "R6 a blocked call has no numeric reading at all (被拒没有读数)",
        toolHeaderReading(call(status = "error", output = "设备策略拒绝这条 Shell 命令", elapsedMs = 12L)),
        null,
    )
    check(
        "R7 …and its reading cell says so instead",
        toolRowReading(ToolState.Rejected, null),
        "没有执行",
    )
    check("R8 an ordinary row keeps whatever its block passed", toolRowReading(ToolState.Success, "12 项"), "12 项")
    check("V1 the verdict is the state word", toolRowVerdict(ToolState.Success, false), "成功")
    check("V2 truncation rides along", toolRowVerdict(ToolState.Success, true), "成功 · 已截断")
    check("V3 a blocked call never claims truncation", toolRowVerdict(ToolState.Rejected, true), "被拒")

    // ------------------------------------------------------------------ 3. the four states
    check("S1 pending is 运行中", toolStateOf(call(status = "pending")), ToolState.Running)
    check("S2 a clean result is 成功", toolStateOf(call()), ToolState.Success)
    check("S3 an unrecognised error is 失败", toolStateOf(call(status = "error", output = "boom")), ToolState.Failed)
    check(
        "S4 the blocker's own sentence is 被拒",
        toolStateOf(call(status = "error", output = "Tool execution was blocked")),
        ToolState.Rejected,
    )
    check(
        "S5 …matched as a prefix, so output that merely mentions it is still 失败",
        toolStateOf(call(status = "error", output = "grep: Tool execution was blocked? no match")),
        ToolState.Failed,
    )
    check("S6 the glyphs are the four from 06 §4", listOf(
        toolStateGlyph(ToolState.Running),
        toolStateGlyph(ToolState.Success),
        toolStateGlyph(ToolState.Failed),
        toolStateGlyph(ToolState.Rejected),
    ), listOf("…", "✓", "✗", "⊘"))

    // ------------------------------------------------------------------ 4. the run plan
    //
    // Rail rows in this fixture are `ToolCall`s built through `railStateOf`, which is the same
    // function the transcript list calls; a `Notice` stands for "this row is not on the rail"
    // (prose, a thinking row, a user bubble — anything that breaks a run).
    fun tool(state: ToolState) = call(
        name = "read",
        status = if (state == ToolState.Running) "pending" else if (state == ToolState.Success) "success" else "error",
        output = if (state == ToolState.Rejected) "Tool execution was blocked" else "x",
    )

    fun notice() = app.pi.rpc.Notice(key = "n", ts = 0L, text = "break")

    val uniform = toolRunPlan(listOf(tool(ToolState.Success), tool(ToolState.Success), tool(ToolState.Success)).map { railStateOf(it) })
    check("P1 a uniform run marks only its two ends", uniform.map { it.firstOfRun to it.lastOfRun }, listOf(
        true to false, false to false, false to true,
    ))
    check("P2 …and carries that state for every row", uniform.map { it.ring }, listOf(
        RailState.Success, RailState.Success, RailState.Success,
    ))

    val mixed = toolRunPlan(listOf(tool(ToolState.Success), tool(ToolState.Failed)).map { railStateOf(it) })
    check("P3 a mixed run has no state of its own", mixed.map { it.ring }, listOf(null, null))

    val broken = toolRunPlan(
        listOf(tool(ToolState.Success), notice(), tool(ToolState.Failed))
            .map { railStateOf(it) },
    )
    check("P4 a non-rail row breaks the run", broken.map { it.firstOfRun to it.lastOfRun }, listOf(
        true to true, true to true, true to true,
    ))
    check("P5 …so each side is its own uniform run", broken.map { it.ring }, listOf(
        RailState.Success, null, RailState.Failed,
    ))

    val diff = toolRunPlan(
        listOf(
            app.pi.rpc.ToolDiff(key = "d", ts = 0L, toolName = "edit", path = "a.kt", diffText = "", added = 1, removed = 1),
            app.pi.rpc.ToolDiff(key = "d2", ts = 0L, toolName = "edit", path = "b.kt", diffText = "", added = 1, removed = 1),
        ).map { railStateOf(it) },
    )
    check("P6 diffs are rail rows with their own neutral tone", diff.map { it.ring }, listOf(
        RailState.Diff, RailState.Diff,
    ))
    check("P7 and they group as a run like any other", diff.map { it.firstOfRun to it.lastOfRun }, listOf(
        true to false, false to true,
    ))

    val alone = toolRunPlan(listOf(railStateOf(tool(ToolState.Rejected))))
    check("P8 one card is a whole run", alone, listOf(ToolRunSlot(firstOfRun = true, lastOfRun = true, ring = RailState.Rejected)))
    check("P9 an empty transcript has no plan", toolRunPlan(emptyList()), emptyList<ToolRunSlot>())

    // ------------------------------------------------------------------ 5. the block rhythm
    //
    // `差异表` §2 第 7 行: the default is 4 (an accounted deviation from `06 §2`'s 8), and the
    // three density steps stay a doubling chain so the preference still moves the stream.
    check("G1 compact is half the default", blockGapDp("compact"), 2)
    check("G2 the default is `PiSpacing.small`", blockGapDp(null), 4)
    check("G3 cozy is double the default (= `06 §2`'s 8)", blockGapDp("cozy"), 8)
    // The invariant the rail and the shell rest on: **the number a row is padded with is the
    // number the overdraw is given**, for every tier — because both ask this one function.
    for (density in listOf("compact", null, "cozy")) {
        val item = tool(ToolState.Success)
        check(
            "G4 rowGapDp == blockGapDp for ${density ?: "default"} (overdraw == the row's own air)",
            item.rowGapDp(density),
            blockGapDp(density),
        )
    }

    // ------------------------------------------------------------------ 6. one place gives it
    //
    // The two halves of the F11 regression guard, read as source text (the layout itself needs
    // Compose, but "how many places give the spacing" is a property of the source).
    val root = System.getProperty("pi.repo.root")?.let { java.io.File(it) } ?: java.io.File(".")
    val chat = java.io.File(root, "app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt")
    val chrome = java.io.File(root, "app/src/main/kotlin/app/pi/ui/blocks/BlockChrome.kt")
    val rail = java.io.File(root, "app/src/main/kotlin/app/pi/ui/blocks/ToolRail.kt")
    check("X1 the three sources this reads are there", listOf(chat.isFile, chrome.isFile, rail.isFile), listOf(true, true, true))
    if (chat.isFile && chrome.isFile && rail.isFile) {
        val chatText = chat.readText()
        val chromeText = chrome.readText()
        val railText = rail.readText()
        check(
            "X2 the transcript list spaces nothing (F11: block padding + list spacedBy = twice the gap)",
            chatText.contains("verticalArrangement = Arrangement.spacedBy(blockSpacing)"),
            false,
        )
        // X3 asserts the **contract**, not the call's spelling. An earlier version of this check
        // pinned the literal `LocalRowGap provides rowGapDp(item, …)` and CI went red the moment
        // `3282248` rewrote the very same call as `item.rowGapDp(…)` — the row still got its own
        // gap, and nothing about the layout changed: the assertion was testing a spelling. What
        // must hold is 「每一行拿到自己的底部留白」, which is three facts: exactly one provider,
        // the provided value comes from the row's own gap function seeded by the density
        // preference, and it is set inside the list's per-row scope (a single provider around the
        // whole list would hand every row the same number and undo the point).
        val provides = chatText.indexOf("LocalRowGap provides")
        check("X3.1 the row's gap has exactly one provider", chatText.split("LocalRowGap provides").size - 1, 1)
        check("X3.2 it is inside the list's per-row scope, not once for the list", provides > chatText.indexOf("itemsIndexed("), true)
        val provided = if (provides < 0) "" else chatText.substring(provides).lineSequence().first()
        check("X3.3 the value comes from the row's own gap function", provided.contains("rowGapDp("), true)
        check("X3.4 …seeded by the density preference", provided.contains("prefs.messageDensity"), true)
        check(
            "X4 and exactly one block applies it as bottom padding",
            chromeText.split("padding(bottom = LocalRowGap.current)").size - 1,
            1,
        )
        check("X5 the rail reads that very value back for its overdraw", railText.contains("LocalRowGap.current"), true)
        // The other half of "one source": the value has exactly one applier and one reader, so
        // "the overdraw equals the row's own bottom air" is structural rather than a promise —
        // and the list itself never touches it (that is the F11 shape).
        check(
            "X11 exactly one applier and one reader of that value",
            listOf(
                chromeText.split("LocalRowGap.current").size - 1,
                railText.split("LocalRowGap.current").size - 1,
                chatText.split("LocalRowGap.current").size - 1,
            ),
            listOf(1, 1, 0),
        )
        check(
            "X6 with no compile-time bridge constant to disagree with it",
            railText.contains("internal val RAIL_BRIDGE"),
            false,
        )

        // ------------------------------------------------- 7. the two 1:1 values the review found
        //
        // Both are numbers Compose owns, so they cannot be instantiated here — but both are
        // *decisions*, and the defects they fix were decisions that drifted apart from the
        // geometry they were derived from. Asserting the decision (one number, and the arithmetic
        // that relates three numbers) is what stops the next drift.
        val header = java.io.File(root, "app/src/main/kotlin/app/pi/ui/blocks/ToolBlockChrome.kt")
        check("X7 the collapsed row's source is there", header.isFile, true)
        if (header.isFile) {
            val headerText = header.readText()
            // 审查 #19: v5 pins the verdict cell's right edge with the reading track's own fixed
            // width. `auto` there — which is what a width-less `Column` is — lets the state word
            // slide with whatever the reading happens to spell.
            val readout = Regex("""TOOL_READOUT_WIDTH\s*=\s*([0-9.]+)\.dp""")
                .find(headerText)?.groupValues?.get(1)?.toFloat()
            check("X8 v5's reading track is one fixed 44 dp (审查 #19)", readout, 44f)
            check(
                "X9 …and the cell is laid out on every row, so the verdict's edge cannot move",
                headerText.contains("if (reading != null || elapsedMs != null)"),
                false,
            )
        }
        // 审查 #13: the line's centre and the node's centre are one number. `left 1` was right
        // for a 17 dp node; 15 dp needs `left 2`, or the circle sits 1 dp off its wire.
        fun dpOf(name: String): Float? =
            Regex("""\b$name:\s*Dp\s*=\s*([0-9.]+)\.dp""").find(railText)?.groupValues?.get(1)?.toFloat()
        val lineMid = (dpOf("RAIL_LINE_LEFT") ?: -1f) + 0.5f
        val nodeMid = (dpOf("RAIL_NODE_LEFT") ?: -1f) + (dpOf("RAIL_NODE_SIZE") ?: -1f) / 2f
        check(
            "X10 the 15 dp node is concentric with the 1 dp line: left 2 + 7.5 == 9 + 0.5 (审查 #13)",
            nodeMid,
            lineMid,
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
