package app.pi.session

import app.pi.host.HostCommands
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * 一行会话（列表用）。字段与旧 `PiSessionStore.Summary` 一致，所以界面可以原样用。
 *
 * [file] 是**宿主机**上的文件（App 自己能读写的那份）；宿主（node，跑在 guest 里）说的是 guest 路径，
 * 由 [SessionLibrary] 换算。[model] 恒为 null：SDK 的 `SessionInfo` 不带模型，新设计里列表也不再显示它。
 */
data class SessionSummary(
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
    /** 显示名：有会话名用会话名，否则用第一条用户消息。 */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: title
}

data class SessionHit(val session: SessionSummary, val snippet: String)

data class SessionPreviewTurn(
    val role: String,
    val text: String,
    val timestamp: Long?,
)

/**
 * 会话库：列表 / 搜索 / 预览 / 改名 / 删除，**全部经宿主命令**（SDK 的 `SessionManager`）。
 * 取代 `PiSessionStore` 等约 2800 行「自己扫盘、自己解析 JSONL」的代码（docs/sdk-host/sessions.md §1）。
 *
 * 路径换算：宿主只认 guest 路径（`<guestAgentDir>/sessions/…`），App 持有的是宿主机路径
 * （`<hostAgentDir>/sessions/…`），二者是同一目录的两种拼法，只差前缀。换不出来的路径（不在会话根之下）
 * **丢弃**，不猜 —— 与旧 `guestSessionPath` 的规则一致。
 *
 * 需要引擎已打开（宿主的 `session.list` 要先 `session.open`）；没打开时命令会失败，调用方保留手上那份列表。
 */
class SessionLibrary(
    private val hostAgentDir: File,
    private val guestAgentDir: String,
    private val commands: () -> HostCommands?,
) {
    private val hostRoot: String get() = File(hostAgentDir, "sessions").absolutePath
    private val guestRoot: String get() = "$guestAgentDir/sessions"

    suspend fun list(opId: String? = null): List<SessionSummary> {
        val args = if (opId != null) {
            obj("all" to JsonPrimitive(true), "opId" to JsonPrimitive(opId))
        } else {
            obj("all" to JsonPrimitive(true))
        }
        val data = requireCommands().call("session.list", args).data
        return rows(data["sessions"]).mapNotNull(::summaryOf)
    }

    suspend fun search(query: String, limit: Int = 50, opId: String? = null): List<SessionHit> {
        val args = JsonObject(
            buildMap {
                put("query", JsonPrimitive(query))
                put("all", JsonPrimitive(true))
                put("limit", JsonPrimitive(limit))
                opId?.let { put("opId", JsonPrimitive(it)) }
            },
        )
        val data = requireCommands().call("session.search", args).data
        return rows(data["matches"]).mapNotNull { m ->
            val s = (m["session"] as? JsonObject)?.let(::summaryOf) ?: return@mapNotNull null
            SessionHit(s, str(m, "snippet").orEmpty())
        }
    }

    /** 只读预览指定会话的当前分支消息（宿主 `session.read { mode: "branch" }`）。 */
    suspend fun preview(file: File, limit: Int = 40): List<SessionPreviewTurn> {
        val path = guestPathOf(file) ?: return emptyList()
        val data = requireCommands().call(
            "session.read",
            obj("path" to JsonPrimitive(path), "mode" to JsonPrimitive("branch")),
        ).data
        return rows(data["entries"])
            .filter { str(it, "type") == "message" }
            .mapNotNull { entry ->
                val raw = entry["message"] as? JsonObject ?: return@mapNotNull null
                val parsed = app.pi.rpc.parsePiMessage(raw)
                val text = parsed.text.trim()
                if (text.isEmpty()) return@mapNotNull null
                SessionPreviewTurn(
                    role = parsed.role,
                    text = text,
                    timestamp = millis(str(entry, "timestamp")),
                )
            }
            .takeLast(limit)
    }

    /** @return 是否成功；宿主拒绝（路径越界、文件没了）都是 false，不抛。 */
    suspend fun rename(file: File, name: String): Boolean {
        val path = guestPathOf(file) ?: return false
        return ok { call("session.rename", obj("path" to JsonPrimitive(path), "name" to JsonPrimitive(name))) }
    }

    suspend fun delete(file: File): Boolean {
        val path = guestPathOf(file) ?: return false
        return ok { call("session.delete", obj("path" to JsonPrimitive(path))) }
    }

    // ---- 路径换算 ----

    fun guestPathOf(file: File): String? {
        val p = file.absolutePath
        if (p != hostRoot && !p.startsWith("$hostRoot/")) return null
        return guestRoot + p.removePrefix(hostRoot)
    }

    fun hostFileOf(guestPath: String): File? {
        if (guestPath != guestRoot && !guestPath.startsWith("$guestRoot/")) return null
        return File(hostRoot + guestPath.removePrefix(guestRoot))
    }

    // ---- 内部 ----

    private fun requireCommands(): HostCommands = commands() ?: throw IllegalStateException("引擎没有在运行，读不到会话列表")

    private suspend fun ok(block: suspend HostCommands.() -> Unit): Boolean = try {
        requireCommands().block(); true
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun summaryOf(o: JsonObject): SessionSummary? {
        val file = hostFileOf(str(o, "path") ?: return null) ?: return null
        val id = str(o, "id") ?: return null
        val created = millis(str(o, "created"))
        val modified = millis(str(o, "modified")) ?: created
        return SessionSummary(
            file = file,
            id = id,
            cwd = str(o, "cwd").orEmpty(),
            startedAt = created ?: 0L,
            lastActivityAt = modified ?: 0L,
            name = str(o, "name")?.takeIf { it.isNotBlank() },
            title = str(o, "firstMessage").orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { "（空会话）" },
            model = null,
            messageCount = (o["messageCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            // 原样保留 guest 路径：`SessionForkLabel` 约定 parentSession 是 guest 路径，由它去映射。
            parentSession = str(o, "parentSessionPath"),
        )
    }

    private fun rows(e: Any?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun obj(vararg kv: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*kv))

    data class ListProgress(val opId: String, val loaded: Int, val total: Int)

    companion object {
        fun listProgress(event: app.pi.host.HostSignal.Event): ListProgress? {
            if (event.name != "session.list.progress") return null
            val d = event.data as? JsonObject ?: return null
            val opId = (d["opId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val loaded = (d["loaded"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val total = (d["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ListProgress(opId, loaded, total)
        }
    }
}
