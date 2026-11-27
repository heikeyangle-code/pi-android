package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.HookMessage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 扩展自定义消息（`custom_message` / `registerMessageRenderer`）：
 * 采用一体化精简卡片（柔和顶栏 + 细竖色标 + 清晰分割线 + 干净正文画布），
 * 既保留 Pi 主题的 `customMessageBg` / `customMessageLabel` 语义色，又避免厚重默认 M3 色块与圆药丸。
 */
@Composable
fun HookMessageBlock(
    item: HookMessage,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    var expanded by rememberSaveable(item.key, defaultExpanded) { mutableStateOf(defaultExpanded) }
    val messageRenderer = app.pi.ui.extension.LocalMessageRenderer.current
    val rendered by produceState<List<String>?>(null, item.key, item.markdown, item.details, expanded, messageRenderer) {
        value = messageRenderer?.render(item, expanded) ?: value
    }
    val renderedLines = rendered
    val collapsible = renderedLines != null || item.markdown.length > 240 || item.markdown.count { it == '\n' } > 4
    val copyBody = remember(renderedLines, item.markdown) {
        if (renderedLines != null) {
            app.pi.ui.extension.trimEmptyEdgeLines(app.pi.ui.extension.panelRows(renderedLines))
                .joinToString("\n") { app.pi.ui.extension.chromeText(it) }
        } else {
            item.markdown
        }
    }
    val cardShape = RoundedCornerShape(10.dp)
    val bodyBg = remember(palette) { lerp(palette.cardBg, palette.customMessageBg, 0.22f) }
    val headerBg = remember(palette) { lerp(palette.cardBg, palette.customMessageBg, 0.44f) }
    val borderColor = remember(palette) {
        lerp(palette.borderMuted, palette.customMessageLabel, 0.18f).copy(alpha = 0.56f)
    }

    ToolActionMenu(command = null, output = copyBody, fullOutputPath = null) {
        BlockColumn(modifier) {
            SelectableContent {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(bodyBg)
                        .border(0.5.dp, borderColor, cardShape),
                ) {
                    ToggleContent(
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        enabled = collapsible,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(headerBg)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(palette.customMessageLabel.copy(alpha = 0.85f)),
                            )
                            Text(
                                text = item.customType.ifEmpty { "extension" },
                                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Medium),
                                color = palette.customMessageLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (collapsible) ExpandLabel(expanded)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(palette.borderMuted.copy(alpha = 0.36f)),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (renderedLines != null) {
                                app.pi.ui.extension.RenderedAnsiBlock(
                                    lines = renderedLines,
                                    expanded = expanded,
                                    collapsedLimit = COLLAPSED_MESSAGE_LINES,
                                    expandHint = { hidden -> tr("还有 $hidden 行，点击展开", "$hidden more lines, tap to expand") },
                                )
                            } else {
                                val transform = app.pi.ui.extension.LocalMarkdownTransform.current
                                val transformedMarkdown by produceState(item.markdown, item.key, item.markdown, transform) {
                                    value = if (item.markdown.isEmpty() || transform == null) {
                                        item.markdown
                                    } else {
                                        transform.transform(item.markdown, "custom", item.key)
                                    }
                                }
                                if (expanded || !collapsible) {
                                    PiMarkdownText(
                                        markdown = transformedMarkdown.ifEmpty { tr("（空消息）", "(Empty message)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        textColor = palette.customMessageText,
                                        immediate = expanded,
                                    )
                                } else {
                                    ProseText(
                                        text = transformedMarkdown.ifEmpty { tr("（空消息）", "(Empty message)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = palette.customMessageText,
                                        maxLines = COLLAPSED_MESSAGE_LINES,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The collapsed preview length this block already used for a long hook message. */
private const val COLLAPSED_MESSAGE_LINES = 4
