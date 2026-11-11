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

class SessionHit(val session: SessionSummary, val snippet: String)

/**
 * 会话库：列表 / 搜索 / 改名 / 删除，**全部经宿主命令**（SDK 的 `SessionManager`）。
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

    suspend fun list(): List<SessionSummary> {
        val data = requireCommands().call("session.list", obj("all" to JsonPrimitive(true))).data
        return rows(data["sessions"]).mapNotNull(::summaryOf)
    }

    suspend fun search(query: String, limit: Int = 50): List<SessionHit> {
        val data = requireCommands().call("session.search", obj("query" to JsonPrimitive(query), "all" to JsonPrimitive(true), "limit" to JsonPrimitive(limit))).data
        return rows(data["matches"]).mapNotNull { m ->
            val s = (m["session"] as? JsonObject)?.let(::summaryOf) ?: return@mapNotNull null
            SessionHit(s, str(m, "snippet").orEmpty())
        }
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
}
