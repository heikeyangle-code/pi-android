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
 * Layout (docs/session-format.md in pi):
 *
 *   <agentDir>/sessions/--<cwd with / \ : replaced by -->--/<ISO>_<UUIDv7>.jsonl
 *
 * One file per session, append-only JSONL, first line a `session` header, then
 * entries carrying `id`/`parentId` that form the branch tree. Nothing here is a
 * second index: the file *is* the truth, and this class only reads enough of it
 * to draw a list.
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
     * pi caps its own discovery scan at 1 MiB per file for the same reason we do:
     * a long session can be tens of megabytes, and a list must not read it all.
     */
    private val headerScanBudget = 1L shl 20

    suspend fun list(limit: Int = 300): List<Summary> = withContext(Dispatchers.IO) {
        if (!sessionsRoot.isDirectory) return@withContext emptyList()
        val out = ArrayList<Summary>()
        sessionsRoot.listFiles()?.forEach { group ->
            if (!group.isDirectory) return@forEach
            // Group directory names encode the cwd; used only as a fallback when
            // a file's header is unreadable.
            val groupCwd = encodedCwdFromGroupName(group.name)
            group.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".jsonl")) return@forEach
                readSummary(file, groupCwd)?.let { out += it }
            }
        }
        out.sortByDescending { it.lastActivityAt }
        if (out.size > limit) out.subList(0, limit) else out
    }

    private fun readSummary(file: File, groupCwd: String): Summary? {
        var id = file.nameWithoutExtension
        var cwd = groupCwd
        var startedAt = file.lastModified()
        var lastActivityAt = file.lastModified()
        var name: String? = null
        var title: String? = null
        var model: String? = null
        var parentSession: String? = null
        var messageCount = 0

        runCatching {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var consumed = 0L
                while (consumed < headerScanBudget) {
                    val line = reader.readLine() ?: break
                    consumed += line.length + 1
                    if (line.isBlank()) continue
                    val obj = parseObjectOrNull(line) ?: continue

                    when (obj.str("type")) {
                        "session" -> {
                            obj.str("id")?.let { id = it }
                            obj.str("cwd")?.let { cwd = it }
                            // The header's timestamp is an ISO-8601 string
                            // (`new Date().toISOString()`), not a number.
                            parseIsoMillis(obj.str("timestamp"))?.let { startedAt = it }
                            obj.str("parentSession")?.let { parentSession = it }
                        }
                        "session_info" -> obj.str("name")?.let { name = it }
                        "message" -> {
                            messageCount++
                            // Entry timestamps are ISO strings; the nested
                            // AgentMessage carries the authoritative numeric
                            // Unix-millisecond stamp. Prefer the number, fall
                            // back to the entry string.
                            val msg = obj["message"] as? JsonObject
                            val stamp = msg?.long("timestamp") ?: parseIsoMillis(obj.str("timestamp"))
                            if (stamp != null) lastActivityAt = stamp
                            if (msg != null) {
                                val role = msg.str("role")
                                if (role == "user" && title == null) {
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
            }
        }

        if (messageCount == 0 && title == null) {
            // A file with a header but no messages is a session that was created
            // and abandoned; still worth showing, but don't invent a title.
            title = "(空会话)"
        }
        return Summary(
            file = file,
            id = id,
            cwd = cwd,
            startedAt = startedAt,
            lastActivityAt = lastActivityAt,
            name = name,
            title = title ?: file.nameWithoutExtension,
            model = model,
            messageCount = messageCount,
            parentSession = parentSession,
        )
    }

    /**
     * The group directory name is `--<cwd with a leading slash stripped and
     * `/`, `\`, `:` replaced by `-`>--` (pi's `session-manager.ts`).
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
