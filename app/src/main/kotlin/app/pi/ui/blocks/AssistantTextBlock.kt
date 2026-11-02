package app.pi.ui.blocks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.pi.rpc.AssistantText
import app.pi.rpc.PiImage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.render.withStreamingCursor

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
 *
 * **The prose is one selection scope** ([SelectableContent]), which is what makes
 * the assistant's own text the thing the user can select — the complaint this
 * fixes (「长按只能出现一个特别丑的框，是复制全部，没法用系统的自由复制」). One scope
 * around the whole markdown, not one per paragraph: [PiMarkdownText] paints several
 * `Text` nodes and a selection has to be able to run out of one into the next.
 * Because the text takes the long press, the block's one action (复制全部) is reached by
 * long-pressing the row's own chrome (the gap beside the streaming cursor, the block's
 * margins). There is **no ⋮ button**: it would cost 32 dp of width on every paragraph, and
 * the chrome long press reaches the same menu (see [BlockActionMenu]).
 *
 * **Do not throttle the text this block hands to [PiMarkdownText].** A 140 ms
 * "settled text" window was tried here to cut the renderer's per-update cost
 * (`design/ui-refactor/08-hang-diagnosis.md` §9.5/§10) and it broke 触底跟随: the
 * transcript's follow effect is keyed on the *publication* (`ChatScreen.kt`'s
 * `LaunchedEffect(state.revision, state.streaming, …)`) and then reads the
 * settled geometry, so a row that grows in stepped bursts is usually observed
 * after the burst is already over — the viewport stops moving and the user has
 * to scroll by hand. Growth *per publication* is what that design relies on.
 * The renderer's cost is bounded on the other side instead, by the stable parser
 * objects `PiMarkdown.kt` now hands to the library (that is the ANR fix); the
 * streaming row is allowed to re-render once per publication.
 */
@Composable
fun AssistantTextBlock(
    item: AssistantText,
    modifier: Modifier = Modifier,
    /**
     * A tap on an image written in the message's own markdown, handed to the same viewer the
     * attachments and tool screenshots use (`ChatScreen`'s `viewedImage`). Null keeps every
     * image in the body inert — the rendering itself is unchanged, because
     * `PiMarkdownText` only installs the tap when this is supplied.
     */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BlockColumn(modifier) {
        // §4.8: 助手消息长按 → 复制全部. 保存为文件 / 重新生成 are not offered here —
        // see the block-action report: neither has a target in this build.
        //
        // 复制全部 still copies `item.text` in full — pi's own message content, never
        // the rendered, wrapped or truncated form the markdown pipeline produced.
        BlockActionMenu(
            actions = listOf(
                BlockAction("复制全部") {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text))
                },
            ),
        ) {
        SelectableContent {
            // 光标 `▍` 接在 Markdown 源码末尾（`withStreamingCursor`），紧跟最后一个字，也不占
            // 布局宽度：原来是正文旁边另放一个 8×16 的方块，贴着整块正文的右缘（最后一行很短
            // 时离字很远），并且流式结束它消失的那一帧正文变宽、已断好的行会重排。
            // 「复制全部」仍复制 `item.text` 原文，光标不会进剪贴板。
            val shown = if (item.streaming) withStreamingCursor(item.text) else item.text
            if (shown.isNotEmpty()) {
                PiMarkdownText(
                    markdown = shown,
                    modifier = Modifier.fillMaxWidth(),
                    onImageClick = onImageClick,
                    streaming = item.streaming,
                )
            }
        }
        }
    }
}
