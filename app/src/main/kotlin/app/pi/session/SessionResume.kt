package app.pi.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 引擎（重新）启动时，**靠什么把这一段对话认回来**：会话 `id`，还是那个文件本身的路径。
 *
 * ## 为什么需要这个判定（真机制，不是猜测）
 *
 * App 现在只有一种钉法：`PiSessionViewModel.restartEngine`（`:2307-2314`）把 `meta.sessionId`
 * 交出去，由 `PiLaunchOptions.continueSessionId` 渲染成 `--session-id <id>`。pi 对这个参数的处理
 * 在 pi 1.0.1 的 `dist/main.js:344-351`：
 *
 * ```
 * if (parsed.sessionId) {
 *     const existingSession = findLocalSessionByExactId(parsed.sessionId, cwd, sessionDir);
 *     if (existingSession) return SessionManager.open(existingSession.path, sessionDir);
 *     console.error(`Warning: No project session found with id '${parsed.sessionId}'; creating a new session with that id.`);
 * }
 * return SessionManager.create(cwd, sessionDir, { id: parsed.sessionId });
 * ```
 *
 * 也就是说：**找不到就用同一个 `id` 新建一个文件**，此后每条消息都写进那个新文件。而「找不到」的
 * 判据不是「磁盘上有没有这个 id」，`SessionManager.findById`（`dist/core/session-manager.js:1421-1441`）
 * 要求三件事同时成立：
 *
 *  1. 文件是它扫的那一层（`--session-dir` 目录）里的**直接**子文件（`readdirSync(dir)`，不递归）；
 *  2. header 的 `id` 等于要的 id；
 *  3. `sessionCwdMatches(header.cwd, resolvePath(cwd))`（`dist/core/session-manager.js:441-443`）——
 *     header 的 `cwd` 非空、且经 pi 的 `resolvePath`（= `path.resolve`，**不做 realpath**）后与
 *     **引擎进程的 cwd** 逐字符相等。
 *
 * 三条里有任何一条不成立，用户得到的形状就是：一段对话被劈成两个文件、两个文件顶着**同一个
 * `id`**（原件是前半段，新建那份是后半段）。**列表现在两行都留**（`PiSessionStore.mergeSameIdRow`：
 * 两份互不为前缀就不合并），名字各取自己文件里第一条 user 消息 —— 用户看到的「名字改成半截开头
 * 那条」就是这个；而在列表只按 id 留一行的那些版本里，剩下的那半截看上去就是整段对话
 * （「往上翻，翻到头也没有了」「转录顶部没有『加载更早』」—— 因为**那个文件确实是从那儿开始的**）。
 * 真 pi 1.0.1 的探针（同 cwd / cwd 不同 / header 无 cwd / 文件在分组目录四组）复现了后三种：
 * 只有 cwd 相同时是 `SessionManager.open`。
 *
 * 第 ① 条还带一个次序上的事实：同一个 id 在那一层里有**两份**时，`findById` 取的是
 * `readdirSync` 顺序里的**第一个**（不是 mtime 最新的那个）—— 「劈开的两半都躺在平铺层」时
 * `--session-id` 因此可能落到**另一份**上。这也是 [ResumeTarget.ByPath] 的第二个理由：路径形式
 * 没有这次查找，也就没有这个不确定性。（`-c` 不一样：`findMostRecentSession` 按**文件 mtime**
 * 降序取第一个，所以在两半之间它选的是最近写过的那个。）
 *
 * 第 3 条为什么在真机上会不成立：`docs/proroot-mode-audit.md:20-48` —— 绑定 host 侧不是内核拼写时
 * proroot 的 `getcwd()` **泄漏宿主路径**，于是 proroot 时期写下的 header 是 `/data/data/...`，
 * 而引擎启动时的 cwd 是 guest 拼写 `/workspace/...`。两个拼写**指向同一个存在的目录**（所以从列表
 * 打开这段对话一切正常，`core/session-cwd.ts` 的存在性检查过得去，用户看不到任何报错），只有
 * `findById` 的字符串比较不过。切换运行时（proot ↔ proroot）、重装/重建运行时（探针重跑、
 * 失效回退 proot）、以及把工作区搬进 rootfs 那类改动，都会翻转这个拼写。
 *
 * ## 修法，以及为什么改的是这一格
 *
 * `findById` 的 cwd 过滤**无法从 argv 关掉**（`filterCwd` 只取决于「有没有显式给 `--session-dir`」，
 * 而这个 App 一直给）。能精确定位文件的参数只有一个：`--session <path>`（pi 的 space 形式，
 * `dist/cli/args.js:89`）——它走 `dist/main.js:337-345` 的 `openSessionOrExit`
 * → `SessionManager.open(path, sessionDir)`，**没有 cwd 过滤**。而 `--session <path>` 起出来的
 * runtime，其 cwd 取 `sessionManager.getCwd()`（`dist/main.js:695-700`），也就是**这段会话自己
 * 记录的 cwd** —— 与 App 现在「从列表打开一段对话」走的 `switch_session`
 * （`core/agent-session-runtime.js:197-224`）完全同一个状态。所以换成路径**不引入任何新的运行时
 * 语义**，只是把「按 id 猜」换成「按文件开」。
 *
 * 但 `--session <path>` 不能无条件用：`PiLaunchOptions` 的 KDoc 记着它唯一的新失败面 ——
 * 「文件存在但不是 pi 会话」会让 `_setSessionFile` 抛异常（`session-manager.js:662-690`），
 * `openSessionOrExit` 于是 `process.exit(1)`（`dist/main.js:266-274`），引擎根本起不来。而
 * `--session-id` 在同一格今天的行为是**静默劈开对话**（数据还在、但界面上少了一半，而且每次重启
 * 都可能再劈一次）。两边都不是好结果，所以这里的选择是：**只在「id 形式一定、且唯一地命中这个
 * 文件」时才留在 id 形式，其余情况换成路径** —— 也就是只在今天已经坏掉的那些格改行为。正常情况
 * （文件在平铺层、header 的 cwd 相符、那个 id 在这一层里只有它一份）argv 与今天**逐字相同**
 * （[ResumeTarget.ById]），所以正常用户的启动路径一点没变。
 *
 * **「唯一」是第二个理由，不是同一条的重复**：`findById` 取的是 `readdirSync` 顺序里的第一个命中，
 * 而那个顺序不是 mtime —— 所以同一个 id 有两份（真劈开的两半都躺在平铺层）时它可能给出**另一份**，
 * 用户重启后会看到另一个半截、新消息也写进那一份。歧义不猜：见
 * [resumeTargetFor] 的 `idResolvesUniquely` 与 [countIdLookupCandidates]。
 *
 * ## 为什么这些判定住在 `app.pi.session` 而不是 ViewModel
 *
 * 它是**读**会话文件的两件事之一（header 的 id、header 的 cwd）加上 pi 自己那条查找规则的复刻，
 * 而它的价值全在「哪一格该换、哪一格不该换」上 —— 那必须能被裸 JVM 逐格跑一遍
 * （`SessionResumeCheck.kt`），而不是靠读代码相信。所以这里不 import 任何 Android 类。
 *
 * 另外这条规则与 pi 自己的 `resolvePath` 语义绑得很紧：字符串相等就是 pi 的判据，**不是**
 * `realpath`。`/data/data/x` 与 `/data/user/0/x` 是同一个目录、但是两个字符串 —— 这正是要把
 * 它判成「解析不到」的那一格（[normalizeGuestPath] 的 KDoc 写了为什么不能顺手把符号链接解开）。
 */
internal sealed interface ResumeTarget {
    /**
     * 照旧发 `--session-id <id>`。**这一支只在 pi 的查找一定、且唯一地命中我们点名的那个文件时
     * 才用**（[resumeTargetFor] 的四条判据），理由见文件 KDoc：路径形式多一个 `process.exit(1)`
     * 的面，能不发就不发。
     */
    data class ById(val id: String) : ResumeTarget

    /**
     * 发 `--session <guestPath>`（`--session-id` 一个都不发：`main.js` 先看 `parsed.session`，
     * 两个一起给时 id 分支根本不会被走到，所以「两个都给」是自欺）。
     */
    data class ByPath(val guestPath: String) : ResumeTarget

    /** 没有意见：交给 `-c` / 新建（冷启动那条路，与今天逐字相同）。 */
    object None : ResumeTarget
}

/**
 * 决定这一次 `restartEngine` 用哪种钉法。
 *
 * @param requestedId `meta.sessionId`：App 认为自己正待着的那段对话。空 = 没有意见（[ResumeTarget.None]）。
 * @param guestPath   `meta.sessionFile`：pi 自己报的 guest 路径。空 = App 说不出文件（只能按 id）。
 * @param fileExists  App 把它映射成 host 文件后，那个文件**存不存在**。不存在时必须留在 id 形式：
 *   「文件还没有」是正常状态（pi 在第一条 assistant 消息落盘前不建文件，`session-manager.js`
 *   的 `_persist`），而 id 形式对它的行为正是我们要的 —— 用同一个 id 建（`main.js:344-351`）。
 * @param flatChildOfSessionDir 这个是文件**直接**躺在 pi 扫的那一层里（`--session-dir`，
 *   `PiEngineHost.kt:619`），还是躺在某个 `--<cwd>--` 分组目录里。分组层是 App 的读取器支持、
 *   而 `findById` **不看**的一层（它只 `readdirSync(dir)` 一次），所以分组层的文件必然要走路径。
 * @param headerId  文件 header 里的 `id`。与 [requestedId] 不符时**不许走路径** —— App 只能为
 *   「它刚读过的、确实是这一段对话的那个文件」担保，拿错文件去开比劈开更糟（那是换了一段对话）。
 * @param headerCwd 文件 header 里的 `cwd`，**原样**（不 trim、不 realpath）：pi 就是拿这个原样
 *   过 `resolvePath` 再比的。
 * @param launchCwd 这个引擎**即将**启动时的 guest cwd（`GuestWorkspacePath` 的拼写），也就是
 *   `process.cwd()` 会成为的那个字符串 —— `findById` 的第 3 条比的就是它。
 * @param idResolvesUniquely `findById` 在那一层里**只有一个**命中，而且就是我们点名的这个文件
 *   （[countIdLookupCandidates] == 1）。**这是第二个必须走路径形式的理由**：`findById` 遍历的是
 *   `readdirSync` 的顺序，**不是文件 mtime**，所以同一个 id 有两份时它可能给出**另一份** —— 用户
 *   重启后看到另一个半截，新消息也写进那一份，而且完全不可预测。歧义不许猜：直接把文件交出去。
 *   说不清（没算、算不出来）时按 `false` 传，也就是走路径 —— 那是安全的那一边。
 */
internal fun resumeTargetFor(
    requestedId: String?,
    guestPath: String?,
    fileExists: Boolean,
    flatChildOfSessionDir: Boolean,
    headerId: String?,
    headerCwd: String?,
    launchCwd: String?,
    idResolvesUniquely: Boolean,
): ResumeTarget {
    val id = requestedId?.takeIf { it.isNotBlank() } ?: return ResumeTarget.None
    val path = guestPath?.takeIf { it.isNotBlank() } ?: return ResumeTarget.ById(id)
    if (!fileExists) return ResumeTarget.ById(id)
    // 这个文件不是我们要的那段对话（或者 header 读不出来）：不许拿它当路径。
    if (headerId == null || headerId != id) return ResumeTarget.ById(id)
    if (flatChildOfSessionDir && cwdMatches(headerCwd, launchCwd) && idResolvesUniquely) {
        return ResumeTarget.ById(id)
    }
    return ResumeTarget.ByPath(path)
}

/**
 * pi 的 `findById` 在 [sessionDir] 这一层里会有**几个**命中（0 / 1 / 2 —— 到 2 就停）。
 *
 * 复刻的是它自己的扫描（`dist/core/session-manager.js:1421-1441`）：只 `readdirSync` 这一层一次
 * （不进 `--<cwd>--` 分组目录）、只看 `.jsonl`、每个文件的 **header** 里 `id` 相等且 `cwd` 经
 * [normalizeGuestPath] 后与 [launchCwd] 相等（[isIdLookupCandidate]）。
 *
 * **为什么要数它。** `findById` 取的是 `readdirSync` 顺序里的**第一个**命中，而那个顺序**不是**
 * 文件 mtime（也就不是「最新/最活的那份」）。所以同一个 id 有两份时，`--session-id` 给出哪一份是
 * 不可预测的 —— 而调用方（[resumeTargetFor]）的规矩是：**歧义不猜，直接把文件路径交出去**。
 * 数到 2 就停下，所以「有歧义」这一格最多读两个 header；只有读到一个（或一个都没有）时才扫完。
 *
 * 读不出来的文件按「不是候选」算（`headerOf` 抛异常或给出 null）—— 少算一个候选只会让结果更
 * 倾向于路径形式，那是安全的一边（见 [isIdLookupCandidate] 的 KDoc）。
 */
internal fun countIdLookupCandidates(
    sessionDir: File,
    requestedId: String,
    launchCwd: String?,
    headerOf: (File) -> JsonObject?,
): Int {
    val files = runCatching { sessionDir.listFiles() }.getOrNull() ?: return 0
    var found = 0
    for (file in files) {
        // 与 `findById` 同名同款：只看 `.jsonl`。目录被 `isFile` 挡掉（pi 那边靠 `readHeader`
        // 失败挡掉；这里把它说明白）。
        if (!file.name.endsWith(".jsonl") || !file.isFile) continue
        val header = runCatching { headerOf(file) }.getOrNull()
        if (!isIdLookupCandidate(header, requestedId, launchCwd)) continue
        found++
        if (found > 1) return found
    }
    return found
}

/**
 * 一个文件的 header 算不算 `findById` 的一个命中：`id` 相等 ∧ `cwd` 与 [launchCwd] 按 pi 的规则
 * 相等（[cwdMatches]）。**与「这个文件是不是我们要的那份」无关** —— 它只回答「pi 的查找会不会
 * 把这一行算进来」。
 *
 * header 读不出来（null）时不算命中：**少算一个候选只会让调用方更倾向于路径形式**，而路径形式是
 * 精确的那一边；反过来（把读不出来的当成候选）只会让人更放心地发 id，方向相反。
 */
internal fun isIdLookupCandidate(header: JsonObject?, requestedId: String, launchCwd: String?): Boolean {
    val id = (header?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (id == null || id != requestedId) return false
    val cwd = (header?.get("cwd") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return cwdMatches(cwd, launchCwd)
}

/**
 * pi 的 `sessionCwdMatches`（`dist/core/session-manager.js:441-443`）：
 * `cwd !== undefined && cwd !== "" && resolvePath(cwd) === resolvePath(launchCwd)`。
 *
 * 两边都要求是绝对路径：header 的 `cwd` 是 pi 用 `resolvePath(process.cwd())` 写下的，一个相对的
 * 或空的 `cwd` 在 pi 那里 `resolvePath` 之后也不会等于引擎 cwd，所以判「不匹配」正是复刻它的行为
 * （相对 cwd 会被解析到**引擎 cwd 之下**，那是另一个字符串）。
 *
 * **不做 realpath。** 这不是疏漏：pi 的 `resolvePath` 是 `path.resolve`，只做纯字符串归一化，
 * 而「同一个目录的两种拼写」恰恰是这条链上真正在出事的那一格（proroot 的 `getcwd()` 泄漏宿主
 * 拼写）。顺手 realpath 一下会把那一格判成「匹配」⇒ 继续发 `--session-id` ⇒ 继续劈开。
 */
private fun cwdMatches(headerCwd: String?, launchCwd: String?): Boolean {
    val a = normalizeGuestPath(headerCwd) ?: return false
    val b = normalizeGuestPath(launchCwd) ?: return false
    return a == b
}

/**
 * [cwdMatches] 用的归一化：`path.resolve` 对**绝对**路径做的那几件事 —— 丢掉空的与 `.` 段、按
 * `..` 回退一层、用单斜杠拼回去、去掉结尾斜杠（根保留）。不是绝对路径（空、相对、`~` 开头）
 * 一律回答 null，也就是「pi 那边不会相等」。
 *
 * 三处容易「顺手改坏」的地方，写在这里免得下次有人改：
 *  - **不 realpath / 不读文件系统**：见 [cwdMatches]；
 *  - **不 trim**：pi 比的是原样字符串，一个尾部空格在它那边就是不相等，这里跟着不相等才不会
 *    出现「App 说能找到、pi 说找不到」；
 *  - **`..` 只能在有东西可退时退**：`/..` 在 `path.resolve` 里还是 `/`，所以退到底就停住。
 */
internal fun normalizeGuestPath(path: String?): String? {
    val raw = path ?: return null
    if (!raw.startsWith("/")) return null
    val out = ArrayList<String>()
    for (segment in raw.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out.add(segment)
        }
    }
    return "/" + out.joinToString("/")
}
