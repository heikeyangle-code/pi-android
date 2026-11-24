package app.pi.ui.render.rich.html

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.render.LocalPiCardMenuSlot
import app.pi.ui.render.PiRichCardFrame
import app.pi.ui.render.rememberPiHighlightedCode
import app.pi.ui.render.rich.FenceInfo
import app.pi.ui.render.rich.rememberTreeParse
import app.pi.ui.theme.PiTheme

/**
 * 一个裸 HTML 块的安全子集视图（方案 §六）。
 *
 * 这是接入调用方唯一需要认识的入口；解析、白名单协作和渲染分别在
 * [PiHtmlDocument] 与 [PiHtmlRenderer]。
 *
 * ## 回退契约
 *
 * 以下情况**原样显示 [source]**，并在上方给一行原因：
 * - [settled] 为 `false`：流式生成中的 HTML 不提前解析成交互组件（§十四.3）；
 * - [PiHtmlPolicy] 拒绝（字符 / 节点 / 深度超限，或策略自身解析失败）；
 * - 超过字符 / 节点 / 深度上限；
 * - jsoup 解析失败或安全清理后为空。
 *
 * 危险标签、事件属性和非法协议由 [PiHtmlPolicy] **清理**而不是在这里回退；
 * 清理后的片段才进入 [PiHtmlDocument]。因此“策略删除了危险子树”不属于本视图的
 * 回退条件 —— 那是策略层的决定，渲染器只对收到的安全片段负责。
 *
 * 回退时不截断源码、不做 HTML 转义、不把标签当结构执行；它只是普通文字。这样
 * 即使安全策略或解析器出错，模型输出也不会从屏幕上消失。
 *
 * ## 为什么没有 WebView
 *
 * 安全 HTML 只需要 Compose 原生组件。任何 WebView 都会重新引入脚本、外部资源和
 * 生命周期问题，与方案 §六.3 的禁令冲突。
 *
 * @param source 模型输出的原始 HTML 块。
 * @param settled 生成是否结束；`false` 时只显示源码。
 * @param modifier 外层修饰符。
 */
@Composable
fun PiHtmlBlockView(
    source: String,
    settled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // jsoup 解析放后台并缓存（小块同步，见 `rememberTreeParse`）：大块第一次出现不卡主线程。
    val result = rememberTreeParse("html", source, settled) {
        PiHtmlDocument.parse(source = source, settled = settled)
    }

    when (result) {
        null -> HtmlSourceFallback(source = source, notice = null, modifier = modifier)

        is PiHtmlParseResult.Success -> PiHtmlRenderer(
            nodes = result.nodes,
            modifier = modifier,
        )

        is PiHtmlParseResult.Failure -> HtmlSourceFallback(
            source = source,
            notice = htmlFailureNotice(result.reason),
            modifier = modifier,
        )
    }
}

/**
 * 回退视图：一行原因 + 原始源码。
 */
@Composable
private fun HtmlSourceFallback(
    source: String,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val highlighted = rememberPiHighlightedCode(source, "html")
    val inCardFrame = LocalPiCardMenuSlot.current != null
    if (inCardFrame) {
        // Already wrapped by PiHtmlFence's PiRichCardFrame: avoid nested double card chrome.
        Column(modifier = modifier.fillMaxWidth()) {
            if (!notice.isNullOrEmpty()) {
                Text(
                    text = notice,
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (source.isNotBlank()) {
                Text(
                    text = highlighted.text,
                    style = PiTheme.text.code.copy(
                        color = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    val info = remember { FenceInfo.parse("html") }
    PiRichCardFrame(
        code = source,
        info = info,
        palette = palette,
        status = notice,
    ) {
        if (source.isNotBlank()) {
            Text(
                text = highlighted.text,
                style = PiTheme.text.code.copy(
                    color = if (highlighted.languageKnown) palette.text else palette.mdCodeBlock,
                ),
                modifier = modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

private fun htmlFailureNotice(reason: PiHtmlFailure): String? {
    val limits = PiHtmlDocument.DEFAULT_LIMITS
    val text = when (reason) {
        PiHtmlFailure.NotSettled -> null
        PiHtmlFailure.Empty -> null
        PiHtmlFailure.TooLarge -> tr("超过 ${limits.maxChars / 1024} KiB 上限", "Exceeds ${limits.maxChars / 1024} KiB limit")
        PiHtmlFailure.TooDeep -> tr("嵌套超过 ${limits.maxDepth} 层", "Nesting exceeds ${limits.maxDepth} levels")
        PiHtmlFailure.TooManyNodes -> tr("节点超过 ${limits.maxNodes} 个", "Exceeds ${limits.maxNodes} nodes")
        PiHtmlFailure.EmptyAfterPolicy -> tr("无可预览正文内容", "No previewable content")
        PiHtmlFailure.Malformed -> null
        is PiHtmlFailure.Rejected -> {
            val detail = oneLine(reason.reason, maxChars = 120)
            detail.ifEmpty { tr("未通过安全策略", "Blocked by safety policy") }
        }
    } ?: return null
    return oneLine(text, maxChars = 180)
}

private fun oneLine(raw: String, maxChars: Int): String {
    val out = StringBuilder(minOf(raw.length, maxChars))
    var lastWasSpace = false
    for (ch in raw) {
        if (out.length >= maxChars) break
        if (ch == '\n' || ch == '\r' || ch == '\t') {
            if (!lastWasSpace && out.isNotEmpty()) {
                out.append(' ')
                lastWasSpace = true
            }
        } else {
            out.append(ch)
            lastWasSpace = ch == ' '
        }
    }
    return out.toString().trim()
}
