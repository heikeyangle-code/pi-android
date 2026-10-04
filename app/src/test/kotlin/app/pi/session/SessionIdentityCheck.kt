package app.pi.session

// 会话列表的**身份**判据。注册在 `tools/run-app-pure-checks.sh` 的 `session-identity`。
//
// 为什么必须钉住：`PiSessionStore.list()` 分别遍历 pi 的两个布局（引擎平铺写的
// `sessions/<ISO>_<id>.jsonl`、pi 默认的 `sessions/--<cwd>--/<ISO>_<id>.jsonl`），而**同一个
// 会话可以在磁盘上留一份以上** —— 组目录里的快照、`/import` 拷进来的副本（`prepareImport` 只换
// 名字、不改 header），以及 pi 在 id 查找漏掉时**用同一个 id 新建**的那一份。
//
// 列表的规则不是「同一个 id 只留一行」，而是**「一份的字节是另一份的前缀才合并」**
// （`PiSessionStore.mergeSameIdRow`）：
//
//  1. **互为前缀（逐字节相同）** ⇒ 一行，按 `laterSessionRow` 的全序挑一份（第 2 组夹具；夹具把
//     mtime 与正确答案**反过来**，所以「按 mtime 选」会红）。
//  2. **一份是另一份的前缀且更短** ⇒ 一行，留**长的那份**（第 1、3 组夹具：快照只能是活文件的前缀，
//     因为追加只在文件末尾长出来）—— 「相等才算重复」的更简单实现会在这里红。
//  3. **不互为前缀** ⇒ **两行都留**（第 3b 组夹具）：这是**一段对话的两个半截**，任何「只留一行」
//     的规则都必然藏掉用户的一半（「重装完只剩后面这一半截」）。官方 pi 在这里也是一个 `.jsonl`
//     一行、完全不按 id 去重（`core/session-manager.ts:941-967`）。
//  4. **分组的键仍然只是身份**：两个真正不同的会话可以有同一个标题（用户两轮都只打「继续」）——
//     那是两段对话，必须留两行（第 4 组夹具）。按标题去重会把其中一段**藏起来**，比重复更糟。
//  5. **`limit` 数的是行**（= 一段对话一行，真的劈开的那一段是两行）：去重若发生在截断之后，
//     一份快照就会占掉一行、把一段真实对话挤出屏幕（第 5 组）。
//
// 以及两件**反向**的事：第 7 节要求「几个不同 `id` 的段落」各行都在（去重不许按标题合并），
// 第 8 节直接钉住那条前缀判据本身（含「等长但不同字节 ⇒ 不合并」和「读不出来 ⇒ 不合并」这两个
// 边界）。
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
    // 所以副本带着与原件相同的 `id`。它是**逐字节**的拷贝（`importFromJsonl` 就是原样拷，
    // `dist/core/agent-session-runtime.ts:359-413`），之后原件继续追加 ⇒ **副本是原件的前缀**。
    // 夹具直接把头部那两行拿来共用，免得「这是一份拷贝」变成靠巧合成立。副本的 mtime 更晚 ——
    // 这正是「别拿 mtime 决定」的那一条。
    val importedHead = listOf(
        header("imported", "2026-03-01T00:00:00.000Z"),
        message("i1", null, "2026-03-01T00:00:01.000Z", "user", "导入过的那段对话", mid + 3_000),
    )
    val importedOriginal = sessionFile(
        root,
        "2026-03-01T00-00-00-000Z_imported.jsonl",
        importedHead + message("i2", "i1", "2026-03-01T00:00:02.000Z", "assistant", "原件更长", mid + 3_001),
        mtime = old,
    )
    val importedCopy = sessionFile(root, "imported-1.jsonl", importedHead, mtime = new)

    // ------------------------ 3b. **真被劈开的那一段**：同 id、互不为前缀 ⇒ 两行都在
    // pi 的 `--session-id` 在 `findById` 漏掉时（id ∧ header 的 `cwd` 等于引擎进程的 cwd ∧
    // 文件是 `--session-dir` 那一层的直接子文件，三条里漏一条）会用**同一个 id 新建**一个文件
    // （`dist/main.js:344-351`、`dist/core/session-manager.js:1421-1441`），此后每条消息都写进
    // 那个新文件。于是原件是**前半段**、孪生文件是**后半段**，两份之间没有任何重叠，而且孪生文件的
    // header 是**另一次**创建的（时间戳不同）—— 所以它们从第一块字节起就不同。
    //
    // 这不是「同一段对话在磁盘上留了两份」，是「一段对话被切成了两半」，所以**两行都要在**：
    // 只留一行必然藏掉用户的一半（留新的就丢了开头，留旧的就丢了最近说的），而用户报的正是
    // 「重装完只剩后面这一半截、名字也改成半截开头的名字」。两行的名字各自取自自己文件里的第一条
    // user 消息 —— 这正是用户看到的那个「名字变成半截开头那条」。
    val splitFirstLines = listOf(
        header("split", "2026-08-01T00:00:00.000Z"),
        message("p1", null, "2026-08-01T00:00:01.000Z", "user", "前半截的第一句", mid + 8_000),
        message("p2", "p1", "2026-08-01T00:00:02.000Z", "assistant", "前半截的回答", mid + 8_001),
    )
    val splitSecondLines = listOf(
        header("split", "2026-09-01T00:00:00.000Z"),
        message("q1", null, "2026-09-01T00:00:01.000Z", "user", "重启后接着说的第一句", mid + 9_000),
        message("q2", "q1", "2026-09-01T00:00:02.000Z", "assistant", "后半截的回答", mid + 9_001),
    )
    val splitFirstHalf = sessionFile(
        root,
        "2026-08-01T00-00-00-000Z_split.jsonl",
        splitFirstLines,
        mtime = old,
    )
    val splitSecondHalf = sessionFile(
        root,
        "2026-09-01T00-00-00-000Z_split.jsonl",
        splitSecondLines,
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

        // 10 个会话文件（flatTwolayout / groupTwolayout / flatTie / groupTie / importedOriginal /
        // importedCopy / splitFirstHalf / splitSecondHalf / sameTitleA / sameTitleB），7 行 ——
        // 旧实现（每个文件一行）会给出 10 行，「同 id 只留一行」的实现会给出 6 行（把劈开的那段
        // 藏掉一半）。
        check("10 个文件 → 7 行（两对前缀合并、一对真劈开的两行都在）", rows.size, 7)
        check(
            "除了真劈开的那一段，每一行的会话身份互不相同",
            rows.filter { it.id != "split" }.map { it.id }.distinct().size,
            rows.count { it.id != "split" },
        )
        check(
            "每一行指向的文件互不相同（同 id 的两行也是两个文件）",
            rows.map { it.file.absolutePath }.distinct().size,
            rows.size,
        )
        check("每一行的文件都真的存在", rows.all { it.file.isFile }, true)

        // 1. 跨布局的重复：两份是**前缀**关系（组目录那份更长），留下长的那份 —— 不是 mtime 更新
        // 的那份，也不是「平铺优先」的那份。
        val twolayout = rows.first { it.id == "twolayout" }
        check("跨布局的同一个会话只留一行", rows.count { it.id == "twolayout" }, 1)
        check(
            "留下的是内容更新的那一份",
            twolayout.file.absolutePath,
            groupTwolayout.absolutePath,
        )
        check("它的消息数就是那份更长副本的", twolayout.messageCount, 4)

        // 2. 内容并列（逐字节相同）：平铺的那份赢，即使副本的 mtime 更新。
        val tie = rows.first { it.id == "tie" }
        check("字节相同、mtime 不同的两份也只留一行", rows.count { it.id == "tie" }, 1)
        check(
            "并列时留下平铺的那份（引擎正在追加的那份），不是 mtime 更新的副本",
            tie.file.absolutePath,
            flatTie.absolutePath,
        )

        // 3. 平铺层里的 `/import` 拷贝：同 id、不同文件名，是原件的**前缀** ⇒ 只留一行，留原件。
        check("`/import` 的拷贝也只留一行", rows.count { it.id == "imported" }, 1)
        check(
            "留下的是内容更全的原件",
            rows.first { it.id == "imported" }.file.absolutePath,
            importedOriginal.absolutePath,
        )

        // 3b. **真被劈开的那一段**：同 id、互不为前缀 ⇒ 两行都在，名字各取自己文件的第一条
        // user 消息。（这正是用户看到的形状：列表里多出一条以「半截开头那句」命名的对话。）
        check("真劈开的两半 → 两行（谁也不许藏掉谁）", rows.count { it.id == "split" }, 2)
        check(
            "两行指向的是劈开的那两个文件",
            rows.filter { it.id == "split" }.map { it.file.absolutePath }.sorted(),
            listOf(splitFirstHalf.absolutePath, splitSecondHalf.absolutePath).sorted(),
        )
        check(
            "两行的名字各取自己文件里的第一条 user 消息",
            rows.filter { it.id == "split" }.map { it.displayName }.sorted(),
            listOf("前半截的第一句", "重启后接着说的第一句").sorted(),
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

        // 5. `limit` 数的是**行**（一段对话一行，真劈开的那一段是两行）：7 行，limit=7 就是 7 行；
        // limit=6 去掉的是活动最旧的那一行（same-title-b），而不是让某份快照占掉一个位置。
        check("limit 数行：7 行 → limit=7 给 7 行", store.list(limit = 7).size, 7)
        check(
            "limit=6 去掉的是活动最旧的那一行，不是快照占位",
            store.list(limit = 6).map { it.title }.sorted(),
            listOf("两种布局里的同一个会话", "并列的两份", "导入过的那段对话", "前半截的第一句", "重启后接着说的第一句", "继续")
                .sorted(),
        )
        check(
            "limit 之后那一段劈开的对话仍然是两行（劈开本身不许被截断掉一半）",
            store.list(limit = 6).count { it.id == "split" },
            2,
        )

        // 6. 合并/并列都只是**读数**：一个文件都没有被删、也没有被改。
        check(
            "被合并掉的那几份、以及劈开的两半，都仍然在磁盘上（store 不删文件）",
            listOf(
                flatTwolayout.exists(),
                groupTie.exists(),
                importedCopy.exists(),
                splitFirstHalf.exists(),
                splitSecondHalf.exists(),
                sameTitleB.exists(),
            ),
            listOf(true, true, true, true, true, true),
        )
        check(
            "被合并掉的那几份内容没被动过",
            groupTie.readText(),
            tieLines.joinToString("\n") + "\n",
        )
        check(
            "劈开的两半内容也没被动过",
            listOf(splitFirstHalf.readText(), splitSecondHalf.readText()),
            listOf(
                splitFirstLines.joinToString("\n") + "\n",
                splitSecondLines.joinToString("\n") + "\n",
            ),
        )
    }

    // ------------------- 7. **另一回事**：真的分成几段的对话，不许被去重合并掉
    //
    // 第 3b 组的劈开是「同一个 `id`、两份文件」，这一节是「**几个不同的 `id`**」：一段不断继续的
    // 对话在早期版本的 App 里会在磁盘上留下几个不同的会话（每次引擎启动都新开一个，几个不同的
    // `id`、标题各不相同）。这两形状都必须**如实分行**，而且谁都不许靠标题合并。
    // （今天引擎已经由 argv 钉住同一段对话 —— `--session-id`，或在 pi 的 `findById` 找不到它时
    // 换成 `--session <path>`，见 `restartEngine` / `SessionResume.kt` —— 所以第 3b 组那种「同一个
    // `id` 的两个半截」才是当前会出现的形状；这一节的形状仍要如实显示：老会话还在硬盘上。）
    //
    // 这一节钉住的是「store 说的是实话，而且合并判据不许看标题」：血缘由 pi 自己的 `parentSession`
    // 字段表达，不是靠标题猜。谁要是把合并做成「标题相同就合并」，这几行会红。
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

    // ------------------- 8. 那条前缀判据本身（含两个边界）
    //
    // 列表的合并规则全压在 `prefixRelationOf` 上，所以直接把它按四个答案各测一遍 —— 这一节不碰
    // `list()`，因此它红了就说明是判据本身，而不是分组的接线。第三个夹具**等长但内容不同**：
    // 这一格是「相等 / 前缀」两种实现唯一会给出不同答案的地方（等长时前缀只可能是相等），
    // 所以它必须回答 Disjoint 而不是 Same，否则一份被改过的同 id 文件会被悄悄合并掉。
    val pairRoot = java.io.File(root.parentFile, "pi-session-pairs-${System.nanoTime()}")
    pairRoot.mkdirs()
    val pairLines = listOf(
        header("pair", "2026-10-01T00:00:00.000Z"),
        message("r1", null, "2026-10-01T00:00:01.000Z", "user", "同一段对话的开头", mid + 10_000),
    )
    val pairShort = sessionFile(pairRoot, "short.jsonl", pairLines, mtime = old)
    val pairLong = sessionFile(
        pairRoot,
        "long.jsonl",
        pairLines + message("r2", "r1", "2026-10-01T00:00:02.000Z", "assistant", "后来长出来的", mid + 10_001),
        mtime = new,
    )
    val equalLengthDifferent = sessionFile(
        pairRoot,
        "equal.jsonl",
        listOf(
            header("pair", "2026-10-01T00:00:00.000Z"),
            message("r1", null, "2026-10-01T00:00:01.000Z", "user", "同一段对话的开头", mid + 10_999),
        ),
        mtime = old,
    )
    check("短的是长的前缀 → AInB", prefixRelationOf(pairShort, pairLong), PrefixRelation.AInB)
    check("长的看短的那份 → BInA（方向是判据的一部分）", prefixRelationOf(pairLong, pairShort), PrefixRelation.BInA)
    check("同一份文件自己 → Same", prefixRelationOf(pairShort, pairShort), PrefixRelation.Same)
    check(
        "等长但内容不同 → Disjoint（这一格「相等」和「前缀」才会给出不同答案）",
        prefixRelationOf(pairShort, equalLengthDifferent),
        PrefixRelation.Disjoint,
    )
    check(
        "劈开的两半 → Disjoint",
        prefixRelationOf(splitFirstHalf, splitSecondHalf),
        PrefixRelation.Disjoint,
    )
    check(
        "读不出来（文件不存在）→ Disjoint：证明不了是前缀就不许合并",
        prefixRelationOf(pairShort, java.io.File(pairRoot, "gone.jsonl")),
        PrefixRelation.Disjoint,
    )
    pairRoot.deleteRecursively()

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
