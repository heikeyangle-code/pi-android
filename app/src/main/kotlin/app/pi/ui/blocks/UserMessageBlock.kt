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
import app.pi.rpc.PiImage
import app.pi.rpc.UserMessage
import app.pi.ui.render.PiMarkdownText
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing

/**
 * `user-message`, to the **frozen v2 board** (`design/ui-refactor/design-demos/
 * direction-b-v2.html:1599-1605`, and again at `:2454-2460`):
 *
 * ```jsx
 * <div style={{background:'var(--user-bg)',borderRadius:12,padding:12,marginBottom:10}}>
 *   <div className="t14" style={{color:'var(--user-text)'}}>…正文…</div>
 *   <ImageGrid n={1} mb={8}/>
 *   <div className="rw" style={{justifyContent:'flex-end'}}>
 *     <span className="mono t12 c-muted">14:02</span>
 *   </div>
 * </div>
 * ```
 *
 * So the bubble is **full width** — the board's own caption for the row is 「用户消息：
 * 满宽气泡，右下角时间」 — in `--user-bg` (`#343541`) with `--user-text` (`#D4D4D4`)
 * prose, radius 12, padding 12, a 10 bottom margin, and the turn's timestamp once in
 * the bottom-right corner in the machine face at 12. It is deliberately **not** a
 * right-aligned chat bubble: the container is what distinguishes the user's turn from
 * the assistant's container-less prose (`06 §2`), and a bubble with a tail would say
 * the opposite.
 *
 * **Frozen board vs the older prose specs (for the owner of the design docs — not
 * changed here).** `docs/pi-android-ui-spec.md:447`/`:824` and
 * `design/ui-refactor/02-real-content.md:222` still describe 16dp radius, 14dp padding
 * and a 15/23 body, and `design/ui-refactor/05-compose-migration-plan.md:253` records an
 * explicit decision to keep the bubble at 16dp. The board is the newer, frozen artefact,
 * so this file follows 12 / 12 / `t14` (14/23). That is also why the radius no longer
 * comes from `PiShapes.card`: `card` is still the older spec's 16dp and is shared with
 * other blocks.
 *
 * The block's actions (复制 / 编辑并从此分叉) come from **long-pressing the bubble's own
 * chrome** — there is no ⋮ beside it (see [BlockActionMenu]: the button cost 32 dp of width
 * per row, and the chrome long press opens the same menu). The system text-selection scope
 * ([SelectableContent]) and the attachment grid that opens the full-screen viewer
 * ([onImageClick]) are unchanged.
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
    /** A tap on an attached image opens it full screen ([PiImageViewer]). */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    BlockColumn(modifier) {
        BlockActionMenu(
            actions = buildList {
                add(BlockAction("复制") { clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.text)) })
                // pi forks a session from a *user* message (`fork`, rpc-types.ts:62;
                // pi's own picker is /fork, docs/sessions.md:31). Only the row's
                // *transcript key* travels from here: it is pi's entry id on the
                // replay path and a synthetic `user-<millis>-<n>` on the live one, so
                // the ViewModel resolves it against `get_fork_messages` rather than
                // handing it to pi (`forkFromMessage`, `PiSessionViewModel.kt`).
                if (onForkFromMessage != null) {
                    add(BlockAction("编辑并从此分叉") { onForkFromMessage(item.key) })
                }
            },
        ) {
        // The bubble's body is one selection scope: the user's own words are the
        // first thing anyone tries to copy, and before this the only way was the
        // menu's 复制. The ⋮ above keeps that whole-bubble copy for a single tap.
        SelectableContent {
        Surface(
            // The board's `marginBottom:10`, verbatim. It is deliberately a literal and
            // not `PiSpacing.blockGap` (8): the board's own number is 10, and this file
            // may not add a token. Applied outside the `Surface`, so it stacks with the
            // list's `Arrangement.spacedBy` instead of replacing it, and the gap is
            // never painted in `userMessageBg`.
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            // `borderRadius:12`.
            shape = PiShapes.cardInner,
            color = palette.userMessageBg,
        ) {
            Column(
                // The board's `padding:12`, both axes.
                modifier = Modifier.padding(PiSpacing.cardPadding),
                // `ImageGrid mb={8}`: the gap that follows the grid. The board has no
                // gap between the body and the grid, and 8 is the nearest step of v2's
                // own rhythm to the 10 this used to be.
                verticalArrangement = Arrangement.spacedBy(PiSpacing.blockGap),
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
                        onImageClick = onImageClick,
                    )
                }
                Text(
                    text = formatClock(item.ts),
                    modifier = Modifier.align(Alignment.End),
                    // `mono t12`: the machine face at v2's label step, which is exactly
                    // the `monoSmall` role (12/18, `PiMonoFamily`). `meta` is the same
                    // size in the *system* face, and the board's markup asks for `.mono`
                    // on this timestamp specifically.
                    style = PiTheme.text.monoSmall,
                    // `c-muted` (`--muted: #808080`), the v2 palette's `muted` token.
                    // It measures 3.07:1 on `userMessageBg`: over spec §9's 3:1
                    // metadata floor, which is why the board's colour can be used
                    // literally here (F12 kept `dim` — 2.11:1 — out of this bubble).
                    color = palette.muted,
                )
            }
        }
        }
        }
    }
}
