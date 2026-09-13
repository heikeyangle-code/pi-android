package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pi.rpc.AssistantText
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing
import kotlinx.coroutines.delay

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
                    // While the answer is still arriving this is the text as of the last
                    // [STREAM_TEXT_WINDOW_MS] window, not the newest token; the moment the
                    // row settles it is `item.text` itself. See [settledStreamingText].
                    markdown = settledStreamingText(item.text, item.streaming),
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

/**
 * The text handed to the markdown renderer while the answer is still arriving.
 *
 * ## Why this exists
 *
 * [PiMarkdownText] gives the library the *whole* document, and the library pays for it
 * **per update**: one parse (measured 8.95 ms / 1026 AST nodes for 5 KB, 22.97 ms / 9976
 * for 50 KB, 58.02 ms / 29811 for 150 KB — `design/ui-refactor/08-hang-diagnosis.md` §2.2)
 * plus one full re-composition of every node in the document. pi hands the transcript one
 * row per streamed event, so an unthrottled row asks for that 10-50 times a second; with a
 * few markdown rows on screen the frame thread never goes idle and Android raises
 * 「没有响应」. Handing the renderer a text that changes at most once per
 * [STREAM_TEXT_WINDOW_MS] cuts that by several times without changing what is finally
 * shown.
 *
 * ## The two rules
 *
 *  - **A settled row is exact, and immediate.** `streaming == false` returns [text]
 *    untouched — no window, no lag, no lost tail. This is the text the user keeps looking
 *    at, and it must never be approximate.
 *  - **A streaming row may lag, but must always advance.** The effect is keyed on
 *    [streaming] alone, deliberately **never** on [text]: an effect keyed on the text (the
 *    `produceState(text) { delay(…); value = text }` shape) is cancelled by every arrival
 *    and, while tokens keep coming faster than the window, never fires at all — the row
 *    would freeze mid-answer instead of merely updating a few times a second. A loop that
 *    wakes on its own and reads the latest value cannot stall. It has a suspension point
 *    (`delay`) on every iteration and it exists exactly as long as the row streams:
 *    `streaming` flipping false removes the effect from the composition, which cancels it,
 *    and the same frame paints the final text.
 *
 * It writes state only when the text really moved, so a window in which nothing arrived
 * costs one comparison and no recomposition. `retainState = true` in [PiMarkdownText] is
 * what makes a throttled update safe to show: the renderer keeps the previous document on
 * screen while the next one is prepared, so a window boundary is a *slightly older*
 * document, never a blank row.
 */
@Composable
private fun settledStreamingText(text: String, streaming: Boolean): String {
    if (!streaming) return text
    val latest = rememberUpdatedState(text)
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(Unit) {
        while (true) {
            // Leading edge first, so a row paints as soon as it appears; then one window
            // per update. The write is conditional, so an idle window is free.
            val next = latest.value
            if (shown != next) shown = next
            delay(STREAM_TEXT_WINDOW_MS)
        }
    }
    return shown
}

/**
 * How long a streaming row's text stands before the renderer is asked to rebuild the
 * document. 140 ms is the top of the 120-150 ms the hang report asks for: long enough
 * to turn a fast model's 10-50 updates/s into ~7, short enough that the row still reads as
 * 「正在打字」. The pending `delay` when a row settles is cancelled with the effect, so
 * nothing waits after the last token.
 */
private const val STREAM_TEXT_WINDOW_MS = 140L
