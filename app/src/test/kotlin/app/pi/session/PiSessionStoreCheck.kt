package app.pi.session

// A bare-JVM harness for the session index reader. Android-free on purpose: it
// compiles and runs with the Kotlin stdlib, kotlinx.serialization and
// kotlinx.coroutines, because `PiSessionStore` touches nothing else. Registered in
// `tools/run-app-pure-checks.sh` as `sessions`.
//
// What it pins, and why each one is worth pinning (every rule below is pi's, with
// the file:line in `core/session-manager.ts` given next to it):
//
//  1. **The flat layout is listed.** Our engine passes
//     `--session-dir /root/.pi/agent/sessions` and the same value in
//     `PI_CODING_AGENT_SESSION_DIR`, and with an explicit session directory pi
//     writes the files *directly* into it (`:1551-1552`, `:947-949`). A reader that
//     only walks subdirectories therefore finds nothing at all — the reported bug
//     ("the session list is empty, so the app cannot open the conversation I just
//     had"), and no other check in this repository would have caught it.
//  2. **The grouped layout still is** (pi's default, `:473-486`, and what pi's own
//     all-projects list reads, `:1706-1718`), and the two together — mixed — produce
//     both rows and no duplicates.
//  3. **A file that is not a session is not listed.** pi requires the first parseable
//     line to be the `session` header (`:707-709`) and skips blank/malformed lines
//     while looking for it (`:503-512`). Without this, a stray `.jsonl` becomes a row
//     that pi refuses to open (`:905-908`).
//  4. **Ordering is by the conversation, not the file.** pi sorts its picker by
//     `SessionInfo.modified` — the newest user/assistant message timestamp, else the
//     header timestamp, else mtime (`:744-749`, sorted at `:1675`) — while `-c` sorts
//     by *file mtime* (`:649`). Both rules exist in pi and they can disagree, so
//     both are pinned here: `list()` follows the picker, `mostRecentForResume`
//     follows `-c`, including `-c`'s cwd filter (`:631-633`) and its flat-directory
//     scope.
//  5. **The name rules**: the latest `session_info` wins, and an empty one clears it
//     (`:714-716`).
//  6. **Deletion is contained**: only a regular `.jsonl` inside the session root.

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

/** One `session` header, in the shape pi 0.85.1 writes (`session-manager.ts:932-939`). */
private fun header(id: String, iso: String, cwd: String?): String =
    "{\"type\":\"session\",\"version\":3,\"id\":\"$id\",\"timestamp\":\"$iso\"" +
        (cwd?.let { ",\"cwd\":\"$it\"" } ?: "") + "}"

/** One `message` entry (`:1071-1080`): entry id/parentId plus the nested AgentMessage. */
private fun message(id: String, parentId: String?, iso: String, role: String, text: String, ms: Long): String =
    "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parentId?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"$iso\",\"message\":{\"role\":\"$role\"," +
        "\"content\":[{\"type\":\"text\",\"text\":\"$text\"}],\"timestamp\":$ms}}"

private fun sessionInfo(id: String, parentId: String, iso: String, name: String?): String =
    "{\"type\":\"session_info\",\"id\":\"$id\",\"parentId\":\"$parentId\",\"timestamp\":\"$iso\"" +
        (name?.let { ",\"name\":\"$it\"" } ?: "") + "}"

private fun sessionFile(
    dir: java.io.File,
    name: String,
    lines: List<String>,
    mtime: Long,
): java.io.File {
    dir.mkdirs()
    val file = java.io.File(dir, name)
    file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    file.setLastModified(mtime)
    return file
}

fun main() {
    val root = java.io.File(
        System.getProperty("java.io.tmpdir"),
        "pi-session-store-check-${System.nanoTime()}",
    )
    root.mkdirs()
    val store = PiSessionStore(root)

    val old = java.time.Instant.parse("2024-01-01T00:00:00.000Z").toEpochMilli()
    val mid = java.time.Instant.parse("2024-06-01T00:00:00.000Z").toEpochMilli()
    val new = java.time.Instant.parse("2024-12-01T00:00:00.000Z").toEpochMilli()

    // ------------------------------------------------- 1. flat layout (our engine)
    // `flat` has the *older conversation* but the *newer file mtime*: that is the one
    // case where pi's picker order and pi's `-c` order disagree, and pinning both here
    // is what keeps the two readers from quietly becoming the same one.
    val flat = sessionFile(
        root,
        "2024-06-01T00-00-00-000Z_flat.jsonl",
        listOf(
            header("flat", "2024-06-01T00:00:00.000Z", CWD),
            message("m1", null, "2024-06-01T00:00:01.000Z", "user", "flat hello", mid + 1_000),
            message("m2", "m1", "2024-06-01T00:00:02.000Z", "assistant", "flat reply", mid + 2_000),
        ),
        mtime = new,
    )
    val flatNewerTalk = sessionFile(
        root,
        "2024-12-01T00-00-00-000Z_flat-newer-talk.jsonl",
        listOf(
            header("flat-newer-talk", "2024-12-01T00:00:00.000Z", CWD),
            message("n1", null, "2024-12-01T00:00:01.000Z", "user", "newer talk", new + 1_000),
            message("n2", "n1", "2024-12-01T00:00:02.000Z", "assistant", "newer reply", new + 2_000),
        ),
        mtime = old,
    )

    // ------------------------------------------------- 2. grouped layout (pi default)
    val group = java.io.File(root, "--workspace-pi-workspaces-workspace-2--")
    // No `cwd` in this header on purpose: the group name is the fallback.
    sessionFile(
        group,
        "2024-06-01T00-00-00-000Z_grouped-nocwd.jsonl",
        listOf(
            header("grouped-nocwd", "2024-06-01T00:00:00.000Z", null),
            message("g1", null, "2024-06-01T00:00:01.000Z", "user", "grouped hello", mid + 1_000),
            message("g2", "g1", "2024-06-01T00:00:02.000Z", "assistant", "grouped reply", mid + 2_000),
        ),
        mtime = mid,
    )
    // And one whose header *does* carry its cwd: it must be listed (the directory it
    // happens to live in says nothing about it), and `-c` must still not find it,
    // because pi's `findMostRecentSession` reads only the directory it is given.
    sessionFile(
        group,
        "2024-06-01T00-00-00-000Z_grouped-cwd.jsonl",
        listOf(
            header("grouped-cwd", "2024-06-01T00:00:00.000Z", OTHER_CWD),
            message("h1", null, "2024-06-01T00:00:01.000Z", "user", "second workspace", mid + 1_000),
        ),
        mtime = mid,
    )

    // ------------------------------------------------- 3. things that are not sessions
    sessionFile(root, "not-a-session.jsonl", listOf("{\"type\":\"message\",\"id\":\"x\"}"), mtime = new)
    sessionFile(root, "empty.jsonl", emptyList(), mtime = new)
    sessionFile(
        root,
        "leading-junk.jsonl",
        listOf("", "{not json", header("junk-then-header", "2024-06-01T00:00:00.000Z", CWD)),
        mtime = mid,
    )
    // A session whose header only appears past the 1 MiB discovery budget is not a
    // session for this reader — pi's bounded header scan gives up there too
    // (`session-manager.ts:487-489`, `:571-575`).
    sessionFile(
        root,
        "oversized-header.jsonl",
        listOf("{malformed ".repeat(110_000) + header("too-late", "2024-06-01T00:00:00.000Z", CWD)),
        mtime = new,
    )

    // The outside root for the deletion guard lives in its own directory so that two
    // concurrent runs of this harness cannot delete each other's fixtures.
    val outsideDir = java.io.File(root.parentFile, "pi-session-store-outside-${System.nanoTime()}")
    outsideDir.mkdirs()
    val outside = java.io.File(outsideDir, "outside.jsonl")
    outside.writeText(header("outside", "2024-06-01T00:00:00.000Z", CWD) + "\n")

    kotlinx.coroutines.runBlocking {
        val all = store.list()
        val names = all.map { it.file.name }.sorted()
        check(
            "both layouts are listed and nothing else",
            names,
            listOf(
                "2024-06-01T00-00-00-000Z_flat.jsonl",
                "2024-06-01T00-00-00-000Z_grouped-cwd.jsonl",
                "2024-06-01T00-00-00-000Z_grouped-nocwd.jsonl",
                "2024-12-01T00-00-00-000Z_flat-newer-talk.jsonl",
                "leading-junk.jsonl",
            ).sorted(),
        )

        val flatRow = all.first { it.file == flat }
        check("flat: title is the first user message", flatRow.title, "flat hello")
        check("flat: message count", flatRow.messageCount, 2)
        check("flat: cwd comes from the header", flatRow.cwd, CWD)
        check("flat: id comes from the header", flatRow.id, "flat")
        check("flat: last activity is the last message", flatRow.lastActivityAt, mid + 2_000)
        check("flat: started at is the header timestamp", flatRow.startedAt, mid)
        check("flat: no parent session", flatRow.parentSession, null)

        val groupedRow = all.first { it.file.name.endsWith("_grouped-nocwd.jsonl") }
        check(
            "grouped: cwd falls back to the encoded directory name",
            groupedRow.cwd,
            "workspace-pi-workspaces-workspace-2",
        )
        check("grouped: title is the first user message", groupedRow.title, "grouped hello")
        check(
            "grouped: a header cwd wins over the directory name",
            all.first { it.file.name.endsWith("_grouped-cwd.jsonl") }.cwd,
            OTHER_CWD,
        )

        // 4. ordering: by last activity, not by file mtime.
        check(
            "ordering follows last activity, not file mtime",
            all.first().file.name,
            "2024-12-01T00-00-00-000Z_flat-newer-talk.jsonl",
        )
        check(
            "limit keeps the newest",
            store.list(limit = 1).map { it.file.name },
            listOf("2024-12-01T00-00-00-000Z_flat-newer-talk.jsonl"),
        )

        check("blank and malformed leading lines are skipped", all.first { it.file.name == "leading-junk.jsonl" }.title, "(空会话)")
        check("a header past the scan budget is not a session", names.contains("oversized-header.jsonl"), false)
        check("a message-only .jsonl is not a session", names.contains("not-a-session.jsonl"), false)
        check("an empty .jsonl is not a session", names.contains("empty.jsonl"), false)

        // 5. names.
        val named = sessionFile(
            root,
            "named.jsonl",
            listOf(
                header("named", "2024-06-01T00:00:00.000Z", CWD),
                message("a", null, "2024-06-01T00:00:01.000Z", "user", "hi", mid + 1_000),
                sessionInfo("b", "a", "2024-06-01T00:00:02.000Z", "first name"),
                sessionInfo("c", "b", "2024-06-01T00:00:03.000Z", "final name"),
            ),
            mtime = mid,
        )
        check("the latest session_info wins", store.list().first { it.file == named }.name, "final name")
        sessionFile(
            root,
            "cleared.jsonl",
            listOf(
                header("cleared", "2024-06-01T00:00:00.000Z", CWD),
                message("a", null, "2024-06-01T00:00:01.000Z", "user", "hi", mid + 1_000),
                sessionInfo("b", "a", "2024-06-01T00:00:02.000Z", "gone"),
                sessionInfo("c", "b", "2024-06-01T00:00:03.000Z", null),
            ),
            mtime = mid,
        )
        check("an empty session_info clears the name", store.list().first { it.file.name == "cleared.jsonl" }.name, null)

        // 6. `-c`: file mtime, cwd filter, flat directory only.
        check(
            "resume uses file mtime, not last activity",
            store.mostRecentForResume(CWD)?.file?.name,
            "2024-06-01T00-00-00-000Z_flat.jsonl",
        )
        check("resume ignores sessions for another cwd", store.mostRecentForResume("/root")?.file?.name, null)
        check(
            "resume does not look into group directories",
            store.mostRecentForResume(OTHER_CWD)?.file?.name,
            null,
        )
        java.io.File(root, "leading-junk.jsonl").setLastModified(new + 10_000)
        check(
            "resume picks the newest mtime among matching files",
            store.mostRecentForResume(CWD)?.file?.name,
            "leading-junk.jsonl",
        )

        // 7. deletion is contained.
        check("delete refuses a path outside the session root", store.delete(outside), false)
        check(
            "delete refuses a non-.jsonl file",
            store.delete(java.io.File(root, "named.txt").apply { writeText("x") }),
            false,
        )
        val toDelete = sessionFile(root, "delete-me.jsonl", listOf(header("delete-me", "2024-06-01T00:00:00.000Z", CWD)), mtime = mid)
        check("delete removes a flat session file", store.delete(toDelete), true)
        check("the deleted file is gone", toDelete.exists(), false)

        // A missing directory is "no sessions", not an error: pi returns an empty list
        // for one (`session-manager.ts:819-821`).
        check(
            "a missing session root lists nothing",
            PiSessionStore(java.io.File(root, "nope")).list(),
            emptyList<PiSessionStore.Summary>(),
        )

        // 8. the scan is *bounded*, and an over-long line is dropped rather than read.
        //
        // Why this is a check and not a comment: a session file's lines are messages,
        // and one of them is legitimately a multi-megabyte tool result (pi's own cap
        // is 50 KB per result) or an inline base64 image. `BufferedReader.readLine()`
        // returns such a line *in full* before any budget can be consulted, so the
        // store's 1 MiB "header scan budget" bounded nothing at all — on a phone whose
        // free memory is ~1 GB, opening the session list could allocate whatever the
        // largest line happened to be, three times over (reader buffer, String,
        // JsonObject). `SessionFileScan` is the bound; these checks pin it.
        val kept = mutableListOf<String>()
        val consumed = SessionFileScan.forEachLine(
            java.io.StringReader("a\r\nb\n" + "x".repeat(5_000) + "\nc"),
            budget = 4096,
            maxLineChars = 1024,
        ) { line ->
            kept += line
            true
        }
        check("the scan strips a CR, keeps the lines it read", kept, listOf("a", "b"))
        check("the scan stops exactly at its budget", consumed, 4096L)

        val afterLongLine = mutableListOf<String>()
        SessionFileScan.forEachLine(
            java.io.StringReader("y".repeat(5_000) + "\nkept\n"),
            budget = 100_000,
            maxLineChars = 1024,
        ) { line ->
            afterLongLine += line
            true
        }
        check("an over-long line is dropped and the next line survives", afterLongLine, listOf("kept"))

        var stopsEarly = 0
        SessionFileScan.forEachLine(java.io.StringReader("one\ntwo\n"), budget = 100, maxLineChars = 100) {
            stopsEarly++
            false
        }
        check("returning false stops the scan after one line", stopsEarly, 1)

        val unterminated = mutableListOf<String>()
        SessionFileScan.forEachLine(java.io.StringReader("tail"), budget = 100, maxLineChars = 100) { line ->
            unterminated += line
            true
        }
        check("an unterminated tail at EOF is a line", unterminated, listOf("tail"))

        // ...and the same thing through the store's public surface: a session whose
        // third line is larger than the whole scan budget still lists, with the header
        // and the first user message intact, and falls back to the file's mtime for
        // "last activity" (the scan was truncated, `PiSessionStore.kt` on `truncated`).
        val hugeFile = sessionFile(
            root,
            "huge-line.jsonl",
            listOf(
                header("huge-line", "2024-06-01T00:00:00.000Z", CWD),
                message("h1", null, "2024-06-01T00:00:01.000Z", "user", "before the huge line", mid + 1_000),
                "{\"type\":\"message\",\"id\":\"h2\",\"message\":{\"role\":\"toolResult\",\"output\":\"" +
                    "z".repeat(2 * 1024 * 1024) + "\"}}",
            ),
            mtime = new,
        )
        val huge = store.list().firstOrNull { it.file.name == "huge-line.jsonl" }
        check("a session with an over-budget line is still listed", huge != null, true)
        check("its header is still read", huge?.cwd, CWD)
        check("its first user message is still the title", huge?.title, "before the huge line")
        // Compared against the file's own mtime rather than the value handed to
        // `setLastModified`: the point is the *rule* ("a truncated scan falls back to
        // mtime"), and a filesystem with coarser timestamp granularity must not be able
        // to red this check.
        check(
            "a truncated scan falls back to the file mtime",
            huge?.lastActivityAt,
            hugeFile.lastModified(),
        )
    }

    outsideDir.deleteRecursively()
    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
