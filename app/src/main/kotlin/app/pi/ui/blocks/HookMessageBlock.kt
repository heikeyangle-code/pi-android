package app.pi.ui.blocks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val collapsible = item.markdown.length > 240 || item.markdown.count { it == '\n' } > 4

    BlockColumn(modifier) {
        BlockCard(
            color = palette.customMessageBg,
            borderColor = palette.customMessageLabel.copy(alpha = 0.35f),
        ) {
            ToggleRow(
                expanded = expanded,
                onToggle = { if (collapsible) expanded = !expanded },
            ) {
                AccentStripe(palette.customMessageLabel, 20.dp)
                Spacer(Modifier.width(PiSpacing.inner))
                Text(
                    text = item.customType.ifEmpty { "extension" },
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.monoSmall,
                    color = palette.customMessageLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collapsible) ExpandLabel(expanded)
            }
            if (expanded || !collapsible) {
                // Expanded (or short enough that there is nothing to collapse):
                // markdown. The renderer's own hook-message component builds a
                // `Markdown` over the message body
                // (`packages/coding-agent/src/modes/interactive/components/custom-message.ts:107-111`),
                // and the engine names the field `markdown` for the same reason.
                PiMarkdownText(
                    markdown = item.markdown.ifEmpty { "（空消息）" },
                    modifier = Modifier.fillMaxWidth(),
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

/** The collapsed preview length this block already used for a long hook message. */
private const val COLLAPSED_MESSAGE_LINES = 4
