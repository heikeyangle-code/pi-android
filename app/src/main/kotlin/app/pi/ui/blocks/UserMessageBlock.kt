package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.pi.ui.chat.MessageActionTarget
import app.pi.ui.chat.MessageRole
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
 * The block's actions come from **one long press on the whole bubble**: a haptic tick and a bottom
 * sheet (复制全部 / 选择文字 / 引用 / 编辑并从此分叉 / 从这里继续 / 加标签 / 从上下文隐藏), see
 * [MessageActionTarget]. The text is deliberately not in a system selection container any more —
 * that made the same gesture mean two things depending on where the finger landed; free selection
 * is the sheet's 「选择文字」. The attachment grid that opens the full-screen viewer
 * ([onImageClick]) is unchanged.
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
    BlockColumn(modifier) {
        // 长按 → 底部操作面，见 [MessageActionTarget]。pi 的 `/fork` 只能从**用户**消息分叉
        // （`getUserMessagesForForking`），所以「编辑并从此分叉」只在这里给；传出去的仍是行键，
        // 由 ViewModel 对着 pi 的可分叉点解析（实时行的键是合成的，pi 不认）。
        MessageActionTarget(
            item = item,
            role = MessageRole.User,
            text = item.text,
            onEditAndFork = onForkFromMessage?.let { fork -> { fork(item.key) } },
        ) {
        // 气泡右对齐（所有档位）、宽度跟内容走、最宽 86%，右下角收成小半径。
        // **不用 `BoxWithConstraints`**：它是 SubcomposeLayout，内容在布局阶段才组合，整段 Markdown
        // 异步解析时行高会被短暂算小，LazyColumn 的滚动偏移被夹回去（用户报的「从气泡底部跳到顶部」）。
        // 这里用普通的 `layout` 修饰符：最大宽度取 86%，量完再靠右摆。
        Surface(
            // The board's `marginBottom:10`, verbatim. It is deliberately a literal and
            // not `PiSpacing.blockGap` (8): the board's own number is 10, and this file
            // may not add a token. Applied outside the `Surface`, so it stacks with the
            // list's `Arrangement.spacedBy` instead of replacing it, and the gap is
            // never painted in `userMessageBg`.
            modifier = Modifier
                .layout { measurable, constraints ->
                    val width = constraints.maxWidth
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = 0, maxWidth = (width * 0.86f).roundToInt()),
                    )
                    layout(width, placeable.height) { placeable.placeRelative(width - placeable.width, 0) }
                }
                .padding(bottom = 10.dp),
            // `borderRadius:12`.
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp),
            color = palette.userMessageBg,
        ) {
            Column(
                // The board's `padding:12`, both axes.
                // 水平 14、垂直 10：16 sp / 27 sp 的正文自带上下半行的空气（约 5 dp），
                // 垂直再给 12 就比水平松；14/10 让气泡四边的视觉留白接近。
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                // `ImageGrid mb={8}`: the gap that follows the grid. The board has no
                // gap between the body and the grid, and 8 is the nearest step of v2's
                // own rhythm to the 10 this used to be.
                verticalArrangement = Arrangement.spacedBy(PiSpacing.blockGap),
            ) {
                if (item.text.isNotEmpty()) {
                    PiMarkdownText(
                        markdown = item.text,
                        modifier = Modifier,
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
                        modifier = Modifier.widthIn(min = 160.dp),
                        onImageClick = onImageClick,
                    )
                }
                if (showTimestamps) {
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
