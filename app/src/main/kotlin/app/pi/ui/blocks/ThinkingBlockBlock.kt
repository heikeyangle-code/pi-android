package app.pi.ui.blocks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pi.rpc.ThinkingBlock
import app.pi.ui.theme.PiContrast
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiThinkingLevel
import app.pi.ui.theme.numeric

/**
 * `thinking-block` (docs/pi-android-ui-spec.md §7.4): one row carrying the thinking-level
 * pen (the app's signature visual), the headline, the level, the elapsed time and the
 * `展开 / 收起` label — expanding to italic prose on the level's own faint ground.
 *
 * ## A 案：笔锋贴屏幕缘，文字回到页边轴（v5, `差异表` §2 第 19/20 行）
 *
 * The pen used to be the first cell *inside* the row (3 dp + an 8 dp gap = the text started at
 * 25 dp from the page edge), which put 「思考」 on an axis of its own — 11 dp right of the axis
 * every other human row uses. A 案 moves the pen **out** of the row and onto the page margin:
 * absolute x = 0 … 3 (`RAIL_INDENT` has nothing to do with it; the block's own left edge is the
 * page margin, so the stroke is drawn 14 dp to the left of it) and the headline starts at the
 * block's x = 0, i.e. absolute 14 — exactly the axis of the prose, the user bubble and a
 * thinking block's own body. The pen is drawn on the container that holds the row **and** the
 * expanded body, so it grows with what it is a pen stroke beside, and it is the identity channel:
 * it is there collapsed and expanded alike.
 *
 * ## C 案：展开体带一层极淡的等级色底
 *
 * The expanded body gets a ground of the level's own pen colour at a very low alpha — the same
 * 「左 3px 条 + 淡底」 idiom the error card uses (`06 §2` 错误块), not a new form. **This is an
 * accounted deviation**: the thinking block had no surface of its own before, so this adds one new
 * surface step to the transcript. The reason is legibility of *scope*: an italic paragraph sitting
 * directly on the canvas has no edge, and a reader scrolling a long thinking block cannot tell
 * where the model's parenthesis ends; the pen says 「something of this level is here」 and the
 * wash says 「this much of it」.
 *
 * **The colour is derived, never invented**: `pen.copy(alpha = …)` composited over the page, so an
 * imported theme still owns it. The alpha is per canvas because the same alpha is not the same
 * ink on both: on pi's dark `pageBg` the built-in pens at 8 % measure 1.09–1.11 : 1 against the
 * page (a visible large-area tint); on the near-white light `pageBg` the same 8 % measures
 * 1.03–1.07 : 1, which is effectively invisible. The light tier therefore takes 16 %, which
 * measures 1.06–1.14 : 1 — the same band the dark theme gets. `PiContrast.luminance` decides which
 * canvas this is, so a user's own theme lands on the right tier too.
 *
 * **The body's text is re-derived against the wash, not against the page.** `thinkingBodyOnCanvas`
 * is `PiContrast.ensure(thinkingText, pageBg, 4.5)` — for pi's light theme it sits exactly *on*
 * the 4.5 : 1 floor, so any ground at all under it would drop the paragraph below §9's body
 * floor. `PiContrast.ensure(…, wash, 4.5)` lifts it by the little the wash cost (on the dark
 * theme it returns the token untouched, because there the same text already measures ~5.2 : 1 on
 * the wash). That is the app's existing 「保真 + 一个修正档」 rule, applied to the one surface the
 * paragraph is actually painted on.
 *
 * ## 展开动画是同一个
 *
 * The wash and the body are **one** container inside one [AnimatedVisibility]: they cannot arrive
 * one after the other (「不是先出字后出色」), and the pen — drawn on the parent of both — grows with
 * the same animation frames. No fade, no scale: only the height of the one container moves, which
 * is the design's own `grid-template-rows: 0fr → 1fr`.
 *
 * ## 耗时在流式时也在走
 *
 * `差异表` §3 第 1 行: a still-streaming block counts up instead of staying blank, which it did
 * before. The clock is the ViewModel's existing 1 Hz one — [nowMs], the same value [ShellBlock]
 * reads, published by `UiState.hasPendingToolClock` (which now also yields for a streaming
 * thinking row). **No timer is started here**, and an idle transcript still publishes nothing.
 * Once the block closes, the number is **pi's own measurement** (`item.elapsedMs`, set by the
 * reducer when the block ends) — never a locally timed interval, exactly as [ShellBlock] does it.
 */
@Composable
fun ThinkingBlockBlock(
    item: ThinkingBlock,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    /**
     * `UiState.nowMs`: the ViewModel's 1 Hz coarse clock, non-null exactly while some row in
     * the transcript is live (a pending tool call, or a thinking block that is still
     * streaming). Null is the pre-clock behaviour for the one hop before the ViewModel's clock
     * arms, and for a caller that passes no clock at all.
     */
    nowMs: Long? = null,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    val pen = palette.thinking(item.level ?: "medium")
    val levelLabel = item.level?.takeIf { it.isNotBlank() }?.let { PiThinkingLevel.fromWire(it).label }
    // The row is a **two-voice** line, which is rule #7 (`docs/pi-android-ui-spec.md` §1) applied
    // to a row that used to speak with three: the word 「思考」 and the streaming verb are the
    // app's own copy (UI face), while the elapsed time is a measurement the engine produced —
    // so it is set in the machine face, exactly like the tool card's own footer reading
    // (`ToolBlockChrome.toolHeaderReading` → `monoSmall`). Keeping the duration inside the
    // Chinese string was the odd one out: it changed the sentence's advance width as it
    // ticked, and it left the row with one voice per fragment.
    val headline = if (item.streaming) "思考中…" else "思考"
    // `04 §1.1`-style live reading: while the block streams the number is the ViewModel's clock
    // minus the block's own start; once it closes the number is pi's (`item.elapsedMs`) and the
    // clock is not consulted at all. Both go through the **same** `formatDuration`
    // (`BlockChrome.kt`), so the running and the settled spellings cannot drift.
    val elapsedMs = if (item.streaming) {
        ((nowMs ?: System.currentTimeMillis()) - item.ts).coerceAtLeast(0)
    } else {
        item.elapsedMs
    }
    val headlineDuration = elapsedMs?.let { formatDuration(it) }
    // The wash and the text colour it forces — see the C 案 note above. Both are remembered
    // because `PiContrast.ensure` walks a blend ladder, and this row recomposes on every
    // streamed chunk.
    val washAlpha = remember(palette.pageBg) { thinkWashAlpha(palette.pageBg) }
    val washOnPage = remember(pen, palette.pageBg, washAlpha) { lerp(palette.pageBg, pen, washAlpha) }
    val bodyColor = remember(washOnPage, palette.thinkingBodyOnCanvas) {
        PiContrast.ensure(palette.thinkingBodyOnCanvas, washOnPage, THINK_BODY_CONTRAST_FLOOR)
    }

    BlockColumn(modifier) {
        // F28: the whole block is the toggle target, which is what pi does —
        // `components/assistant-message.ts:160-166` wraps the entire thinking
        // component in the `MouseRegion` that flips its visibility.
        //
        // The pen rides this container (not the row below it), which is what makes it span
        // the headline *and* the disclosed body: 3 dp wide, round-capped, and inset 2 dp from
        // both ends — v5's `.pen{top:2;bottom:2;width:3;border-radius:2}`. When collapsed the
        // container is the row's own line box (23 dp — the natural height of `prose` 14/23), so
        // the stroke is 19 dp: exactly the 3 × 19 the geometry sheet measures.
        ToggleContent(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier.drawBehind {
                val inset = THINK_PEN_INSET.toPx()
                val height = (size.height - 2 * inset).coerceAtLeast(0f)
                drawRoundRect(
                    color = pen,
                    topLeft = Offset(-THINK_PEN_LEFT.toPx(), inset),
                    size = Size(PiSpacing.stripe.toPx(), height),
                    cornerRadius = CornerRadius(THINK_PEN_RADIUS.toPx()),
                )
            },
        ) {
            // 行高 = `PiTheme.text.prose` 的自然行盒（14/23），**不加强制高度**：思考行是一行
            // 纯文字（没有节点要跟行心），加 1dp 只为凑 24 是白加的死空间。`差异表` §2 第 18 行
            // 说的「并入 24dp 模数」是**节奏/间距**（它上下的留白按同一套模数走），不是给文字行
            // 硬塞高度 —— 设计稿自己也是 23，笔锋因此是 3×19（上下各内缩 2）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline,
                    // v2: `t14 w5` — the row's headline is 14 sp at weight 500.
                    style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                    // F13: this is a body role, so it takes the 4.5:1 variant
                    // (`thinkingText` and `muted` are the same #808080 in pi's dark
                    // theme; 4.47:1 on the canvas is under the floor). It sits on the canvas,
                    // not on the wash, so it keeps the page-derived token.
                    color = palette.thinkingBodyOnCanvas,
                )
                if (headlineDuration != null) {
                    // The engine's own measurement, in the machine face (rule #7). The gap
                    // is the same 8 dp the level word uses, so the row still has one
                    // rhythm; the colour is the body colour rather than the level's pen,
                    // because the duration is not a level.
                    Spacer(Modifier.width(THINK_ROW_GAP))
                    Text(
                        text = headlineDuration,
                        style = PiTheme.text.numeric,
                        color = palette.thinkingBodyOnCanvas,
                    )
                }
                if (levelLabel != null) {
                    Spacer(Modifier.width(THINK_ROW_GAP))
                    Text(
                        text = levelLabel,
                        // The level is pi's own identifier (`off`…`max`, see
                        // `PiThinkingLevel`), so it takes the app's *machine* face rather
                        // than the UI one — rule #7 (`docs/pi-android-ui-spec.md` §1):
                        // anything the engine emitted is set in mono, and that is what
                        // makes it read as a tag beside the Chinese headline instead of as
                        // a second word in the sentence. Same 12 sp step as `meta`, so the
                        // row's rhythm is unchanged; the colour stays the level's pen
                        // (`getThinkingBorderColor`'s ramp), which is the one channel that
                        // already carries the level.
                        style = PiTheme.text.monoSmall,
                        color = pen,
                    )
                }
                Spacer(Modifier.weight(1f))
                // 「展开 / 收起」 二字保留（`差异表` §2 第 22 行）: the disclosure is a word plus
                // the label's own chevron, and the whole block is the hit target underneath it.
                ExpandLabel(expanded)
            }
            // **One container, one animation.** The wash is the body's own background and the
            // body is its only child, so the ground and the text cannot appear in different
            // frames; the pen above grows on the same frames because it is drawn on this
            // container's parent.
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The 6 dp step below the headline is the design's own `.ttext{margin:6px
                        // 0 0}`; it is outside the wash so the ground hugs the paragraph.
                        .padding(top = PiSpacing.gutter)
                        // Painted **translucent** — the level's pen at this canvas's alpha, which
                        // is what lets the ground respect whatever is behind the row (the search
                        // highlight is one). [washOnPage] is only the opaque composite the
                        // contrast reading above needs.
                        .background(pen.copy(alpha = washAlpha)),
                ) {
                    // One scope around the body: the thinking text is model prose like any
                    // other and the user asked for it to be selectable. This block has no
                    // actions of its own, so nothing has to move to a ⋮ here.
                    SelectableContent {
                        Text(
                            text = item.text.ifEmpty { "（无思考内容）" },
                            // v2: `t14`, italic, in the tool-body grey. No left indent any
                            // more: with the pen out on the page margin (A 案) the body keeps
                            // the page axis, like the headline above it (`padding-left 0`).
                            modifier = Modifier.padding(vertical = PiSpacing.tiny),
                            style = PiTheme.text.prose.copy(fontStyle = FontStyle.Italic),
                            color = bodyColor,
                        )
                    }
                }
            }
        }
    }
}

/**
 * v5's `ThinkRow` `gap:8` — between the headline, the elapsed reading and the level word.
 */
private val THINK_ROW_GAP = 8.dp

/**
 * A 案's pen, expressed from the block's own left edge: the page margin is 14
 * (`PiSpacing.pageHorizontal`), and the stroke is to sit **on the screen's edge** (absolute
 * x = 0 … 3), so it is drawn 14 dp to the left of this container.
 */
private val THINK_PEN_LEFT = PiSpacing.pageHorizontal

/** v5's `.pen`: `top:2;bottom:2` — 2 dp of air at each end of the block it frames. */
private val THINK_PEN_INSET = 2.dp

/** v5's `.pen`: `border-radius:2`. */
private val THINK_PEN_RADIUS = 2.dp

/**
 * C 案's alpha, per canvas — see the class KDoc for the measurements behind the two numbers.
 *
 * Neither is a new colour: both are the level's own pen token at a lower alpha, which is the one
 * way this app derives a surface (`copy(alpha = ADJUSTMENT)`), and the two are the same *ink*
 * on their respective grounds.
 */
private const val THINK_WASH_ALPHA_DARK = 0.08f
private const val THINK_WASH_ALPHA_LIGHT = 0.16f

/** §9: 正文 ≥4.5:1 — the floor the expanded body must still clear on its own ground. */
private const val THINK_BODY_CONTRAST_FLOOR = 4.5

/**
 * This canvas's wash alpha — see the class KDoc for the measurements behind the two numbers.
 *
 * Neither is a new colour: both are the level's own pen token at a lower alpha, which is the one
 * way this app derives a surface (`copy(alpha = ADJUSTMENT)`), and the two are the same *ink* on
 * their respective grounds.
 */
private fun thinkWashAlpha(page: Color): Float =
    if (PiContrast.luminance(page) < 0.5f) THINK_WASH_ALPHA_DARK else THINK_WASH_ALPHA_LIGHT
