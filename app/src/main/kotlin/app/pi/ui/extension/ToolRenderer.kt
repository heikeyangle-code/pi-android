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
    /** render.tool formats rows for a terminal width; a different width must not reuse wrapping. */
    val width: Int,
)

/**
 * Progress snapshots are not settled render inputs. pi publishes them about every 200 ms;
 * using `output.length` or `liveDetails` while Pending turns each publication into a guest
 * render RPC. The status transition at completion invalidates the key and includes the final
 * output/details. Keep width in the key because the host renderer receives it and may wrap.
 */
internal fun toolRenderKey(item: ToolCall, expanded: Boolean, width: Int = 0) =
    ToolRenderKey(
        toolCallId = item.toolCallId,
        status = item.status,
        outputLength = if (item.status == ToolStatus.Pending) 0 else item.output.length,
        argsHash = item.args?.hashCode() ?: 0,
        detailsHash = if (item.status == ToolStatus.Pending) 0
        else (item.details ?: item.liveDetails)?.hashCode() ?: 0,
        expanded = expanded,
        width = width,
    )

/**
 * Session-scoped, bounded LRU for extension tool results.
 *
 * Do not hold a global lock across [fetch]: rendering one slow guest tool must not serialize
 * unrelated visible cards. Concurrent misses for one key can duplicate a request, but cannot
 * block every other card or hold the UI-facing caller behind another card's round trip.
 */
internal class ToolRendererCache(
    private val fetch: suspend (ToolCall, Boolean, Int) -> HostCommands.ToolRender,
) {
    private val lock = Any()
    private val absent = HashSet<String>()
    private val last = object : LinkedHashMap<ToolRenderKey, HostCommands.ToolRender>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ToolRenderKey, HostCommands.ToolRender>?,
        ): Boolean = size > MAX_ENTRIES
    }

    suspend fun render(item: ToolCall, expanded: Boolean, width: Int): HostCommands.ToolRender? {
        val key = toolRenderKey(item, expanded, width)
        synchronized(lock) {
            if (item.toolName in absent) return null
            last[key]?.let { return it }
        }

        // Never hold the cache lock while waiting for the guest. The caller owns cancellation;
        // a row leaving composition can cancel its own request without stalling other rows.
        val result = fetch(item, expanded, width)
        synchronized(lock) {
            if (result.call == null && result.result == null) {
                absent += item.toolName
                return null
            }
            // Another caller may have completed the same key while this request was in flight.
            // Prefer its cached value so all consumers converge on the same immutable result.
            last[key]?.let { return it }
            last[key] = result
            return result
        }
    }

    fun reset() = synchronized(lock) {
        absent.clear()
        last.clear()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
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
 * = 没有渲染器。结果按（条目、展开态）缓存，「是否有渲染器」按 customType 缓存。
 */
fun interface EntryRenderer {
    suspend fun render(item: ExtensionEntry, expanded: Boolean): List<String>?
}

val LocalEntryRenderer = staticCompositionLocalOf<EntryRenderer?> { null }

/**
 * 扩展的 markdown transformer（`registerMarkdownTransformer`）：这里只对已完成的消息套用，
 * 避免流式时每个 token 都往返 guest。
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
