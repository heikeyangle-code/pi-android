package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pi.rpc.UserMessage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * `user-message` (docs/pi-android-ui-spec.md §7.4): a full-width container in
 * `userMessageBg`, 16dp radius, 14dp padding, with the body rendered as the
 * markdown pi draws, any images below it, and the turn's timestamp once in the
 * bottom-right corner.
 *
 * F15 (`docs/rendering-review.md`): pi sends the user's own text through
 * `Markdown` with `userMessageText` as `defaultTextStyle.color`
 * (`components/user-message.ts:40-56`), so a pasted fence or `**bold**` used to
 * reach the screen as source here and as prose in every assistant message.
 * [PiMarkdownText]'s `textColor` is that base foreground; the per-token colours
 * (headings, links, code) still come from the palette on top of it, exactly as
 * pi layers them. **Known limit:** pi passes `preserveOrderedListMarkers` and
 * `preserveBackslashEscapes` at that call site and this renderer exposes neither
 * (`ui/render/PiMarkdown.kt` has no such parameter), so a list pi would leave
 * numbered from its source may be renumbered by the library's own renderer — not
 * verifiable without the renderer artifact, recorded rather than promised.
 */
@Composable
fun UserMessageBlock(
    item: UserMessage,
    modifier: Modifier = Modifier,
    /** §4.8: 编辑并从此分叉 — `session.forkFrom(entryId)`; null hides the action. */
    onForkFromMessage: ((String) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BlockColumn(modifier) {
        BlockActionMenu(
            actions = buildList {
                add(BlockAction("复制") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) })
                // pi forks a session from a *user* message (`fork`, rpc-types.ts:62;
                // pi's own picker is /fork, docs/sessions.md:31). The entry id is
                // the block's stable key, which is pi's own entry id for a
                // projected user message.
                if (onForkFromMessage != null) {
                    add(BlockAction("编辑并从此分叉") { onForkFromMessage(item.key) })
                }
            },
        ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = PiShapes.card,
            color = palette.userMessageBg,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (item.text.isNotEmpty()) {
                    PiMarkdownText(
                        markdown = item.text,
                        modifier = Modifier.fillMaxWidth(),
                        textColor = palette.userMessageText,
                    )
                }
                if (item.images.isNotEmpty()) {
                    ImageGridBlock(
                        images = item.images,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = formatClock(item.ts),
                    modifier = Modifier.align(Alignment.End),
                    style = PiTheme.text.meta,
                    // F12 (`docs/rendering-review.md`): `dim` is 2.11:1 on
                    // `userMessageBg`, under spec §9's 3:1 metadata floor. The
                    // token itself is pi's own (`dark.json` `vars.dimGray`), so the
                    // fix is which colour this bubble's least readable line uses:
                    // `userMessageText` lifted to 62 % clears the floor without
                    // inventing a palette entry.
                    color = palette.userMessageText.copy(alpha = 0.62f),
                )
            }
        }
        }
    }
}
