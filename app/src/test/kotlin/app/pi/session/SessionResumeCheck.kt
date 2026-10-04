package app.pi.session

import app.pi.rpc.PiLaunchOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 「引擎启动时靠什么认回这段对话」的判定 —— `SessionResume.kt` 的 harness。
//
// 它钉的是**用户报的那条现象**的形状：一段对话被劈成两个文件、两个文件顶着同一个 id，
// App 的列表只显示其中一个（用户的原话：「每次都重装完，之前的一些长对话被切的光剩底部这些了」
// 「名字也改成半截开头的名字了」「转录顶部没有『加载更早』那一行」「每次出问题的都是刚聊过的
// 对话」）。劈开的那一刀在 pi 那边：`--session-id` 的 id 查找带上 cwd 过滤，找不到就用**同一个 id**
// 新建一个文件（`SessionResume.kt` 的 KDoc 有 `dist/main.js:344-351` 与
// `dist/core/session-manager.js:1421-1441`）。
//
// 这个 harness 要钉住的是修法的**边界**，而不是它的实现：
//
//  1. **能发 id 就发 id。** 只有「文件是一段真会话 ∧ 它在平铺层 ∧ header 的 cwd 与引擎 cwd
//     逐字符相等 ∧ 那个 id 在那一层里**只有这一份**」时才发 `--session-id` —— 那也正是 pi 自己
//     一定且唯一会命中的那一格，所以正常用户的 argv 一个字都没变。少一条就换路径
//     （第 6/6b/7/8/9/11 组）。
//  2. **不许拿一个不能担保的文件去开。** header 读不出来、或者 header 的 id 不是我们要的那段对话，
//     一律留在 id 形式 —— 拿错文件去开等于把用户换到另一段对话里，比劈开更糟（第 4/5 组）。
//  3. **「文件还没有」是正常状态，不是错误。** pi 在第一条 assistant 消息落盘前不建文件，
//     而 id 形式对它的行为正是我们要的（用同一个 id 建），所以那一格必须留在 id（第 3 组）。
//  4. **相等就是 pi 的相等，不是 realpath。** `/data/data/x` 与 `/data/user/0/x` 指向同一个目录、
//     但是两个字符串 —— 那正是真机上出事的那一格（proroot 的 `getcwd()` 泄漏宿主拼写，
//     `docs/proroot-mode-audit.md:20-48`），必须判成「不相等」（第 7 组），
//     而只差结尾斜杠/重复斜杠/`.` 段的两种拼写是 pi 眼里的同一个路径，必须判成「相等」（第 10 组）。
//  5. **歧义不猜。** `findById` 取的是 `readdirSync` 顺序里的第一个命中，**不是** mtime 最新的
//     那一份 —— 所以同一个 id 有两份时 `--session-id` 可能给出**另一份**（用户重启后看到另一个
//     半截，新消息也写进那一份）。那两格（歧义 + 能担保）必须换成路径：第 6b/14c 组问判定，
//     第 16 组问「第几个候选」是怎么数出来的（含「cwd 不同 / 无 cwd / 读不出来都不算候选」）。
//
// 第 13 组把五件可独立真假的事穷举成 **32 格**，并钉死两个数字：pi 一定且唯一命中的只有 **1 格**
// （留在 id 形式、argv 逐字不变），「App 能担保而 pi 会找不到或给出另一份」的 **7 格**全部换成
// 路径，其余 25 格留在 id 形式。
//
// Android-free：只用 stdlib + kotlinx.serialization（构造夹具 header），所以
// `tools/run-app-pure-checks.sh` 那套 kotlinc 配方能跑它。

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val ID = "aaaa1111"

/** 引擎**即将**启动时的 guest cwd（`GuestWorkspacePath` 的拼写）。 */
private const val LAUNCH_CWD = "/workspace/pi/workspaces/workspace-1"

/** 会话文件的 guest 路径（`meta.sessionFile` 的形状）。 */
private const val GUEST_PATH =
    "/root/.pi/agent/sessions/2026-03-01T00-00-00-000Z_$ID.jsonl"

/** [resolveTarget] 的全部入参都有默认值，好让每一组只写出它真正在量那一个。 */
private fun target(
    requestedId: String? = ID,
    guestPath: String? = GUEST_PATH,
    fileExists: Boolean = true,
    flatChildOfSessionDir: Boolean = true,
    headerId: String? = ID,
    headerCwd: String? = LAUNCH_CWD,
    launchCwd: String? = LAUNCH_CWD,
    idResolvesUniquely: Boolean = true,
): ResumeTarget = resumeTargetFor(
    requestedId = requestedId,
    guestPath = guestPath,
    fileExists = fileExists,
    flatChildOfSessionDir = flatChildOfSessionDir,
    headerId = headerId,
    headerCwd = headerCwd,
    launchCwd = launchCwd,
    idResolvesUniquely = idResolvesUniquely,
)

fun main() {
    // ---------------------------------------------- 1/2. 没有意见，或说不出文件
    check("1 没有会话 id（冷启动）→ 没有意见，交给 -c", target(requestedId = null), ResumeTarget.None)
    check("1b 空白 id 与 null 同类", target(requestedId = "   "), ResumeTarget.None)
    check("2 说不出文件 → 只能按 id", target(guestPath = null), ResumeTarget.ById(ID))
    check("2b 空白文件路径与 null 同类", target(guestPath = "  "), ResumeTarget.ById(ID))

    // ---------------------------------------------- 3. 文件还没有（正常状态）
    // pi 在第一条 assistant 消息落盘前不建文件，而 `--session-id` 对「找不到」的行为正是我们要的：
    // 用同一个 id 建（`dist/main.js:344-351`）。换成路径反而会让 pi 在那个显式路径上另起一个新 id。
    check("3 文件还不存在 → 留在 id 形式", target(fileExists = false), ResumeTarget.ById(ID))

    // ---------------------------------------------- 4/5. 不许拿不能担保的文件去开
    check("4 header 读不出来 → 留在 id 形式", target(headerId = null), ResumeTarget.ById(ID))
    check("5 header 的 id 是另一段对话 → 留在 id 形式", target(headerId = "bbbb2222"), ResumeTarget.ById(ID))

    // ---------------------------------------------- 6. 正常情况：argv 一个字都不变
    check(
        "6 平铺层 + cwd 相同 + 那个 id 只有这一份（pi 一定且唯一命中）→ 照旧发 --session-id",
        target(),
        ResumeTarget.ById(ID),
    )

    // ---------------------------------------------- 6b. 同一个 id 有两份 ⇒ 歧义，不猜
    // `findById` 取的是 `readdirSync` 顺序里的**第一个**命中，而那个顺序不是 mtime —— 所以
    // 「两半都躺在平铺层」时 `--session-id` 可能给出**另一份**：用户重启后看到另一个半截，新消息
    // 也写进那一份，而且完全不可预测。歧义时把文件交出去，才是确定的。
    check(
        "6b 那个 id 在平铺层里有两份（pi 会给出哪一份不可预测）→ 换成路径",
        target(idResolvesUniquely = false),
        ResumeTarget.ByPath(GUEST_PATH),
    )
    check(
        "6c 歧义 + 说不出文件 → 只能按 id（没有路径可交）",
        target(guestPath = null, idResolvesUniquely = false),
        ResumeTarget.ById(ID),
    )
    check(
        "6d 歧义 + 文件不在平铺层 → 换成路径（flat 判据本身已经定了）",
        target(flatChildOfSessionDir = false, idResolvesUniquely = false),
        ResumeTarget.ByPath(GUEST_PATH),
    )

    // ---------------------------------------------- 7. 真机上出事的那一格
    // proroot 时期写下的 header 是宿主拼写；它与 guest 拼写指向同一个存在的目录，pi 的
    // `resolvePath` 不做 realpath，于是字符串不等 ⇒ `findById` 跳过 ⇒ 同一个 id 新建一个文件。
    check(
        "7 cwd 是同一个目录的宿主拼写 → 换成路径（不许再让 pi 按 id 找）",
        target(headerCwd = "/data/data/app.pi/files/pi/runtime/x/rootfs/workspace/pi/workspaces/workspace-1"),
        ResumeTarget.ByPath(GUEST_PATH),
    )
    check(
        "7b cwd 是另一个工作区（真实存在、但不同）→ 换成路径",
        target(headerCwd = "/workspace/pi/workspaces/workspace-2"),
        ResumeTarget.ByPath(GUEST_PATH),
    )
    check("7c cwd 是被删掉的旧路径 → 也换路径（至少不许静默劈开）", target(headerCwd = "/old/gone"), ResumeTarget.ByPath(GUEST_PATH))
    check("7d 引擎 cwd 报不出来（null）→ 换路径", target(launchCwd = null), ResumeTarget.ByPath(GUEST_PATH))

    // ---------------------------------------------- 8. header 没有 cwd
    // `sessionCwdMatches` 要求 cwd 非空（`session-manager.js:441-443`），所以这一格 pi 必然跳过。
    check("8 header 没有 cwd → 换成路径", target(headerCwd = null), ResumeTarget.ByPath(GUEST_PATH))
    check("8b header 的 cwd 是空串 → 换成路径", target(headerCwd = ""), ResumeTarget.ByPath(GUEST_PATH))

    // ---------------------------------------------- 9. 文件不在平铺层
    // `findById` 只 `readdirSync(dir)` 一次，不看 `--<cwd>--` 分组目录 —— 而 App 的读取器**两种都读**
    // （`PiSessionStore.list`）。这个不对称本身就是一条劈开的通道。
    check(
        "9 文件在分组目录里（pi 不看那一层）→ 换成路径",
        target(flatChildOfSessionDir = false),
        ResumeTarget.ByPath(GUEST_PATH),
    )

    // ---------------------------------------------- 10. pi 眼里的「同一个路径」
    // `resolvePath` = `path.resolve`：结尾斜杠、重复斜杠、`.` 段都会被吃掉，`..` 会回退一层。
    // 这几种写法在 pi 那边**相等**，所以不许在这里换成路径（否则就是无谓地改 argv）。
    check("10 只差结尾斜杠 → 仍按 id", target(launchCwd = "$LAUNCH_CWD/"), ResumeTarget.ById(ID))
    check("10b 重复斜杠 → 仍按 id", target(launchCwd = "/workspace//pi/workspaces/workspace-1"), ResumeTarget.ById(ID))
    check("10c `.` 段 → 仍按 id", target(headerCwd = "/workspace/./pi/workspaces/workspace-1"), ResumeTarget.ById(ID))
    check("10d `..` 回退到同一个路径 → 仍按 id", target(headerCwd = "/workspace/pi/workspaces/other/../workspace-1"), ResumeTarget.ById(ID))
    check("10e 尾部空格不是同一条路径（pi 比原样字符串）→ 换成路径", target(headerCwd = "$LAUNCH_CWD "), ResumeTarget.ByPath(GUEST_PATH))

    // ---------------------------------------------- 11. 相对 / 非法 cwd
    check("11 相对 cwd → pi 那边必然不相等 ⇒ 换成路径", target(headerCwd = "pi/workspaces/workspace-1"), ResumeTarget.ByPath(GUEST_PATH))
    check("11b `~` 开头的 cwd → 换成路径", target(headerCwd = "~/workspace"), ResumeTarget.ByPath(GUEST_PATH))

    // ---------------------------------------------- 12. 归一化本身
    check("12 根路径", normalizeGuestPath("/"), "/")
    check("12b `..` 退到底就停住（path.resolve 的语义）", normalizeGuestPath("/.."), "/")
    check("12c 多个 `..` 一起退", normalizeGuestPath("/a/b/c/../../d"), "/a/d")
    check("12d 相对路径 → null（不是「相等」）", normalizeGuestPath("a/b"), null)
    check("12e 空 → null", normalizeGuestPath(""), null)
    check("12f null → null", normalizeGuestPath(null), null)
    check("12g 不做 realpath：`/data/data/x` 与 `/data/user/0/x` 是两条路径", normalizeGuestPath("/data/data/x") == normalizeGuestPath("/data/user/0/x"), false)

    // ---------------------------------------------- 13. 规则本身（穷举，把「哪一格换」钉成等式）
    //
    // 五件可独立真假的事：文件在不在、在不在平铺层、header 的 id 对不对、cwd 等不等、
    // 那个 id 在那一层里**是不是只有这一份**（32 格）。两条互逆的断言，合起来就是这条修法的
    // 全部内容：
    //
    //  13a **pi 一定且唯一命中的那一格必须留在 id 形式**（argv 一个字都不变）——
    //      「文件在 ∧ 平铺层 ∧ id 对 ∧ cwd 相等 ∧ 唯一」；
    //  13b **「App 能为这个文件担保（文件在 ∧ header 的 id 对）而 pi 会找不到、或会给出另一份」
    //      的每一格必须换成路径** —— 也就是那一刀（和那次不可预测的命中）落下的所有格子。
    //
    // 其余两格（文件不在、header 的 id 不是这一段对话）本来就留在 id 形式，那是**今天的行为**：
    // 前者是 pi 的文档化语义（用同一个 id 建），后者 App 说不清那个文件是谁的，不许拿它去开。
    val flags = listOf(true, false)
    var byId = 0
    var byPath = 0
    var piResolves = 0
    var vouchedButBroken = 0
    var wrongAnswer = 0
    for (exists in flags) {
        for (flat in flags) {
            for (idOk in flags) {
                for (cwdOk in flags) {
                    for (unique in flags) {
                        val answer = resumeTargetFor(
                            requestedId = ID,
                            guestPath = GUEST_PATH,
                            fileExists = exists,
                            flatChildOfSessionDir = flat,
                            headerId = if (idOk) ID else "bbbb2222",
                            headerCwd = if (cwdOk) LAUNCH_CWD else "/workspace/pi/workspaces/workspace-2",
                            launchCwd = LAUNCH_CWD,
                            idResolvesUniquely = unique,
                        )
                        val resolves = exists && flat && idOk && cwdOk && unique
                        // App 能担保这个文件（它读过 header、id 对），但 pi 的查找不保证给出它。
                        val broken = exists && idOk && !(flat && cwdOk && unique)
                        if (resolves) piResolves++
                        if (broken) vouchedButBroken++
                        when (answer) {
                            is ResumeTarget.ById -> {
                                byId++
                                // 唯一命中的必须按 id；说不清文件的两格也按 id（今天的行为）。
                                if (!resolves && !(!exists || !idOk)) wrongAnswer++
                            }
                            is ResumeTarget.ByPath -> {
                                byPath++
                                if (!broken) wrongAnswer++
                            }
                            ResumeTarget.None -> wrongAnswer++
                        }
                    }
                }
            }
        }
    }
    check(
        "13 32 格里 pi 一定且唯一命中的恰好是「文件在 ∧ 平铺层 ∧ id 对 ∧ cwd 相等 ∧ 唯一」这一格",
        piResolves,
        1,
    )
    check("13b 而 App 能担保、pi 又会找不到或给出另一份的格子有 7 个", vouchedButBroken, 7)
    check("13c 那 7 格全部换成了路径（一格都不许漏）", byPath, 7)
    check("13d 其余 25 格留在 id 形式（正常用户的 argv 一个字都没变）", byId, 25)
    check("13e 没有一格落在第三条路上", wrongAnswer, 0)
    check("13f 三档都算得出来（32 格全过了一遍）", byId + byPath, 32)

    // ---------------------------------------------- 14. 用户报的那条形状的起步动作
    //
    // 「刚聊过的对话」＋「重启引擎/切换运行时/重装」：App 手上的 id 与文件都是那一段对话的，
    // 而那一刀正是在「cwd 拼写变了」的那一格落下的。断言这个判定给出的答案**不是** id 形式 ——
    // 也就是这一格现在不会再产生第二个同 id 文件。
    val split = resumeTargetFor(
        requestedId = ID,
        guestPath = GUEST_PATH,
        fileExists = true,
        flatChildOfSessionDir = true,
        headerId = ID,
        headerCwd = "/data/user/0/app.pi/files/pi/workspaces/workspace-1",
        launchCwd = LAUNCH_CWD,
        // 拼写变了的那一格连 cwd 都对不上（也就不是 pi 的候选），唯一性在这里不是理由；
        // 给 `true` 是为了让这一条只量 cwd 那一格。
        idResolvesUniquely = true,
    )
    check("14 拼写变了的那一格不再走 id（否则 pi 会再劈一个同 id 文件）", split, ResumeTarget.ByPath(GUEST_PATH))
    check("14b 而它交出去的正是那一段对话自己的文件", (split as? ResumeTarget.ByPath)?.guestPath, GUEST_PATH)

    // 14c. 「两半都躺在平铺层」的形状（这一轮新确定的那一条）：cwd 一样、文件也在平铺层，但那个 id
    // 有**两份** —— `findById` 会按 `readdirSync` 顺序给出其中一份，不可预测，所以必须走路径。
    val ambiguous = resumeTargetFor(
        requestedId = ID,
        guestPath = GUEST_PATH,
        fileExists = true,
        flatChildOfSessionDir = true,
        headerId = ID,
        headerCwd = LAUNCH_CWD,
        launchCwd = LAUNCH_CWD,
        idResolvesUniquely = false,
    )
    check("14c 同 id 两份且都在平铺层 → 换成路径（歧义不猜）", ambiguous, ResumeTarget.ByPath(GUEST_PATH))

    // ---------------------------------------------- 15. argv 的拼法
    //
    // 这一节是「正常情况逐字节不变」的自证，也是「换路径时 pi 真的能收到那个路径」的自证：
    //   * 两个会话标志都只能发**空格形式**（`cli/args.ts:89`、`:92` 只认裸标志取下一个 token；
    //     `=` 形式会掉进未知标志分支被交给扩展 ⇒ 值消失、pi 静默开一个新会话）；
    //   * id 形式今天渲染成 `" --session-id " + 值`，这里要求它一个字都没变；
    //   * 路径形式同理，且路径里有空格时要引起来（guest 命令走 `bash -lc`）。
    check(
        "15 正常情况（cwd 相同）的 argv 与今天逐字节相同",
        PiLaunchOptions(continueSessionId = ID).commandLineSuffix(),
        " --session-id $ID",
    )
    check(
        "15b 换成路径时发空格形式的 `--session <path>`",
        PiLaunchOptions(resumeSessionPath = GUEST_PATH).commandLineSuffix(),
        " --session $GUEST_PATH",
    )
    check(
        "15c 两个都给时路径赢（pi 先看 `parsed.session`，见 main.ts:310-345）",
        PiLaunchOptions(continueSessionId = ID, resumeSessionPath = GUEST_PATH).commandLineSuffix(),
        " --session $GUEST_PATH",
    )
    check(
        "15d 路径里有空格就引起来（否则 bash 会把它拆成两个 token）",
        PiLaunchOptions(resumeSessionPath = "/root/.pi/agent/sessions/a b.jsonl").commandLineSuffix(),
        " --session '/root/.pi/agent/sessions/a b.jsonl'",
    )
    check(
        "15e 一个都没有、而 continueMostRecent 开着 → `-c`",
        PiLaunchOptions(continueMostRecent = true).commandLineSuffix(),
        " -c",
    )
    check(
        "15f 两个标志都不许走 `=` 形式（pi 会当未知标志吞掉）",
        listOf(
            PiLaunchOptions(continueSessionId = ID).commandLineSuffix().contains("--session-id="),
            PiLaunchOptions(resumeSessionPath = GUEST_PATH).commandLineSuffix().contains("--session="),
        ),
        listOf(false, false),
    )
    check(
        "15g 路径赢过 `-c`（精确的那个优先于启发式）",
        PiLaunchOptions(resumeSessionPath = GUEST_PATH, continueMostRecent = true).commandLineSuffix(),
        " --session $GUEST_PATH",
    )

    // ---------------------------------------------- 16. 「那个 id 在那一层里有几份」怎么数
    //
    // 这一节量 [countIdLookupCandidates] —— 它是 `idResolvesUniquely` 的来源，而它**必须与 pi 的
    // `findById` 同款**：只这一层（不进 `--<cwd>--` 分组目录）、只看 `.jsonl`、只认 header 的
    // `id` 相等 ∧ `cwd` 与引擎 cwd 按 pi 的规则相等。数错了就会改变选路 —— 少算一个候选就继续发
    // `--session-id`，可能落到**另一份**上（正是这一轮要确定性化的那件事）。
    //
    // 用**真文件**（函数自己 `listFiles`），header 由调用方给 —— 这正是 ViewModel 传进去的那个
    // lambda 的形状（`SessionFileReader.readHeader`），所以这里不碰 `SessionFileReader`。
    val pairDir = java.io.File(
        System.getProperty("java.io.tmpdir"),
        "pi-session-resume-check-${System.nanoTime()}",
    )
    pairDir.mkdirs()
    fun headerOf(id: String?, cwd: String?): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("session"))
        if (id != null) put("id", JsonPrimitive(id))
        if (cwd != null) put("cwd", JsonPrimitive(cwd))
    }
    fun fileIn(name: String): java.io.File = java.io.File(pairDir, name).also { it.writeText("{}\n") }
    val mine = fileIn("2026-03-01T00-00-00-000Z_$ID.jsonl")
    val twin = fileIn("2026-10-04T05-22-43-234Z_$ID.jsonl")
    val otherId = fileIn("2026-03-02T00-00-00-000Z_bbbb2222.jsonl")
    val otherWorkspace = fileIn("2026-03-03T00-00-00-000Z_ccc33333.jsonl")
    val notJsonl = fileIn("notes.txt")
    val unreadable = fileIn("2026-03-04T00-00-00-000Z_dddd4444.jsonl")
    // 同名但是**目录**：`findById` 那边靠 `readHeader` 失败挡掉，这里靠 `isFile` 挡掉。
    val directory = java.io.File(pairDir, "2026-03-05T00-00-00-000Z_eeee5555.jsonl").also { it.mkdirs() }

    val base = mapOf(
        mine to headerOf(ID, LAUNCH_CWD),
        otherId to headerOf("bbbb2222", LAUNCH_CWD),
        otherWorkspace to headerOf("ccc33333", LAUNCH_CWD),
        notJsonl to headerOf(ID, LAUNCH_CWD),
        unreadable to headerOf(null, null),
        directory to headerOf(ID, LAUNCH_CWD),
    )
    fun count(extra: Map<java.io.File, JsonObject?> = emptyMap()): Int =
        countIdLookupCandidates(pairDir, ID, LAUNCH_CWD) { base[it] ?: extra[it] }

    check("16 只有我们自己那一份 ⇒ 1（别的 id / 别的文件名 / 目录都不算）", count(), 1)
    check("16b 同一个 id 的第二份（孪生）⇒ 2（歧义：调用方会换路径）", count(mapOf(twin to headerOf(ID, LAUNCH_CWD))), 2)
    check(
        "16c 同一 id 但 cwd 是别的工作区 ⇒ 仍算 1（pi 的 cwd 过滤把它排除在外）",
        count(mapOf(fileIn("2026-03-06T00-00-00-000Z_ffff6666.jsonl") to headerOf(ID, "/workspace/pi/workspaces/workspace-2"))),
        1,
    )
    check(
        "16d header 没有 cwd ⇒ 仍算 1（`sessionCwdMatches` 要求 cwd 非空）",
        count(mapOf(fileIn("2026-03-07T00-00-00-000Z_7777.jsonl") to headerOf(ID, null))),
        1,
    )
    check(
        "16e header 的 cwd 是空串 ⇒ 仍算 1",
        count(mapOf(fileIn("2026-03-08T00-00-00-000Z_8888.jsonl") to headerOf(ID, ""))),
        1,
    )
    check(
        "16f 读不出来的 header ⇒ 不算候选（少算只会更偏向路径形式，那是安全的一边）",
        count(mapOf(fileIn("2026-03-09T00-00-00-000Z_9999.jsonl") to null)),
        1,
    )
    check(
        "16g 目录不存在 ⇒ 0（不是「算出来有歧义」，是「问不出来」）",
        countIdLookupCandidates(java.io.File(pairDir, "gone"), ID, LAUNCH_CWD) { null },
        0,
    )
    check("16h 直接问判据：id 对 + cwd 对 ⇒ 命中", isIdLookupCandidate(headerOf(ID, LAUNCH_CWD), ID, LAUNCH_CWD), true)
    check("16i id 对但 cwd 不同 ⇒ 不算命中", isIdLookupCandidate(headerOf(ID, "/elsewhere"), ID, LAUNCH_CWD), false)
    check("16j header 为 null ⇒ 不算命中", isIdLookupCandidate(null, ID, LAUNCH_CWD), false)
    check("16k 引擎 cwd 问不出来（null）⇒ 不算命中（`resolvePath` 比较不可能成立）", isIdLookupCandidate(headerOf(ID, LAUNCH_CWD), ID, null), false)
    pairDir.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
