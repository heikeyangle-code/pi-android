package app.pi.ui.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.render.rich.html.PiHtmlBlockView
import app.pi.ui.render.rich.html.PiHtmlDocument
import app.pi.ui.render.rich.html.PiHtmlFailure
import app.pi.ui.render.rich.html.PiHtmlParseResult
import app.pi.ui.render.rich.html.splitHtmlSvg
import app.pi.ui.render.rich.svg.PiSvgFenceView

/**
 * 一块 HTML：夹着 `<svg>` 时按 SVG 切开，SVG 段交给 SVG 渲染器（含动画），其余段走 HTML 渲染器；
 * 没有 SVG 就是原来的单块。正文里的 HTML 块和 html 围栏的「预览」共用这一处。
 *
 * 当 HTML 是包裹 `<svg>` 的整页骨架（如 `<!DOCTYPE html><html><head><style>...</style></head><body>` 和
 * `</body></html>`）时，SVG 前后切出的空壳片段经安全策略清理后不含任何可见内容，直接跳过而不是画成
 * 「HTML 安全清理后为空，已回退源码」的报错代码框。
 */
@Composable
internal fun PiHtmlSegments(text: String, settled: Boolean, modifier: Modifier = Modifier) {
    val parts = remember(text) { splitHtmlSvg(text) }
    if (parts == null) {
        PiHtmlBlockView(source = text, settled = settled, modifier = modifier)
        return
    }
    Column(modifier) {
        parts.forEach { part ->
            if (part.svg) {
                PiSvgFenceView(source = part.text, settled = settled)
            } else if (part.text.isNotBlank()) {
                val skipShell = remember(part.text, settled) {
                    if (!settled) {
                        false
                    } else {
                        val res = PiHtmlDocument.parse(source = part.text, settled = true)
                        res is PiHtmlParseResult.Failure &&
                            (res.reason == PiHtmlFailure.Empty || res.reason == PiHtmlFailure.EmptyAfterPolicy)
                    }
                }
                if (!skipShell) {
                    PiHtmlBlockView(source = part.text, settled = settled)
                }
            }
        }
    }
}

/** html 围栏：默认是高亮源码，菜单里「预览渲染」切到渲染结果，再切回来。 */
@Composable
internal fun PiHtmlFence(
    code: String,
    info: app.pi.ui.render.rich.FenceInfo,
    highlighted: PiHighlightedCode,
    style: androidx.compose.ui.text.TextStyle,
    palette: app.pi.ui.theme.PiPalette,
    classic: Boolean,
) {
    var preview by androidx.compose.runtime.saveable.rememberSaveable(code) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (!preview) {
        PiRichCodeBlock(
            code = code, info = info, highlighted = highlighted, style = style,
            palette = palette, classic = classic,
            extraMenu = listOf(PiCardMenuItem(tr("预览渲染", "Preview Rendered")) { preview = true }),
        )
    } else {
        PiRichCardFrame(
            code = code, info = info, palette = palette,
            menu = listOf(PiCardMenuItem(tr("查看源码", "View Source")) { preview = false }),
        ) {
            PiHtmlSegments(
                text = code,
                settled = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
