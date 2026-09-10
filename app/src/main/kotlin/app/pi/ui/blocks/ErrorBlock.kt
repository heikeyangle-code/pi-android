package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.ErrorText
import app.pi.ui.theme.PiTheme

/**
 * `error-text` (docs/pi-android-ui-spec.md §7.4): a weakened `toolErrorBg` card
 * with an `error` stripe on the left — one human sentence, then 详情 and 重试.
 * A tool failure does not raise a global error; it lands here, in the stream.
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    val palette = PiTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val detail = item.detail

    BlockColumn(modifier) {
        BlockCard(
            color = palette.toolErrorBg.copy(alpha = 0.7f),
            borderColor = palette.error.copy(alpha = 0.45f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentStripe(palette.error, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    ProseText(
                        text = item.message.ifEmpty { "出错了" },
                        color = palette.text,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!detail.isNullOrBlank()) {
                    Row(
                        modifier = Modifier.clickable(
                            onClickLabel = if (expanded) "收起详情" else "查看详情",
                        ) {
                            expanded = !expanded
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "详情",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        ExpandLabel(expanded, expandText = "", collapseText = "")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onRetry != null) {
                    TextButton(onClick = onRetry) {
                        Text("重试", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (expanded && !detail.isNullOrBlank()) {
                MonoText(
                    text = detail,
                    color = palette.toolOutput,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
