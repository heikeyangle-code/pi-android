package app.pi.session

import app.pi.host.HostCommands
import app.pi.session.list.displayText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * 会话列表的一行（`/resume` 选择器里的一个会话）。字段来自宿主 `session.list`（SDK 的 `SessionInfo`
 * 加上宿主按官方规则算好的树信息）。
 *
 * [file] 是**宿主机**上的文件（App 自己能读写的那份）；宿主（node，跑在 guest 里）说的是 guest 路径，
 * 由 [SessionLibrary] 换算。树信息 [depth] / [isLast] / [ancestorContinues] 就是官方 `flattenSessionTree`
 * 给的那三项，threaded 排序且无搜索时才有层级，其余视图全是 depth 0。
 */
data class SessionSummary(
    val file: File,
    val id: String,
    val cwd: String,
    /** 官方 `shortenHomePath(cwd)`（家目录缩成 `~`），宿主算好。 */
    val cwdShort: String,
    val pathShort: String,
    val startedAt: Long,
    val lastActivityAt: Long,
    val name: String?,
    val firstMessage: String,
    val messageCount: Int,
    val parentSession: String?,
    val depth: Int = 0,
    val isLast: Boolean = true,
    val ancestorContinues: List<Boolean> = emptyList(),
    /** 宿主按官方的路径比较键判断：这一行就是当前会话（不可删除）。 */
    val isCurrent: Boolean = false,
) {
    /** 官方显示文本：有会话名用会话名，否则第一条消息（控制字符清成空格）；都没有时才用占位。 */
    val displayName: String get() = displayText(name, firstMessage).ifEmpty { "（空会话）" }
}

/** 宿主 `session.list` 的 `scope`：官方选择器的 Current Folder / All。 */
enum class SessionScope(val wire: String) { Current("current"), All("all") }

/** 官方的三种排序：threaded（成树）/ recent（按最近活动）/ relevance（按相关度，需要查询才有意义）。 */
enum class SessionSort(val wire: String) { Threaded("threaded"), Recent("recent"), Relevance("relevance") }

data class SessionListQuery(
    val scope: SessionScope = SessionScope.All,
    val sort: SessionSort = SessionSort.Threaded,
    val namedOnly: Boolean = false,
    val query: String = "",
) {
    /** 磁盘缓存只存「无查询」的视图（有查询的结果取决于输入，没有缓存价值）。 */
    val cacheKey: String get() = "${scope.wire}|${sort.wire}|${if (namedOnly) "named" else "all"}|g"
}

class SessionListPage(val rows: List<SessionSummary>, val total: Int, val queryError: String?, val raw: JsonArray? = null)

/** 宿主 `sessions.progress` 事件：已读 [loaded]/[total] 个文件；[rows] 为部分结果（官方每 10 / 100 个发一次）。 */
class SessionLoadProgress(val scope: SessionScope, val loaded: Int, val total: Int, val rows: List<SessionSummary>?)

/** 删除结果：官方 `deleteSessionFile` 先试 trash 命令再 unlink，[method] 区分「已移到回收站」与「已删除」。 */
class SessionDeleteOutcome(val ok: Boolean, val method: String?, val error: String?)

/**
 * 会话库：列表 / 搜索 / 预览 / 改名 / 删除，**全部经宿主命令**（SDK 的 `SessionManager`）。
 * 取代 `PiSessionStore` 等约 2800 行「自己扫盘、自己解析 JSONL」的代码（docs/sdk-host/sessions.md §1）。
 *
 * 排序、成树、过滤、搜索（含 `re:` / `"短语"`）都在宿主里用官方实现算好，这里只发命令、换算路径、
 * 并把上一次的结果存成一份小 JSON：冷启动时**先画上次的列表**，宿主读完磁盘（SDK 要逐个解析会话文件，
 * 这是慢的根源，省不掉）再无声地换成新的。
 *
 * 路径换算：宿主只认 guest 路径（`<guestAgentDir>/sessions/…`），App 持有的是宿主机路径
 * （`<hostAgentDir>/sessions/…`），二者是同一目录的两种拼法，只差前缀。换不出来的路径（不在会话根之下）
 * **丢弃**，不猜。
 *
 * 需要引擎已打开（宿主的 `session.list` 要先 `session.open`）；没打开时命令会失败，调用方保留手上那份列表。
 */
class SessionLibrary(
    private val hostAgentDir: File,
    private val guestAgentDir: String,
    private val cacheFile: File?,
    private val commands: () -> HostCommands?,
) {
    private val hostRoot: String get() = File(hostAgentDir, "sessions").absolutePath
    private val guestRoot: String get() = "$guestAgentDir/sessions"

    /** @param refresh true = 宿主重读磁盘（官方 `refreshSessionsAfterMutation` / 首次进入）；false = 用宿主缓存重算视图。 */
    suspend fun list(q: SessionListQuery, refresh: Boolean): SessionListPage {
        val data = requireCommands().call(
            "session.list",
            obj(
                "scope" to JsonPrimitive(q.scope.wire),
                "sortMode" to JsonPrimitive(q.sort.wire),
                "nameFilter" to JsonPrimitive(if (q.namedOnly) "named" else "all"),
                "query" to JsonPrimitive(q.query),
                "refresh" to JsonPrimitive(refresh),
                // 手机上按工作目录分组展示（App 呈现，非 pi 概念）：threaded 视图每个目录各自成树。
                "groupByCwd" to JsonPrimitive(true),
            ),
        ).data
        return SessionListPage(
            rows = rows(data["rows"]).mapNotNull(::summaryOf),
            total = (data["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            queryError = str(data, "queryError"),
            raw = data["rows"] as? JsonArray,
        )
    }

    fun parseProgress(data: JsonElement?): SessionLoadProgress? {
        val o = data as? JsonObject ?: return null
        val scope = SessionScope.entries.firstOrNull { it.wire == str(o, "scope") } ?: return null
        val partial = (o["rows"] as? JsonArray)?.let { arr -> arr.mapNotNull { it as? JsonObject }.mapNotNull(::summaryOf) }
        return SessionLoadProgress(
            scope,
            (o["loaded"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            (o["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            partial,
        )
    }

    // ---- 上次的列表（冷启动先画它） ----

    fun readCache(q: SessionListQuery): SessionListPage? {
        val f = cacheFile ?: return null
        return runCatching {
            val root = Json.parseToJsonElement(f.readText()) as JsonObject
            if (str(root, "key") != q.cacheKey || str(root, "root") != hostRoot) return null
            SessionListPage(rows(root["rows"]).mapNotNull(::summaryOf), (root["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0, null)
        }.getOrNull()
    }

    /** 只在**完整读完**、**无查询**时写；原样存宿主给的行（所以读回来走同一个解析）。 */
    @Synchronized
    fun writeCache(q: SessionListQuery, rawRows: JsonArray?, total: Int) {
        val f = cacheFile ?: return
        // 只存默认视图：用户切过排序 / 命名过滤后重启，缓存键对不上就会白屏，所以别用「当时的视图」当键。
        if (q.query.isNotBlank() || rawRows == null || q != SessionListQuery()) return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp-${System.nanoTime()}")
            try {
                tmp.writeText(
                    JsonObject(
                        mapOf(
                            "key" to JsonPrimitive(q.cacheKey),
                            "root" to JsonPrimitive(hostRoot),
                            "total" to JsonPrimitive(total),
                            "rows" to rawRows,
                        ),
                    ).toString(),
                )
                if (!tmp.renameTo(f)) {
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }
    }

    /** @return 是否成功；宿主拒绝（路径越界、文件没了）都是 false，不抛。 */
    suspend fun rename(file: File, name: String): Boolean {
        val path = guestPathOf(file) ?: return false
        return ok { call("session.rename", obj("path" to JsonPrimitive(path), "name" to JsonPrimitive(name))) }
    }

    suspend fun delete(file: File): SessionDeleteOutcome {
        val path = guestPathOf(file) ?: return SessionDeleteOutcome(false, null, "不在会话目录内")
        return try {
            val data = requireCommands().call("session.delete", obj("path" to JsonPrimitive(path))).data
            SessionDeleteOutcome(true, str(data, "method"), null)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionDeleteOutcome(false, null, e.message)
        }
    }

    // ---- 路径换算 ----

    fun guestPathOf(file: File): String? {
        val rootCanon = runCatching { File(hostRoot).canonicalPath }.getOrDefault(hostRoot)
        val p = runCatching { file.canonicalPath }.getOrNull() ?: return null
        if (p != rootCanon && !p.startsWith("$rootCanon/")) return null
        return guestRoot + p.removePrefix(rootCanon)
    }

    fun hostFileOf(guestPath: String): File? {
        if (guestPath != guestRoot && !guestPath.startsWith("$guestRoot/")) return null
        val rel = guestPath.removePrefix(guestRoot).trimStart('/')
        if (rel.split('/').any { it == ".." }) return null
        val candidate = File(hostRoot, rel)
        val rootCanon = runCatching { File(hostRoot).canonicalPath }.getOrDefault(hostRoot)
        val candCanon = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        if (candCanon != rootCanon && !candCanon.startsWith("$rootCanon/")) return null
        return candidate
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
            cwdShort = str(o, "cwdShort").orEmpty(),
            pathShort = str(o, "pathShort").orEmpty(),
            startedAt = created ?: 0L,
            lastActivityAt = modified ?: 0L,
            name = str(o, "name")?.takeIf { it.isNotBlank() },
            firstMessage = str(o, "firstMessage").orEmpty(),
            messageCount = (o["messageCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            // 原样保留 guest 路径：`SessionForkLabel` 约定 parentSession 是 guest 路径，由它去映射。
            parentSession = str(o, "parentSessionPath"),
            depth = (o["depth"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            isLast = (o["isLast"] as? JsonPrimitive)?.content != "false",
            ancestorContinues = (o["ancestorContinues"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content == "true" } ?: emptyList(),
            isCurrent = (o["isCurrent"] as? JsonPrimitive)?.content == "true",
        )
    }

    private fun rows(e: Any?): List<JsonObject> = (e as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun millis(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    private fun obj(vararg kv: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*kv))
}
