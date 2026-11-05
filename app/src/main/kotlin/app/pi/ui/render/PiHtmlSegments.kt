package app.pi.ui.render

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.pi.ui.render.rich.html.PiHtmlBlockView
import app.pi.ui.render.rich.html.splitHtmlSvg
import app.pi.ui.render.rich.svg.PiSvgFenceView

/**
 * 一块 HTML：夹着 `<svg>` 时按 SVG 切开，SVG 段交给 SVG 渲染器（含动画），其余段走 HTML 渲染器；
 * 没有 SVG 就是原来的单块。正文里的 HTML 块和 html 围栏的「预览」共用这一处。
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
                PiHtmlBlockView(source = part.text, settled = settled)
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
            extraMenu = listOf(PiCardMenuItem("预览渲染") { preview = true }),
        )
    } else {
        PiRichCardFrame(
            code = code, info = info, palette = palette,
            menu = listOf(PiCardMenuItem("查看源码") { preview = false }),
        ) {
            PiHtmlSegments(
                text = code,
                settled = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
