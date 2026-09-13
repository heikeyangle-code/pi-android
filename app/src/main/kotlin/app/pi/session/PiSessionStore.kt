package app.pi.session

import kotlinx.coroutines.Dispatchers
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
     */
    private val headerScanBudget = 1L shl 20

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
     */
    suspend fun list(limit: Int = 300): List<Summary> = withContext(Dispatchers.IO) {
        if (!sessionsRoot.isDirectory) return@withContext emptyList()
        val out = ArrayList<Summary>()
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
                        if (isSessionFile(file)) readSummary(file, groupCwd)?.let { out += it }
                    }
                }
                // The layout our engine actually writes (`:1551-1552`): the session
                // files sit directly in the session directory, so there is no group
                // name to fall back to.
                isSessionFile(entry) -> readSummary(entry, null)?.let { out += it }
            }
        }
        out.sortByDescending { it.lastActivityAt }
        if (out.size > limit) out.subList(0, limit) else out
    }

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
     */
    suspend fun mostRecentForResume(cwd: String?): Summary? = withContext(Dispatchers.IO) {
        if (!sessionsRoot.isDirectory) return@withContext null
        val target = sessionsRoot.listFiles().orEmpty()
            .filter { isSessionFile(it) }
            .filter { cwd == null || readHeaderCwd(it) == cwd }
            .maxByOrNull { it.lastModified() } ?: return@withContext null
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
                var consumed = 0L
                while (consumed < headerScanBudget) {
                    val line = reader.readLine() ?: break
                    consumed += line.length + 1
                    val obj = parseObjectOrNull(line) ?: continue
                    // pi's discovery requires the first entry to be the session
                    // header and its id to be a string (`:566-570`); anything else is
                    // not a session and has no cwd to match.
                    if (obj.str("type") == "session" && obj.str("id") != null) cwd = obj.str("cwd")
                    break
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
     */
    private fun readSummary(file: File, fallbackCwd: String?): Summary? {
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

        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var consumed = 0L
                while (consumed < headerScanBudget) {
                    val line = reader.readLine() ?: break
                    consumed += line.length + 1
                    if (line.isBlank()) continue
                    val obj = parseObjectOrNull(line) ?: continue

                    if (firstParsed) {
                        firstParsed = false
                        if (obj.str("type") != "session") return null
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
                }
                // Leaving the loop with budget left means the reader hit EOF; only a
                // budget exhaustion counts as truncated.
                truncated = consumed >= headerScanBudget
            }
        }

        // No parseable line at all, or the header is not within the scan budget:
        // pi's discovery gives up in both cases (`:571-575` returns null for an
        // oversized header), and listing nothing is the honest answer.
        if (firstParsed) return null

        val mtime = file.lastModified()
        // pi's own chain for `modified` (`:744-749`): last message activity, else
        // the header timestamp, else mtime. When the scan was cut short the last
        // activity we saw is a *lower bound*, so fall back to mtime — pi's own last
        // resort (`:749`), and an upper bound on the file's real last write.
        val activity = if (truncated) mtime else lastActivityAt ?: startedAt ?: mtime

        return Summary(
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
