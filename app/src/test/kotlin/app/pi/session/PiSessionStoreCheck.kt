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
//     (`session-manager.ts:828-831`) — and "latest" now means **the whole file**, not
//     its first megabyte (see 7), which is what a rename on a long conversation
//     depends on.
//  6. **Deletion is contained**: only a regular `.jsonl` inside the session root.
//  7. **The summary reads the whole file** (`PiSessionStore.readSummary`, pi's
//     `buildSessionInfo` at `session-manager.ts:799`), because pi **appends**
//     `session_info` to the end (`:1304-1313` → `appendFileSync`, `:1187`) while the
//     reader used to stop at 1 MiB: the reported bug was a rename that never reached
//     the list. `SessionFileScan` is what keeps that O(1) in memory — it streams the
//     file and can drop an over-long line instead of reading it, because a session
//     file's lines are messages (one of which can be a multi-megabyte tool result or
//     an inline image). That per-line cap is the store's only remaining bound
//     ([SessionFileScan.NO_BUDGET] is its total budget), and it is pinned below.
//  8. **`/import` and `/export` follow pi's own rules** (`SessionImport.kt`,
//     `SessionExportNaming.kt`): which first line makes a file a session
//     (`session-manager.ts:538-577` — `loadEntriesFromFile`, header checked at `:573-576`),
//     what an imported copy is named when the name is
//     taken (`agent-session-runtime.ts:372-380`), which argument suffix picks which
//     export writer (`interactive-mode.ts:6188-6198`), and that the user-visible
//     failure sentences name no internal path.

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

/** One `session` header, in the shape pi 0.86.1 writes (`SessionHeader` at `session-manager.ts:40-47`, written at `:974-981`). */
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

/**
 * 一条带内联图片的 `message`：会话文件**真实的形状**，也是全文扫描贵在哪里的答案
 * （见 `SessionFileScan` 的 KDoc —— 一行就是一条消息，一条消息可以带一张 base64 的图）。
 */
private fun imageMessage(id: String, parentId: String?, iso: String, base64Chars: Int, ms: Long): String =
    "{\"type\":\"message\",\"id\":\"$id\",\"parentId\":${parentId?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"$iso\",\"message\":{\"role\":\"assistant\"," +
        "\"content\":[{\"type\":\"image\",\"data\":\"" + "A".repeat(base64Chars) + "\"}]," +
        "\"timestamp\":$ms}}"

/** 一行摘要的**每一个**可比较字段，加上派生出来的显示名 —— 「逐字段相等」的那把尺子。 */
private fun rowFields(s: PiSessionStore.Summary): List<Any?> = listOf(
    s.file.absolutePath,
    s.id,
    s.cwd,
    s.startedAt,
    s.lastActivityAt,
    s.name,
    s.title,
    s.model,
    s.messageCount,
    s.parentSession,
    s.displayName,
)

private fun rowFieldsAll(rows: List<PiSessionStore.Summary>): List<List<Any?>> = rows.map(::rowFields)

/** 「同顺序、同去重、同 limit」的逐字段比对；失败时把两份读数原样打出来。 */
private fun checkRows(name: String, actual: List<PiSessionStore.Summary>, expected: List<PiSessionStore.Summary>) {
    check("$name（行数）", actual.size, expected.size)
    check(name, rowFieldsAll(actual), rowFieldsAll(expected))
}

/** 一个「内存冷」的 store（索引可选）。每次调用都是新实例，所以进程内缓存一定是空的。 */
private fun coldStore(root: java.io.File, indexFile: java.io.File?, parallelism: Int = 4): PiSessionStore =
    PiSessionStore(root, indexFile, parallelism)

private fun List<PiSessionStore.Summary>.titleOf(id: String): String? = firstOrNull { it.id == id }?.title

private fun List<PiSessionStore.Summary>.nameOf(id: String): String? = firstOrNull { it.id == id }?.name

private fun List<PiSessionStore.Summary>.countWithId(id: String): Int = count { it.id == id }

/** 中位数（读数个数奇数时就是中间那个）。避免把一次 GC 当成结论。 */
private fun median(runs: List<Long>): Long = runs.sorted()[runs.size / 2]

/**
 * 造一批「真实形状」的会话：每个文件 2 行 0.6 MB 的内联图片（**在 1 MiB 单行上限之内**，
 * 所以它们真的会被逐行 JSON 解析 —— 慢就慢在这里）+ 1 行 1.2 MB（超上限，被 `SessionFileScan`
 * 丢弃，只付读取的代价）。两条路径都是真的，不能只测其中一条。
 */
private fun buildRealisticSessions(dir: java.io.File, count: Int, base: Long): List<java.io.File> {
    val out = ArrayList<java.io.File>()
    for (i in 1..count) {
        val id = "perf-$i"
        out += sessionFile(
            dir,
            "2024-06-01T00-00-00-000Z_$id.jsonl",
            listOf(
                header(id, "2024-06-01T00:00:00.000Z", CWD),
                message("m0", null, "2024-06-01T00:00:01.000Z", "user", "conversation $i", base + i),
                imageMessage("img1", "m0", "2024-06-01T00:00:02.000Z", 600_000, base + i * 10 + 1),
                imageMessage("img2", "img1", "2024-06-01T00:00:03.000Z", 600_000, base + i * 10 + 2),
                imageMessage("img3", "img2", "2024-06-01T00:00:04.000Z", 1_200_000, base + i * 10 + 3),
                sessionInfo("s1", "img3", "2024-06-01T00:00:05.000Z", "name $i"),
            ),
            mtime = base + i,
        )
    }
    return out
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
    // 第 12–14 节（摘要索引）用自己的一套时间戳与夹具目录，与上面那批互不干扰。
    val iBase = java.time.Instant.parse("2025-01-01T00:00:00.000Z").toEpochMilli()

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
    // A session whose only header sits on a line longer than the 1 MiB per-line cap is
    // not a session for this reader: the line is dropped, so no parseable line remains.
    // That cap is the scan's *memory* bound, not a total budget — the user's ruling is
    // that the summary reads the whole file (see `PiSessionStore.readSummary` and the
    // check below that a trailing message past 1 MiB is read).
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

    // 第 12–14 节（摘要索引 / 并行 / 耗时）的夹具目录。在里面建、在这里声明，
    // 是为了跑完能删掉它们 —— 那批合成会话有几十 MB。
    var idxRootPath: String? = null
    var perfRootPath: String? = null

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
        check("a header on an over-long line is not read", names.contains("oversized-header.jsonl"), false)
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
        // for one (`session-manager.ts:858`).
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
        // store's old 1 MiB scan budget bounded nothing at all — on a phone whose
        // free memory is ~1 GB, opening the session list could allocate whatever the
        // largest line happened to be, three times over (reader buffer, String,
        // JsonObject). `SessionFileScan` is the bound; these checks pin it. What the
        // store scans now is the whole file ([NO_BUDGET]), so the per-line cap is the
        // only bound left standing there.
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

        val wholeFile = mutableListOf<String>()
        SessionFileScan.forEachLine(
            java.io.StringReader("one\ntwo\nthree\n"),
            budget = SessionFileScan.NO_BUDGET,
            maxLineChars = 1024,
        ) { line ->
            wholeFile += line
            true
        }
        check("[NO_BUDGET] reads every line to EOF", wholeFile, listOf("one", "two", "three"))

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

        // ...and the same thing through the store's public surface. The third line is
        // 2 MiB — larger than the per-line cap, so it is dropped rather than read — and
        // the fourth line sits **past 1 MiB of file**, so it is the check that the
        // summary scan does not stop at a 1 MiB total budget: the trailing user message
        // must be the one that names "last activity". Before the file-wide scan it was
        // the file's mtime instead, because `truncated` made the store fall back to it.
        sessionFile(
            root,
            "huge-line.jsonl",
            listOf(
                header("huge-line", "2024-06-01T00:00:00.000Z", CWD),
                message("h1", null, "2024-06-01T00:00:01.000Z", "user", "before the huge line", mid + 1_000),
                "{\"type\":\"message\",\"id\":\"h2\",\"message\":{\"role\":\"toolResult\",\"output\":\"" +
                    "z".repeat(2 * 1024 * 1024) + "\"}}",
                message("h3", "h2", "2024-06-01T00:00:04.000Z", "user", "after the huge line", mid + 5_000),
            ),
            mtime = new,
        )
        val huge = store.list().firstOrNull { it.file.name == "huge-line.jsonl" }
        check("a session with an over-long line is still listed", huge != null, true)
        check("its header is still read", huge?.cwd, CWD)
        check("its first user message is still the title", huge?.title, "before the huge line")
        // The trailer is past 1 MiB and was never read before the file-wide scan.
        check("a message past 1 MiB is still read", huge?.lastActivityAt, mid + 5_000)
        // The dropped 2 MiB line is not a `message` entry this scan saw, and since the
        // fallback-to-mtime branch is gone there is nothing to hide that: the count is
        // one (h1), plus the trailer (h3) — pi would say three, because its `readline`
        // materialises the over-long line.
        check("an over-long line is dropped, not counted", huge?.messageCount, 2)

        // 9. the whole file, not its first megabyte — the ruling behind `readSummary`.
        //
        // pi appends `session_info` to the end (`session-manager.ts:1304-1313` →
        // `_persist`'s `appendFileSync`, `:1187`) and its own list streams the whole
        // file (`:799`), so a name set on a >1 MiB conversation must be visible.
        // `filler` puts the file at ~1.2 MiB, past the old scan: every check in this
        // section fails against the 1 MiB prefix reader.
        val filler = (1..300).map { i ->
            message(
                "f$i",
                if (i == 1) null else "f${i - 1}",
                "2024-06-01T00:00:02.000Z",
                "assistant",
                "x".repeat(4_000),
                mid + i,
            )
        }
        sessionFile(
            root,
            "big-name.jsonl",
            listOf(
                header("big-name", "2024-06-01T00:00:00.000Z", CWD),
            ) + filler + listOf(
                // Two trailing entries: the *latest* one wins, even when the earlier one
                // is itself past 1 MiB.
                sessionInfo("s1", "f300", "2024-06-01T00:00:05.000Z", "first tail name"),
                sessionInfo("s2", "s1", "2024-06-01T00:00:06.000Z", "final tail name"),
            ),
            mtime = new,
        )
        val bigNamed = store.list().firstOrNull { it.file.name == "big-name.jsonl" }
        check("a name appended past 1 MiB is listed", bigNamed?.name, "final tail name")
        check("the last session_info wins at the end of a big file", bigNamed?.displayName, "final tail name")
        // The whole file is counted, not the first megabyte: 300 filler messages
        // (pi counts every `message` entry, `session-manager.ts:833-834`).
        check("messageCount covers the whole file", bigNamed?.messageCount, 300)
        check("last activity comes from the end of the file", bigNamed?.lastActivityAt, mid + 300)

        sessionFile(
            root,
            "big-cleared.jsonl",
            listOf(
                header("big-cleared", "2024-06-01T00:00:00.000Z", CWD),
                message("c0", null, "2024-06-01T00:00:01.000Z", "user", "big clear", mid + 1_000),
                // Inside the old 1 MiB prefix — the name a truncated reader would keep.
                sessionInfo("c1", "c0", "2024-06-01T00:00:03.000Z", "stale name"),
            ) + filler + listOf(
                // An empty trailing entry clears the name (`session-manager.ts:828-831`).
                sessionInfo("c2", "f300", "2024-06-01T00:00:07.000Z", null),
            ),
            mtime = new,
        )
        check(
            "an empty trailing session_info clears a name set before 1 MiB",
            store.list().first { it.file.name == "big-cleared.jsonl" }.name,
            null,
        )

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

        // ============================================================ 12. 摘要索引
        //
        // 落盘的摘要索引（`PiSessionIndex`）要证明的**唯一**一件事是：它不改变任何结果。
        // 下面每一条都是"同一批文件、两份读数、逐字段相等"，其中"带索引"的那一份一律是
        // **新的 store 实例** —— 所以进程内缓存一定是冷的，能省下的时间只可能来自磁盘索引。
        //
        // 与之配对的是**退化**：索引缺失/损坏/版本不认识就全量扫描；任何一个文件的 length
        // 或 mtime 动了就重扫它；而 (length, mtime) 都没动的同长度改写**必须**给出旧行 ——
        // 那不是缺陷，是照抄今天的进程内缓存语义（见 `PiSessionStore.Cached` 的 KDoc，
        // 以及任务书里点名的那一条）。
        val idxRoot = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "pi-session-index-check-${System.nanoTime()}",
        ).also { idxRootPath = it.absolutePath }
        val idxSessions = java.io.File(idxRoot, "sessions")
        // 索引的落点是**注入**的（库里没有任何硬编码路径）：父目录一开始并不存在，
        // 顺带把「目录要自己建」也走一遍。
        val idxFile = java.io.File(idxRoot, "runtime/.session-index.json")

        // 一个**并列的 lastActivityAt** 出现在夹具里是有意的：`sortByDescending` 是稳定
        // 排序，并列的两行谁在前只由进 `bySession` 的先后决定 —— 那正是并行最容易弄丢的东西。
        val tie = iBase + 500

        suspend fun withIndex(parallelism: Int = 4): List<PiSessionStore.Summary> =
            coldStore(idxSessions, idxFile, parallelism).list()

        suspend fun withoutIndex(): List<PiSessionStore.Summary> = coldStore(idxSessions, null).list()

        val bigA = sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_big-a.jsonl",
            listOf(
                header("big-a", "2024-06-01T00:00:00.000Z", CWD),
                message("a1", null, "2024-06-01T00:00:01.000Z", "user", "first message of A", iBase + 1_000),
                imageMessage("a2", "a1", "2024-06-01T00:00:02.000Z", 600_000, iBase + 1_001),
                sessionInfo("a3", "a2", "2024-06-01T00:00:03.000Z", "名字 A"),
            ),
            mtime = iBase + 1_010,
        )
        sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_big-b.jsonl",
            listOf(
                header("big-b", "2024-06-01T00:00:00.000Z", CWD),
                message("b1", null, "2024-06-01T00:00:01.000Z", "user", "first message of B", iBase + 2_000),
                imageMessage("b2", "b1", "2024-06-01T00:00:02.000Z", 600_000, iBase + 2_001),
            ),
            mtime = iBase + 2_010,
        )
        sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_big-c.jsonl",
            listOf(
                header("big-c", "2024-06-01T00:00:00.000Z", CWD),
                message("c1", null, "2024-06-01T00:00:01.000Z", "user", "first message of C", iBase + 3_000),
                imageMessage("c2", "c1", "2024-06-01T00:00:02.000Z", 600_000, iBase + 3_001),
                imageMessage("c3", "c2", "2024-06-01T00:00:03.000Z", 600_000, iBase + 3_002),
            ),
            mtime = iBase + 3_010,
        )
        sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_big-d.jsonl",
            listOf(
                header("big-d", "2024-06-01T00:00:00.000Z", CWD),
                message("d1", null, "2024-06-01T00:00:01.000Z", "user", "first message of D", iBase + 4_000),
                imageMessage("d2", "d1", "2024-06-01T00:00:02.000Z", 600_000, iBase + 4_001),
            ),
            mtime = iBase + 4_010,
        )
        // 标题正好 4 个 ASCII 字符，好做"同长度改写"。
        val sameLenFile = sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_same-length.jsonl",
            listOf(
                header("same-length", "2024-06-01T00:00:00.000Z", CWD),
                message("s1", null, "2024-06-01T00:00:01.000Z", "user", "AAAA", iBase + 5_000),
            ),
            mtime = iBase + 5_010,
        )
        sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_tie-1.jsonl",
            listOf(
                header("tie-1", "2024-06-01T00:00:00.000Z", CWD),
                message("t1", null, "2024-06-01T00:00:01.000Z", "user", "tie one", tie),
            ),
            mtime = iBase + 6_010,
        )
        sessionFile(
            idxSessions,
            "2024-06-01T00-00-00-000Z_tie-2.jsonl",
            listOf(
                header("tie-2", "2024-06-01T00:00:00.000Z", CWD),
                message("t2", null, "2024-06-01T00:00:01.000Z", "user", "tie two", tie),
            ),
            mtime = iBase + 7_010,
        )
        // 组目录：一份 header 没有 cwd 的（回退到目录名解出来的 cwd），一份是 big-a 的副本
        // （同一个 id、但内容更旧 —— `laterSessionRow` 必须留下平铺那一份）。
        val idxGroup = java.io.File(idxSessions, "--workspace-pi-workspaces-workspace-2--")
        sessionFile(
            idxGroup,
            "2024-06-01T00-00-00-000Z_grouped-nocwd.jsonl",
            listOf(
                header("grouped-nocwd", "2024-06-01T00:00:00.000Z", null),
                message("g1", null, "2024-06-01T00:00:01.000Z", "user", "grouped hello", iBase + 600),
            ),
            mtime = iBase + 6_100,
        )
        sessionFile(
            idxGroup,
            "2024-06-01T00-00-00-000Z_copy-of-big-a.jsonl",
            listOf(
                header("big-a", "2024-06-01T00:00:00.000Z", CWD),
                message("ca1", null, "2024-06-01T00:00:01.000Z", "user", "an older copy of A", iBase + 100),
            ),
            mtime = iBase + 8_000,
        )
        sessionFile(idxSessions, "not-a-session.jsonl", listOf("{\"type\":\"message\",\"id\":\"x\"}"), mtime = iBase + 9_000)
        sessionFile(idxSessions, "empty.jsonl", emptyList(), mtime = iBase + 9_100)

        // 12.1 「不带索引」就是今天的行为，也是后面每一条的基准。
        val baseline = withoutIndex()
        check("12.1 夹具：两种布局都列出来、非会话文件不列", baseline.size, 8)
        check("12.1 夹具：同一会话的两份复制只留一行", baseline.countWithId("big-a"), 1)
        check("12.1 夹具：留下的是平铺那一份", baseline.first { it.id == "big-a" }.file.parentFile?.absolutePath, idxSessions.absolutePath)
        check("12.1 夹具：组目录 cwd 回退", baseline.first { it.id == "grouped-nocwd" }.cwd, "workspace-pi-workspaces-workspace-2")
        check("12.1 夹具：并列的两行都在", baseline.filter { it.id.startsWith("tie-") }.size, 2)

        // 12.2 第一次带索引的扫描：索引还不存在 ⇒ 全量扫描 + 落盘。结果必须一模一样。
        val firstIndexed = withIndex()
        checkRows("12.2 第一次带索引扫描（索引不存在）== 不带索引扫描", firstIndexed, baseline)
        check("12.2 索引文件被写出来了", idxFile.isFile, true)
        check("12.2 索引目录是写的时候建的", idxFile.parentFile?.isDirectory, true)

        // 12.3 新实例 + 已有的索引：**用户抱怨的那一次**（进程内冷、索引热）。
        val warmIndexed = withIndex()
        checkRows("12.3 带着索引冷扫描 == 不带索引扫描", warmIndexed, baseline)
        check("12.3 走索引那条路时组目录 cwd 仍回退到目录名", warmIndexed.first { it.id == "grouped-nocwd" }.cwd, "workspace-pi-workspaces-workspace-2")
        check("12.3 走索引那条路时同一会话仍只留一行", warmIndexed.countWithId("big-a"), 1)
        check("12.3 走索引那条路时留下的是平铺那份", warmIndexed.first { it.id == "big-a" }.file.parentFile?.absolutePath, idxSessions.absolutePath)
        checkRows("12.3b 同顺序（含并列的 lastActivityAt）", withIndex(), baseline)

        // 12.4 limit：截断发生在去重之后，且索引不改变它。
        checkRows(
            "12.4 limit=3：索引扫描 == 纯扫描",
            coldStore(idxSessions, idxFile).list(limit = 3),
            coldStore(idxSessions, null).list(limit = 3),
        )
        check("12.4 limit=1 就是最新那一条（按 lastActivityAt，不是文件 mtime）", coldStore(idxSessions, idxFile).list(limit = 1).map { it.id }, listOf("same-length"))

        // 12.5 新文件：索引里没有它 ⇒ 它自己全量扫描，其余命中。
        sessionFile(
            idxSessions,
            "2025-01-01T00-00-00-000Z_new.jsonl",
            listOf(
                header("new-one", "2025-01-01T00:00:00.000Z", CWD),
                message("n1", null, "2025-01-01T00:00:01.000Z", "user", "brand new", iBase + 9_000),
            ),
            mtime = iBase + 9_010,
        )
        checkRows("12.5 新文件：索引扫描 == 纯扫描", withIndex(), withoutIndex())
        check("12.5 新文件确实出现了", withIndex().countWithId("new-one"), 1)

        // 12.6 变大：追加一条消息 + 一条改名的 `session_info`（长度与 mtime 都变）。
        bigA.appendText(
            message("a4", "a3", "2025-01-01T00:00:10.000Z", "user", "appended later", iBase + 10_000) + "\n" +
                sessionInfo("a5", "a4", "2025-01-01T00:00:11.000Z", "renamed after append") + "\n",
        )
        bigA.setLastModified(iBase + 10_010)
        checkRows("12.6 变大：索引扫描 == 纯扫描", withIndex(), withoutIndex())
        check("12.6 追加的消息被算进去了", withIndex().first { it.id == "big-a" }.messageCount, baseline.first { it.id == "big-a" }.messageCount + 1)
        check("12.6 追加的 session_info 改了名字", withIndex().nameOf("big-a"), "renamed after append")

        // 12.7 变小：截断到只剩 header。
        val bigC = java.io.File(idxSessions, "2024-06-01T00-00-00-000Z_big-c.jsonl")
        val beforeC = baseline.first { it.id == "big-c" }.messageCount
        bigC.writeText(header("big-c", "2024-06-01T00:00:00.000Z", CWD) + "\n", Charsets.UTF_8)
        bigC.setLastModified(iBase + 20_010)
        checkRows("12.7 变小：索引扫描 == 纯扫描", withIndex(), withoutIndex())
        check("12.7 变得确实更小", bigC.length() < beforeC.toLong() * 1024, true)
        check("12.7 条数跟着变小", withIndex().first { it.id == "big-c" }.messageCount, 0)

        // 12.8 **只有长度变**（mtime 复位回旧值）⇒ 索引必须失配、重扫这个文件。
        val bigD = java.io.File(idxSessions, "2024-06-01T00-00-00-000Z_big-d.jsonl")
        val oldLength = bigD.length()
        val oldMtime = bigD.lastModified()
        bigD.writeText(
            header("big-d", "2025-01-01T00:00:00.000Z", CWD) + "\n" +
                message("d9", null, "2025-01-01T00:00:01.000Z", "user", "only the length changed", iBase + 30_000) + "\n",
            Charsets.UTF_8,
        )
        bigD.setLastModified(oldMtime)
        check("12.8 长度确实变了", bigD.length() != oldLength, true)
        check("12.8 mtime 已复位（所以失配只可能来自长度）", bigD.lastModified(), oldMtime)
        checkRows("12.8 只变长度：索引扫描 == 纯扫描", withIndex(), withoutIndex())
        check("12.8 重扫之后读到的是新内容", withIndex().titleOf("big-d"), "only the length changed")

        // 12.9 同长度改写、且 (length, mtime) 都没动 ⇒ **必须退化成「用旧行」**。
        // 这条不是"顺手"，是任务书点名的那一条：今天的进程内缓存就是这个语义，
        // 索引只是把同一个语义延长到进程之外，改掉它才是行为变化。
        val oldSameLenTitle = withIndex().titleOf("same-length")
        check("12.9 改写前的标题", oldSameLenTitle, "AAAA")
        val savedLength = sameLenFile.length()
        val savedMtime = sameLenFile.lastModified()
        sameLenFile.writeText(sameLenFile.readText(Charsets.UTF_8).replace("AAAA", "BBBB"), Charsets.UTF_8)
        sameLenFile.setLastModified(savedMtime)
        check("12.9 改写后长度没变", sameLenFile.length(), savedLength)
        check("12.9 改写后 mtime 没变", sameLenFile.lastModified(), savedMtime)
        check("12.9 文件里确实是新内容了", sameLenFile.readText(Charsets.UTF_8).contains("BBBB"), true)
        check("12.9 索引给出**旧行**（这正是今天的进程内缓存语义）", withIndex().titleOf("same-length"), "AAAA")
        check("12.9 无索引、内存也冷的扫描会给出新行 —— 两者不同是刻意的", withoutIndex().titleOf("same-length"), "BBBB")

        // 12.9b 同长度改写 + mtime **变了** ⇒ 索引失配、重扫，读到新内容。
        // 与 12.9 成对：两条一起才说明"失效判据到底是 length+mtime，两条都算数"。
        val bumped = sameLenFile.lastModified() + 60_000
        sameLenFile.writeText(sameLenFile.readText(Charsets.UTF_8).replace("BBBB", "AAAA"), Charsets.UTF_8)
        sameLenFile.setLastModified(bumped)
        check("12.9b 长度仍然没变", sameLenFile.length(), savedLength)
        check("12.9b 索引失配、重扫出文件里的新内容", withIndex().titleOf("same-length"), "AAAA")
        checkRows("12.9b 索引扫描 == 纯扫描", withIndex(), withoutIndex())

        // 12.10 删除文件：行消失，而且索引里也不许留 —— 索引是有界的。
        java.io.File(idxSessions, "2024-06-01T00-00-00-000Z_big-d.jsonl").delete()
        checkRows("12.10 删除：索引扫描 == 纯扫描", withIndex(), withoutIndex())
        check("12.10 删除的行消失了", withIndex().countWithId("big-d"), 0)
        check("12.10 被删掉的文件不再出现在索引文件里", idxFile.readText(Charsets.UTF_8).contains("big-d"), false)

        // 12.11 索引读不懂 ⇒ 全量扫描（四种坏法，含"写了一半"的形状）。
        idxFile.writeText("{ this is not json", Charsets.UTF_8)
        checkRows("12.11 索引不是 JSON：退回全量扫描", withIndex(), withoutIndex())
        idxFile.writeText("{\"version\":999,\"entries\":[]}", Charsets.UTF_8)
        checkRows("12.11 版本不认识：退回全量扫描", withIndex(), withoutIndex())
        idxFile.writeText("{\"version\":1,\"entries\":[{\"path\":\"/nowhere/nowhere.jsonl\"}]}", Charsets.UTF_8)
        checkRows("12.11 残缺的 entries（坏行只丢它自己）：结果 == 纯扫描", withIndex(), withoutIndex())
        idxFile.writeText("{\"version\":1,\"entries\":[", Charsets.UTF_8)
        checkRows("12.11 被截断的索引（写了一半的形状）：结果 == 纯扫描", withIndex(), withoutIndex())

        // 12.12 索引写不进去 ⇒ 静默退回（不抛、结果不变）。父目录是个普通文件，建不出来。
        val blockedParent = java.io.File(idxRoot, "blocked")
        blockedParent.writeText("not a directory", Charsets.UTF_8)
        val blockedIndex = java.io.File(blockedParent, "session-index.json")
        checkRows("12.12 索引不可写：结果 == 纯扫描", coldStore(idxSessions, blockedIndex).list(), withoutIndex())
        check("12.12 那次失败没有留下半份文件", blockedIndex.exists(), false)

        // 12.13 原子写：写完不留临时文件。
        idxFile.delete()
        withIndex()
        val leftovers = idxFile.parentFile?.listFiles()?.map { it.name }?.filter { it.contains(".tmp") }.orEmpty()
        check("12.13 写完不留临时文件", leftovers, emptyList<String>())

        // ========================================================== 13. 并行 == 串行
        //
        // 并行唯一可能弄丢的是**顺序**：`sortByDescending` 稳定，`lastActivityAt` 并列的
        // 两行谁在前取决于谁先进 `bySession`。夹具里就有并列的两行（tie-1/tie-2）和一组
        // 同 id 的副本，所以"按完成先后折叠进 bySession"的实现会在这里红。
        // 两种取值都比同样的东西：先比"两边都真的全量扫"（索引删掉），再比"索引热"。
        idxFile.delete()
        val serialFull = coldStore(idxSessions, idxFile, 1).list()
        idxFile.delete()
        val parallelFull = coldStore(idxSessions, idxFile, 4).list()
        checkRows("13.1 索引不存在（两边都真全量扫）：并行 4 == 串行 1", parallelFull, serialFull)
        check("13.1 并列的两行都在且顺序固定", parallelFull.filter { it.id.startsWith("tie-") }.map { it.id }, serialFull.filter { it.id.startsWith("tie-") }.map { it.id })
        coldStore(idxSessions, idxFile, 1).list() // 把索引写热
        checkRows("13.2 索引热：并行 4 == 串行 1", coldStore(idxSessions, idxFile, 4).list(), coldStore(idxSessions, idxFile, 1).list())
        checkRows(
            "13.3 limit=3：并行 4 == 串行 1",
            coldStore(idxSessions, idxFile, 4).list(limit = 3),
            coldStore(idxSessions, idxFile, 1).list(limit = 3),
        )
        checkRows("13.4 并行 2 == 并行 4", coldStore(idxSessions, idxFile, 2).list(), coldStore(idxSessions, idxFile, 4).list())
        check("13.5 夹具的会话数多于线程数（真的分批了）", parallelFull.size > 4, true)

        // 13.6 `-c`（`mostRecentForResume`）走的是**另一条**调用路径，但它读的也是同一个
        // `readSummary`，所以索引对它一样生效 —— 也就一样必须给出同一行。它只读平铺那一层，
        // 键因此与 `list` 对同一个文件用的键完全相同。
        coldStore(idxSessions, idxFile).list() // 把索引写热
        val resumePlain = PiSessionStore(idxSessions).mostRecentForResume(CWD)
        val resumeIndexed = PiSessionStore(idxSessions, idxFile).mostRecentForResume(CWD)
        check("13.6 带索引的 -c 与不带索引的 -c 选出同一个会话", resumeIndexed?.file?.absolutePath, resumePlain?.file?.absolutePath)
        check("13.6 那一行的每个字段都一样", resumeIndexed?.let { rowFields(it) }, resumePlain?.let { rowFields(it) })
        check("13.6 选出来的确实是 CWD 的会话", resumeIndexed?.cwd, CWD)

        // ============================================================== 14. 数字
        //
        // N 个会话 × 每个 M 字节，真实形状（每行 0.6 MB 的内联图片；见
        // `buildRealisticSessions`）。三个读数，每一个都用**新的 store 实例**（所以进程内
        // 缓存一定是冷的）：① 冷扫描（无索引）② 有索引、0 个文件变 ③ 有索引、K 个文件变。
        //
        // 这台机器是与别的代理共用的，单个读数摆动很大（第一次跑会带上刚写完那几十 MB 的
        // 缺页，别人一编译又会把 CPU 抢走）。所以：三条路径先各预热一遍，然后**交替**测
        // "冷扫描 / 有索引" —— 交替是为了让一次负载尖峰同时打在两边，而不是只打在其中一边；
        // 报告每一个读数 + 中位数 + 最小值（最小值最接近无争用时的代价，是这种比较里
        // 唯一稳的读数）。
        val perfRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-session-index-perf-${System.nanoTime()}")
            .also { perfRootPath = it.absolutePath }
        val perfSessions = java.io.File(perfRoot, "sessions")
        val perfIndex = java.io.File(perfRoot, "runtime/.session-index.json")
        val perfCount = 24
        val perfFiles = buildRealisticSessions(perfSessions, perfCount, iBase)
        val perfBytes = perfFiles.sumOf { it.length() }
        println("== 14 索引耗时对比：$perfCount 个会话，共 $perfBytes 字节（每个 ${perfBytes / perfCount} 字节）==")

        // 每一次计时都顺手记下"读到了几个会话"：如果某条路径返回 0 行，它会立刻"变快"，
        // 而那是一个假结论。收在这里断言一次，而不是每跑一次就印一行。
        val rowsSeen = ArrayList<Int>()

        suspend fun timeScan(index: java.io.File?): Long {
            val started = System.nanoTime()
            val rows = coldStore(perfSessions, index).list()
            rowsSeen += rows.size
            return (System.nanoTime() - started) / 1_000_000
        }

        // 预热：三条路径各跑一遍。
        timeScan(null)
        timeScan(perfIndex)
        timeScan(perfIndex)

        val coldRuns = ArrayList<Long>()
        val warmRuns = ArrayList<Long>()
        repeat(5) {
            coldRuns += timeScan(null)
            warmRuns += timeScan(perfIndex)
        }
        // K = 3 个文件变了：**每一轮都重新弄脏那 3 个**（追加一条消息 ⇒ 长度与 mtime 都变），
        // 否则第一轮之后索引就已经把它们的新闻记下来了，后面几轮测到的是 K=0。
        val partialRuns = (1..5).map { round ->
            perfFiles.take(3).forEachIndexed { i, file ->
                file.appendText(
                    message(
                        "extra$round$i",
                        null,
                        "2025-06-01T00:00:00.000Z",
                        "user",
                        "changed $round-$i",
                        iBase + 100_000 + round * 10 + i,
                    ) + "\n",
                    Charsets.UTF_8,
                )
                file.setLastModified(iBase + 200_000 + round * 10 + i)
            }
            timeScan(perfIndex)
        }

        check("14.1 索引文件写出来了", perfIndex.isFile, true)
        check(
            "14.2 ${rowsSeen.size} 次计时扫描，每一次都读到 $perfCount 个会话",
            rowsSeen.all { it == perfCount },
            true,
        )
        checkRows("14.3 K=3 变了之后：索引扫描 == 纯扫描", coldStore(perfSessions, perfIndex).list(), coldStore(perfSessions, null).list())
        check("14.4 索引远小于会话本身（它存的是行，不是内容）", perfIndex.length() < perfBytes / 100, true)
        println("14 冷扫描（无索引）         ms: $coldRuns  中位数 ${median(coldRuns)}  最小 ${coldRuns.min()}")
        println("14 有索引（K=0 个文件变）   ms: $warmRuns  中位数 ${median(warmRuns)}  最小 ${warmRuns.min()}")
        println("14 有索引（K=3 个文件变）   ms: $partialRuns  中位数 ${median(partialRuns)}  最小 ${partialRuns.min()}")
        println("14 索引文件 ${perfIndex.length()} 字节；加速比（冷扫描最小 / 有索引 K=0 最小）= " +
            "${coldRuns.min().toDouble() / warmRuns.min().toDouble()}")
    }

    outsideDir.deleteRecursively()
    root.deleteRecursively()
    // 第 12–14 节的夹具（含那批几 MB 的合成会话）也在跑完之后删掉。
    idxRootPath?.let { java.io.File(it).deleteRecursively() }
    perfRootPath?.let { java.io.File(it).deleteRecursively() }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
