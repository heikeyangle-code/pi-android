package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import app.pi.ui.theme.PiSpacing

/**
 * `error-text` (docs/pi-android-ui-spec.md §7.4): a `toolErrorBg` card with an
 * `error` stripe on the left — `✗ 出错了`, one human sentence, then 详情. A tool
 * failure does not raise a global error; it lands here, in the stream.
 *
 * The `✗ 出错了` pair is `06 §4`'s triple encoding for this block (符号 + 字 + 色),
 * and it is what the card is scanned for; the sentence below it is the detail. The
 * ground is the token itself rather than a weakened copy, matching `06 §2`'s
 * 错误块 row — which also restores `bodyOnTool`'s contrast guarantee, since that
 * derived token is computed against the full `toolErrorBg`.
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
            color = palette.toolErrorBg,
            borderColor = palette.error.copy(alpha = 0.45f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // F28: unified onto the titled-card stripe height. The 36dp this
                // used matched no spec row and no pi value (pi draws no stripe at
                // all), while the three other titled cards drew 20dp.
                AccentStripe(palette.error, PiSpacing.accentStripe)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "✗",
                            style = PiTheme.text.mono,
                            color = palette.error,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(PiSpacing.gutter))
                        Text(
                            text = "出错了",
                            style = PiTheme.text.meta,
                            color = palette.error,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(PiSpacing.tiny))
                    // The header above already says 出错了, so an empty message is left
                    // out rather than repeated: pi prints the sentence it was given, and
                    // nothing when there is none.
                    if (item.message.isNotBlank()) {
                        ProseText(
                            text = item.message,
                            color = palette.text,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!detail.isNullOrBlank()) {
                    Row(
                        // F28: the content region toggles (pi's rule); this row is
                        // inside the card's region, so the tap still means the same
                        // thing wherever on the card it lands.
                        modifier = Modifier.toggleContent(expanded, { expanded = !expanded }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) "收起" else "详情",
                            style = MaterialTheme.typography.labelMedium,
                            // v2 paints the error card's disclosure in the card's own
                            // colour (`c-err`), not in the accent: the label belongs to
                            // the state it reveals.
                            color = palette.error,
                        )
                        Spacer(Modifier.width(PiSpacing.small))
                        ExpandLabel(expanded, expandText = "", collapseText = "", color = palette.error)
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
                    // F13 (`docs/rendering-review.md`): raw error detail is body
                    // text on a tool-error surface, so it takes the variant that
                    // clears §9's 4.5:1 floor.
                    color = palette.bodyOnTool,
                    modifier = Modifier.padding(top = PiSpacing.tiny),
                )
            }
        }
    }
}
