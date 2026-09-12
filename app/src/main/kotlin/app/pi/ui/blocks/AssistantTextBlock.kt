package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pi.rpc.AssistantText
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * `assistant-text` (docs/pi-android-ui-spec.md §7.4): no container at all — the
 * prose lands straight on the canvas, which is the whole point of "input vs
 * content" being a container difference rather than a bubble.
 *
 * The body is markdown, rendered by [PiMarkdownText]. pi runs every assistant
 * message through its markdown renderer before it reaches the terminal, so a
 * transcript that printed the raw source would be showing the user asterisks and
 * backticks pi never shows.
 *
 * The streaming cursor is a static block on purpose: this app avoids pulling in
 * animation APIs it does not own, and a still cursor already reads as "still
 * typing" next to the growing text. It is bottom-aligned so it rides the last
 * line of whatever the renderer produced, code block included.
 */
@Composable
fun AssistantTextBlock(
    item: AssistantText,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BlockColumn(modifier) {
        // §4.8: 助手消息长按 → 复制全部. 保存为文件 / 重新生成 are not offered here —
        // see the block-action report: neither has a target in this build.
        BlockActionMenu(
            actions = listOf(
                BlockAction("复制全部") {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text))
                },
            ),
        ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (item.text.isNotEmpty()) {
                PiMarkdownText(
                    markdown = item.text,
                    modifier = Modifier.weight(1f),
                )
            }
            if (item.streaming) {
                Box(
                    modifier = Modifier
                        .padding(start = PiSpacing.small)
                        .size(width = 8.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.accent.copy(alpha = 0.65f)),
                )
            }
        }
        }
    }
}
