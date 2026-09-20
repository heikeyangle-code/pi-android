package app.pi.session

// 会话列表的**身份**判据：一段对话只有一行。注册在
// `tools/run-app-pure-checks.sh` 的 `session-identity`。
//
// 为什么必须钉住：`PiSessionStore.list()` 分别遍历 pi 的两个布局（引擎平铺写的
// `sessions/<ISO>_<id>.jsonl`、pi 默认的 `sessions/--<cwd>--/<ISO>_<id>.jsonl`），而**同一个
// 会话可以在两边各留一份** —— 组目录里的副本是某个时刻的快照，`/import` 的拷贝更是只换名字、
// 不改 header（`PiSessionViewModel.prepareImport`，工作树 `:4738-4750`），于是两份带同一个 `id`。旧 `list()` 把两个
// 布局的文件都直接塞进结果里，所以一段对话会变成两行（用户报的「就那么一个对话……出现了好几个
// 版本，就像被切开一样」）。本文件的前四组夹具就是那个形状；下面的检查要求这些夹具只产出
// **5 行**，而旧实现会产出 **8 行**（每个文件一行）。
//
// 三件不能靠读代码保证的事，也是这个 harness 真正钉的东西：
//
//  1. **去重的键是会话身份，不是显示名。** `Summary.id`（header 的 `id`，也是文件名里
//     `_<id>` 那一段）。两个真正不同的会话可以有同一个标题（用户两轮都只打「继续」）—— 那是
//     两段对话，必须留两行。按标题去重会把其中一段**藏起来**，比重复更糟。
//  2. **留下哪一份是一个全序**（`PiSessionStore.laterSessionRow`）：内容更新的先赢，并列时
//     平铺的那份赢，再并列才比 mtime，最后按路径定序。夹具故意把 mtime 与被选中的那份**相反**
//     （平铺原件 mtime 更新、内容更旧；组目录副本 mtime 更旧、内容更新），所以「按 mtime 选」或
//     「按布局选」这两种更简单的实现都会红。
//  3. **`limit` 数的是会话。** 去重若发生在截断之后，一份副本就会占掉一行、把一段真实对话挤出
//     屏幕；夹具里 8 个文件、5 段对话，`limit = 4` 必须给出最旧的那段之外的四段。
//
// 以及一件**反向**的事（第 7 节）：磁盘上真的分成几段的对话（每次引擎启动新开一个会话文件，
// 几个不同的 `id`、标题各不相同）**不是**重复行，去重不许把它们合并 —— 那一节要求三行都在，
// 标题各自取自自己文件里的第一条 user 消息。
//
// Android-free by construction：只用 stdlib、`java.io.File`、kotlinx.serialization（经 `:rpc` 的
// `PiJson`）与 kotlinx.coroutines。`PiSessionStore` 如果长出 Android import，这里就编译不过。

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val CWD = "/workspace/pi/workspaces/workspace-1"
/** pi 的组目录名：cwd 去掉前导 `/`、`/`→`-`，两头包 `--`（`session-manager.ts:500-505`）。 */
private const val GROUP = "--workspace-pi-workspaces-workspace-1--"

/** 一行 `session` 头，形状取自 pi 0.86.1（`SessionHeader` 在 `session-manager.ts:40-47`，写入在 `:974-981`）。 */
private fun header(id: String, iso: String): String =
    "{\"type\":\"session\",\"version\":3,\"id\":\"$id\",\"timestamp\":\"$iso\",\"cwd\":\"$CWD\"}"

/** 一行 `message`（`:1113-1122`）：entry 的 id/parentId + 内层 AgentMessage。 */
private fun message(
    entryId: String,
    parentId: String?,
    iso: String,
    role: String,
    text: String,
    ms: Long,
): String =
    "{\"type\":\"message\",\"id\":\"$entryId\",\"parentId\":${parentId?.let { "\"$it\"" } ?: "null"}," +
        "\"timestamp\":\"$iso\",\"message\":{\"role\":\"$role\"," +
        "\"content\":[{\"type\":\"text\",\"text\":\"$text\"}],\"timestamp\":$ms}}"

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
        "pi-session-identity-check-${System.nanoTime()}",
    )
    root.mkdirs()
    val store = PiSessionStore(root)

    val old = java.time.Instant.parse("2024-01-01T00:00:00.000Z").toEpochMilli()
    val mid = java.time.Instant.parse("2024-06-01T00:00:00.000Z").toEpochMilli()
    val new = java.time.Instant.parse("2024-12-01T00:00:00.000Z").toEpochMilli()

    // ---------------------------------------------- 1. 同一个会话，两种布局各一份
    // 引擎平铺写的那份（`PiEngineHost.kt:592` 的 `--session-dir`）是**旧内容**，却带着**更新的
    // mtime**；组目录里那份是同一会话后来继续过的快照（内容更长、mtime 更旧）。两条判据指向相反
    // 的两份，而正确的是内容更新的那份 —— 它拿着的对话更长，`messageCount` 也更多。
    val flatTwolayout = sessionFile(
        root,
        "2026-01-01T00-00-00-000Z_twolayout.jsonl",
        listOf(
            header("twolayout", "2026-01-01T00:00:00.000Z"),
            message("t1", null, "2026-01-01T00:00:01.000Z", "user", "两种布局里的同一个会话", mid + 1_000),
            message("t2", "t1", "2026-01-01T00:00:02.000Z", "assistant", "第一段", mid + 1_001),
        ),
        mtime = new,
    )
    val groupTwolayout = sessionFile(
        java.io.File(root, GROUP),
        "2026-01-01T00-00-00-000Z_twolayout.jsonl",
        listOf(
            header("twolayout", "2026-01-01T00:00:00.000Z"),
            message("t1", null, "2026-01-01T00:00:01.000Z", "user", "两种布局里的同一个会话", mid + 1_000),
            message("t2", "t1", "2026-01-01T00:00:02.000Z", "assistant", "第一段", mid + 1_001),
            message("t3", "t2", "2026-06-01T00:00:03.000Z", "user", "接着说", mid + 5_000),
            message("t4", "t3", "2026-06-01T00:00:04.000Z", "assistant", "第二段", mid + 5_001),
        ),
        mtime = mid,
    )

    // ------------------------------------- 2. 同一个会话，两处字节相同、mtime 不同
    // 一次 `cp` 就会留下这个形状：内容逐字相同，副本的 mtime 比原件新（这里刻意新 10 秒）。
    // 两行在内容上完全并列，这时留下的是**平铺的那份** —— pi 正往它里面追加，`switch_session`
    // 打开的也是它；组目录那份是快照。按 mtime 选会选错这一条。
    val tieLines = listOf(
        header("tie", "2026-02-01T00:00:00.000Z"),
        message("e1", null, "2026-02-01T00:00:01.000Z", "user", "并列的两份", mid + 4_000),
        message("e2", "e1", "2026-02-01T00:00:02.000Z", "assistant", "一样的字节", mid + 4_001),
    )
    val flatTie = sessionFile(root, "2026-02-01T00-00-00-000Z_tie.jsonl", tieLines, mtime = old)
    val groupTie = sessionFile(java.io.File(root, GROUP), "2026-02-01T00-00-00-000Z_tie.jsonl", tieLines, mtime = new + 10_000)

    // ------------------------------- 3. 平铺层里的 `/import` 拷贝（同 id、不同文件名）
    // `PiSessionViewModel.prepareImport` 只挑一个没被占用的名字，**改的是文件名不是 header**，
    // 所以副本带着与原件相同的 `id`。副本是更早的快照（一条消息），但 mtime 更晚。
    val importedOriginal = sessionFile(
        root,
        "2026-03-01T00-00-00-000Z_imported.jsonl",
        listOf(
            header("imported", "2026-03-01T00:00:00.000Z"),
            message("i1", null, "2026-03-01T00:00:01.000Z", "user", "导入过的那段对话", mid + 3_000),
            message("i2", "i1", "2026-03-01T00:00:02.000Z", "assistant", "原件更长", mid + 3_001),
        ),
        mtime = old,
    )
    val importedCopy = sessionFile(
        root,
        "imported-1.jsonl",
        listOf(
            header("imported", "2026-03-01T00:00:00.000Z"),
            message("i1", null, "2026-03-01T00:00:01.000Z", "user", "导入过的那段对话", mid + 500),
        ),
        mtime = new,
    )

    // --------------------------------- 4. **两个不同的会话，标题一模一样**（不许合并）
    // 用户两轮都只打了「继续」，于是两段对话的 `title`/`displayName` 相同、`id` 不同。
    val sameTitleA = sessionFile(
        root,
        "2026-04-01T00-00-00-000Z_same-title-a.jsonl",
        listOf(
            header("same-title-a", "2026-04-01T00:00:00.000Z"),
            message("s1", null, "2026-04-01T00:00:01.000Z", "user", "继续", mid + 2_000),
            message("s2", "s1", "2026-04-01T00:00:02.000Z", "assistant", "第一次继续", mid + 2_001),
        ),
        mtime = mid,
    )
    val sameTitleB = sessionFile(
        root,
        "2026-05-01T00-00-00-000Z_same-title-b.jsonl",
        listOf(
            header("same-title-b", "2026-05-01T00:00:00.000Z"),
            message("u1", null, "2026-05-01T00:00:01.000Z", "user", "继续", mid + 1_000),
            message("u2", "u1", "2026-05-01T00:00:02.000Z", "assistant", "第二次继续", mid + 1_001),
        ),
        mtime = mid,
    )

    kotlinx.coroutines.runBlocking {
        val rows = store.list()

        // 8 个会话文件（flatTwolayout / groupTwolayout / flatTie / groupTie / importedOriginal /
        // importedCopy / sameTitleA / sameTitleB = 8 个文件、5 段对话），旧实现每个文件一行。
        check("8 个文件、5 段对话 → 只有 5 行", rows.size, 5)
        check(
            "每一行的会话身份互不相同",
            rows.map { it.id }.distinct().size,
            rows.size,
        )
        check("每一行的文件都真的存在", rows.all { it.file.isFile }, true)

        // 1. 跨布局的重复：留下内容更长的那份，不是 mtime 更新、也不是「平铺优先」的那份。
        val twolayout = rows.first { it.id == "twolayout" }
        check("跨布局的同一个会话只留一行", rows.count { it.id == "twolayout" }, 1)
        check(
            "留下的是内容更新的那一份",
            twolayout.file.absolutePath,
            groupTwolayout.absolutePath,
        )
        check("它的消息数就是那份更长副本的", twolayout.messageCount, 4)

        // 2. 内容并列：平铺的那份赢，即使副本的 mtime 更新。
        val tie = rows.first { it.id == "tie" }
        check("字节相同、mtime 不同的两份也只留一行", rows.count { it.id == "tie" }, 1)
        check(
            "并列时留下平铺的那份（引擎正在追加的那份），不是 mtime 更新的副本",
            tie.file.absolutePath,
            flatTie.absolutePath,
        )

        // 3. 平铺层里的 `/import` 拷贝：同 id、不同文件名，一样只留一行。
        check("`/import` 的拷贝也只留一行", rows.count { it.id == "imported" }, 1)
        check(
            "留下的是内容更全的原件",
            rows.first { it.id == "imported" }.file.absolutePath,
            importedOriginal.absolutePath,
        )

        // 4. 去重不许看显示名：两段不同的对话共用标题时，两行都要在。
        check("两个不同会话共用同一个标题 → 两行", rows.count { it.displayName == "继续" }, 2)
        check(
            "两行是两个不同的会话身份",
            rows.filter { it.displayName == "继续" }.map { it.id }.sorted(),
            listOf("same-title-a", "same-title-b"),
        )
        check(
            "两行的文件也各自不同",
            rows.filter { it.displayName == "继续" }.map { it.file.absolutePath }.sorted(),
            listOf(sameTitleA.absolutePath, sameTitleB.absolutePath).sorted(),
        )

        // 5. `limit` 数的是**会话**：5 段对话，limit=5 就是 5 行；limit=4 去掉的是活动最旧的
        // 那段对话（same-title-b），不是被某份副本占掉一行。
        check("limit 数会话：5 段对话 → limit=5 给 5 行", store.list(limit = 5).size, 5)
        check(
            "limit=4 去掉的是最旧的那段对话，不是副本占位",
            store.list(limit = 4).map { it.id }.sorted(),
            listOf("imported", "same-title-a", "tie", "twolayout").sorted(),
        )
        check(
            "limit 之后每一行仍然是不同的会话",
            store.list(limit = 4).map { it.id }.distinct().size,
            4,
        )

        // 6. 去重只是**读数**：一个文件都没有被删、也没有被改。
        check(
            "被折叠掉的那几份仍然在磁盘上（store 不删文件）",
            listOf(flatTwolayout.exists(), groupTie.exists(), importedCopy.exists(), sameTitleB.exists()),
            listOf(true, true, true, true),
        )
        check(
            "被折叠掉的那几份内容没被动过",
            groupTie.readText(),
            tieLines.joinToString("\n") + "\n",
        )
    }

    // ------------------- 7. **另一回事**：真的分成几段的对话，不许被去重合并掉
    //
    // 上面全部是「同一段对话在磁盘上留了两份」。用户报的症状还有第二种磁盘形状，它**不是**重复
    // 行：引擎每次启动都新开一个 pi 会话（guest argv 固定为 `--session-dir <agentDir>/sessions`，
    // 没有 `-c`/`--session`，`PiEngineHost.kt:592-596`；「接着上次」是 attach 之后发的一条
    // `switch_session`，由默认关闭的设置 `app.sessions.resumeLast` 守着
    // （`PiSessionViewModel.maybeResumeLastSession`，工作树 `:2517-2520`；
    // `PiSettingsRegistry.kt:846`）。所以一段不断继续的对话会在磁盘上留下**几个不同的会话**
    // （几个不同的 `id`），每个文件的第一条 user 消息就是它自己的标题 —— 用户看到的
    // 「一个对话被切成几段、标题还各不相同」正是这个形状。
    //
    // 这一节钉住的是「store 在这种形状下说的是实话，而且去重不许碰它」：同一 `id` 才是同一段
    // 对话（上面的夹具），不同 `id` 是两段对话，血缘由 pi 自己的 `parentSession` 字段表达，
    // 不是靠标题猜。谁要是把去重做成「标题相同就合并」，这几行会红。
    val segmentRoot = java.io.File(root.parentFile, "pi-session-segments-${System.nanoTime()}")
    segmentRoot.mkdirs()
    val segmentStore = PiSessionStore(segmentRoot)
    val segments = listOf(
        "seg-1" to "帮我做 X",
        "seg-2" to "接着刚才的",
        "seg-3" to "再补一句",
    ).mapIndexed { index, (id, firstMessage) ->
        sessionFile(
            segmentRoot,
            "2026-07-0${index + 1}T00-00-00-000Z_$id.jsonl",
            listOf(
                header(id, "2026-07-0${index + 1}T00:00:00.000Z"),
                message("m$index", null, "2026-07-0${index + 1}T00:00:01.000Z", "user", firstMessage, mid + 7_000 + index * 1_000),
                message("n$index", "m$index", "2026-07-0${index + 1}T00:00:02.000Z", "assistant", "好", mid + 7_001 + index * 1_000),
            ),
            mtime = mid,
        )
    }

    kotlinx.coroutines.runBlocking {
        val rows = segmentStore.list()
        check("分成三段的对话 → 三行（不是三份副本，去重不许合并）", rows.size, 3)
        check(
            "每一段的标题就是它自己文件里的第一条 user 消息",
            rows.sortedBy { it.id }.map { it.displayName },
            listOf("帮我做 X", "接着刚才的", "再补一句"),
        )
        check(
            "三行是三个不同的会话身份",
            rows.map { it.id }.sorted(),
            listOf("seg-1", "seg-2", "seg-3"),
        )
        check("三个文件一个都没被折叠掉", segments.all { it.isFile }, true)
    }
    segmentRoot.deleteRecursively()

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
