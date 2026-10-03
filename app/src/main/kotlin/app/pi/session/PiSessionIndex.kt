package app.pi.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 会话列表的那份摘要缓存，**落盘**。
 *
 * ## 它解决的是什么
 *
 * [PiSessionStore.list] 的一行就是对一个会话文件的**全文扫描**：`name`、`lastActivityAt`、
 * `title`、`messageCount` 四个读数本来就定义在整个文件上（见 `readSummary` 的 KDoc），
 * 而一个会话文件是 JSONL 的消息流，一条工具结果或一张内联图片就是几 MB 的 base64。
 * 几十个会话 × 每个几十 MB ⇒ 进一次会话列表要逐行解析几百 MB 的 JSON ⇒ 用户报的
 * 「加载比较慢，要好几秒」。
 *
 * 进程内的 `summaryCache` 已经把这件事限制在「每个文件每个版本一次」，但它是**进程内**的：
 * 冷启动、ViewModel 重建（切工作区会把 `sessionStore` 一起丢掉）之后的那一次仍然是全量。
 * 而用户抱怨的**正是**那一次。所以这份缓存必须活过进程。
 *
 * ## 它不可能改变结果
 *
 * 键与失效判据和进程内那份**逐字相同**：`(绝对路径, 回退 cwd)` 定位，`(length, mtime)`
 * 判断还新不新（[keyOf] / [lookup]）。一份落在磁盘上的索引因此只是「上一次进程的
 * `summaryCache`」，命中它 == 那次扫描的结果还在内存里。任何一处对不上 —— 文件不见、
 * 变长、变短、mtime 动了 —— 都退回 [PiSessionStore.readSummary] 的全量扫描，而**扫描永远
 * 赢**：索引只省掉重复解析，从不参与判定。
 *
 * 特别地，**内容变了但 `(length, mtime)` 没变**（同长度改写）时这里照样命中并给出旧行。
 * 这不是缺陷，是**照抄**：进程内那份缓存就是这个语义（`Cached` 的 KDoc 解释了为什么
 * `(length, mtime)` 对 pi 的追加式写入是可靠的键），改掉它才是行为变化。
 *
 * ## 格式与版本
 *
 * 一个 JSON 对象：`{"version":1,"entries":[{"path":…,"fallbackCwd":…,"length":…,…}]}`。
 * [VERSION] 不认识、顶层不是对象、`entries` 不是数组、任何一条缺字段或数字读不出来 ——
 * 一律当成**没有索引**，全量扫描（[read] 返回空表）。
 *
 * 用 JSON 而不是二进制，是因为这份文件的唯一用途是给开发者/下一次改动看得懂；它的大小
 * 与"会话数"成正比（几百条、几十 KB），读写成本相对于它省下的解析可以忽略。
 *
 * ## 有界与原子
 *
 * 写入的是**这一次扫描见到的全部行**（调用方传进来的 `next`），也就是和
 * `synchronized(cacheLock) { summaryCache.keys.retainAll(seen) }` 同一件事的磁盘版本：
 * 已经消失的会话不许在索引里留一条。写入是「同目录临时文件 + `rename`」
 * （[write]），所以不会被读到半份；写不进去（目录不可写、空间满、任何 IO 异常）
 * **静默退回**：今天一次都不落盘也要能工作，这个类只是加速器，不是运行前提。
 *
 * ## 落点
 *
 * 由调用方给的 `File` 决定，见 `PiPaths.sessionIndexFile`：`<files>/pi/session-index.json`，
 * 和 `image-cache/` 同一个先例 —— App 私有、不进 rootfs、不进会话目录，而且**可以随时丢掉**。
 * 丢掉它的唯一代价是下一次进会话列表回到全量扫描。
 */
internal class PiSessionIndex(private val file: File) {

    /**
     * 一条落盘的行：`PiSessionStore.Summary` 除掉 `File` 自己的全部字段（路径在键里），
     * 加上判定它是否还新的 `(length, mtime)`。
     *
     * 为什么不直接存 `File`：`Summary.file` 是**这次扫描拿到的那个 `File` 对象**
     * （`listFiles()` 拼出来的路径），索引读回来的行只能给出一个路径。所以读的一方
     * （`PiSessionStore`）用**手上的那个 `File`** 重建 `Summary`，而不是拿路径再 new 一个 ——
     * 这样 `laterSessionRow` 里的 `file.absolutePath` / `file.lastModified()` /
     * `isFlatLayout` 与串行扫描看到的完全是同一个对象。
     */
    data class Row(
        val length: Long,
        val modified: Long,
        val id: String,
        val cwd: String,
        val startedAt: Long,
        val lastActivityAt: Long,
        val name: String?,
        val title: String,
        val model: String?,
        val messageCount: Int,
        val parentSession: String?,
    )

    private val lock = Any()

    /** 已经读进来的那份，`null` 表示还没读过。 */
    private var rows: Map<String, Row>? = null

    /**
     * 内存里的行与磁盘上的**不是**同一份（上一次写失败了，或者还没写过）。
     * 有它才会在写失败之后重试，而不是"失败一次就永远不再落盘"。
     */
    private var dirty = false

    /**
     * 键对得上、且 `(length, mtime)` 也还新的时候给出那一行，否则 null（调用方全量扫描）。
     *
     * 与 `PiSessionStore.summaryCache` 的命中判据逐字相同：**先比长度再比 mtime**，
     * 两个都相等才算命中。放松任何一条（例如"变小了就当没变"）都会让一个改过的会话
     * 显示旧名字。
     */
    fun lookup(key: String, length: Long, modified: Long): Row? = synchronized(lock) {
        val hit = rows()[key] ?: return null
        if (hit.length == length && hit.modified == modified) hit else null
    }

    /**
     * 把索引换成 [next]，并原子地写到磁盘。
     *
     * `next` 由调用方按「这次扫描见到的键」构造，所以这是**有界**的：索引里不可能留下
     * 一个这次扫描没见到的文件。内容与已经知道的那份一样、且磁盘上那份是好的，就什么都不做
     * （进会话列表会被反复调用，没必要每次都重写同样几十 KB）。
     */
    fun replaceAll(next: Map<String, Row>) {
        synchronized(lock) {
            val changed = rows() != next
            rows = HashMap(next)
            if (!changed && !dirty) return
            dirty = !write(next)
        }
    }

    // ------------------------------------------------------------------ 读

    private fun rows(): Map<String, Row> = rows ?: read().also { rows = it }

    /**
     * 读索引，任何读不懂的地方都回答「没有索引」（空表）。
     *
     * 一条**坏行**只丢它自己（那一行对应的文件下次全量扫描），而版本不认识、顶层不是对象、
     * `entries` 不是数组这种**整体**的坏法丢整份。两种都退化成全量扫描，只是前者少扫几个文件。
     */
    private fun read(): Map<String, Row> {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val root = runCatching { app.pi.rpc.PiJson.parseObjectOrNull(text) }.getOrNull() ?: return emptyMap()
        if (root.int(VERSION_KEY) != VERSION) return emptyMap()
        val entries = root[ENTRIES_KEY] as? JsonArray ?: return emptyMap()
        val out = HashMap<String, Row>(entries.size)
        entries.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val path = obj.str(PATH_KEY) ?: return@forEach
            val length = obj.long(LENGTH_KEY) ?: return@forEach
            val modified = obj.long(MODIFIED_KEY) ?: return@forEach
            val id = obj.str(ID_KEY) ?: return@forEach
            val cwd = obj.str(CWD_KEY) ?: return@forEach
            val startedAt = obj.long(STARTED_AT_KEY) ?: return@forEach
            val lastActivityAt = obj.long(LAST_ACTIVITY_AT_KEY) ?: return@forEach
            val title = obj.str(TITLE_KEY) ?: return@forEach
            val messageCount = obj.int(MESSAGE_COUNT_KEY) ?: return@forEach
            out[keyOf(path, obj.str(FALLBACK_CWD_KEY))] = Row(
                length = length,
                modified = modified,
                id = id,
                cwd = cwd,
                startedAt = startedAt,
                lastActivityAt = lastActivityAt,
                name = obj.str(NAME_KEY),
                title = title,
                model = obj.str(MODEL_KEY),
                messageCount = messageCount,
                parentSession = obj.str(PARENT_SESSION_KEY),
            )
        }
        return out
    }

    // ------------------------------------------------------------------ 写

    private fun write(next: Map<String, Row>): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temp = runCatching { File.createTempFile("${file.name}.", ".tmp", parent) }.getOrNull()
            ?: return false
        return try {
            temp.writeText(encode(next), Charsets.UTF_8)
            // 同目录内的 rename，所以要么是旧的那份、要么是新的那份，没有半份可读。
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (error: Throwable) {
            // 缓存写不进去不是错误 —— 下一轮进列表会重试（`dirty`），而今天一次都不落盘
            // 也照样工作。故意不记日志、不上报：它没有用户可见的后果。
            false
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun encode(rows: Map<String, Row>): String {
        // 按键排序，于是**同一批行永远写出同一份字节**：这份文件是给人翻的（读得懂、
        // 能 diff），而 `HashMap` 的遍历顺序不该体现在磁盘上。比较是否变化用的是 Map 相等
        // （与顺序无关），所以排序不影响 `replaceAll` 那个"没变就不写"的判定。
        val entries = JsonArray(
            rows.entries.sortedBy { it.key }.map { (key, row) ->
                JsonObject(
                    linkedMapOf(
                        PATH_KEY to JsonPrimitive(pathOf(key)),
                        FALLBACK_CWD_KEY to fallbackCwdOf(key).toJson(),
                        LENGTH_KEY to JsonPrimitive(row.length),
                        MODIFIED_KEY to JsonPrimitive(row.modified),
                        ID_KEY to JsonPrimitive(row.id),
                        CWD_KEY to JsonPrimitive(row.cwd),
                        STARTED_AT_KEY to JsonPrimitive(row.startedAt),
                        LAST_ACTIVITY_AT_KEY to JsonPrimitive(row.lastActivityAt),
                        NAME_KEY to row.name.toJson(),
                        TITLE_KEY to JsonPrimitive(row.title),
                        MODEL_KEY to row.model.toJson(),
                        MESSAGE_COUNT_KEY to JsonPrimitive(row.messageCount),
                        PARENT_SESSION_KEY to row.parentSession.toJson(),
                    ),
                )
            },
        )
        // `JsonObject.toString()` 走的是 kotlinx.serialization 自己的 JSON 编码器
        // （字符串该转义的一定转义），所以这里不需要第二个序列化器。
        return JsonObject(
            linkedMapOf(
                VERSION_KEY to JsonPrimitive(VERSION),
                ENTRIES_KEY to entries,
            ),
        ).toString()
    }

    private fun String?.toJson(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

    // ------------------------------------------------------------- 键与 JSON

    companion object {

        /**
         * 索引格式的版本。
         *
         * 只增不减：**任何**对 [Row] 字段集合或键含义的改动都要 +1，否则一次升级之后旧的
         * 索引会被当成"读得懂"，而那些字段的含义已经变了 —— 这正是"索引与扫描结果不一致"
         * 里唯一扫描赢不了的一类。改成不认识的值就是全量扫描，这是安全的默认。
         */
        const val VERSION: Int = 1

        private const val VERSION_KEY = "version"
        private const val ENTRIES_KEY = "entries"
        private const val PATH_KEY = "path"
        private const val FALLBACK_CWD_KEY = "fallbackCwd"
        private const val LENGTH_KEY = "length"
        private const val MODIFIED_KEY = "modified"
        private const val ID_KEY = "id"
        private const val CWD_KEY = "cwd"
        private const val STARTED_AT_KEY = "startedAt"
        private const val LAST_ACTIVITY_AT_KEY = "lastActivityAt"
        private const val NAME_KEY = "name"
        private const val TITLE_KEY = "title"
        private const val MODEL_KEY = "model"
        private const val MESSAGE_COUNT_KEY = "messageCount"
        private const val PARENT_SESSION_KEY = "parentSession"

        /**
         * 进程内缓存（`PiSessionStore.summaryCache`）与这份索引**共用**的键，所以两者
         * 不可能对不上：命中索引 == 命中那次扫描留在内存里的同一行。
         */
        private const val SEPARATOR = '\u0000'

        /**
         * 缓存键：`绝对路径 + NUL + 回退 cwd`。
         *
         * 回退 cwd（组目录名解出来的那个，见 `encodedCwdFromGroupName`）必须在键里，因为它
         * 是**摘要内容**的一部分：`readSummary` 拿它当 header 没有 `cwd` 时的兜底，同一个
         * 文件用不同的回退 cwd 读会得到两份合法的摘要（`list` 传组目录名，
         * `mostRecentForResume` 传 null）。用一个键服务两边，两边就会对同一个文件给出不同答案。
         *
         * `null` 与空串压成同一个键，这也是进程内缓存的现状（`fallbackCwd ?: ""`），而它没有
         * 后果：组目录名解不出空串（`ifBlank { "~" }`），空回退只来自"这个文件不属于任何
         * 组目录"，而那时两者给出的 `Summary.cwd` 本来就一样（都是 `cwd.orEmpty()`）。
         *
         * 分隔符用 NUL 是因为它不可能出现在路径里，所以键可以无歧义地拆回两个字段
         * （[pathOf] / [fallbackCwdOf]）—— 落盘要的就是这两个字段。
         */
        fun keyOf(path: String, fallbackCwd: String?): String =
            path + SEPARATOR + (fallbackCwd ?: "")

        /** [keyOf] 的第一段。 */
        fun pathOf(key: String): String = key.substringBefore(SEPARATOR)

        /** [keyOf] 的第二段，空串回答 `null`（见 [keyOf]）。 */
        fun fallbackCwdOf(key: String): String? =
            key.substringAfter(SEPARATOR, "").ifEmpty { null }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

        private fun JsonObject.int(key: String): Int? = long(key)?.let { value ->
            // 一个超出 Int 的 `messageCount` 会静默回绕成一个负的条数，所以这里显式判范围。
            if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) null else value.toInt()
        }
    }
}
