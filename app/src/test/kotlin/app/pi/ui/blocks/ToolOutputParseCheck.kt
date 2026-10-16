package app.pi.ui.blocks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

// A bare-JVM harness for the per-tool result parser (P2-1, `docs/capability-gap.md` §4.9).
// Registered in `tools/run-app-pure-checks.sh` as `tool-output-parse`.
//
// Why this is worth pinning: `ToolOutputParse` is the only place that turns pi's *text*
// results into the rows the eight tool blocks paint, and every rule in it is a claim about
// text pi wrote somewhere else:
//
//   `grep`  rows: `core/tools/grep.ts:211` (`rel:line: text`) and `:212` (`rel-line- text`),
//           empty answer `:256-259`, trailing notice `:303`;
//   `find`  rows: `core/tools/find.ts:268-273`, empty answer `:261`, notice `:292`;
//   `ls`    rows: `core/tools/ls.ts:121-129` (directory = `"/"` suffix), empty `:135`,
//           notice `:155`;
//   `read`  footers: `core/tools/read.ts:56`, `:64`, `:66`, `:73`.
//
// If pi's wording changes, the rule stops matching *silently*: a `grep` result would render
// as one unrecognised blob, and nothing in the build would say so. The other half of the
// contract is that nothing here may crash — a newer engine's result must degrade to the
// generic card, not blank the transcript — so the last section feeds every entry point
// hostile input and asserts that each call still returns.
//
// Everything below is Android-free: kotlinx.serialization for the `JsonObject` arguments the
// protocol delivers, and the Kotlin stdlib. Run with `tools/run-app-pure-checks.sh`.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** A call that must return something rather than raise, whatever it was handed. */
private fun survives(name: String, block: () -> Any?) {
    val outcome = try {
        block()
        "returned"
    } catch (error: Throwable) {
        "threw ${error::class.java.simpleName}: ${error.message}"
    }
    check(name, outcome, "returned")
}

private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            null -> put(key, kotlinx.serialization.json.JsonNull)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            else -> error("unsupported test argument type for $key")
        }
    }
}

/**
 * A call line's runs, rendered so that a failure message is readable **and the tokens are part
 * of the assertion**.
 *
 * A tool card's argument line makes two separate claims about pi's source — the text, and which
 * `theme.fg(...)` run each piece of it is — and both have to be pinned. The shapes below quote
 * pi's own runs (`renderers/grep.js:19-26`, `find.js:18-24`, `ls.js:12-19`), where the subject
 * (a search pattern, a path) is `accent` and everything around it is `toolOutput`, so a change
 * that keeps the text but moves the pattern out of `accent` is exactly the drift this check
 * exists to catch. Printing `token(text)` in pi's own order pins both at once; `bold` is printed
 * only where pi bolds more than a tool's name (`core/tools/renderers/bash.js:31`).
 */
private fun runs(parts: List<ToolCallPart>): String =
    parts.joinToString(" ") { part ->
        val bold = if (part.bold) "bold " else ""
        "$bold${part.token}(${part.text})"
    }

fun main() {
    // ------------------------------------------------------------------ grep

    val grep = ToolOutputParse.grepBody(
        "app/a.kt:3: val x = 1\napp/a.kt:9: val y = 2\nsrc/b.kt:12: fun f()",
    )
    check("grep: two files become two groups", grep?.groups?.size, 2)
    check(
        "grep: the first group is the first file, with its two hits",
        grep?.groups?.firstOrNull()?.let { it.path to it.matches.size },
        "app/a.kt" to 2,
    )
    check("grep: line numbers are pi's", grep?.groups?.firstOrNull()?.matches?.map { it.line }, listOf(3, 9))
    check("grep: hit text is everything after the second colon", grep?.groups?.firstOrNull()?.matches?.first()?.text, "val x = 1")
    check("grep: hit count over all files", grep?.matchCount, 3)
    check("grep: file count over all files", grep?.fileCount, 2)
    check("grep: nothing omitted", grep?.omitted, 0)
    check("grep: a match row is not a context row", grep?.groups?.firstOrNull()?.matches?.first()?.context, false)

    // pi's context blocks use `-` instead of `:` (`core/tools/grep.ts:212`).
    val context = ToolOutputParse.grepBody("src/a.ts-10- before\nsrc/a.ts:11: hit\nsrc/a.ts-12- after")
    check(
        "grep: context rows keep their own line numbers and flag",
        context?.groups?.firstOrNull()?.matches?.map { Triple(it.line, it.context, it.text) },
        listOf(
            Triple(10, true, "before"),
            Triple(11, false, "hit"),
            Triple(12, true, "after"),
        ),
    )

    val nonAscii = ToolOutputParse.grepBody("文档/设计 说明.md:12: 中文内容\n文档/设计 说明.md:14: 第二处")
    check("grep: a non-ASCII path survives", nonAscii?.groups?.firstOrNull()?.path, "文档/设计 说明.md")
    check("grep: a non-ASCII body survives", nonAscii?.groups?.firstOrNull()?.matches?.first()?.text, "中文内容")

    val notice = ToolOutputParse.grepBody(
        "a.kt:1: x\n\n[100 matches limit reached. Use limit=200 for more, or refine pattern]",
    )
    check("grep: the notice leaves the body", notice?.matchCount, 1)
    check(
        "grep: the notice is pi's own sentence",
        notice?.notice,
        "100 matches limit reached. Use limit=200 for more, or refine pattern",
    )

    val noMatches = ToolOutputParse.grepBody("No matches found")
    check("grep: \"No matches found\" is the empty answer", noMatches?.empty, true)
    check("grep: an empty answer has no groups", noMatches?.groups?.size, 0)

    val blank = ToolOutputParse.grepBody("")
    check("grep: a blank result is pending, not empty", blank?.empty, false)
    check("grep: a blank result has no groups", blank?.groups?.size, 0)

    // The one case that must render the generic card: text in no shape pi's grep writes.
    check("grep: an unrecognised result answers null (generic card)", ToolOutputParse.grepBody("this is not a grep result"), null)

    val partial = ToolOutputParse.grepBody("app/a.kt:3: x\n??? an unknown row ???")
    check("grep: an unknown row beside known ones is kept as raw", partial?.raw, listOf("??? an unknown row ???"))
    check("grep: an unknown row does not disturb the group", partial?.groups?.firstOrNull()?.matches?.size, 1)

    val crlf = ToolOutputParse.grepBody("a.kt:1: x\r\nb.kt:2: y")
    check("grep: CRLF rows lose the carriage return", crlf?.groups?.map { it.matches.first().text }, listOf("x", "y"))

    // pi caps a grep line at 500 characters (`core/tools/truncate.ts:13`), so the app can
    // never be handed a line longer than that… unless the engine changes; the cap holds.
    val longLine = ToolOutputParse.grepBody("a.kt:1: " + "y".repeat(300_000))
    // pi caps the *match text* at 500 characters (`core/tools/truncate.ts:13`); this parser
    // caps the whole row at the same number, so the text is that much shorter by the length
    // of the path and the line number in front of it — either way one row is bounded and the
    // truncation is visible.
    val longText = longLine?.groups?.firstOrNull()?.matches?.first()?.text.orEmpty()
    check("grep: one row is capped at pi's 500 characters", longText.length <= TOOL_LINE_MAX_CHARS, true)
    check("grep: the capped row is marked as cut", longText.endsWith("…"), true)
    check("grep: a result past the scan budget says so", longLine?.scanCapped, true)

    val many = (1..500).joinToString("\n") { "f$it.kt:$it: hit" }
    val manyBody = ToolOutputParse.grepBody(many)
    check("grep: 500 rows are all counted", manyBody?.matchCount, 500)
    check("grep: the file count is not the capped group count", manyBody?.fileCount, 500)
    // The parser itself stops at the app's budget, which is what a `bash`-like flood would
    // otherwise turn into 500 groups (`omitted` is the rest, and the block reports it).
    check("grep: the parser stops at the app's budget", manyBody?.groups?.size, TOOL_LIST_MAX_ENTRIES)
    val manyPlan = capGrepGroups(manyBody!!.groups, TOOL_LIST_MAX_ENTRIES)
    check("grep: the painted plan stops at the app's budget", countMatches(manyPlan), 200)
    check("grep: what the plan left out is reported", (manyBody.matchCount - countMatches(manyPlan)), 300)
    check("grep: the parser itself also stops at the budget", manyBody.omitted, 300)

    // ------------------------------------------------------------------ find

    val find = ToolOutputParse.findBody("docs/a.md\nsrc/x.kt\nsrc/y.kt\nREADME.md")
    check("find: entries are grouped by directory", find?.groups?.map { it.label to it.entries.map { e -> e.name } }, listOf(
        "docs" to listOf("a.md"),
        "src" to listOf("x.kt", "y.kt"),
        "" to listOf("README.md"),
    ))
    check("find: every entry is counted", find?.entryCount, 4)
    check("find: the body knows which tool produced it", find?.kind, PathBodyKind.Find)
    // `fd` does not sort, so the same directory may appear twice in a row order; the group
    // must still be one heading per directory.
    val shuffled = ToolOutputParse.findBody("src/a.kt\ndocs/b.md\nsrc/c.kt")
    check("find: a directory split across the output is still one group", shuffled?.groups?.map { it.label to it.entries.map { e -> e.name } }, listOf(
        "src" to listOf("a.kt", "c.kt"),
        "docs" to listOf("b.md"),
    ))
    val findEmpty = ToolOutputParse.findBody("No files found matching pattern")
    check("find: pi's empty answer", findEmpty?.empty, true)
    val findNotice = ToolOutputParse.findBody("a.md\n\n[100 results limit reached. Use limit=200 for more, or refine pattern]")
    check("find: the notice leaves the list", findNotice?.entryCount, 1)
    check("find: the notice is pi's own sentence", findNotice?.notice, "100 results limit reached. Use limit=200 for more, or refine pattern")
    val findNonAscii = ToolOutputParse.findBody("文档/说明.md\nsrc/文件.kt")
    check("find: non-ASCII directories group by their real prefix", findNonAscii?.groups?.map { it.label }, listOf("文档", "src"))

    // -------------------------------------------------------------------- ls

    val ls = ToolOutputParse.lsBody("AGENTS.md\napp/\nbuild/\ngradlew\n文档.md")
    check("ls: pi's \"/\" suffix splits directories from files", ls?.groups?.map { it.label to it.entries.map { e -> e.name } }, listOf(
        "目录" to listOf("app", "build"),
        "文件" to listOf("AGENTS.md", "gradlew", "文档.md"),
    ))
    check("ls: directory entries are marked as directories", ls?.groups?.firstOrNull()?.entries?.first()?.kind, PathKind.Directory)
    check("ls: file entries are marked as files", ls?.groups?.lastOrNull()?.entries?.first()?.kind, PathKind.File)
    check("ls: every entry is counted", ls?.entryCount, 5)
    check("ls: the body knows which tool produced it", ls?.kind, PathBodyKind.Ls)
    val lsEmpty = ToolOutputParse.lsBody("(empty directory)")
    check("ls: pi's empty answer", lsEmpty?.empty, true)
    val lsNotice = ToolOutputParse.lsBody("a/\nb\n\n[100 entries limit reached. Use limit=200 for more]")
    check("ls: the notice leaves the list", lsNotice?.entryCount, 2)
    val lsMany = ToolOutputParse.lsBody((1..300).joinToString("\n") { "f$it.txt" })
    check("ls: 300 entries are counted", lsMany?.entryCount, 300)
    check("ls: the parser stops at the budget", lsMany?.omitted, 100)
    check("ls: the painted plan stops at the budget", countEntries(capPathGroups(lsMany!!.groups, TOOL_LIST_MAX_ENTRIES)), 200)

    // ------------------------------------------------------------------ read

    val read = ToolOutputParse.readBody(args("path" to "a.kt", "offset" to 5, "limit" to 2), "line five\nline six")
    check("read: the body is the file's own lines", read.lines, listOf("line five", "line six"))
    check("read: line numbers start at pi's offset", read.startLine, 5)
    check("read: no footer when pi sent none", read.footer, null)
    check("read: an absent offset means line 1", ToolOutputParse.readBody(args("path" to "a.kt"), "x").startLine, 1)

    val truncated = ToolOutputParse.readBody(args("path" to "a.kt"), "l1\nl2\n\n[Showing lines 1-2 of 400. Use offset=3 to continue.]")
    check("read: pi's truncation footer leaves the body", truncated.lines, listOf("l1", "l2"))
    check("read: the footer is kept as pi's sentence", truncated.footer, "Showing lines 1-2 of 400. Use offset=3 to continue.")

    val userLimited = ToolOutputParse.readBody(args("path" to "a.kt", "offset" to 1, "limit" to 2), "a\nb\n\n[398 more lines in file. Use offset=3 to continue.]")
    check("read: pi's user-limit footer leaves the body", userLimited.lines, listOf("a", "b"))
    check("read: the user-limit footer is kept", userLimited.footer, "398 more lines in file. Use offset=3 to continue.")

    val firstLine = ToolOutputParse.readBody(
        args("path" to "big.txt"),
        "[Line 1 is 60 KB, exceeds 50 KB limit. Use bash: sed -n '1p' big.txt | head -c 51200]",
    )
    check("read: the first-line-exceeds answer has no file lines", firstLine.lines.size, 0)
    check("read: the first-line-exceeds answer keeps pi's sentence", firstLine.footer?.startsWith("Line 1 is 60 KB"), true)

    // A file whose only line is bracketed is *content*: pi's footers are matched by shape.
    val bracketFile = ToolOutputParse.readBody(args("path" to "build.gradle"), "[dependencies]\n")
    check("read: a bracketed file line is not a footer", bracketFile.lines, listOf("[dependencies]"))
    check("read: a bracketed file line leaves no footer", bracketFile.footer, null)

    val emptyFile = ToolOutputParse.readBody(args("path" to "empty.txt"), "")
    check("read: an empty result has no lines", emptyFile.lines.size, 0)
    check("read: an empty result is not the pending state", emptyFile.totalLines, 0)

    val trailing = ToolOutputParse.readBody(args("path" to "a.kt"), "a\n\n\n")
    check("read: trailing blank lines are trimmed as pi trims them", trailing.lines, listOf("a"))

    val readNonAscii = ToolOutputParse.readBody(args("path" to "文档/说明.md"), "第一行\n第二行 中文")
    check("read: non-ASCII content survives", readNonAscii.lines, listOf("第一行", "第二行 中文"))

    val readMany = ToolOutputParse.readBody(args("path" to "a.txt"), (1..500).joinToString("\n") { "line $it" })
    check("read: 500 lines are all counted", readMany.totalLines, 500)
    check("read: the body stops at the app's budget", readMany.lines.size, TOOL_BODY_MAX_LINES)
    val readHuge = ToolOutputParse.readBody(args("path" to "a.txt"), "y".repeat(250_000))
    check("read: a result past the scan budget says so", readHuge.scanCapped, true)

    // ----------------------------------------------------------------- write

    val write = ToolOutputParse.writeBody(args("path" to "a.kt", "content" to "fun main() {}\n"))
    check("write: the body is the argument's content", write.lines, listOf("fun main() {}"))
    check("write: a write body starts at line 1", write.startLine, 1)
    check("write: the path is the argument's", write.path, "a.kt")
    check("write: no content argument means an empty body", ToolOutputParse.writeBody(args("path" to "a.kt")).lines.size, 0)
    check("write: a null argument set means an empty body", ToolOutputParse.writeBody(null).lines.size, 0)
    val writeMany = ToolOutputParse.writeBody(args("path" to "a.kt", "content" to (1..500).joinToString("\n") { "l$it" }))
    check("write: 500 lines are counted", writeMany.totalLines, 500)
    check("write: the body stops at the app's budget", writeMany.lines.size, TOOL_BODY_MAX_LINES)

    // ----------------------------------------------------------------- shell

    check("shell: a structured exit code wins", ToolOutputParse.shellExitCode(7, "Command exited with code 2"), 7)
    check(
        "shell: pi's sentence is read when details carried none",
        ToolOutputParse.shellExitCode(null, "boom\n\nCommand exited with code 2"),
        2,
    )
    check(
        "shell: a timeout has no exit code and none is invented",
        ToolOutputParse.shellExitCode(null, "partial\n\nCommand timed out after 30 seconds"),
        null,
    )
    check(
        "shell: an abort has no exit code",
        ToolOutputParse.shellExitCode(null, "partial\n\nCommand aborted"),
        null,
    )
    check("shell: a clean run has no exit code", ToolOutputParse.shellExitCode(null, "ok"), null)
    // pi appends that sentence to the very end of the result (`core/tools/bash.ts:363-364`),
    // which is why the parser reads only the tail — and why a sentence with a long tail after
    // it is *not* this result's, so no code is invented for it.
    check(
        "shell: pi's sentence at the end of the result is read",
        ToolOutputParse.shellExitCode(null, "boom\n\nCommand exited with code 3"),
        3,
    )
    check(
        "shell: a sentence buried under a long tail is not read",
        ToolOutputParse.shellExitCode(null, "Command exited with code 3" + "x".repeat(PATH_SCAN_CHARS * 3)),
        null,
    )


    // 0.86.1's `formatDuration` (`renderers/bash.ts:32-42`) stops being one-decimal-seconds
    // at a minute: whole minutes + seconds, then hours. Pinned here because 0.85.1 printed
    // `1483.2s` for a 25-minute call and nothing else in the tree would notice the switch.
    check("shell duration: sub-minute is one decimal, in seconds", ToolOutputParse.formatDuration(59_949), "59.9s")
    check("shell duration: a minute is m + s, whole seconds", ToolOutputParse.formatDuration(90_000), "1m 30s")
    check("shell duration: exactly a minute keeps its zero seconds", ToolOutputParse.formatDuration(60_000), "1m 0s")
    check("shell duration: an hour adds hours", ToolOutputParse.formatDuration(3_930_000), "1h 5m 30s")
    check("shell duration: a negative clock reads as zero", ToolOutputParse.formatDuration(-5), "0.0s")

    // ------------------------------------------------- pi's call-line shapes

    check("read call: no range when the call asked for none", readRange(null), "")
    check("read call: an offset alone", readRange(args("offset" to 3)), ":3")
    check("read call: offset and limit, as pi prints them", readRange(args("offset" to 3, "limit" to 10)), ":3-12")
    check("read call: a limit alone starts at 1", readRange(args("limit" to 10)), ":1-10")

    check(
        "grep call: pattern is accent, ` in src`, glob and limit are toolOutput",
        runs(grepSubject(args("pattern" to "foo", "path" to "src", "glob" to "*.kt", "limit" to 50))),
        "Accent(/foo/) ToolOutput( in src) ToolOutput( (*.kt)) ToolOutput( limit 50)",
    )
    check(
        "grep call: pi's default path",
        runs(grepSubject(args("pattern" to "foo"))),
        "Accent(/foo/) ToolOutput( in .)",
    )
    check(
        "find call: pattern is accent, ` in src` is toolOutput",
        runs(findSubject(args("pattern" to "*.kt", "path" to "src"))),
        "Accent(*.kt) ToolOutput( in src)",
    )
    check(
        "find call: the limit suffix is toolOutput too",
        runs(findSubject(args("pattern" to "*.kt", "path" to "src", "limit" to 20))),
        "Accent(*.kt) ToolOutput( in src) ToolOutput( (limit 20))",
    )
    check("ls call: pi's default path is accent", runs(lsSubject(null)), "Accent(.)")
    check(
        "ls call: the limit suffix is toolOutput",
        runs(lsSubject(args("path" to "src", "limit" to 10))),
        "Accent(src) ToolOutput( (limit 10))",
    )

    // ------------------------------------- helpers moved out of ToolCallBlock

    check(
        "truncation: an untruncated result has no block",
        truncationOf(args("truncation" to null)),
        null,
    )
    val truncation = truncationOf(
        buildJsonObject {
            put(
                "truncation",
                buildJsonObject {
                    put("truncated", true)
                    put("truncatedBy", "lines")
                    put("outputLines", 1)
                    put("totalLines", 400)
                },
            )
        },
    )
    check("truncation: pi's numbers are read", truncation?.totalLines, 400)
    check("truncation: pi's warning line", truncationNotice(null, truncation), "已截断：显示 1 / 400 行")
    check("truncation: no path and no truncation means no line", truncationNotice(null, null), null)
    check("truncation: a path alone still prints", truncationNotice("/tmp/x", null), "完整输出：/tmp/x")

    val shellText = "out\n\n[Showing lines 1-2 of 9. Full output: /tmp/pi-bash-x.log]"
    check(
        "full output: the sentence pi repeats is stripped",
        stripFullOutputFooter(shellText, "/tmp/pi-bash-x.log"),
        "out",
    )
    check(
        "full output: a footer naming another path is left alone",
        stripFullOutputFooter("out\n\n[other]", "/tmp/x"),
        "out\n\n[other]",
    )
    check("full output: no path means no strip", stripFullOutputFooter("out", null), "out")
    check("full output: the structured field wins", fullOutputPathOf(args("fullOutputPath" to "/a"), ""), "/a")
    check("full output: pi's sentence is read from the tail", fullOutputPathOf(null, shellText), "/tmp/pi-bash-x.log")
    check("full output: nothing to report", fullOutputPathOf(null, "no marker here"), null)

    // ------------------------------------------------ nothing may throw

    val hostile = listOf(
        "",
        "\n\n\n",
        "\u0000\u0000",
        "[",
        "]",
        "[unclosed",
        "::::",
        "x:1:",
        "-1-",
        "\r\n\r\n",
        "\\u4e2d\\u6587:1: ok",
        "a".repeat(100_000) + ":1: x",
        (1..4_000).joinToString("\n") { "line $it" },
        "😀/文件.kt:1: emoji 路径",
    )
    for ((index, text) in hostile.withIndex()) {
        survives("hostile #$index: grep returns", { ToolOutputParse.grepBody(text) })
        survives("hostile #$index: find returns", { ToolOutputParse.findBody(text) })
        survives("hostile #$index: ls returns", { ToolOutputParse.lsBody(text) })
        survives("hostile #$index: read returns", { ToolOutputParse.readBody(null, text) })
        survives("hostile #$index: shell exit code returns", { ToolOutputParse.shellExitCode(null, text) })
        survives("hostile #$index: read range returns", { readRange(args("offset" to "not a number")) })
        survives("hostile #$index: notice split returns", { splitNotice(text) })
        survives("hostile #$index: footer split returns", { splitReadFooter(text) })
    }

    // Every result must respect the caps, whatever it was handed: a 4000-line body becomes
    // at most 200 painted lines, and a 4000-entry list at most 200 rows.
    val hugeBody = ToolOutputParse.readBody(null, (1..4_000).joinToString("\n") { "line $it" })
    check("cap: a 4000-line body paints at most 200 lines", hugeBody.lines.size <= TOOL_BODY_MAX_LINES, true)
    val hugeList = ToolOutputParse.lsBody((1..4_000).joinToString("\n") { "f$it" })
    // The listing counts every entry pi sent (`entryCount` is what the footer reports) but
    // paints at most the budget's worth; `omitted` is the difference.
    check("cap: a 4000-entry listing is fully counted", hugeList?.entryCount, 4_000)
    check("cap: a 4000-entry listing paints at most 200", countEntries(hugeList!!.groups) <= TOOL_LIST_MAX_ENTRIES, true)
    check("cap: the omitted count is the rest", hugeList.omitted, 4_000 - TOOL_LIST_MAX_ENTRIES)

    // A parse that fails must degrade, never raise: the blocks turn null into the generic
    // card, and read/write answer an empty body instead.
    check("degrade: an unrecognised grep is null", ToolOutputParse.grepBody("nope") == null, true)
    check("degrade: a null argument set is safe", ToolOutputParse.readBody(null, "").lines.size, 0)

    // --------------------------------------- the call line, both of pi's halves

    // pi's generic call line, collapsed: `key=value` for **every** argument, cut to pi's 100
    // characters (`core/tools/render-utils.js:60-79`). This is what the generic card's title
    // cell takes, and the whole reason it changed: `summarizeToolArgs` returned one *value*,
    // so `limit` and every second parameter were invisible.
    check("params: a single string value keeps its key", toolParamSummary(args("query" to "x")), "query=\"x\"")
    check(
        "params: every argument, in wire order, JSON-quoted",
        toolParamSummary(args("query" to "mcp docs", "limit" to 8)),
        "query=\"mcp docs\" limit=8",
    )
    check("params: no arguments means no text", toolParamSummary(null), null)
    check("params: an empty argument set means no text", toolParamSummary(args()), null)
    val longParams = toolParamSummary(JsonObject(mapOf("command" to JsonPrimitive("x".repeat(200)))))
    check("params: pi's 100-character cut keeps its ellipsis", longParams?.length, 100)
    check("params: the cut ends in pi's three dots", longParams?.endsWith("..."), true)

    // The expanded half: one `key: value` per line, a **string printed raw** (not quoted)
    // and a non-string pretty-printed with two spaces, continuation lines indented by four.
    check("params expanded: a string is raw, with pi's two-space lead", toolParamLines(args("path" to "a.kt")), listOf("  path: a.kt"))
    check(
        "params expanded: a number is bare",
        toolParamLines(args("limit" to "8")),
        listOf("  limit: 8"),
    )
    check(
        "params expanded: a nested object is JSON.stringify(value, null, 2), indented by four",
        toolParamLines(JsonObject(mapOf("opts" to JsonObject(mapOf("deep" to JsonPrimitive(true)))))),
        listOf("  opts: {\n      \"deep\": true\n    }"),
    )
    check("params expanded: an array keeps its own line", toolParamLines(JsonObject(mapOf("xs" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))), listOf("  xs: [\n      1,\n      2\n    ]"))
    check("params expanded: no arguments means no lines", toolParamLines(null), emptyList<String>())
    // One 100 KB single-line value must not become a thousand rows on the phone: every line
    // is capped at pi's own per-line limit (its `GREP_MAX_LINE_LENGTH`).
    check(
        "params expanded: a huge single value is capped per line",
        toolParamLines(JsonObject(mapOf("blob" to JsonPrimitive("y".repeat(100_000))))).first().length,
        TOOL_LINE_MAX_CHARS + 1,
    )

    // pi labels an MCP tool's row `<server>/<tool>` (`extensions/mcp/tools.js:244`) although
    // the wire name is `mcp__<server>__<tool>`; its renderer matches that shape with this
    // regex even for a tool that is not registered (`extensions/mcp/index.js:280`).
    check("mcp: the wire name becomes pi's label", mcpLabel("mcp__docs__search"), "docs/search")
    check("mcp: dashes and underscores survive in the server part", mcpLabel("mcp__dev-radius__get_issue"), "dev-radius/get_issue")
    check("mcp: a plain tool name is not an MCP label", mcpLabel("read"), null)
    check("mcp: an incomplete prefix is not an MCP label", mcpLabel("mcp__docs__"), null)
    check("mcp: no name is not an MCP label", mcpLabel(null), null)

    // ------------------------------------------------ read's own continuation sentence

    // pi's four shapes (`core/tools/read.js:139`, `:148`, `:151`, `:159`), translated with
    // pi's numbers and its `offset=` spelling kept. Anything unrecognised stays pi's text.
    check(
        "read footer: the line-range sentence",
        readFooterText("Showing lines 1-2000 of 3001. Use offset=2001 to continue."),
        "显示第 1–2000 行，共 3001 行；用 offset=2001 继续。",
    )
    check(
        "read footer: the line-range sentence with the byte limit in it",
        readFooterText("Showing lines 1-2000 of 3001 (50 KB limit). Use offset=2001 to continue."),
        "显示第 1–2000 行，共 3001 行（上限 50 KB）；用 offset=2001 继续。",
    )
    check(
        "read footer: the user-limit sentence",
        readFooterText("1 more lines in file. Use offset=4 to continue."),
        "文件里还有 1 行；用 offset=4 继续。",
    )
    check(
        "read footer: the first-line sentence keeps pi's bash remedy",
        readFooterText("Line 1 is 60 KB, exceeds 50 KB limit. Use bash: sed -n '1p' big.txt | head -c 51200"),
        "第 1 行本身就有 60 KB，超过 50 KB 上限。可用 bash：sed -n '1p' big.txt | head -c 51200",
    )
    check("read footer: an unknown sentence is left alone", readFooterText("Bracketed but not pi's"), "Bracketed but not pi's")

    // ------------------------------- the two counts pi's list tools report and this app dropped

    // pi's grep warning line carries three `details` fields (`renderers/grep.js:194-206`):
    // the match limit, the truncation report and the long-line flag. Only the middle one had
    // a reader, so a search cut at pi's 100-match limit said nothing at all.
    val grepLimited = args("matchLimitReached" to "100")
    check("grep notice: the match limit alone", grepNoticeText(grepLimited), "已截断：100 处上限")
    check("grep notice: nothing to report", grepNoticeText(null), null)
    check(
        "grep notice: pi's own order, match limit first",
        grepNoticeText(
            JsonObject(
                mapOf(
                    "matchLimitReached" to JsonPrimitive(100),
                    "linesTruncated" to JsonPrimitive(true),
                    "truncation" to JsonObject(
                        mapOf("truncated" to JsonPrimitive(true), "truncatedBy" to JsonPrimitive("lines"), "outputLines" to JsonPrimitive(20)),
                    ),
                ),
            ),
        ),
        "已截断：100 处上限、显示 20 行、部分行被截断",
    )
    check("grep notice: the full-output path leads, as the other cards' notice does", grepNotice("/tmp/x", grepLimited), "完整输出：/tmp/x；已截断：100 处上限")

    check("find notice: the result limit", pathListNoticeText(args("resultLimitReached" to "1000"), entries = false), "已截断：1000 条上限")
    check("ls notice: the entry limit", pathListNoticeText(args("entryLimitReached" to "500"), entries = true), "已截断：500 项上限")
    check("find notice: the other tool's key is not read", pathListNoticeText(args("entryLimitReached" to "500"), entries = false), null)

    // ---------------------------------------------------------- codemode's nested calls

    // `details.calls` is the only place a script's nested calls survive (`execute.js:394`).
    val callsJson = JsonObject(
        mapOf(
            "calls" to kotlinx.serialization.json.JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive("read"),
                            "args" to JsonPrimitive("{\"path\":\"a\"}"),
                            "status" to JsonPrimitive("ok"),
                            "durationMs" to JsonPrimitive(120),
                            "cost" to JsonPrimitive(0.0012),
                        ),
                    ),
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive("bash"),
                            "args" to JsonPrimitive("ls"),
                            "status" to JsonPrimitive("error"),
                            "durationMs" to JsonPrimitive(3400),
                            "error" to JsonPrimitive("boom"),
                        ),
                    ),
                    JsonObject(mapOf("name" to JsonPrimitive("grep"), "args" to JsonPrimitive("{}"), "status" to JsonPrimitive("running"))),
                    JsonObject(mapOf("name" to JsonPrimitive("ls"), "args" to JsonPrimitive("{}"), "status" to JsonPrimitive("cancelled"))),
                ),
            ),
        ),
    )
    val calls = codemodeCallsOf(callsJson)
    check("codemode: every nested call is read, in order", calls.map { it.name }, listOf("read", "bash", "grep", "ls"))
    check("codemode: the four statuses map", calls.map { it.status }, listOf(CodemodeCallStatus.Ok, CodemodeCallStatus.Error, CodemodeCallStatus.Running, CodemodeCallStatus.Cancelled))
    check("codemode: a status a newer engine adds is unknown, not a success", codemodeCallsOf(JsonObject(mapOf("calls" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("x"), "status" to JsonPrimitive("what")))))))).single().status, CodemodeCallStatus.Unknown)
    check("codemode: no calls is an empty list", codemodeCallsOf(null), emptyList<CodemodeCall>())
    check("codemode: a call with no name is dropped", codemodeCallsOf(JsonObject(mapOf("calls" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("status" to JsonPrimitive("ok")))))))), emptyList<CodemodeCall>())
    // pi's `statusIcon` (`renderer.js:26-40`), and the `?` its HTML exporter paints instead of
    // the TUI's `undefined` for a status it does not know.
    check("codemode: pi's four glyphs", calls.map { codemodeCallIcon(it.status) }, listOf("✓", "✗", "…", "⊘"))
    check("codemode: an unknown status still gets a glyph", codemodeCallIcon(CodemodeCallStatus.Unknown), "?")
    // pi's `formatCall` (`:41-56`): name, arguments, duration, cost — and, expanded, the
    // failure's own text on the next line indented by four.
    check("codemode: a call line is name + args + duration + cost", codemodeCallText(calls[0], expanded = false), "read {\"path\":\"a\"} 120ms $0.0012")
    check("codemode: 3.4s is pi's seconds spelling", codemodeCallText(calls[1], expanded = false), "bash ls 3.4s")
    check("codemode: no duration and no cost prints neither", codemodeCallText(calls[2], expanded = false), "grep {}")
    check("codemode: expanded adds the failure indented by four", codemodeCallText(calls[1], expanded = true), "bash ls 3.4s\n    boom")
    val longArgs = CodemodeCall("read", "x".repeat(200), CodemodeCallStatus.Ok, null, null, null)
    check("codemode: collapsed arguments are cut to pi's 80", codemodeCallText(longArgs, expanded = false).length, "read ".length + 80)
    check("codemode: expanded arguments are whole", codemodeCallText(longArgs, expanded = true).length, "read ".length + 200)
    // pi's `formatCost`: cents when it has them, two significant digits below (`:45-47`).
    check("codemode: a cent-scale cost is two decimals", codemodeCost(0.06), "$0.06")
    check("codemode: a fraction of a cent keeps two significant digits", codemodeCost(0.0012), "$0.0012")
    check("codemode: a slow call is seconds with one decimal", codemodeDuration(3400.0), "3.4s")
    check("codemode: a fast call is whole milliseconds", codemodeDuration(120.4), "120ms")
    // The total is printed only when **more than one** call was priced (`:53-59`).
    check("codemode: one priced call prints no total", codemodeModelCallsTotal(listOf(calls[0])), null)
    check("codemode: no priced call prints no total", codemodeModelCallsTotal(listOf(calls[2])), null)
    check("codemode: two priced calls are summed", codemodeModelCallsTotal(listOf(calls[0], calls[0])), "Model calls: $0.0024")
    check("codemode: the script is read from the arguments", codemodeCode(args("code" to "const a = 1;\n")), "const a = 1;")
    check("codemode: no script argument is null", codemodeCode(args("other" to "x")), null)

    if (failures > 0) {
        println("tool-output-parse: $failures check(s) failed")
        exitProcess(1)
    }
    println("harness: OK")
}
