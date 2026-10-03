package app.pi.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Reads pi's session index straight off disk.
 *
 * pi's RPC surface has **no list-sessions command** — it can only switch to a
 * session you already know the path of (docs/pi-android-app-design.md §4.5). The
 * desktop UI gets its picker by scanning the session directory, and so must we.
 *
 * ## Two layouts, and why both are read
 *
 *  - **Flat.** When a session directory is given explicitly — `--session-dir`, or
 *    the `PI_CODING_AGENT_SESSION_DIR` environment variable, both of which feed the
 *    same `sessionDir` parameter (`main.ts:670-676`) — pi writes *directly* into it:
 *    `SessionManager.create` takes `sessionDir` and only falls back to the
 *    per-cwd directory when it is absent (`session-manager.ts:1551-1552`), and the
 *    file name is `join(getSessionDir(), "<ISO>_<id>.jsonl")` (`:947-949`).
 *    **This is what our engine produces**: `PiEngineHost` passes
 *    `--session-dir /root/.pi/agent/sessions` and the same value in the
 *    environment (`PiEngineHost.kt:275`, `:307`), so every session the chat writes
 *    is a top-level `.jsonl` file. Reading only subdirectories is therefore a
 *    reader for a layout nothing writes (the bug this file had).
 *  - **Grouped.** pi's own default, used when no session directory is supplied:
 *    `join(agentDir, "sessions", "--<cwd with the leading / and every / \ : turned
 *    into ->--")` (`session-manager.ts:473-486`). The same `sessions` directory is
 *    shared with the guest's terminal (`PtyLauncher.kt:321`), and pi's own
 *    "all projects" list reads exactly one level of such subdirectories
 *    (`session-manager.ts:1706-1718`) — not recursively, at any level.
 *
 * Mixed content is therefore normal, not a corruption: a `sessions` directory may
 * hold flat files and group directories at the same time. One file per session,
 * append-only JSONL, first line a `session` header, then entries carrying
 * `id`/`parentId` that form the branch tree. Nothing here is a second index: the
 * file *is* the truth, and this class only projects the fields a list row needs.
 *
 * 两个布局一起读还有第二个后果：**同一个会话可能在两边各有一份**（副本、`/import` 的拷贝），
 * 于是同一段对话本可以列成两行 —— 用户原话「就那么一个对话……出现了好几个版本，就像被切开
 * 一样」。所以 [list] 按会话身份去重，两个布局里哪一份留下见 [laterSessionRow]。
 */
class PiSessionStore(
    private val sessionsRoot: File,
    /**
     * 摘要索引落盘的位置，`null` 表示**不落盘** —— 也就是这个类以前的行为。
     *
     * 路径是注入的，这里**不出现任何硬编码位置**：这个类只依赖 `java.io.File`，它拿不到
     * `Context` 也拿不到 [app.pi.runtime.PiPaths]，所以"索引放哪"是调用方的决定
     * （见 `PiPaths.sessionIndexFile` 的 KDoc，以及那个 accessor 存在的理由）。
     *
     * 它只是一个**缓存文件**：读不出来、写不进去、内容不认识，都退化成全量扫描，一行结果
     * 都不会变（[PiSessionIndex] 的 KDoc 解释了为什么）。
     */
    indexFile: File? = null,
    /**
     * 同时读几个会话文件。默认 [DEFAULT_PARALLELISM]，`1` 就是**今天的串行行为**。
     *
     * 存在的唯一理由是让 `session-store` harness 能把"并行 == 串行"当成一条真跑的断言
     * （同一批文件、同一份夹具，两种取值逐字段比）。它**不影响结果**：读的顺序不参与
     * 任何判定，只有"谁先进 `bySession`"参与，而那个顺序由 [list] 的目录遍历定死。
     */
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    data class Summary(
        val file: File,
        val id: String,
        val cwd: String,
        val startedAt: Long,
        val lastActivityAt: Long,
        val name: String?,
        val title: String,
        val model: String?,
        val messageCount: Int,
        val parentSession: String?,
    ) {
        /** Display name: the session name if set, else the first user message. */
        val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: title
    }

    /**
     * The number 1 MiB, used for **two different jobs** in this reader. They are not
     * the same permission and must not be read as one.
     *
     *  - **A whole budget, for header discovery.** [readHeaderCwd] spends it as the
     *    total it reads looking for the first line. That is pi's own synchronous
     *    header scan (`core/session-manager.ts:690-694`, bounded by
     *    `MAX_SESSION_HEADER_SCAN_BYTES`, `:607`).
     *  - **A per-line cap, for the summary.** [readSummary] passes it as
     *    [SessionFileScan]'s `maxLineChars`, because a session line is a *message*:
     *    one can be a multi-megabyte tool result or an inline base64 image (see that
     *    object's KDoc for why `readLine()` is not allowed here). This is a **memory**
     *    bound on one line, not a bound on the file.
     *
     * The old reading — "[readSummary] stops after this many characters" — was deleted
     * on the user's ruling (「什么 1M 上限？谁规定的？和 pi 一样就可以」). It is the
     * wrong rule for a name: pi **appends** the `session_info` entry to the end of the
     * file and never rewrites it (see [Cached]), so a session past 1 MiB showed the
     * old name — or no name at all — forever, while pi's own list, which streams the
     * whole file (`:799`), showed the new one. The one deviation that remains is
     * the per-line cap above; it is stated where it bites, in [readSummary].
     */
    private val headerScanBudget = 1 shl 20

    /**
     * One file's summary, as of the (length, mtime) it was scanned at.
     *
     * The scan behind a summary reads up to [headerScanBudget] characters and parses
     * every line in them, so re-deriving one for a file that has not changed is the
     * whole cost of the sessions screen: 300 files of ~300 KB measured 1.5–2.4 s on a
     * desktop JVM, i.e. seconds on a phone, on **every** entry to the screen
     * (`.pi/agent/sessions` grows without bound and `list`'s `limit` only trims the
     * result, never the work).
     *
     * ## Why (length, mtime) is a sound key
     *
     * pi appends to a session file and never rewrites it in place:
     * `SessionManager` opens a session's JSONL for append and every new entry is a
     * new line (`session-manager.ts`, `_persist`/`appendMessage`), and the only
     * other writer is the app's own import, which creates a *new* file. So a file
     * whose length and mtime are unchanged cannot have a different summary, and a
     * changed file is re-scanned in full — which is correct rather than merely
     * cheap, because every field of a row (`name`, `lastActivityAt`, `title`,
     * `messageCount`) now comes from the file's bytes rather than from a bounded
     * prefix of them ([readSummary]).
     *
     * Cost of being wrong: a stale row for a file that changed twice within one
     * filesystem timestamp tick **and** kept its byte length (a same-length rewrite).
     * Nothing in this app or in pi produces that; the refresh button and every
     * `refreshSessions()` caller re-derive as soon as either number moves.
     *
     * The fallback cwd is part of the key because [readSummary]'s `cwd` depends on
     * it: the list path passes a group directory's decoded name while
     * [mostRecentForResume] passes null, and one entry serving both would make the
     * two disagree about the same file.
     */
    private class Cached(val length: Long, val modified: Long, val summary: Summary)

    private val summaryCache = HashMap<String, Cached>()

    private val cacheLock = Any()

    /**
     * [summaryCache] 的磁盘版：上一次进程到这里为止攒下的行。
     *
     * 它只是把上面那句 KDoc 里的「每个文件每个版本一次」从**每个进程一次**延长到
     * **每个文件每个版本一次**。键与失效判据完全一样（[PiSessionIndex.keyOf] /
     * [PiSessionIndex.lookup]），所以命中它的效果就是"那次扫描的结果还在内存里" ——
     * 而冷启动/ViewModel 重建之后的那一次全量扫描正是用户抱怨的那一次
     * （「在聊天界面点左上角查看这些对话历史，加载比较慢，要好几秒」）。
     *
     * 调用方没给位置（`null`）时这里也是 `null`，于是每个 `readSummary` 都走全量扫描：
     * 本类以前的行为，一字不差。
     */
    private val index: PiSessionIndex? = indexFile?.let { PiSessionIndex(it) }

    /**
     * Serialises whole-directory scans.
     *
     * Concurrent scans are already folded at the source — `PiSessionViewModel`
     * coalesces overlapping `refreshSessions()` calls into one follow-up pass — but
     * this mutex still guards every *other* pair of hands, and two passes over the
     * same files are two full scans. One mutex makes the second wait for the first —
     * whose results it then reuses straight out of [summaryCache].
     */
    private val scanMutex = Mutex()

    /**
     * Every session under [sessionsRoot], most recent activity first.
     *
     * Ordering matches pi's picker: `SessionManager.list` sorts by
     * `SessionInfo.modified` descending (`session-manager.ts:1675`), and `modified`
     * is the newest user/assistant message timestamp, else the header timestamp,
     * else the file's mtime (`:744-749`) — *not* the file's mtime, which is only
     * pi's last-resort fallback. [Summary.lastActivityAt] is that same value, so the
     * list order here and the list order in the desktop picker agree: [readSummary]
     * reads the whole file, as pi's list does.
     *
     * ## 一个会话只有一行
     *
     * 上面那两个布局是**分别**遍历的，而一个会话可以在两边各留一份：组目录里的那份是
     * 某个时刻的副本（用户 `cp` 过、或本 App 的 `/import` 把它复制进会话目录 ——
     * `PiSessionViewModel.prepareImport`（工作树 `:4738-4750`）只挑一个没占用的名字、**不改
     * header**，所以副本与原件带同一个 `id`），引擎平铺的那份才是 pi 正在追加的。不去重的话，
     * 同一段对话会在
     * 列表里变成两行（用户原话：「就那么一个对话……出现了好几个版本，就像被切开一样」），
     * 而且 `SessionsScreen` 的两处计数（`"${visible.size} 条"` 与组标题的 `rows.size`）
     * 跟着虚高。
     *
     * 去重的键是**会话自己的身份**：`Summary.id`（header 里的 `id`，也是 pi 文件名
     * `"<ISO>_<id>.jsonl"` 里 `_` 之后那一段；header 读不出 id 时才退回文件名）。**不是**
     * 显示名 —— 两个真正不同的会话可以有同一个标题（用户两轮都只打了「继续」），那是两条
     * 对话，必须留两行（`SessionIdentityCheck.kt` 钉住了这一条）。
     *
     * 留下哪一行由 [laterSessionRow] 决定；两行都只是**读数**，这里不删任何一个文件。
     *
     * ## 它把「读不到」和「确实没有」压成了同一个 `emptyList()`（已知形状）
     *
     * 返回类型是 `List<Summary>`，所以下面两组读数冒充了彼此 —— 本项目「四条读数不许互相冒充」
     * 的规矩在这里是破的，而破在 store 的签名上，修法不在本文件手里：
     *
     *  - 目录不存在（`:180` 的 `!isDirectory`）与 `listFiles()` 返回 `null`（`:186`，权限或 IO
     *    错误）走的是同一条 `emptyList()`。前者是 pi 自己的答案（`session-manager.ts:819-821`
     *    对不存在的目录返回空列表），后者是**读不到**；界面两边都写成「还没有会话」。
     *  - [readSummary] 里那次 `runCatching`（`:409`）会吞掉「打开/读这个文件失败」的异常，之后
     *    `firstParsed` 仍为 true，于是 `return null`（`:485`）—— **「读不了这个文件」被说成了「这个
     *    文件不是会话」**，而且不进缓存，每次扫描都再试一遍。
     *
     * 建议的修法（本次不动，因为调用方不在这里）：`list()` 改回一个带原因的 sealed 结果
     * （`Scanned(rows)` / `Unreadable(reason)`），或至少让 `listFiles() == null` 与「目录不存在」
     * 分开；`readSummary` 侧把 failed 与 notASession 分开记账。在此之前，
     * `PiSessionViewModel.refreshSessions()` 的 `runCatching { … }.getOrDefault(emptyList())`
     * （工作树 `PiSessionViewModel.kt:999`）会把异常也变成「没有会话」。
     */
    suspend fun list(limit: Int = 300): List<Summary> = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!sessionsRoot.isDirectory) return@withContext emptyList()
            // 先把「要读哪些文件、按什么顺序读」定下来，再并行去读。
            //
            // 这个顺序**是结果的一部分**：下面那次 `sortByDescending` 是稳定排序，两行
            // `lastActivityAt` 并列时谁在前取决于它们进 `bySession` 的先后，而那个先后
            // 从前就是这层目录遍历的顺序（`sessionsRoot.listFiles()` → 每个组目录自己的
            // `listFiles()`）。所以并行只能并行「读」，收集必须照旧按这个顺序做 ——
            // 把结果按完成先后折进 `bySession` 会让并列的两行换个位置，那就是行为变化。
            val planned = ArrayList<PlannedFile>()
            // 这次扫描见过的缓存键。今天的代码是在 `readSummary` 里顺手收集的；挪到这里，
            // 是因为"哪些文件参与这次扫描"本来就是这层遍历在决定 —— 收集因此与读取的先后
            // 无关，并行读也不会漏掉一个键（漏掉会让 `retainAll` 把一条还活着的摘要删掉）。
            val seen = HashSet<String>()
            sessionsRoot.listFiles()?.forEach { entry ->
                when {
                    // pi's default layout: one directory per cwd, one level deep
                    // (`session-manager.ts:1706-1718` reads `readdir(sessionsDir)` and
                    // then each directory's files — never deeper).
                    entry.isDirectory -> {
                        // Group directory names encode the cwd; used only as a fallback
                        // when a file's header carries no `cwd` of its own.
                        val groupCwd = encodedCwdFromGroupName(entry.name)
                        entry.listFiles()?.forEach { file ->
                            if (isSessionFile(file)) planned.add(plan(file, groupCwd, seen))
                        }
                    }
                    // The layout our engine actually writes (`:1551-1552`): the session
                    // files sit directly in the session directory, so there is no group
                    // name to fall back to.
                    isSessionFile(entry) -> planned.add(plan(entry, null, seen))
                }
            }
            // 会话身份 → 已经留下的那一行。`LinkedHashMap` 只是让「同一身份、完全并列的两份」
            // 也走 [laterSessionRow] 的最后一条规则，而不是看谁的键先被放进来。
            val bySession = LinkedHashMap<String, Summary>()
            readSummaries(planned).forEach { summary ->
                summary?.let { keepOneRowPerSession(bySession, it) }
            }
            // Forget summaries for files that are gone, so a deleted (or imported and
            // later removed) session cannot keep a row's worth of memory alive for the
            // life of the process. Cheap: one pass over the fresh key set.
            synchronized(cacheLock) { summaryCache.keys.retainAll(seen) }
            // 同一件事的磁盘版本：索引里也只许留这次见过的（见 [persistIndex]）。
            persistIndex(seen)
            val out = ArrayList<Summary>(bySession.values)
            out.sortByDescending { it.lastActivityAt }
            // `limit` 数的是**会话**：去重发生在截断之前，否则一份副本就会占掉一行，
            // 把一个真实的会话挤出屏幕。见 `SessionIdentityCheck.kt` 的最后一条检查。
            if (out.size > limit) out.subList(0, limit) else out
        }
    }

    /**
     * 一次扫描要读的一个文件，以及读它时用的回退 cwd。
     *
     * 键本身不进这里：它是 [plan] 收集进 `seen` 用的，而 [readSummary] 会从
     * `(file.absolutePath, fallbackCwd)` 用**同一个** [PiSessionIndex.keyOf] 再算一遍 ——
     * 一处定义，两处调用，所以 `retainAll` 与 `summaryCache` 不可能对不上。
     */
    private class PlannedFile(val file: File, val fallbackCwd: String?)

    /** 记下这次扫描见过的一个文件，并把它的缓存键收进 `seen`（索引有界的那一半）。 */
    private fun plan(file: File, fallbackCwd: String?, seen: MutableSet<String>): PlannedFile {
        seen.add(PiSessionIndex.keyOf(file.absolutePath, fallbackCwd))
        return PlannedFile(file, fallbackCwd)
    }

    /**
     * 按 [planned] 的**原顺序**读出每一份摘要，最多 [parallelism] 个文件同时在读。
     *
     * "原顺序"是这条函数唯一的契约：`awaitAll` 按下标回答（不是按完成先后），调用方就能
     * 照串行的样子把结果折进 `bySession`。串行的实现（[parallelism] 为 1）走上面那条
     * `map`，与以前逐字相同；并行的实现只是把同一批 `readSummary` 摊到
     * `Dispatchers.IO` 的一个**有界视图**上：最多 [parallelism] 个线程真的在解析，
     * 其余排队 —— 一屏会话不会开出上百个线程（每个线程一次要读的文件是几十 MB 的
     * JSONL，开一屏线程就是把内存和调度都交给最坏情况）。
     *
     * 取消仍然穿透：`awaitAll` 在调用方被取消时取消所有子任务，和以前"循环里被取消"一样。
     */
    private suspend fun readSummaries(planned: List<PlannedFile>): List<Summary?> {
        if (parallelism <= 1 || planned.size <= 1) {
            return planned.map { readSummary(it.file, it.fallbackCwd) }
        }
        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        return coroutineScope {
            planned.map { item -> async(dispatcher) { readSummary(item.file, item.fallbackCwd) } }.awaitAll()
        }
    }

    /**
     * 把这次扫描的结果落盘，**只留这次见到的**（`seen`）。
     *
     * 与上面那句 `retainAll` 是同一个决定：一个已经删掉的会话不许在索引里留一行 ——
     * 否则索引会随会话目录的生命周期单调增长，而"有界"是这个缓存存在的条件之一。
     *
     * 读 [summaryCache] 要在 [cacheLock] 里：并行扫描刚结束，但 `mostRecentForResume`
     * 那条路也可能正在往里写。`filter { it.key in seen }` 在单线程下是上面那句 `retainAll`
     * 的重复（它保证过 `keys ⊆ seen`），在**并发**下不是：另一个协程刚为 `mostRecentForResume`
     * 写进去的那个键不属于这次目录扫描，不该被这次扫描写进索引。
     * 取出来的快照交给 [PiSessionIndex]，写盘由它在自己的锁里做（两次 `list()` 并发时，
     * 后面的覆盖前面的，两份都是同一次扫描的完整结果 —— 而 `list()` 之间本来就有 [scanMutex]）。
     */
    private fun persistIndex(seen: Set<String>) {
        val index = index ?: return
        val rows = synchronized(cacheLock) {
            summaryCache.entries
                .filter { it.key in seen }
                .associate { (key, cached) -> key to cached.toRow() }
        }
        index.replaceAll(rows)
    }

    /** [Cached] → 落盘的行（`File` 不进索引：路径在键里，读回来时用手上那个 `File` 重建）。 */
    private fun Cached.toRow(): PiSessionIndex.Row = PiSessionIndex.Row(
        length = length,
        modified = modified,
        id = summary.id,
        cwd = summary.cwd,
        startedAt = summary.startedAt,
        lastActivityAt = summary.lastActivityAt,
        name = summary.name,
        title = summary.title,
        model = summary.model,
        messageCount = summary.messageCount,
        parentSession = summary.parentSession,
    )

    /** 把一个会话的候选行放进 [bySession]，同一个身份只留 [laterSessionRow] 选中的那一行。 */
    private fun keepOneRowPerSession(bySession: MutableMap<String, Summary>, row: Summary) {
        val previous = bySession[row.id]
        bySession[row.id] = if (previous == null) row else laterSessionRow(previous, row)
    }

    /**
     * 同一个会话的两行里留下哪一行 —— 一个全序，所以答案与文件系统的返回顺序无关。
     *
     * 1. **内容更新的赢。** `lastActivityAt` 是 pi 的 `modified`（`:744-749`），也就是这一行
     *    描述到的那段对话的末尾；两份副本里它更晚的那份拿着的对话更长，正是用户要的那份。
     * 2. **并列时平铺的那份赢**（直接躺在 [sessionsRoot] 下、不属于任何组目录）。理由是
     *    「哪一份是活的」：引擎带 `--session-dir <agentDir>/sessions` 启动
     *    （`PiEngineHost.kt:592`），pi 因此只在平铺目录里创建与追加会话（`session-manager.ts:1551-1552`、
     *    `:947-949`），`switch_session` 打开的就是这一份、pi 接着往它里面写；组目录里的同名
     *    副本是别处留下的快照，打开它等于把这次对话接到快照上。
     *    （这一步**先于**文件时间：一次 `cp` 会让副本的 mtime 比原件新，而两者内容一模一样 ——
     *    按 mtime 选就会选中那份快照。mtime 只在平铺/分组这个更强的判据并列时才用。）
     * 3. **同一布局内**才比较文件 mtime，新者赢（谁最后被写过）。
     * 4. 仍然并列（同一份字节在两处、mtime 也被设成一样）时按绝对路径定序，只为让结果是确定的。
     */
    private fun laterSessionRow(a: Summary, b: Summary): Summary {
        if (a.lastActivityAt != b.lastActivityAt) {
            return if (a.lastActivityAt > b.lastActivityAt) a else b
        }
        val aFlat = isFlatLayout(a.file)
        val bFlat = isFlatLayout(b.file)
        if (aFlat != bFlat) return if (aFlat) a else b
        val aModified = a.file.lastModified()
        val bModified = b.file.lastModified()
        if (aModified != bModified) return if (aModified > bModified) a else b
        return if (a.file.absolutePath <= b.file.absolutePath) a else b
    }

    /** 这一行来自平铺布局（直接躺在 [sessionsRoot] 下），还是来自某个 cwd 组目录。 */
    private fun isFlatLayout(file: File): Boolean =
        file.parentFile?.absolutePath == sessionsRoot.absolutePath

    /**
     * The session pi's `-c` / `--continue` would resume, or null when there is none.
     *
     * [cwd] is the engine's working directory as **pi** sees it (the guest path), and
     * a null [cwd] means "do not filter".
     *
     * This is deliberately *not* [list] first: pi implements `-c` with
     * `SessionManager.continueRecent` (`session-manager.ts:1589-1598`), which calls
     * `findMostRecentSession(dir, cwd)` — and that function
     * (`:636-653`) differs from the picker in three ways that decide which session a
     * user lands in:
     *
     *  - it sorts by **file mtime** (`:649`), not by `modified`, so a rename (which
     *    appends a `session_info` entry and bumps the mtime but is not a message)
     *    moves the session up;
     *  - it filters by `cwd` — `sessionCwdMatches(resolvePath(cwd))` on the header's
     *    `cwd` (`:631-633`) — because `-c` means "the last session *for this
     *    directory*";
     *  - it reads only the session directory itself, never group subdirectories: with
     *    an explicit `--session-dir` the engine's `-c` looks exactly here.
     *
     * `PiSessionViewModel.maybeResumeLastSession` is the `-c` equivalent, so it uses
     * this and not `list(limit = 1)`.
     *
     * **它不受双布局扫描影响**：这里只读 [sessionsRoot] 自己那一层，组目录一个都不进（pi 的
     * `-c` 也只读它拿到的那个目录），所以它最多读出一个 `Summary`，不存在 [list] 那种「同一
     * 会话两行」的膨胀。平铺层里若有两份同一个 `id` 的拷贝（`/import` 那种），它按上面第一条
     * 规则（文件 mtime）选一份 —— 那是 pi 自己的 `-c` 规则，不是这里新加的去重，所以它选中的
     * 不一定是 [list] 留下的那一行；这是已知的、与 pi 一致的行为，不是本文件要修的东西。
     */
    suspend fun mostRecentForResume(cwd: String?): Summary? = withContext(Dispatchers.IO) {
        if (!sessionsRoot.isDirectory) return@withContext null
        // Order first, then look: `maxByOrNull` over a `filter` that reads every
        // file's header opened one session file per candidate to answer a question
        // about exactly one of them (`readHeaderCwd` reads an 8 KiB chunk of each, so
        // 300 sessions cost 300 opens; 152 ms measured on a desktop JVM). Sorting by
        // mtime first and taking the first cwd match is the same answer — `maxByOrNull`
        // returns the first maximum, and the ordering is the same key — for one file's
        // header read instead of all of them.
        val target = sessionsRoot.listFiles().orEmpty()
            .filter { isSessionFile(it) }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { cwd == null || readHeaderCwd(it) == cwd } ?: return@withContext null
        readSummary(target, null)
    }

    /**
     * Remove one session file.
     *
     * `docs/gap-disposition.md` rows **#24/#41**: the app can list, open and rename
     * a session but cannot delete one, while pi's own picker can
     * (`docs/sessions.md:48` — "delete with Ctrl+D, then confirm"). This is the
     * store half of that fix; the confirmation dialog belongs to the screen's owner
     * (patch P9 in that ledger), because pi's delete is a two-step action and the
     * confirm must not be skipped here.
     *
     * Deliberately narrow: only a regular `.jsonl` file **inside** [sessionsRoot] is
     * removed, so a bad `Summary.file` can never become an arbitrary unlink. That
     * guard is why the ViewModel calls this rather than `File.delete()` on a summary
     * it was handed. The active session is *not* special-cased — refusing to delete
     * the session pi is currently appending to is the caller's job, and it is called
     * out in P9.
     */
    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        val root = sessionsRoot.absoluteFile
        val target = file.absoluteFile
        if (!target.path.startsWith(root.path + File.separator)) return@withContext false
        if (!target.isFile || !target.name.endsWith(".jsonl")) return@withContext false
        runCatching { target.delete() }.getOrDefault(false)
    }

    /** A session file is what pi looks for: a regular `.jsonl` (`session-manager.ts:825`). */
    private fun isSessionFile(file: File): Boolean = file.isFile && file.name.endsWith(".jsonl")

    /**
     * The `cwd` in a session file's header, or null when the file is not a session.
     *
     * Reads only until the header is parsed — the discovery half of
     * [mostRecentForResume], where the point is to *avoid* describing every file.
     */
    private fun readHeaderCwd(file: File): String? {
        var cwd: String? = null
        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                SessionFileScan.forEachLine(reader, headerScanBudget, headerScanBudget) { line ->
                    if (line.isBlank()) return@forEachLine true
                    val obj = parseObjectOrNull(line) ?: return@forEachLine true
                    // pi's discovery requires the first entry to be the session
                    // header and its id to be a string (`:566-570`); anything else is
                    // not a session and has no cwd to match.
                    if (obj.str("type") == "session" && obj.str("id") != null) cwd = obj.str("cwd")
                    // One line is the whole answer, so the scan stops here rather than
                    // reading further into a file whose contents are not needed.
                    false
                }
            }
        }
        return cwd
    }

    /**
     * One row of the list, or null when the file is not a pi session.
     *
     * "Is a session" is decided by the **first parseable line**, exactly as pi does
     * it: it must be an object with `type: "session"` (`session-manager.ts:707-709`;
     * blank and malformed lines are skipped at `:503-512`). This filter is load
     * bearing in the flat layout — a stray or truncated `.jsonl` in the session
     * directory would otherwise become a row that pi itself refuses to open
     * (`:905-908` throws for a non-empty file that does not parse as a session).
     *
     * ## 整个文件，不是头 1 MiB（用户裁定）
     *
     * 这一遍扫描读**整个文件**，和 pi 的列表一样。pi 的 `buildSessionInfo`
     * (`core/session-manager.ts:799`) 用 `createReadStream` + `readline`（`:813-816`）
     * 流式读到底；`session_info` 逐个覆盖 `name`，**最后一个生效，空名即清除**
     * （`:828-831`）；`messageCount` 数每一个 `message` 条目（`:833-834`），
     * `lastActivityAt` 取全文里 user/assistant 消息的时间戳（`:836-839`）。行号取自本
     * 仓库检出的 pi 0.87.1 源码（`packages/coding-agent/package.json:3`）。
     *
     * 以前的实现在 [headerScanBudget] 处停下（那个常量的旧读法），而 pi 是把
     * `session_info` **追加到文件末尾**的：`appendSessionInfo` 造一个条目
     * （`session-manager.ts:1304-1313`），`_appendEntry` → `_persist` 用
     * `appendFileSync` 写到最后（`:1187`）。于是任何超过 1 MiB 的会话——一条工具结果或
     * 一张内联图片就够了——改名之后列表永远显示旧名字（或一直没有名字），正是用户报的
     * 「设置显示名称后列表里没变」。
     *
     * **没有照抄 pi 的那一步**：pi 把每条消息的正文累进 `allMessages`（`:848-851`）只是
     * 为了它自己的搜索索引；本 App 的搜索 haystack 不用会话正文，所以这里不存任何消息
     * 文本。内存因此是 O(1)：一行读完即弃，并且单行还有 `maxLineChars` 这道 1 MiB 上限
     * （[headerScanBudget] 的第二个用途）。
     *
     * **保留的那一处偏离**：超过 1 MiB 的**一行**被丢弃而不是读进来（见
     * [SessionFileScan]）—— pi 的 `readline` 会把那一行整个拿进内存，本机不这么做。代价
     * 是那一行不参与 `messageCount`/`lastActivityAt`，从这一刻起这两个读数是**下界**；
     * 以前那种"总预算用完就回退到文件 mtime"的上界随 1 MiB 总预算一起删除：`truncated`
     * 标志在正常路径上永远为假，留一个永远为假的分支就是一句没人校验的断言（用户裁定）。
     *
     * The cost of reading the whole file: 首次进入会话列表要把每个会话读到底。摘要缓存
     * （[Cached]，键是 length+mtime）把它限制在"每个文件每个版本一次"—— 改名只重读那一个
     * 文件，其余文件零成本。**这份缓存现在也落在磁盘上**（[index]），因为"每个文件每个版本
     * 一次"里的"一次"以前是每个**进程**一次：冷启动、切工作区、ViewModel 重建之后的那一次
     * 仍然是全量，而那正是用户报的那一次。
     *
     * @param fallbackCwd the cwd encoded in the group directory name, when the file
     *        came from one. It is only a fallback for a header without `cwd`
     *        (`SessionHeader.cwd` is `string` in this pi version, but old files
     *        exist); it never *replaces* the header's own value, and null simply
     *        means "this file has no group name to fall back to".
     */
    private fun readSummary(file: File, fallbackCwd: String?): Summary? {
        // The fallback cwd is part of the key: the same file has two legitimate
        // summaries depending on who asks (see [Cached]). 键的定义在
        // [PiSessionIndex.keyOf]，这里与 [plan] 都用它 —— 一处定义，两处调用。
        val key = PiSessionIndex.keyOf(file.absolutePath, fallbackCwd)
        val length = file.length()
        val modified = file.lastModified()
        synchronized(cacheLock) {
            summaryCache[key]?.let { cached ->
                if (cached.length == length && cached.modified == modified) return cached.summary
            }
        }
        // 进程内没有，但**上一次进程**可能有：磁盘索引就是那份 `summaryCache`。
        // 命中判据与上面那句逐字相同（键相同、length 与 mtime 都相等），所以命中它 ==
        // "那次扫描的结果还在这台机器的内存里"，一个字段都不会不同；长度/mtime 任何一个动了
        // 就落到下面的全量扫描 —— **扫描永远赢**，索引只省掉重复解析。
        //
        // 命中之后写回进程内那份，理由与解析成功之后那句 `summaryCache[key] = Cached(...)`
        // 完全一样（下一次 `list()` 少一次 stat 之后的查表），也让 [persistIndex] 看得见它。
        index?.lookup(key, length, modified)?.let { row ->
            val summary = row.toSummary(file)
            synchronized(cacheLock) { summaryCache[key] = Cached(length, modified, summary) }
            return summary
        }
        var id: String? = null
        var cwd: String? = fallbackCwd
        var startedAt: Long? = null
        var lastActivityAt: Long? = null
        var name: String? = null
        var title: String? = null
        var model: String? = null
        var parentSession: String? = null
        var messageCount = 0
        var firstParsed = true
        // The first parseable line decided the file is not a session. A flag rather
        // than a non-local return, because the scan hands lines to a lambda now.
        var notASession = false

        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                // 读到底，和 pi 的列表一样（`session-manager.ts:814`）。总预算因此是
                // [SessionFileScan.NO_BUDGET]；真正的界只在两处：这里的 1 MiB 单行上限
                // （[headerScanBudget] 的第二个用途）和 `maxLineChars` 那条丢弃规则。
                SessionFileScan.forEachLine(
                    reader,
                    SessionFileScan.NO_BUDGET,
                    headerScanBudget,
                ) { line ->
                    if (notASession) return@forEachLine false
                    if (line.isBlank()) return@forEachLine true
                    val obj = parseObjectOrNull(line) ?: return@forEachLine true

                    if (firstParsed) {
                        firstParsed = false
                        if (obj.str("type") != "session") {
                            notASession = true
                            return@forEachLine false
                        }
                    }

                    when (obj.str("type")) {
                        "session" -> {
                            obj.str("id")?.let { id = it }
                            obj.str("cwd")?.let { cwd = it }
                            // The header's timestamp is an ISO-8601 string
                            // (`new Date().toISOString()`), not a number.
                            parseIsoMillis(obj.str("timestamp"))?.let { startedAt = it }
                            obj.str("parentSession")?.let { parentSession = it }
                        }
                        // pi keeps the *latest* `session_info`, and an entry with no
                        // name (or an empty one) clears it (`:828-831`). "Latest" is now
                        // over the whole file, which is what makes a rename visible: pi
                        // appends the entry at the end (`:1304-1313`).
                        "session_info" -> name = obj.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                        "message" -> {
                            // pi counts every `message` entry, whatever the role
                            // (`:719`).
                            messageCount++
                            val msg = obj["message"] as? JsonObject
                            val role = msg?.str("role")
                            // Only user/assistant messages define "last activity"
                            // (`:674-690`); tool results and custom messages do not.
                            if (role == "user" || role == "assistant") {
                                // Entry timestamps are ISO strings; the nested
                                // AgentMessage carries the authoritative numeric
                                // Unix-millisecond stamp (`packages/ai/src/types.ts:425`).
                                // Prefer the number, fall back to the entry string.
                                val stamp = msg?.long("timestamp") ?: parseIsoMillis(obj.str("timestamp"))
                                if (stamp != null) lastActivityAt = stamp
                            }
                            if (msg != null) {
                                if (role == "user" && title == null) {
                                    // pi keeps the full first user message and
                                    // truncates at render time (`:461-462`); the cap
                                    // here is the list's own memory budget.
                                    title = firstText(msg)?.take(80)
                                }
                                if (role == "assistant" && model == null) {
                                    model = msg.str("model") ?: msg.str("modelId")
                                }
                            }
                        }
                        // `modelId` is the field pi writes; `model` is tolerated
                        // because it costs nothing and older files may differ.
                        "model_change" -> model = obj.str("modelId") ?: obj.str("model") ?: model
                    }
                    true
                }
            }
        }

        // The first parseable line was not a session header, or there is no parseable
        // line anywhere in the file. pi's list answers null in both cases
        // (`session-manager.ts:822-823`, `:854`), and listing nothing is the honest
        // answer.
        if (notASession) return null
        if (firstParsed) return null

        val mtime = file.lastModified()
        // pi's own chain for `modified` (`:744-749`): last message activity, else the
        // header timestamp, else mtime. Both of the first two now come from the whole
        // file; only the over-long line dropped above can leave `lastActivityAt` a
        // lower bound, and there is no honest fallback for that — see [readSummary]'s
        // KDoc on the deleted `truncated` branch.
        val activity = lastActivityAt ?: startedAt ?: mtime

        val summary = Summary(
            file = file,
            id = id ?: file.nameWithoutExtension,
            cwd = cwd.orEmpty(),
            startedAt = startedAt ?: mtime,
            lastActivityAt = activity,
            name = name,
            // pi's `firstMessage` falls back to "(no messages)" (`:760`); this is
            // the same statement in the app's language.
            title = title ?: "(空会话)",
            model = model,
            messageCount = messageCount,
            parentSession = parentSession,
        )
        // Only a real session is remembered; "this file is not a session" is not
        // cached, so a `.jsonl` that becomes one is described on the next scan.
        synchronized(cacheLock) { summaryCache[key] = Cached(length, modified, summary) }
        return summary
    }

    /**
     * The group directory name is `--<cwd with a leading slash stripped and
     * `/`, `\`, `:` replaced by `-`>--` (pi's `session-manager.ts:476-478`).
     *
     * That substitution is **lossy** — `-` is itself a legal path character — so
     * it cannot be inverted, and pretending otherwise would invent wrong paths
     * for something like `/data/pi-android`. pi puts the authoritative `cwd` in
     * every file's header; this is only the label for a file whose header cannot
     * be read at all.
     */
    private fun encodedCwdFromGroupName(name: String): String =
        name.removePrefix("--").removeSuffix("--").ifBlank { "~" }

    private fun firstText(message: JsonObject): String? {
        val content = message["content"] ?: return null
        return when (content) {
            is JsonPrimitive -> content.content
            is kotlinx.serialization.json.JsonArray -> content.firstNotNullOfOrNull { block ->
                val o = block as? JsonObject ?: return@firstNotNullOfOrNull null
                if (o.str("type") == "text") o.str("text") else null
            }
            is JsonObject -> content.str("text")
        }?.trim()
    }

    private fun parseObjectOrNull(line: String): JsonObject? =
        app.pi.rpc.PiJson.parseObjectOrNull(line)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    /**
     * pi writes every session and entry timestamp as `new Date().toISOString()`,
     * while a nested `AgentMessage.timestamp` is Unix milliseconds. This is the
     * only place the two representations meet, so the conversion is explicit
     * rather than a `toLongOrNull()` that would silently fall back to the file's
     * modification time.
     */
    private fun parseIsoMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return it }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
    }

    /**
     * 索引里的一行 → 一行摘要，用**调用方手上那个 `File`** 而不是从路径新建一个。
     *
     * 这一点是为 [laterSessionRow] 服务的：它比较 `a.file.lastModified()`、看
     * `isFlatLayout(a.file)`（比 `parentFile.absolutePath`）、最后比 `absolutePath`。
     * 用索引里的路径 `File(path)` 重建通常也能得到同样的值，但"通常"不够 —— 手上有现成的
     * 那个对象，就没有任何重建得出差异的余地。
     */
    private fun PiSessionIndex.Row.toSummary(file: File): Summary = Summary(
        file = file,
        id = id,
        cwd = cwd,
        startedAt = startedAt,
        lastActivityAt = lastActivityAt,
        name = name,
        title = title,
        model = model,
        messageCount = messageCount,
        parentSession = parentSession,
    )

    companion object {
        /**
         * 一次扫描同时读几个会话文件。
         *
         * 4：慢的那一段是每个文件的逐行 JSON 解析（几十 MB 文本），不是目录遍历，所以
         * 2–4 个线程就够把首屏耗时压下来；再多只是让几个大文件的解析争同一块内存带宽。
         * `Dispatchers.IO.limitedParallelism` 保证它的上界（不是"IO 池上无限并发"）。
         */
        const val DEFAULT_PARALLELISM: Int = 4
    }
}
