package app.pi.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf
import app.pi.host.HostCommands
import app.pi.rpc.ExtensionEntry
import app.pi.rpc.HookMessage
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus

/**
 * 扩展 / MCP 工具自带的 `renderCall` / `renderResult` 的手机侧入口。
 *
 * 语义不变：渲染器就是 SDK 工具定义上的那两个函数，由宿主在 guest 里调，吐出 ANSI 行；
 * 手机只负责把行画成原生文本（和 widget、表面同一条 ANSI → span 路径）。内置工具
 * （read/write/edit/bash/…）有各自的原生卡，不走这里。
 */
fun interface ToolRenderer {
    /** @return 该工具没有渲染器 / 宿主不可达时为 null（调用方回落通用卡）。 */
    suspend fun render(item: ToolCall, expanded: Boolean): HostCommands.ToolRender?
}

val LocalToolRenderer = staticCompositionLocalOf<ToolRenderer?> { null }

/** 工具卡重新取渲染结果的依据：只有这些变了才值得再问宿主一次。 */
internal data class ToolRenderKey(
    val toolCallId: String,
    val status: ToolStatus,
    val outputLength: Int,
    val argsHash: Int,
    val detailsHash: Int,
    val expanded: Boolean,
)

internal fun toolRenderKey(item: ToolCall, expanded: Boolean) =
    ToolRenderKey(
        item.toolCallId,
        item.status,
        // **输出长度只在工具结束后参与键**。流式期间 pi 每 200 ms 推一次进度，
        // 把长度算进键里等于每个 tick 都去 guest 里重渲染一遍整份结果 —— 这是扩展
        // 工具卡「划过去掉帧」的主因。待完成时固定为 0；完成时 status 变化本身
        // 就会换键，正好拿到最终结果。
        if (item.status == ToolStatus.Pending) 0 else item.output.length,
        item.args?.hashCode() ?: 0,
        (item.details ?: item.liveDetails)?.hashCode() ?: 0,
        expanded,
    )

/** 一个工具名一旦被宿主答过"没有渲染器"，同一会话内不再问。 */
internal class ToolRendererCache(private val fetch: suspend (ToolCall, Boolean, Int) -> HostCommands.ToolRender) {
    private val absent = HashSet<String>()
    /**
     * LRU，不是「满了就 clear()」。
     *
     * 原来的 `if (last.size > 200) last.clear()` 会在长会话里把**所有**已渲染的卡一次性作废：
     * 滚回去时每张卡先回落通用样式、再取回行 —— 高度先矮后高，看起来就是「卡片乱跳」。
     * 保留最近用过的 200 条，淘汰最久没用的那一条即可。
     */
    private val last = object : LinkedHashMap<ToolRenderKey, HostCommands.ToolRender>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ToolRenderKey, HostCommands.ToolRender>?): Boolean =
            size > 200
    }

    suspend fun render(item: ToolCall, expanded: Boolean, width: Int): HostCommands.ToolRender? {
        if (item.toolName in absent) return null
        val key = toolRenderKey(item, expanded)
        last[key]?.let { return it }
        val r = fetch(item, expanded, width)
        if (r.call == null && r.result == null) {
            absent += item.toolName
            return null
        }
        last[key] = r
        return r
    }

    fun reset() { absent.clear(); last.clear() }
}

/**
 * 扩展自定义消息（`custom_message`）的渲染入口：扩展用 `registerMessageRenderer(customType, …)`
 * 注册了渲染器就按它画（行 → 原生文本），没注册就回落到 markdown——与 pi 的 `CustomMessageComponent` 一致。
 */
fun interface MessageRenderer {
    /** @return 没有渲染器 / 宿主不可达时为 null。 */
    suspend fun render(item: HookMessage, expanded: Boolean): List<String>?
}

val LocalMessageRenderer = staticCompositionLocalOf<MessageRenderer?> { null }

/** 同一个 `customType` 被答过"没有渲染器"就不再问；结果按（行、内容、展开态）缓存。 */
internal class MessageRendererCache(private val fetch: suspend (HookMessage, Boolean) -> List<String>?) {
    private val absent = HashSet<String>()
    private val last = HashMap<Triple<String, Int, Boolean>, List<String>>()

    suspend fun render(item: HookMessage, expanded: Boolean): List<String>? {
        if (item.customType in absent) return null
        val key = Triple(item.key, item.markdown.hashCode() * 31 + (item.details?.hashCode() ?: 0), expanded)
        last[key]?.let { return it }
        val lines = fetch(item, expanded)
        if (lines == null) {
            absent += item.customType
            return null
        }
        if (last.size > 200) last.clear()
        last[key] = lines
        return lines
    }

    fun reset() { absent.clear(); last.clear() }
}

/**
 * 扩展自己的 `custom` 会话条目：[render] 返回 null = 宿主不可达；[HostCommands.EntryRender.hasRenderer] 为 false
 * = 没有渲染器，pi 什么也不画。结果按（条目、展开态）缓存，「是否有渲染器」按 customType 缓存。
 */
fun interface EntryRenderer {
    suspend fun render(item: ExtensionEntry, expanded: Boolean): List<String>?
}

val LocalEntryRenderer = staticCompositionLocalOf<EntryRenderer?> { null }

/**
 * 扩展的 markdown transformer（`registerMarkdownTransformer`）：TUI 在 Assistant/UserMessageComponent 里
 * 逐条套用。这里只对**已完成**的消息套用（流式时每个 token 往返一次宿主不值得；与 TUI 的差别写在文档里）。
 */
fun interface MarkdownTransform {
    suspend fun transform(markdown: String, messageType: String, messageId: String): String
}

val LocalMarkdownTransform = staticCompositionLocalOf<MarkdownTransform?> { null }

/** 没有任何扩展注册 transformer 时（count == 0）一次都不再问；有则按（消息、原文）缓存。 */
internal class MarkdownTransformCache(private val fetch: suspend (String, String, String) -> HostCommands.MarkdownRender) {
    private var none = false
    private val last = HashMap<Pair<String, Int>, String>()

    suspend fun transform(markdown: String, messageType: String, messageId: String): String {
        if (none || markdown.isEmpty()) return markdown
        val key = messageId to markdown.hashCode()
        last[key]?.let { return it }
        val result = fetch(markdown, messageType, messageId)
        if (result.count == 0) { none = true; return markdown }
        if (last.size > 300) last.clear()
        last[key] = result.markdown
        return result.markdown
    }

    fun reset() { none = false; last.clear() }
}

internal class EntryRendererCache(private val fetch: suspend (ExtensionEntry, Boolean) -> HostCommands.EntryRender) {
    private val absent = HashSet<String>()
    private val present = HashSet<String>()
    private val last = HashMap<Triple<String, Int, Boolean>, List<String>>()

    /** 已确认有渲染器的 customType（可见性过滤用）。 */
    fun hasRenderer(customType: String) = customType in present
    fun known(customType: String) = customType in absent || customType in present

    suspend fun render(item: ExtensionEntry, expanded: Boolean): List<String>? {
        if (item.customType in absent) return null
        val key = Triple(item.key, item.data.hashCode(), expanded)
        last[key]?.let { return it }
        val result = fetch(item, expanded)
        if (!result.hasRenderer) { absent += item.customType; return null }
        present += item.customType
        val lines = result.lines ?: return null
        if (last.size > 200) last.clear()
        last[key] = lines
        return lines
    }

    fun reset() { absent.clear(); present.clear(); last.clear() }
}
