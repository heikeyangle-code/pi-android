package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import app.pi.rpc.ErrorText
import app.pi.ui.chat.LocalMessageActions
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
 * The spec's recovery path (「重试 / 换模型 / 查看详情」, §4.9): 重试 is the link under the
 * message of the **last** card while pi is idle ([MessageActions.retryKey]); it re-sends the last
 * user prompt, because the SDK has no "retry now" (`session.retry` only flips auto-retry).
 * 换模型 is reachable from the AppBar chip / model row.
 */
@Composable
fun ErrorBlock(
    item: ErrorText,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    var expanded by rememberSaveable { mutableStateOf(false) }
    val detail = item.detail

    // 紧凑版：一行标题（✗ 出错了 … 展开）+ 最多三行正文，不再有一行空的「展开」条。
    // 点卡片任何位置都能展开/收起（pi 的规则：内容区就是开关）；没有更多可看时不画「展开」。
    val expandable = !detail.isNullOrBlank() || item.message.length > ERROR_PREVIEW_CHARS ||
        item.message.count { it == '\n' } >= ERROR_PREVIEW_LINES
    BlockColumn(modifier) {
        BlockCard(
            color = palette.toolErrorBg,
            padding = PaddingValues(horizontal = PiSpacing.cardPadding, vertical = PiSpacing.inline),
        ) {
            ToggleContent(expanded = expanded, onToggle = { expanded = !expanded }, enabled = expandable) {
                Row(verticalAlignment = Alignment.Top) {
                    // 红条只跟标题行一样高，不再伸到正文底下。
                    AccentStripe(palette.error, PiSpacing.accentStripe)
                    Spacer(Modifier.width(PiSpacing.inline))
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
                            Spacer(Modifier.weight(1f))
                            if (expandable) {
                                // 展开提示放在标题行右端，颜色是卡片自己的 error。
                                ExpandLabel(expanded, color = palette.error)
                            }
                        }
                        // 标题与红条已经说明这是错误；整段报错正文再染成红色，长报错
                        // 读起来很累。正文用工具卡的正文色，错误色只留给标题和红条。
                        if (item.message.isNotBlank()) {
                            ProseText(
                                text = item.message,
                                modifier = Modifier.padding(top = PiSpacing.tiny),
                                color = palette.bodyOnTool,
                                maxLines = if (expanded) Int.MAX_VALUE else ERROR_PREVIEW_LINES,
                            )
                        }
                        // 重试：只在末尾那张错误卡上出现（见 MessageActions.retryKey）。点它不会展开卡片。
                        val actions = LocalMessageActions.current
                        if (actions != null && actions.retryKey == item.key) {
                            Text(
                                text = "重试",
                                modifier = Modifier
                                    .padding(top = PiSpacing.tiny)
                                    .clickable(onClickLabel = "重发上一条消息", onClick = actions.retry)
                                    .padding(vertical = PiSpacing.tiny),
                                style = PiTheme.text.meta,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (expanded && !detail.isNullOrBlank()) {
                            MonoText(
                                text = detail,
                                // F13 (`docs/rendering-review.md`): raw error detail is body
                                // text on a tool-error surface, so it takes the variant that
                                // clears §9's 4.5:1 floor.
                                color = palette.bodyOnTool,
                                modifier = Modifier.padding(top = PiSpacing.gutter),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 折叠时最多显示几行正文。 */
private const val ERROR_PREVIEW_LINES = 3

/** 超过这么多字（即使没有换行）也认为折叠态看不全，给「展开」。 */
private const val ERROR_PREVIEW_CHARS = 140
