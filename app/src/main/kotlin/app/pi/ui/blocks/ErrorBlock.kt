package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
 * with an `error` stripe on the left — one human sentence, then 详情. A tool
 * failure does not raise a global error; it lands here, in the stream.
 *
 * The spec's recovery path (「重试 / 换模型 / 查看详情」, §4.9) is not reachable from
 * this block today: the app has no retry action, so F19 deleted the dead
 * `onRetry` parameter and the button it gated. 换模型 is reachable from the AppBar
 * chip / model row; a real 重试 needs an action in `ui/PiSessionViewModel.kt`.
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
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
                // F19 (`docs/rendering-review.md`): the 重试 button lived behind an
                // `onRetry` no host ever passed — the app has no retry action (spec
                // §7.4/§4.9 still ask for one; that is new UI, recorded in the
                // review's F19 row), so the parameter and the button are gone rather
                // than showing a control that does nothing.
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
