package app.pi.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf
import app.pi.host.HostCommands
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
    val expanded: Boolean,
)

internal fun toolRenderKey(item: ToolCall, expanded: Boolean) =
    ToolRenderKey(item.toolCallId, item.status, item.output.length, item.args?.hashCode() ?: 0, expanded)

/** 一个工具名一旦被宿主答过"没有渲染器"，同一会话内不再问。 */
internal class ToolRendererCache(private val fetch: suspend (ToolCall, Boolean, Int) -> HostCommands.ToolRender) {
    private val absent = HashSet<String>()
    private val last = HashMap<ToolRenderKey, HostCommands.ToolRender>()

    suspend fun render(item: ToolCall, expanded: Boolean, width: Int): HostCommands.ToolRender? {
        if (item.toolName in absent) return null
        val key = toolRenderKey(item, expanded)
        last[key]?.let { return it }
        val r = fetch(item, expanded, width)
        if (r.call == null && r.result == null) {
            absent += item.toolName
            return null
        }
        if (last.size > 200) last.clear()
        last[key] = r
        return r
    }

    fun reset() { absent.clear(); last.clear() }
}
