package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.i18n.tr
import app.pi.protocol.HookMessage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `hook-message`: an extension-injected
 * custom message. It must be unmistakably *not* the user and *not* the model, so
 * it gets the `customMessageBg` surface, a `customMessageLabel` stripe and the
 * mono `customType` label — three channels at once.
 */
@Composable
fun HookMessageBlock(
    item: HookMessage,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // 扩展用 `registerMessageRenderer` 给这个 customType 注册了渲染器就按它画（pi 的
    // `CustomMessageComponent` 同样优先走渲染器，没有才是 markdown）。行由宿主按 `expanded` 渲染。
    val messageRenderer = app.pi.ui.extension.LocalMessageRenderer.current
    val rendered by produceState<List<String>?>(null, item.key, item.markdown, item.details, expanded, messageRenderer) {
        value = messageRenderer?.render(item, expanded) ?: value
    }
    val renderedLines = rendered
    val collapsible = renderedLines != null || item.markdown.length > 240 || item.markdown.count { it == '\n' } > 4
    val copyBody = androidx.compose.runtime.remember(renderedLines, item.markdown) {
        if (renderedLines != null) {
            app.pi.ui.extension.trimEmptyEdgeLines(app.pi.ui.extension.panelRows(renderedLines))
                .joinToString("\n") { app.pi.ui.extension.chromeText(it) }
        } else {
            item.markdown
        }
    }

    ToolActionMenu(command = null, output = copyBody, fullOutputPath = null) {
        BlockColumn(modifier) {
            BlockCard(
                color = palette.customMessageBg,
                borderColor = palette.customMessageLabel.copy(alpha = 0.22f),
            ) {
                // F28: the card's content is the target (pi's rule — see
                // `ToggleContent`); `enabled` keeps a short message from toggling into
                // nothing.
                ToggleContent(
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    enabled = collapsible,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PiSpacing.inline),
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            CardChip(
                                text = item.customType.ifEmpty { "extension" },
                                color = palette.customMessageLabel,
                            )
                        }
                        if (collapsible) ExpandLabel(expanded)
                    }
                    if (renderedLines != null) {
                        app.pi.ui.extension.RenderedAnsiBlock(
                            lines = renderedLines,
                            expanded = expanded,
                            collapsedLimit = COLLAPSED_MESSAGE_LINES,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                            expandHint = { hidden -> tr("还有 $hidden 行，点击展开", "$hidden more lines, tap to expand") },
                        )
                    } else if (expanded || !collapsible) {
                        PiMarkdownText(
                            markdown = item.markdown.ifEmpty { tr("（空消息）", "(Empty message)") },
                            modifier = Modifier.fillMaxWidth(),
                            textColor = palette.customMessageText,
                            immediate = expanded,
                        )
                    } else {
                        ProseText(
                            text = item.markdown.ifEmpty { tr("（空消息）", "(Empty message)") },
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

/** The collapsed preview length this block already used for a long hook message. */
private const val COLLAPSED_MESSAGE_LINES = 4
