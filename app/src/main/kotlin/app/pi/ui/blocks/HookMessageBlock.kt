package app.pi.ui.blocks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.HookMessage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * `hook-message` (docs/pi-android-ui-spec.md §7.4): an extension-injected
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

    BlockColumn(modifier) {
        BlockCard(
            color = palette.customMessageBg,
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
        ) {
            // F28: the card's content is the target (pi's rule — see
            // `ToggleContent`); `enabled` keeps a short message from toggling into
            // nothing.
            ToggleContent(
                expanded = expanded,
                onToggle = { expanded = !expanded },
                enabled = collapsible,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardChip(
                        text = item.customType.ifEmpty { "extension" },
                        color = palette.customMessageLabel,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.weight(1f))
                    if (collapsible) ExpandLabel(expanded)
                }
                if (renderedLines != null) {
                    // 折叠只给预览：不少扩展的消息渲染器不看 `expanded`，宿主怎么问都吐全量，
                    // 所以在这里裁剪，卡片才收得起来（工具卡同理，见 `ToolCallBlock`）。
                    val shown = if (expanded || renderedLines.size <= COLLAPSED_RENDER_LINES) {
                        renderedLines
                    } else {
                        renderedLines.take(COLLAPSED_RENDER_LINES)
                    }
                    Column(Modifier.padding(top = PiSpacing.tiny)) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            shown.forEach { app.pi.ui.extension.PanelLine(it, wrap = false) }
                        }
                        val hidden = renderedLines.size - shown.size
                        if (hidden > 0) {
                            Text(
                                text = "… 还有 $hidden 行，点卡片展开",
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )
                        }
                    }
                } else if (expanded || !collapsible) {
                // Expanded (or short enough that there is nothing to collapse):
                // markdown. The renderer's own hook-message component builds a
                // `Markdown` over the message body
                // (`packages/coding-agent/src/modes/interactive/components/custom-message.ts:107-111`),
                // and the engine names the field `markdown` for the same reason.
                // `custom-message.ts:107-111` builds the `Markdown` with an explicit base
                // colour, and it is not `text`:
                //   `color: (text) => theme.fg("customMessageText", text)`
                // (`components/custom-message.js:83-85`). All four `customMessageBg` cards take
                // that same token — this one used to fall through to `text` (`PiMarkdownTheme`'s
                // `textColor ?: palette.text`), which is the one thing that made it a different
                // card from `SkillInvocationBlock`.
                PiMarkdownText(
                    markdown = item.markdown.ifEmpty { "（空消息）" },
                    modifier = Modifier.fillMaxWidth(),
                    textColor = palette.customMessageText,
                )
            } else {
                // Collapsed preview, unchanged: plain text cut to four lines.
                // `custom-message.ts` has no collapsed text branch — it renders
                // the markdown body either way — so the line count comes from
                // this block's own affordance, not from pi.
                    ProseText(
                        text = item.markdown.ifEmpty { "（空消息）" },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.customMessageText,
                        maxLines = COLLAPSED_MESSAGE_LINES,
                    )
                }
            }
        }
    }
}

/** The collapsed preview length this block already used for a long hook message. */
private const val COLLAPSED_MESSAGE_LINES = 4

/** 扩展渲染器给的消息，折叠态最多画几行。 */
private const val COLLAPSED_RENDER_LINES = 5
