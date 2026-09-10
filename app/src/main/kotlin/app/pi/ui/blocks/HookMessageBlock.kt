package app.pi.ui.blocks

import androidx.compose.foundation.layout.Spacer
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
import app.pi.ui.theme.PiTheme

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
    var expanded by remember { mutableStateOf(defaultExpanded) }
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
                Spacer(Modifier.width(10.dp))
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
            ProseText(
                text = item.markdown.ifEmpty { "（空消息）" },
                color = palette.customMessageText,
                maxLines = if (expanded || !collapsible) Int.MAX_VALUE else 4,
            )
        }
    }
}
