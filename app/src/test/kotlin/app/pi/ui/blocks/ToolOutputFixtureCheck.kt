package app.pi.ui.blocks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

// The **tool-result fixture harness**: the real bytes pi's tools returned, fed to the
// parser that paints them. Registered in `tools/run-app-pure-checks.sh` as
// `tool-output-fixtures`.
//
// `ToolOutputParseCheck.kt` next door pins the parser against inputs *we* wrote down, which
// is what makes it good at edge cases and useless at this one question: does the parser
// still understand what the pinned engine actually returns? A parser matched against our
// own idea of pi's wording keeps passing after pi's wording moves.
//
// So the inputs here come from `app/src/test/resources/pi-tool-fixtures/tools.json`, which
// `tools/collect-tool-fixtures.mjs` captures by **running pi's own tool implementations**
// against a fixed workspace (`read`, `grep`, `find`, `ls`, the shell, `write`, `edit`).
// Two halves, one claim:
//
//   * `node tools/collect-tool-fixtures.mjs --check` (CI, `contract` job) — the committed
//     capture is still what the pinned engine produces;
//   * this harness (CI, `pure-checks` job) — the App still parses it.
//
// Either half alone is passable while the app is broken: a stale fixture still parses, and a
// parser that matches nothing still passes a capture nobody re-derives.
//
// What this deliberately does **not** assert: the *rendering* (that is Compose, not
// compiled here) and the parts of the App's reading that need the rpc module
// (`detailsDiffText` lives in `Transcript.kt`). Where a case exists only to keep the
// capture honest, the check says so instead of pretending to test a parser.
//
// The fixture is read through `pi.repo.root`, the system property
// `tools/run-app-pure-checks.sh` passes; a missing or unreadable fixture is a hard failure
// (exit 2), never a silent pass.

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")
private val FIXTURE_FILE: File =
    ROOT.resolve("app/src/test/resources/pi-tool-fixtures/tools.json")

private val FIXTURE: JsonObject = run {
    if (!FIXTURE_FILE.isFile) {
        println("tool-output-fixtures: CANNOT RUN — missing ${FIXTURE_FILE.path}")
        println("  run `node tools/collect-tool-fixtures.mjs` and commit the result")
        exitProcess(2)
    }
    Json.parseToJsonElement(FIXTURE_FILE.readText()).jsonObject
}

private val CASES: Map<String, JsonObject> =
    FIXTURE["cases"]!!.jsonArray.associate { element ->
        val item = element.jsonObject
        item["id"]!!.jsonPrimitive.content to item
    }

private fun case(id: String): JsonObject =
    CASES[id] ?: run {
        println("tool-output-fixtures: CANNOT RUN — the fixture has no case `$id`")
        println("  the reader and tools/collect-tool-fixtures.mjs have drifted apart")
        exitProcess(2)
    }

private fun content(id: String): String = case(id)["content"]!!.jsonPrimitive.content

private fun args(id: String): JsonObject = case(id)["args"]!!.jsonObject

private fun details(id: String) = case(id)["details"]

/** The capture's own sentence about why the case exists: pi's file, and the App's parser. */
private fun probe(id: String): String =
    CASES[id]?.get("probe")?.jsonPrimitive?.content ?: "(fixture-level check)"

var failures = 0

/**
 * One assertion about one fixture case.
 *
 * The name carries both halves — `case.field` — and a failure prints `probe`, because the
 * useful sentence is not "expected 3, got 4" but "pi's `core/tools/bash.ts` appends that
 * number and `ToolOutputParse.shellExitCode` is what reads it".
 */
private fun checkCase(id: String, field: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $id.$field")
    } else {
        failures++
        println("FAIL $id.$field\n  expected: $expected\n  actual:   $actual\n  protects:  ${probe(id)}")
    }
}

fun main() {
    // A reader that stopped matching would otherwise make every assertion below vanish.
    checkCase("fixture", "has the captures this harness reads (>= 15)", CASES.size >= 15, true)
    checkCase(
        "fixture",
        "names the engine that produced it",
        FIXTURE["pi"]?.jsonPrimitive?.content?.isNotEmpty() ?: false,
        true,
    )

    // ------------------------------------------------------------------ shell
    checkCase("bash-plain", "exitCode", ToolOutputParse.shellExitCode(null, content("bash-plain")), null)
    checkCase(
        "bash-plain",
        "nothing to strip (no truncation footer)",
        stripFullOutputFooter(content("bash-plain"), null),
        content("bash-plain"),
    )
    // A non-zero exit THROWS in pi, so the number reaches the app only as this sentence.
    checkCase("bash-exit-3", "exitCode", ToolOutputParse.shellExitCode(null, content("bash-exit-3")), 3)
    checkCase("bash-no-output", "body", content("bash-no-output"), "(no output)")

    val truncated = truncationOf(details("bash-truncated"))
    checkCase("bash-truncated", "truncatedBy", truncated?.truncatedBy, "lines")
    checkCase("bash-truncated", "outputLines", truncated?.outputLines, 2000)
    checkCase("bash-truncated", "totalLines", truncated?.totalLines, 2500)
    val fullPath = fullOutputPathOf(details("bash-truncated"), content("bash-truncated"))
    checkCase("bash-truncated", "fullOutputPath present", fullPath != null, true)
    val stripped = stripFullOutputFooter(content("bash-truncated"), fullPath)
    checkCase("bash-truncated", "footer removed from the body", stripped.contains("Full output:"), false)
    checkCase("bash-truncated", "body keeps pi's last line", stripped.trimEnd().endsWith("l2499"), true)

    // ------------------------------------------------------------------ read
    val plain = ToolOutputParse.readBody(args("read-plain"), content("read-plain"))
    checkCase("read-plain", "footer", plain.footer, null)
    checkCase("read-plain", "lines", plain.lines, listOf("alpha", "beta", "gamma"))
    checkCase("read-plain", "startLine", plain.startLine, 1)

    // `[N more lines in file. Use offset=Z to continue.]` — one of the two footer shapes.
    val offset = ToolOutputParse.readBody(args("read-offset"), content("read-offset"))
    checkCase("read-offset", "footer", offset.footer, "1 more lines in file. Use offset=4 to continue.")
    checkCase("read-offset", "lines", offset.lines, listOf("beta", "gamma"))
    checkCase("read-offset", "startLine (pi's offset)", offset.startLine, 2)

    // The other shape, produced only by pi's own line cap: `[Showing lines X-Y of N. …]`.
    // `lines` is what the block paints, capped at TOOL_BODY_MAX_LINES (200); `totalLines` is
    // pi's own count, so the two numbers are asserted separately on purpose.
    val readTrunc = ToolOutputParse.readBody(args("read-truncated"), content("read-truncated"))
    checkCase(
        "read-truncated",
        "footer",
        readTrunc.footer,
        "Showing lines 1-2000 of 3001. Use offset=2001 to continue.",
    )
    checkCase("read-truncated", "totalLines (pi's count)", readTrunc.totalLines, 2000)
    checkCase("read-truncated", "painted lines (the App's cap)", readTrunc.lines.size, 200)
    checkCase("read-truncated", "first line", readTrunc.lines.firstOrNull(), "l0")

    // ------------------------------------------------------------------ grep
    val match = ToolOutputParse.grepBody(content("grep-match"))
    checkCase("grep-match", "groups", match?.groups?.size, 1)
    checkCase("grep-match", "lines", match?.groups?.firstOrNull()?.matches?.map { it.line }, listOf(1, 3, 4))
    checkCase(
        "grep-match",
        "texts",
        match?.groups?.firstOrNull()?.matches?.map { it.text },
        listOf("needle one", "needle two", "needle three"),
    )
    checkCase("grep-match", "matchCount", match?.matchCount, 3)
    checkCase("grep-match", "empty", match?.empty, false)
    checkCase("grep-match", "notice", match?.notice, null)

    // Context rows use `-` separators (`file-2- text`) and are not matches.
    val context = ToolOutputParse.grepBody(content("grep-context"))
    checkCase(
        "grep-context",
        "context flags",
        context?.groups?.firstOrNull()?.matches?.map { it.context },
        listOf(true, false, true),
    )
    checkCase(
        "grep-context",
        "the one real match",
        context?.groups?.firstOrNull()?.matches?.firstOrNull { !it.context }?.text,
        "needle two",
    )
    checkCase("grep-context", "notice", context?.notice, null)

    checkCase("grep-none", "empty", ToolOutputParse.grepBody(content("grep-none"))?.empty, true)

    val limited = ToolOutputParse.grepBody(content("grep-limit"))
    checkCase(
        "grep-limit",
        "notice",
        limited?.notice,
        "2 matches limit reached. Use limit=4 for more, or refine pattern",
    )
    checkCase("grep-limit", "matches kept", limited?.matchCount, 2)
    checkCase("grep-limit", "groups", limited?.groups?.size, 1)

    // ------------------------------------------------------------------ find / ls
    val find = ToolOutputParse.findBody(content("find-one"))
    checkCase("find-one", "entries", find?.groups?.flatMap { group -> group.entries.map { it.name } }, listOf("hello.txt"))
    checkCase("find-one", "entry kind (fd prints no marker)", find?.groups?.firstOrNull()?.entries?.firstOrNull()?.kind, PathKind.Unknown)
    checkCase("find-one", "empty", find?.empty, false)
    checkCase("find-none", "empty", ToolOutputParse.findBody(content("find-none"))?.empty, true)

    val ls = ToolOutputParse.lsBody(content("ls-mixed"))
    val kinds = ls?.groups?.flatMap { group -> group.entries.map { it.name to it.kind } }?.toMap().orEmpty()
    checkCase("ls-mixed", "entryCount", ls?.entryCount, 5)
    checkCase("ls-mixed", "directory suffix => Directory", kinds["sub"], PathKind.Directory)
    checkCase("ls-mixed", "trailing-slash dir is not a file", kinds["empty"], PathKind.Directory)
    checkCase("ls-mixed", "plain name => File", kinds["hello.txt"], PathKind.File)
    checkCase("ls-mixed", "notice", ls?.notice, null)
    checkCase("ls-empty", "empty", ToolOutputParse.lsBody(content("ls-empty"))?.empty, true)

    // ------------------------------------------------------------------ write / edit
    // Neither is parsed by this file for its *body* (WriteBlock reads `args.content`), so
    // these keep the capture honest rather than testing a parser that does not exist here.
    checkCase("write-ok", "nothing to strip", stripFullOutputFooter(content("write-ok"), null), content("write-ok"))
    checkCase("write-ok", "names the file it wrote", content("write-ok").contains("written.txt"), true)
    val editDetails = details("edit-ok") as? JsonObject
    checkCase("edit-ok", "an edit is never truncated", truncationOf(editDetails), null)
    checkCase("edit-ok", "details.diff is present", editDetails?.get("diff") != null, true)
    checkCase("edit-ok", "firstChangedLine", editDetails?.get("firstChangedLine")?.jsonPrimitive?.content, "2")

    if (failures > 0) {
        println("tool-output-fixtures: FAILED ($failures)")
        println("  a stale capture: re-run `node tools/collect-tool-fixtures.mjs --check`")
        println("  a changed parser: read the `protects:` line above")
        exitProcess(1)
    }
    println("tool-output-fixtures: OK — the pinned engine's tool output still parses (${CASES.size} cases)")
}
