package app.pi.paui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * PAUI v1 的节点树（设计见 `docs/paui-design.md`）：扩展发来的是**数据**，不是代码；
 * 这里把它解析成一棵受限的、带降级的树，渲染器只认这棵树。纯逻辑、不依赖 Android，可单测。
 *
 * 规则（设计文档 §2.6 / §2.7）：
 *  - 未知字段忽略；未知节点类型渲染它的 `fallback`，没有 `fallback` 就渲染一条"需要更新版本"的提示，**不破坏整页**；
 *  - 深度 ≤ [MAX_DEPTH]，节点数 ≤ [MAX_NODES]，超了整棵拒绝并给出可见的错误，不静默截断；
 *  - 颜色只用语义令牌（[Tone]），不传颜色值，主题/暗色/对比度由 App 负责。
 */
sealed interface PauiNode {
    val id: String?

    data class Column(override val id: String?, val children: List<PauiNode>, val gap: Int) : PauiNode
    data class Row(override val id: String?, val children: List<PauiNode>, val gap: Int) : PauiNode
    data class Box(override val id: String?, val children: List<PauiNode>) : PauiNode
    data class Spacer(override val id: String?, val size: Int) : PauiNode
    data class Divider(override val id: String?) : PauiNode
    data class Text(override val id: String?, val text: String, val tone: Tone, val mono: Boolean, val bold: Boolean) : PauiNode
    data class Markdown(override val id: String?, val text: String) : PauiNode
    data class Code(override val id: String?, val text: String, val language: String?) : PauiNode
    /** 终端文本的兜底：[text] 已剥掉 SGR 以外的控制序列后的原文（保留 SGR 供渲染器着色）。 */
    data class Ansi(override val id: String?, val lines: List<String>) : PauiNode
    data class Button(override val id: String?, val label: String, val primary: Boolean, val enabled: Boolean) : PauiNode
    data class Toggle(override val id: String?, val label: String, val detail: String?, val checked: Boolean, val enabled: Boolean) : PauiNode
    data class Item(val id: String, val title: String, val detail: String?, val selected: Boolean)
    data class ListNode(override val id: String?, val items: List<Item>) : PauiNode
    data class Notice(override val id: String?, val text: String, val tone: Tone) : PauiNode
    data class Badge(override val id: String?, val text: String, val tone: Tone) : PauiNode
    data class Progress(override val id: String?, val fraction: Float?, val label: String?) : PauiNode

    /** 渲染器不认识、又没有 `fallback` 的节点：显示一行提示并上报，而不是整页失败。 */
    data class Unsupported(override val id: String?, val type: String) : PauiNode

    enum class Tone { Normal, Accent, Muted, Dim, Success, Warning, Error;
        companion object {
            fun of(name: String?): Tone = when (name?.lowercase()) {
                "accent" -> Accent
                "muted" -> Muted
                "dim" -> Dim
                "success" -> Success
                "warning" -> Warning
                "error" -> Error
                else -> Normal
            }
        }
    }
}

/** 一棵解析好的 surface 内容，加上解析过程中发现的问题（供上报给扩展作者）。 */
data class PauiTree(val root: PauiNode, val unsupported: List<String>)

/** 用户操作回传给扩展的事件（上行 `/paui` 命令的载荷）。 */
data class PauiEvent(val surface: String, val node: String, val type: String, val value: String? = null)

object PauiCodec {
    const val MAX_DEPTH = 24
    const val MAX_NODES = 2000
    const val MAX_BYTES = 1 shl 20

    sealed interface Result {
        data class Ok(val tree: PauiTree) : Result
        data class Rejected(val reason: String) : Result
    }

    /** 渲染器当前认识的类型集合；握手时作为 `caps` 上报，扩展据此选择 fallback。 */
    val KNOWN_TYPES: Set<String> = setOf(
        "column", "row", "box", "spacer", "divider", "text", "markdown", "code", "ansi",
        "button", "toggle", "list", "notice", "badge", "progress",
    )

    fun parse(text: String): Result {
        if (text.length > MAX_BYTES) return Result.Rejected("界面描述超过 ${MAX_BYTES / 1024} KiB")
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Result.Rejected("不是合法的 JSON")
        }
        val state = State()
        val root = state.node(element, 1)
        state.rejected?.let { return Result.Rejected(it) }
        return Result.Ok(PauiTree(root ?: PauiNode.Unsupported(null, "?"), state.unsupported))
    }

    private class State {
        var count = 0
        var rejected: String? = null
        val unsupported = mutableListOf<String>()

        fun node(element: JsonElement, depth: Int): PauiNode? {
            if (rejected != null) return null
            if (depth > MAX_DEPTH) {
                rejected = "界面嵌套超过 $MAX_DEPTH 层"
                return null
            }
            if (++count > MAX_NODES) {
                rejected = "界面节点超过 $MAX_NODES 个"
                return null
            }
            val o = element as? JsonObject ?: run {
                unsupported += "非对象节点"
                return PauiNode.Unsupported(null, "?")
            }
            val id = o.str("id")
            val type = o.str("type") ?: run {
                unsupported += "缺少 type"
                return PauiNode.Unsupported(id, "?")
            }
            fun kids(): List<PauiNode> = (o["children"] as? JsonArray).orEmpty().mapNotNull { node(it, depth + 1) }
            val tone = PauiNode.Tone.of(o.str("tone"))
            return when (type) {
                "column" -> PauiNode.Column(id, kids(), o.int("gap", 8))
                "row" -> PauiNode.Row(id, kids(), o.int("gap", 8))
                "box" -> PauiNode.Box(id, kids())
                "spacer" -> PauiNode.Spacer(id, o.int("size", 8))
                "divider" -> PauiNode.Divider(id)
                "text" -> PauiNode.Text(id, o.str("text").orEmpty(), tone, o.bool("mono", false), o.bool("bold", false))
                "markdown" -> PauiNode.Markdown(id, o.str("text").orEmpty())
                "code" -> PauiNode.Code(id, o.str("text").orEmpty(), o.str("language"))
                "ansi" -> PauiNode.Ansi(
                    id,
                    (o["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(::sanitizeAnsi),
                )
                "button" -> PauiNode.Button(id, o.str("label").orEmpty(), o.bool("primary", false), o.bool("enabled", true))
                "toggle" -> PauiNode.Toggle(id, o.str("label").orEmpty(), o.str("detail"), o.bool("checked", false), o.bool("enabled", true))
                "list" -> PauiNode.ListNode(
                    id,
                    (o["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val io = item as? JsonObject ?: return@mapNotNull null
                        PauiNode.Item(io.str("id") ?: return@mapNotNull null, io.str("title").orEmpty(), io.str("detail"), io.bool("selected", false))
                    },
                )
                "notice" -> PauiNode.Notice(id, o.str("text").orEmpty(), tone)
                "badge" -> PauiNode.Badge(id, o.str("text").orEmpty(), tone)
                "progress" -> PauiNode.Progress(id, o.str("value")?.toFloatOrNull()?.coerceIn(0f, 1f), o.str("label"))
                else -> {
                    unsupported += type
                    val fallback = o["fallback"]
                    if (fallback != null) node(fallback, depth + 1) else PauiNode.Unsupported(id, type)
                }
            }
        }
    }

    /**
     * 只保留 SGR（`ESC [ … m`）和 OSC 8 超链接，其余终端控制序列（光标移动、清屏、改标题…）一律丢弃，
     * 防止扩展用控制序列做光标/标题注入（设计文档 §2.7）。
     */
    fun sanitizeAnsi(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c != '\u001b') {
                if (c >= ' ' || c == '\t') out.append(c)
                i++
                continue
            }
            val next = line.getOrNull(i + 1)
            when (next) {
                '[' -> {
                    var j = i + 2
                    while (j < line.length && line[j] in ' '..'?') j++
                    val final = line.getOrNull(j)
                    if (final == 'm') out.append(line, i, j + 1)
                    i = if (final != null) j + 1 else line.length
                }
                ']' -> {
                    var j = i + 2
                    while (j < line.length && line[j] != '\u0007' && !(line[j] == '\u001b' && line.getOrNull(j + 1) == '\\')) j++
                    val body = line.substring(i + 2, minOf(j, line.length))
                    val end = if (j < line.length && line[j] == '\u001b') j + 2 else j + 1
                    if (body.startsWith("8;")) out.append(line, i, minOf(end, line.length))
                    i = minOf(end, line.length)
                }
                else -> i += 2
            }
        }
        return out.toString()
    }

    /** 去掉所有转义序列后的纯文本（给无障碍朗读、搜索、降级显示用）。 */
    fun plain(line: String): String =
        sanitizeAnsi(line).replace(Regex("\u001b\\[[0-9;]*m"), "").replace(Regex("\u001b\\]8;[^\u0007\u001b]*(\u0007|\u001b\\\\)"), "")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default
    private fun JsonObject.bool(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
}
