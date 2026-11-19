package app.pi.session

// `/import` 与 `/export` 的纯规则（`SessionImport.kt`、`SessionExportNaming.kt`）对照 pi 的真实规则：
// 哪一行首行算会话（`session-manager.ts` 的 `loadEntriesFromFile`）、导入重名时怎么取名
// （`agent-session-runtime.ts`）、哪种后缀走哪个导出写手（`interactive-mode.ts`）、
// 面向用户的失败句子不带内部路径。原先和会话扫盘层的检查放在同一个文件里；扫盘层随会话列表改走
// SDK（宿主 `session.list`）删除，这两节与它无关，独立保留。

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val CWD = "/workspace/pi/workspaces/workspace-1"
private const val OTHER_CWD = "/workspace/pi/workspaces/workspace-2"

private fun header(id: String, iso: String, cwd: String?): String =
    "{\"type\":\"session\",\"version\":3,\"id\":\"$id\",\"timestamp\":\"$iso\"" +
        (cwd?.let { ",\"cwd\":\"$it\"" } ?: "") + "}"

private fun message(id: String, parentId: String?, iso: String, role: String, text: String, ms: Long): String =
    "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parentId?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"$iso\",\"message\":{\"role\":\"$role\"," +
        "\"content\":[{\"type\":\"text\",\"text\":\"$text\"}],\"timestamp\":$ms}}"

fun main() {
    // 10. `/import`'s three pure rules (`SessionImport.kt`).
    //
    // pi's import path copies the file into the session directory and hands it to
    // `switch_session`, whose handler opens it with `SessionManager.open`
    // (`rpc-mode.ts:605-611` → `agent-session-runtime.ts:197-224`). Whether the
    // file *is* a session is therefore pi's rule, not the app's:
    // `loadEntriesFromFile` keeps every parseable line in order and throws the
    // whole list away when the first one is not `{type:"session", id:string}`
    // (`session-manager.ts:538-577`), after which `_setSessionFile` rejects a
    // non-empty file with no entries (`:950-953`). These checks pin the app's
    // transcription of exactly that, because a wrong "yes" here imports a file
    // pi will refuse to open, and a wrong "no" refuses a file pi accepts.

    // The header itself, with the two fields the app reads off it.
    val found = SessionImport.verdictOf(header("import-me", "2024-06-01T00:00:00.000Z", CWD))
    check("a session header is recognised", found is SessionImport.Verdict.Found, true)
    val foundHeader = (found as? SessionImport.Verdict.Found)?.header
    check("its id is read", foundHeader?.id, "import-me")
    check("its cwd is read", foundHeader?.cwd, CWD)

    // Blank and malformed leading lines are skipped, not treated as content
    // (`parseSessionEntryLine`, `:499-508`), so a file with a blank first line and
    // leading junk is still a session — and its cwd comes from the header.
    check(
        "blank and malformed leading lines are skipped",
        SessionImport.verdictOf("\n  \nnot json\n" + header("after-junk", "2024-06-01T00:00:00.000Z", OTHER_CWD)),
        SessionImport.Verdict.Found(SessionImport.Header("after-junk", OTHER_CWD)),
    )

    // The first parseable line decides. A `message` entry first means pi drops
    // the file (`:551-556`) even when a valid header follows it, so the app must
    // say "not a session" rather than let pi fail after the copy.
    check(
        "a non-header first entry is not a session",
        SessionImport.verdictOf(
            message("m1", null, "2024-06-01T00:00:01.000Z", "user", "hi", 1L) + "\n" +
                header("header-after-message", "2024-06-01T00:00:00.000Z", CWD),
        ),
        SessionImport.Verdict.NotASession,
    )

    // A header with no string id is not a header (`:551-556` requires the type
    // *and* a string id).
    check(
        "a session entry without a string id is not a session",
        SessionImport.verdictOf("{\"type\":\"session\",\"version\":3}"),
        SessionImport.Verdict.NotASession,
    )

    // Nothing parseable in the head is *not* a rejection: pi reads the whole file,
    // and an empty file is even valid (it gets a fresh header, `:904-916`).
    check(
        "an empty file is undetermined, not rejected",
        SessionImport.verdictOf(""),
        SessionImport.Verdict.Indeterminate,
    )
    check(
        "a head with only blank lines is undetermined",
        SessionImport.verdictOf("\n\n   \n"),
        SessionImport.Verdict.Indeterminate,
    )

    // Import naming: pi takes the basename, then `name-1.ext`, `name-2.ext` …
    // before the extension (`agent-session-runtime.ts:372-380`).
    val taken = mutableSetOf("2024-06-01T00-00-00-000Z_x.jsonl")
    check(
        "a free name is used as is",
        SessionImport.destinationName("other.jsonl") { it in taken },
        "other.jsonl",
    )
    check(
        "a taken name gets -1 before the extension",
        SessionImport.destinationName("2024-06-01T00-00-00-000Z_x.jsonl") { it in taken },
        "2024-06-01T00-00-00-000Z_x-1.jsonl",
    )
    taken += "2024-06-01T00-00-00-000Z_x-1.jsonl"
    check(
        "a second collision gets -2",
        SessionImport.destinationName("2024-06-01T00-00-00-000Z_x.jsonl") { it in taken },
        "2024-06-01T00-00-00-000Z_x-2.jsonl",
    )
    check(
        "a leading dot is part of the name, not an extension",
        SessionImport.destinationName(".session") { it == ".session" },
        ".session-1",
    )
    // Every candidate taken is an answer, not an infinite loop — and the caller
    // must refuse rather than overwrite a conversation (pi's `COPYFILE_EXCL`).
    check(
        "exhausting the suffix search returns null",
        SessionImport.destinationName("x.jsonl") { true },
        null,
    )
    check("a blank source name is refused", SessionImport.destinationName("   ") { false }, null)

    // 11. `/export`'s naming and typing rules (`SessionExportNaming.kt`).
    //
    // pi picks the writer from the argument's extension
    // (`interactive-mode.ts:6188-6198`): `.jsonl` → JSONL, anything else (including
    // nothing) → HTML. The default HTML name is
    // `<APP_NAME>-session-<会话文件 basename>.html` (`export-html/index.ts:274-281`).
    check("a .jsonl argument goes to the JSONL writer", SessionExportNaming.isJsonl("a.jsonl"), true)
    check("the suffix is matched case-insensitively", SessionExportNaming.isJsonl("A.JSONL"), true)
    check("no argument goes to the HTML writer", SessionExportNaming.isJsonl(null), false)
    check("a .html argument goes to the HTML writer", SessionExportNaming.isJsonl("a.html"), false)

    check(
        "the default HTML name borrows the session file's basename",
        SessionExportNaming.defaultHtmlName("/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_x.jsonl", 1L),
        "pi-session-2024-06-01T00-00-00-000Z_x.html",
    )
    check(
        "a session with no file yet gets a timestamp instead",
        SessionExportNaming.defaultHtmlName(null, 42L),
        "pi-session-session-42.html",
    )
    check(
        "the HTML export is typed as HTML",
        SessionExportNaming.mimeTypeFor("pi-session-x.html"),
        "text/html",
    )
    check(
        "the JSONL export is plain text (no registered JSONL type)",
        SessionExportNaming.mimeTypeFor("session-x.jsonl"),
        "text/plain",
    )

    // The failure sentences are user-visible copy, and this repository's rule is
    // that they name no internal path. pi's raw text for two of the three shapes
    // does (`session-cwd.ts:35-39`, `session-manager.ts:950-953`), which is why
    // `failureSentence` classifies on pi's wording and writes its own sentence.
    val cwdFailure = SessionImport.failureSentence(
        "Stored session working directory does not exist: /root/somewhere",
    )
    val invalidFailure = SessionImport.failureSentence(
        "Session file is not a valid pi session: /root/.pi/agent/sessions/x.jsonl",
    )
    check("the cwd failure mentions no path", cwdFailure.contains("/root"), false)
    check("the invalid-file failure mentions no path", invalidFailure.contains("/root"), false)
    check("the invalid-file failure says what to do", invalidFailure.contains(".jsonl"), true)
    check(
        "an unknown failure still names no path and still gives a next step",
        SessionImport.failureSentence("boom: /data/user/0/app.pi/files/x").contains("/data"),
        false,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
