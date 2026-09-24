package app.pi.session

import kotlinx.coroutines.Dispatchers
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
 * file *is* the truth, and this class only reads enough of it to draw a list.
 *
 * 两个布局一起读还有第二个后果：**同一个会话可能在两边各有一份**（副本、`/import` 的拷贝），
 * 于是同一段对话本可以列成两行 —— 用户原话「就那么一个对话……出现了好几个版本，就像被切开
 * 一样」。所以 [list] 按会话身份去重，两个布局里哪一份留下见 [laterSessionRow]。
 */
class PiSessionStore(private val sessionsRoot: File) {

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
     * pi caps its own header discovery at 1 MiB per file
     * (`session-manager.ts:487-489`, `MAX_SESSION_HEADER_SCAN_BYTES`).
     *
     * Deliberate deviation, and the one place this reader is cheaper than pi's own
     * list: pi's `buildSessionInfo` streams the **whole** file to count messages and
     * collect every message's text for search (`:697-742`), which on a phone is
     * hundreds of megabytes across a full list. We stop here and accept that a
     * session longer than this budget is described by its first megabyte. The
     * consequence is named where it matters — see the `truncated` handling in
     * [readSummary].
     *
     * The budget is spent by [SessionFileScan], which is what makes it a real bound:
     * `readLine()` cannot be capped, so the old shape read a line of any length and
     * only then compared the total with this number (see that object's KDoc for why
     * one line can be gigabytes of *legitimate* content). It also doubles as the
     * per-line cap, because no single line is worth more than the whole scan.
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
     * cheap, because `lastActivityAt`/`title`/`messageCount` all come from a prefix
     * of the file.
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
     * list order here and the list order in the desktop picker agree for any session
     * this reader can see in full.
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
            val out = ArrayList<Summary>()
            val seen = HashSet<String>()
            // 会话身份 → 已经留下的那一行。`LinkedHashMap` 只是让「同一身份、完全并列的两份」
            // 也走 [laterSessionRow] 的最后一条规则，而不是看谁的键先被放进来。
            val bySession = LinkedHashMap<String, Summary>()
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
                            if (isSessionFile(file)) {
                                readSummary(file, groupCwd, seen)?.let { keepOneRowPerSession(bySession, it) }
                            }
                        }
                    }
                    // The layout our engine actually writes (`:1551-1552`): the session
                    // files sit directly in the session directory, so there is no group
                    // name to fall back to.
                    isSessionFile(entry) ->
                        readSummary(entry, null, seen)?.let { keepOneRowPerSession(bySession, it) }
                }
            }
            // Forget summaries for files that are gone, so a deleted (or imported and
            // later removed) session cannot keep a row's worth of memory alive for the
            // life of the process. Cheap: one pass over the fresh key set.
            synchronized(cacheLock) { summaryCache.keys.retainAll(seen) }
            out.addAll(bySession.values)
            out.sortByDescending { it.lastActivityAt }
            // `limit` 数的是**会话**：去重发生在截断之前，否则一份副本就会占掉一行，
            // 把一个真实的会话挤出屏幕。见 `SessionIdentityCheck.kt` 的最后一条检查。
            if (out.size > limit) out.subList(0, limit) else out
        }
    }

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
     * @param fallbackCwd the cwd encoded in the group directory name, when the file
     *        came from one. It is only a fallback for a header without `cwd`
     *        (`SessionHeader.cwd` is `string` in this pi version, but old files
     *        exist); it never *replaces* the header's own value, and null simply
     *        means "this file has no group name to fall back to".
     * @param seen when non-null, the cache keys this scan touched are collected into
     *        it so [list] can drop summaries for files that no longer exist. Null
     *        from a caller that is not a whole-directory scan
     *        ([mostRecentForResume]).
     */
    private fun readSummary(file: File, fallbackCwd: String?, seen: MutableSet<String>? = null): Summary? {
        // The fallback cwd is part of the key: the same file has two legitimate
        // summaries depending on who asks (see [Cached]).
        val key = file.absolutePath + '\u0000' + (fallbackCwd ?: "")
        seen?.add(key)
        val length = file.length()
        val modified = file.lastModified()
        synchronized(cacheLock) {
            summaryCache[key]?.let { cached ->
                if (cached.length == length && cached.modified == modified) return cached.summary
            }
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
        var truncated = false
        // The first parseable line decided the file is not a session. A flag rather
        // than a non-local return, because the scan hands lines to a lambda now.
        var notASession = false
        // Characters the scan actually consumed. Everything the scan skips (blank
        // lines, malformed lines, an over-long line) counts, so this is a real bound on
        // the work done and the memory held — see [SessionFileScan].
        var consumed = 0L

        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                // The scan is the bound (see [SessionFileScan]): pi's own discovery
                // gives up on a file whose header is not within the budget
                // (`:571-575`), and `readLine()` could not have enforced that — it
                // returns a line of any length before any budget can be checked.
                consumed = SessionFileScan.forEachLine(reader, headerScanBudget, headerScanBudget) { line ->
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
                        // name (or an empty one) clears it (`:714-716`).
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
                // Leaving the loop with budget left means the reader hit EOF; only a
                // budget exhaustion counts as truncated.
                truncated = consumed >= headerScanBudget
            }
        }

        // The first parseable line was not a session header, or there was none at all
        // (or the header is not within the scan budget — pi's discovery gives up in
        // all three cases, `:571-575` returns null for an oversized header), and
        // listing nothing is the honest answer.
        if (notASession) return null
        if (firstParsed) return null

        val mtime = file.lastModified()
        // pi's own chain for `modified` (`:744-749`): last message activity, else
        // the header timestamp, else mtime. When the scan was cut short the last
        // activity we saw is a *lower bound*, so fall back to mtime — pi's own last
        // resort (`:749`), and an upper bound on the file's real last write.
        val activity = if (truncated) mtime else lastActivityAt ?: startedAt ?: mtime

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
}
