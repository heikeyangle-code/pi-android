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
 * prose, radius 12, padding 12, a 10 bottom margin, and the turn's timestamp once at the
 * **bottom-right of the row** in the machine face at 12. It is deliberately **not** a
 * right-aligned chat bubble: the container is what distinguishes the user's turn from
 * the assistant's container-less prose (`06 §2`), and a bubble with a tail would say
 * the opposite.
 *
 * **The clock is drawn outside the `userMessageBg`, below the bubble** (this round's one
 * change to this file, and the reason the KDoc's jsx above is no longer literal). The
 * board put it inside as the bubble's last child; with `padding:12` and a gap before it,
 * that made the air below the user's text 3.2× the air above it (38 dp vs 12 dp) and the
 * text read as top-heavy. There is no padding arithmetic that fixes that while the clock
 * is inside — it has to leave the box. See the rendering site for the numbers and for the
 * `dim`-vs-`muted` argument, which only became available once the ground under the clock
 * stopped being `userMessageBg`.
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
    /**
     * `app.appearance.showTimestamps`. **Off hides this bubble's clock**, and off is the
     * default.
     *
     * The clock used to be drawn unconditionally — which is exactly why the switch looked
     * broken: the only thing it reached was the date separators (`ChatScreen`'s
     * `visibleItems`), so turning it off emptied the separators and left a clock on every one
     * of the user's own messages. That is the one timestamp anyone notices, because it sits
     * on the row they just wrote.
     */
    showTimestamps: Boolean = false,
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
            Column(
                // The board's `marginBottom:10`, verbatim. It is deliberately a literal and
                // not `PiSpacing.blockGap` (8): the board's own number is 10, and this file
                // may not add a token.
                //
                // **It hangs off the block now, not off the bubble.** The clock is outside the
                // bubble, so a margin on the `Surface` would have become the bubble-to-clock
                // gap — the bubble would sit 10 dp above its own time — while the clock-to-next-row
                // gap got the list's rhythm. On the block it still stacks under both, and the gap
                // is never painted in `userMessageBg`.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
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
                                // 用户自己消息里 markdown 写的图：与同一个气泡里那张附件网格走同一个
                                // 出口（下面 `ImageGridBlock` 的 `onImageClick`），不然"附件能点开、
                                // 自己写的 `![]()` 不能"就是同一条气泡里的两种行为。
                                onImageClick = onImageClick,
                            )
                        }
                        if (item.images.isNotEmpty()) {
                            ImageGridBlock(
                                images = item.images,
                                modifier = Modifier.fillMaxWidth(),
                                onImageClick = onImageClick,
                            )
                        }
                    }
                }
                // ── The clock, **outside** the bubble, bottom-right ─────────────────────
                //
                // It used to be the bubble's last child, and that is what made the user's own
                // text look off-centre rather than merely tight: with `padding:12` all round plus
                // the `spacedBy(8)` before the clock and the clock's own 18 dp line box, the air
                // above the body was 12 dp while the air below it was 8 + 18 + 12 = 38 dp — 3.2×
                // the top, so every bubble read as if its text had floated up. Moving the clock
                // out makes the bubble's own box symmetric again (12 / 12, the board's
                // `padding:12`), and a one-line bubble drops from 73 dp to 47 dp because the
                // clock's line box is no longer inside the `userMessageBg`.
                //
                // 3 dp is the board's own number (`.clkout` sits 3 px under `.bub`): far enough
                // that the two shapes read as separate objects, close enough that the clock still
                // belongs to the bubble above it rather than to the row below.
                //
                // **Off draws nothing at all — no empty `Text`, no placeholder.** A composed
                // `Text("")` would still hold an 18 dp line box, which is exactly the
                // invisible-but-present row the switch is supposed to remove.
                if (showTimestamps) {
                    Text(
                        text = formatClock(item.ts),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 3.dp),
                        // `mono t12`: the machine face at v2's label step, which is exactly the
                        // `monoSmall` role (12/18, `PiMonoFamily`). `meta` is the same size in the
                        // *system* face, and the board's markup asks for `.mono` on this timestamp.
                        style = PiTheme.text.monoSmall,
                        // `dim` — the board's own `.clkout` colour — and the **ground** is what
                        // makes the board's choice available again: F12
                        // (`docs/rendering-review.md:326`) measured `dim` at 2.11:1 against the
                        // bubble this clock used to sit on, under §9's 3:1 metadata floor, and its
                        // own minimal-fix list named 「move the timestamp out of the bubble」 as one
                        // of the two ways out. Outside, the ground is `pageBg`: `PiPalette.Dark`'s
                        // `dim` (`#7E888E`) on `#21252C` measures 4.25:1, over the floor. Still a
                        // metadata colour, not a body one.
                        color = palette.dim,
                    )
                }
            }
        }
        }
    }
}
