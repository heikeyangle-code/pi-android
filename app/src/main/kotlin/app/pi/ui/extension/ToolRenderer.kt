package app.pi.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf
import app.pi.host.HostCommands
import app.pi.protocol.ExtensionEntry
import app.pi.protocol.HookMessage
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolStatus

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

/** 被扩展显式覆盖渲染器（`registerToolRenderer` 或 `registerTool`）的内置工具集合。 */
val LocalOverriddenNativeTools = staticCompositionLocalOf<Set<String>> { emptySet() }

/** 工具卡重新取渲染结果的依据：只有这些变了才值得再问宿主一次。 */
internal data class ToolRenderKey(
    val toolCallId: String,
    val status: ToolStatus,
    val outputLength: Int,
    val argsHash: Int,
    val detailsHash: Int,
    val imagesCount: Int,
    val durationMs: Long?,
    val expanded: Boolean,
    val width: Int = 0,
)

internal fun toolRenderKey(item: ToolCall, expanded: Boolean, width: Int = 0) =
    ToolRenderKey(
        toolCallId = item.toolCallId,
        status = item.status,
        outputLength = item.output.length,
        argsHash = item.args?.hashCode() ?: 0,
        detailsHash = (item.details ?: item.liveDetails)?.hashCode() ?: 0,
        imagesCount = item.images.size,
        durationMs = item.durationMs,
        expanded = expanded,
        width = width,
    )

/** 一个工具名一旦被宿主答过"没有渲染器"，同一会话内不再问（重载后重置）。 */
internal class ToolRendererCache(private val fetch: suspend (ToolCall, Boolean, Int) -> HostCommands.ToolRender) {
    private val absent = HashSet<String>()
    private val last = HashMap<ToolRenderKey, HostCommands.ToolRender>()

    suspend fun render(item: ToolCall, expanded: Boolean, width: Int): HostCommands.ToolRender? {
        if (item.toolName in absent) return null
        val key = toolRenderKey(item, expanded, width)
        last[key]?.let { return it }
        val r = fetch(item, expanded, width)
        if (!r.hasRenderer) {
            absent += item.toolName
            return null
        }
        if (last.size > 200) last.clear()
        last[key] = r
        return r
    }

    /** 仅失效单张工具卡的缓存（异步动画/定时刷新不牵连其余卡片）。 */
    fun invalidateTool(toolCallId: String) {
        val it = last.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key.toolCallId == toolCallId) it.remove()
        }
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

/** 同一个 `customType` 被答过"没有渲染器"就不再问；结果按（行、内容、展开态、列宽）缓存。 */
internal class MessageRendererCache(private val fetch: suspend (HookMessage, Boolean, Int) -> List<String>?) {
    private data class Key(val itemKey: String, val contentHash: Int, val expanded: Boolean, val width: Int)
    private val absent = HashSet<String>()
    private val last = HashMap<Key, List<String>>()

    suspend fun render(item: HookMessage, expanded: Boolean, width: Int): List<String>? {
        if (item.customType in absent) return null
        val key = Key(item.key, item.markdown.hashCode() * 31 + (item.details?.hashCode() ?: 0), expanded, width)
        last[key]?.let { return it }
        val lines = fetch(item, expanded, width)
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
 * = 没有渲染器，pi 什么也不画。结果按（条目、展开态、列宽）缓存，「是否有渲染器」按 customType 缓存。
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

/** 没有任何扩展注册 transformer 时（count == 0）一次都不再问；有则按（消息、类型、列宽、原文）缓存。 */
internal class MarkdownTransformCache(private val fetch: suspend (String, String, String, Int) -> HostCommands.MarkdownRender) {
    private data class Key(val messageId: String, val messageType: String, val width: Int, val hash: Int)
    private var none = false
    private val last = HashMap<Key, String>()

    suspend fun transform(markdown: String, messageType: String, messageId: String, width: Int): String {
        if (none || markdown.isEmpty()) return markdown
        val key = Key(messageId, messageType, width, markdown.hashCode())
        last[key]?.let { return it }
        val result = fetch(markdown, messageType, messageId, width)
        if (result.count == 0) { none = true; return markdown }
        if (last.size > 300) last.clear()
        last[key] = result.markdown
        return result.markdown
    }

    fun reset() { none = false; last.clear() }
}

internal class EntryRendererCache(private val fetch: suspend (ExtensionEntry, Boolean, Int) -> HostCommands.EntryRender) {
    private data class Key(val itemKey: String, val dataHash: Int, val expanded: Boolean, val width: Int)
    private val absent = HashSet<String>()
    private val present = HashSet<String>()
    private val last = HashMap<Key, List<String>>()

    /** 已确认有渲染器的 customType（可见性过滤用）。 */
    fun hasRenderer(customType: String) = customType in present
    fun known(customType: String) = customType in absent || customType in present

    suspend fun render(item: ExtensionEntry, expanded: Boolean, width: Int): List<String>? {
        if (item.customType in absent) return null
        val key = Key(item.key, item.data.hashCode(), expanded, width)
        last[key]?.let { return it }
        val result = fetch(item, expanded, width)
        if (!result.hasRenderer) { absent += item.customType; return null }
        present += item.customType
        val lines = result.lines ?: return null
        if (last.size > 200) last.clear()
        last[key] = lines
        return lines
    }

    fun reset() { absent.clear(); present.clear(); last.clear() }
}
