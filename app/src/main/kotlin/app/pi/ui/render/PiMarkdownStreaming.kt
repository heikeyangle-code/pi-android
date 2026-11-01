package app.pi.ui.render

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 这条 Markdown 表面是否还在流式生成。
 *
 * ## 为什么是组合局部，而不是只给 `PiMarkdownText` 加参数
 *
 * 代码围栏、块级公式和 HTML 的组件是 Markdown 渲染器按 AST 节点分派到的，
 * 离 `AssistantTextBlock` 那个知道 `item.streaming` 的调用点很远；库的
 * `MarkdownComponents` 又是 `remember` 一次、全程复用的（参数进不去）。
 * 和 [LocalPiImageClick]、[LocalPiImageTransformer] 一样，走组合局部是唯一
 * 不需要把整个组件集按 streaming 重建的缝。
 *
 * ## 默认 `false`
 *
 * 只有 `AssistantTextBlock` 会把 `AssistantText.streaming` 传进来。工具卡、
 * 分支摘要、压缩摘要、技能卡、用户气泡这些表面本来就是已完成的文本，默认
 * `false` 让它们的渲染路径与改动前逐字节相同。
 *
 * **它不是“这一块已经完整”的充分条件。** 流式消息里一个已经闭合的代码围栏
 * 也可以完整渲染；反过来，`streaming = false` 的消息也可能包含未闭合的结构。
 * 富渲染必须同时检查结构完整性（围栏结束 token、`$$` 闭合、JSON/XML 解析成功），
 * 不能只读这个布尔值。
 */
internal val LocalPiMarkdownStreaming = staticCompositionLocalOf { false }
