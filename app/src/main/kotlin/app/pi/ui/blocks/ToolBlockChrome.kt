package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.rpc.ToolCall
import app.pi.ui.theme.DurationMeter
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateTone
import app.pi.ui.theme.stateToneColor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The chrome the per-tool blocks share with the generic tool card.
 *
 * `packages/coding-agent/src/core/tools/renderers/index.ts:34-44` gives every built-in tool
 * its own renderer, but all eight share pi's *card*: one container whose colour is the
 * status (`toolPendingBg`/`toolSuccessBg`/`toolErrorBg`, `modes/interactive/components/
 * tool-execution.ts:172-178`), a title row, and a footer that says how long the call took
 * (`core/tools/renderers/bash.ts:117-121`). Only the title and the body differ per tool, so
 * only those live in the eight block files; everything below is the shared half, and
 * [ToolCallBlock] — the fallback card for a tool pi gives no renderer — is built from it too.
 *
 * Nothing here parses, formats or counts. The one thing all callers must get right is that
 * their *body* is computed outside composition and remembered: a tool row recomposes on
 * every 200 ms publication (`rpc/.../Transcript.kt:618`), so nothing here may scan a result
 * (F31/F8 in `docs/rendering-review.md`).
 */

// [ToolState] and its word/glyph/blocked vocabulary moved to `ToolState.kt` in this same
// package: they are pure (not one import from Compose), and the words on the card are
// exactly what a bare-JVM harness has to be able to read back. Everything below this line
// is the half that needs `Color` / `PiPalette` — see that file's KDoc.

/** The state's tone — the third channel, resolved in one table (`06 §4`). */
internal fun toolStateTone(state: ToolState): StateTone = when (state) {
    ToolState.Running -> StateTone.Warning
    ToolState.Success -> StateTone.Success
    ToolState.Failed -> StateTone.Error
    // Decision D2: 被拒 is neither a failure nor a disabled state, so it takes the
    // neutral token rather than `error`. `StateTone.Rejected` resolves to
    // `bodyOnTool` (`theme/PiStateChip.kt`), the app's derived tool-body grey.
    ToolState.Rejected -> StateTone.Rejected
}

/**
 * The container colour of a tool card: pi's three status backgrounds, plus the
 * ground `06 §3` 构件 5 gives the fourth state (被拒 sits on the *pending* ground —
 * it did not fail, so it does not take the error surface).
 */
internal fun toolContainerColor(state: ToolState, palette: PiPalette): Color = when (state) {
    ToolState.Running -> palette.toolPendingBg
    ToolState.Success -> palette.toolSuccessBg
    ToolState.Failed -> palette.toolErrorBg
    ToolState.Rejected -> palette.toolPendingBg
}

/** The border, stripe, node-ring and tick colour of a card: its state colour. */
internal fun toolAccentColor(state: ToolState, palette: PiPalette): Color =
    stateToneColor(toolStateTone(state), palette)

/** The colour pi's token resolves to in this palette. */
private fun ToolCallToken.color(palette: PiPalette): Color = when (this) {
    ToolCallToken.ToolTitle -> palette.toolTitle
    ToolCallToken.Accent -> palette.accent
    ToolCallToken.ToolOutput -> palette.toolOutput
    ToolCallToken.Warning -> palette.warning
    ToolCallToken.Muted -> palette.muted
    ToolCallToken.Uncoloured -> palette.text
}

/**
 * pi's `renderToolPath` (`core/tools/render-utils.js:57-63`), as one run: the path in
 * `accent`, or [fallback] in `toolOutput` where pi has no path to print.
 *
 * pi's three branches are `rawPath === null` → `[invalid arg]` in `error`, an empty value →
 * `theme.fg("toolOutput", "...")`, and otherwise `theme.fg("accent", shortenPath(value))`.
 * This app's callers always have a string, and they name the empty case in words
 * （「文件」/「未命名文件」）rather than with pi's `...` — the **text** is theirs, the **token**
 * is pi's, which is why only the token is taken from the source here.
 */
internal fun toolPathPart(path: String, fallback: String): ToolCallPart =
    if (path.isEmpty()) {
        ToolCallPart(fallback, ToolCallToken.ToolOutput)
    } else {
        ToolCallPart(path, ToolCallToken.Accent)
    }

/**
 * **The card's one and only line while collapsed** — 状态字形 + 工具名 + 主体 + 判决 +
 * 读数（含刻度）+ 箭头, transcribed from v5's `.row` (`成品-v5.html`; `差异表` §2 第 1/2 行):
 *
 * ```
 * 行高      24 = 20 内容 + 上下各 2（ToolCardRowPadding）；字段间距 6 = PiSpacing.gutter
 *   状态字形  mono 12 状态色，7 宽的居中槽   <- 06 §4 的符号通道（轨道节点也说一遍）
 *   工具名    mono 12 toolTitle + Bold       <- pi: fg("toolTitle", bold(toolName))
 *   主体      mono 12 分段取色               <- pi: the renderer's own fg(token, …) runs
 *   判决      mono 12 text（不是 muted）      <- 状态词，截断时追加「· 已截断」
 *   读数      mono 12 muted，右轴对齐，格子定宽 44 <- the call's own reading (a duration, a count)
 *   刻度      1dp，状态色，读数正下方          <- the same number, ordinal (04 §1.1)
 *   箭头      5×5 几何，bodyOnTool            <- right while collapsed, down while expanded
 * ```
 *
 * ## Why the header absorbed the footer
 *
 * The card used to be **two** rows: this one (name, subject, reading, chevron) and a footer
 * ([ToolFooter]: glyph, state word, exit code, duration, line count, `已截断`, `无输出` plus the
 * tick). On a device that measured 39.5 dp per collapsed call, and a run of five calls took
 * half the screen — the user's own report. Between them the two rows carried **one** state
 * (glyph and word below, reading above) and the **same duration three times** (the reading,
 * `耗时 …` inside the footer's sentence, and the tick's length).
 *
 * Folding them into one 24 dp row therefore drops no *fact*: 退出码 / `N 行` / `已截断` /
 * `无输出` are still on the card — as the **last line of the expanded card** ([ToolFooter],
 * which every block now draws inside its `if (expanded)`) — the state's glyph and word are
 * here, and the duration is the reading plus its tick, right here. What is gone is only the
 * repetition. The two rows of pi's own terminal card are not a spec point being broken:
 * pi prints the call line and its `Elapsed`/`Took` line because a terminal has no second
 * axis for them; a phone card's collapsed row is a *summary* and its expanded body is where
 * the sentence lives.
 *
 * ## The chevron is now always drawn
 *
 * It used to be conditional: `expandable = false` meant no chevron, "because an indicator
 * that never changes is a lie". With the footer inside the expanded state that rule would
 * make the footer **unreachable** on exactly the cards that have no body — a `read` of an
 * empty file, a settled `edit`, a command with no output — and the flag also flipped
 * (false → true) the moment a call's first result chunk arrived, so a pending card would
 * have changed height by one line mid-stream. Every card now has a footer sentence, so the
 * disclosure is real everywhere; the words 「展开 / 收起」 for the second-level disclosure below
 * a body are unchanged, and `Modifier.toggleContent` was already unconditional on every tool
 * card, so no gesture changed.
 *
 * **Everything on this row is monospace**, because everything on it is machine
 * language (rule 7 / `06 §2`「机器语言层一律等宽」). It used to set the subject in
 * M3's `bodyLarge` (15 sp, sans) and the tool name in `dim`; both were two steps
 * off v2 on a row the eye lands on first.
 *
 * **The subject is not one colour, because pi's is not.** Every built-in renderer builds its
 * call line out of several `theme.fg(token, …)` runs — `read`'s path is `accent` and its
 * `:1-50` is `warning`, `grep`'s pattern is `accent` while ` in <path>` is `toolOutput`, a
 * shell's whole line is `toolTitle`. v2 paints the whole subject `c-text`, and the user's
 * 「全修的一致」 ruling puts pi's semantics first, so the subject arrives as the runs the
 * renderer actually emits ([ToolCallPart]) and this composable only resolves their tokens.
 * Text, order, spacing, size and the monospace face are unchanged: **colour is the only
 * channel this parameter carries**.
 *
 * **The state is not only in the expanded footer any more, and the word is never `muted`.**
 * `06 §4`'s triple encoding (符号 + 字 + 色) has to survive on the collapsed card — a card
 * whose state is only visible after a tap is not a state indicator — so the glyph leads this
 * row and the state **word** sits in the verdict cell in `text`, exactly the two colours
 * (`06 §4`) and exactly the pair the footer used to carry. A 被拒 card reads `⊘ 被拒 … 没有执行`:
 * its reading cell takes the second half of `被拒 · 没有执行` rather than a number, because
 * nothing ran and there is nothing to measure ([toolRowReading]).
 *
 * Nothing shifts under pi's own two themes: `toolTitle`, `text` and `accent` are the same
 * value there (`theme.ts`'s dark/light `toolTitle = text`, and both ship `accent` as the one
 * non-neutral hue), so this — like the name's `toolTitle` — only becomes visible under an
 * imported theme that distinguishes them. That is exactly the drift the palette exists to
 * prevent, and exactly the case the ruling is about.
 *
 * @param subject the call line's runs, in pi's order. A block with no pi recipe for its
 *   arguments passes one [ToolCallToken.Uncoloured] run.
 * @param state the row's four-state reading: it paints the glyph, the rule above the reading,
 *   the tick, and the rejected reading slot.
 * @param right the reading column's text. Defaults to [toolHeaderReading] of [item];
 *   a block whose tool reads out as a *count* rather than a duration (`find`, `ls`)
 *   passes its own string.
 * @param elapsedMs the number the tick is drawn from — `ToolCall.elapsedMs` everywhere except
 *   the two shells, which pass the ViewModel's live value (`ShellBlock`); the reading beside
 *   it must be computed from the same number, which is why `ShellBlock` passes [right] too.
 * @param expanded whether the card is open, which only turns the chevron.
 */
@Composable
internal fun ToolHeader(
    item: ToolCall,
    title: String,
    subject: List<ToolCallPart>,
    state: ToolState,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    right: String? = toolHeaderReading(item),
    elapsedMs: Long? = item.elapsedMs,
) {
    val palette = PiTheme.palette
    val accent = toolAccentColor(state, palette)
    // One `AnnotatedString` with one span per run. Built here rather than at the seven call
    // sites: the row's style (`monoSmall`, single line, ellipsis) stays in one place, so no
    // block can accidentally change anything but a colour.
    val subjectText = remember(subject, palette) {
        buildAnnotatedString {
            subject.forEach { part ->
                withStyle(
                    SpanStyle(
                        color = part.token.color(palette),
                        fontWeight = if (part.bold) FontWeight.Bold else null,
                    ),
                ) { append(part.text) }
            }
        }
    }
    val reading = toolRowReading(state, right)
    Row(
        modifier = modifier.heightIn(min = TOOL_ROW_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The state's **symbol** channel, first cell. 7 dp is v5's `.mk` column
        // (`data-tool-row`'s first grid track): wide enough for one monospace glyph, narrow
        // enough that it does not push the tool name off the row.
        Text(
            text = toolStateGlyph(state),
            modifier = Modifier.width(TOOL_STATE_GLYPH_WIDTH),
            style = PiTheme.text.monoSmall,
            color = accent,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        Text(
            text = title,
            // **Bold, because pi bolds it**: `theme.fg("toolTitle", theme.bold(toolName))`
            // (`components/tool-execution.js:91`, and `:316` for the same header rebuilt on
            // update). The bundled family carries Regular + Bold and nothing between
            // (`PiMonoFamily`), so `Bold` is the face pi's bold actually maps onto here —
            // asking for SemiBold would make Android synthesise a weight the font does not have.
            style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
            // **`toolTitle` — the token pi paints a tool's *name* with.** Every built-in
            // renderer passes it (`core/tools/renderers/bash.ts:40` and the same line in
            // `read`/`write`/`edit`/`grep`/`find`/`ls`), and this cell is exactly that name.
            // It used to be `muted` because v2's prototype draws it `c-muted`; the user's
            // ruling is that pi's own semantics win (「全修的一致」), so that board line was
            // superseded and `06 §2` + `docs/pi-android-ui-spec.md` §2.5 were updated with it —
            // otherwise both documents would describe a colour this row no longer has.
            //
            // Nothing moves under pi's own two themes (`toolTitle = text` there), which is why
            // the difference was invisible until now — and why a theme that *does* distinguish
            // them is the case this fixes.
            color = palette.toolTitle,
            maxLines = 1,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        Text(
            text = subjectText,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.monoSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        // The verdict cell, **adaptive and right-edge aligned** (v5's design note: a
        // fixed-width verdict column squeezed `关东风雪长歌` into an ellipsis on the rows whose
        // word was longest). `text`, not `muted`: it is the state *word*, `06 §4`'s second
        // channel, and the one the footer already printed in `text`.
        Text(
            text = toolRowVerdict(state, item.outputTruncated),
            style = PiTheme.text.monoSmall,
            color = palette.text,
            maxLines = 1,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        // The reading cell, **one global fixed width** (v5's `.rd`: `.row`'s fifth grid track is
        // a literal `44px`, see [TOOL_READOUT_WIDTH]).
        //
        // It used to be as wide as its own contents. That did pin the reading's **right** axis —
        // the chevron and the two gutters behind it hold that — but it left the **verdict cell's
        // right edge** free to slide with the reading: a `12 项` row put 成功 4 dp to the left of
        // where a `575ms` row put it, and a row with no reading at all slid the whole way over to
        // the chevron. Three cards of one state did not line up down the transcript, which is the
        // one thing the reading column is *for*. v5 pins both axes with this one number, and it
        // pins them on **every** row: a grid track exists even when empty, so the three 运行中
        // rows that draw nothing there still hand the verdict its exact x. Hence no `if` around
        // the cell — only around the number inside it.
        Column(
            modifier = Modifier
                .width(TOOL_READOUT_WIDTH)
                // A **fixed** width must not become a **clipped** one. `没有执行` is four
                // full-width glyphs (~48 dp at 12 sp, the mono family falls back to the system
                // CJK face) and would be cut by a 44 dp box. v5 has the same overflow and the same
                // answer: the grid track stays 44 px and the number overflows it *leftward*, so
                // its right edge — the axis — never moves. `unbounded` measures the number at its
                // own width and `End` places it flush with this cell's right edge, spilling into
                // the 6 dp gutter exactly like CSS does.
                .wrapContentWidth(align = Alignment.End, unbounded = true),
            horizontalAlignment = Alignment.End,
        ) {
            if (reading != null) {
                Text(
                    text = reading,
                    style = PiTheme.text.monoSmall,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
            // `04 §1.1`: 刻度是读数的补充，不是替代 — so it sits under the number it
            // belongs to, at v5's 1 px gap. It draws nothing without a real `ms` (`DurationMeter`
            // returns early), which is why an empty cell costs no pixels and no measure pass.
            DurationMeter(
                ms = elapsedMs,
                color = accent,
                modifier = Modifier.padding(top = PiSpacing.hairline),
            )
        }
        Spacer(Modifier.width(PiSpacing.gutter))
        DisclosureChevron(expanded = expanded, tint = palette.bodyOnTool)
    }
}

/**
 * The collapsed row's height, and where 24 comes from.
 *
 * `差异表` §2 第 1/2 行: the folded card is **24 dp**, down from the 39.5 dp the two-row card
 * measured on a device. The card's own vertical padding is **0** ([ToolCardRowPadding]), so
 * this row *is* the card: `06 §2` 工具卡's `padding:7px 10px` keeps its 10 horizontal and gives
 * up the 7 vertical, which a row that must hold a 18 dp line box and v5's 15 dp tall reading
 * stack (12 dp number + 1 dp gap + 1 dp tick; [TOOL_READOUT_WIDTH] is its *width*) does not need.
 *
 * A **minimum**, not a fixed height: `app.appearance.fontScaleDelta` and the system font scale
 * can make a 12 sp line box taller than 18 dp, and a hard height would clip the text rather than
 * grow (§9's readability floor is the one thing a density win may not trade).
 */
private val TOOL_ROW_MIN_HEIGHT = 24.dp

/** v5's `.mk`: the state glyph's centred cell (one monospace advance). */
private val TOOL_STATE_GLYPH_WIDTH = 7.dp

/**
 * v5's `.rd`: the reading cell's **one global fixed width** — the row's fifth grid track.
 *
 * It is not a maximum but the cell's actual width on every card, because it is what holds the
 * **verdict** cell's right edge still: the value the reading happens to spell (`575ms`, `8ms`,
 * `12 项`, `没有执行`, or nothing at all) must not be able to move the state word it stands
 * beside. That is the whole reason v5 gives the track a literal `44px` rather than `auto` —
 * with `auto`, `.vd`'s right edge followed `.num`'s width and a column of `成功`s did not line
 * up. The reading's own right axis was already pinned by the chevron and the two gutters; the
 * verdict's was not.
 *
 * 44 fits the widest *number* the column ever prints — the shell face's `2m 49s` and a
 * three-digit `575ms` with room to spare — and the one phrase that does not fit overflows
 * leftward without moving the axis; see the cell's own note in [ToolHeader].
 */
private val TOOL_READOUT_WIDTH = 44.dp

/**
 * The card's **last line while expanded**: the state's glyph, then pi's whole footer sentence.
 *
 * `06 §4`「页脚永远同时有符号与状态词」— `… 运行中`, `✓ 成功`, `✗ 失败`, `⊘ 被拒` — and this
 * composable changes **nothing** about the sentence: the wording, the field order and the
 * ` · ` separators are the callers' ([toolFooterText], `ShellBlock`'s `shellFooter`,
 * `GrepBlock`'s own builder), and each of them is byte-for-byte what it was.
 *
 * **It moved here** (`差异表` §2 第 3 行): it used to be drawn unconditionally below the
 * header, which is what made a collapsed card two rows. Every block now draws it inside its
 * `if (expanded)`, after the body and after the warning line, so it is the last line of the
 * open card — 「事实一条不丢，只是换位置」.
 *
 * **No tick on this line.** The tick lives on the always-visible row, under the reading it
 * belongs to (`04 §1.1`: 刻度是它的视觉补充，不是替代), and it is on screen in both states;
 * drawing it here as well would be the third copy of one number that the single-row card
 * exists to remove.
 *
 * **6 dp of lower air is part of this row.** The card's own vertical padding is 0 now
 * ([ToolCardRowPadding]: the collapsed card *is* its 24 dp row), and the design gives the
 * expanded body `.body{padding:6px 0}` — since this line is always the last thing in that body,
 * carrying the 6 dp here is what keeps the sentence off the card's bottom edge, and it keeps it
 * in one place instead of in fourteen `if (expanded)` branches.
 */
@Composable
internal fun ToolFooter(
    text: String,
    state: ToolState,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(
        modifier = modifier.padding(bottom = PiSpacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = toolStateGlyph(state),
            style = PiTheme.text.monoSmall,
            color = toolAccentColor(state, palette),
            maxLines = 1,
        )
        Spacer(Modifier.width(PiSpacing.gutter))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.monoSmall,
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The second-level disclosure: pi's `... (N more lines, … to expand)`
 * (`core/tools/renderers/read.ts:133-135`, `grep.ts:53-55`, `write.ts:121-123`).
 *
 * pi has two expansions on a tool card: the card itself (its `options.expanded`) and the
 * keybound one that stops cutting the body at the preview count. The app keeps both — the
 * card's content region toggles the first (`Modifier.toggleContent`), and this label is the
 * second, which is why it is a real tap target rather than a hint. It is bounded by the
 * app's own budget, and whatever is still left over is reported in words, not painted.
 */
@Composable
internal fun ExpandAllLabel(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .clickable(onClickLabel = "展开全部输出", onClick = onClick)
            .padding(vertical = PiSpacing.tiny),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * pi's warning line under a result, in this app's language.
 *
 * pi prints `[Full output: <path>. Truncated: …]` in the warning colour
 * (`core/tools/renderers/bash.ts:100-118`, `renderers/grep.ts:61-66`), after the body and
 * before the elapsed line. The path is the one part of a truncation report a user needs
 * elsewhere, so the line copies it on tap.
 */
@Composable
internal fun ToolNotice(
    text: String,
    copyOnTap: String?,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val clipboard = LocalClipboardManager.current
    val target = copyOnTap
    val tap = if (target == null) {
        Modifier
    } else {
        Modifier.clickable(onClickLabel = "复制完整输出路径") {
            clipboard.setText(AnnotatedString(target))
        }
    }
    Text(
        text = text,
        style = PiTheme.text.meta,
        color = palette.warning,
        modifier = modifier.then(tap),
    )
}

/**
 * The card itself: the rail frame, pi's status container, the one content-region gesture
 * (`Modifier.toggleContent`, F28), and the block's own column of rows.
 *
 * The rail (`06 §3` 构件 1) wraps the card rather than the card wrapping the rail, because
 * the node and the line live in the card's left margin — outside the surface that carries
 * the tap gesture, exactly as v2 draws them (`marginLeft:-26; paddingLeft:26`).
 *
 * The rows inset by [ToolCardRowPadding] (10 horizontally, **0** vertically): the collapsed
 * card is exactly the row's 24 dp now (`差异表` §2 第 5 行 — a two-row card could afford 6 + 6 of
 * vertical padding, a one-row card of 39.5 → 24 cannot), and the expanded card's last line
 * carries the 6 dp of lower air the design's `.body{padding:6px 0}` gives it ([ToolFooter]).
 *
 * ## The 1 px outline belongs to the run, not to this card
 *
 * The card used to carry `borderColor = 状态色 @35%` (`06 §2`「描边 1px 状态色 35%」). In the
 * merged form the outline is drawn once for a whole **run** by [ToolRailFrame] — rounded at the
 * run's two ends, with the interior boundaries as 1 px dividers below each row — because an
 * outline per card would put a rectangle around every row of a run and undo the merge. The ring
 * keeps the same rule for a run that is one card (`该状态色 @35%`), and takes the neutral token
 * for a run whose rows disagree (`borderMuted@35%`) — see [railRingColor].
 *
 * @param firstOfRun/lastOfRun whether this card is the first / last tool row of its
 *   consecutive run — see [ToolRailFrame]; the list computes both.
 * @param ring the **run's** state ([ToolRunSlot.ring]): a uniform run paints its ring with that
 *   row's state colour, a mixed one neutrally. Null covers both "mixed" and a caller that passes
 *   nothing — the same neutral a lone card never had to use.
 */
@Composable
internal fun ToolCard(
    item: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
    ring: RailState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    val fill = toolContainerColor(state, palette)
    ToolRailFrame(
        glyph = toolStateGlyph(state),
        tone = toolStateTone(state),
        label = toolStateLabel(state),
        modifier = modifier,
        firstOfRun = firstOfRun,
        lastOfRun = lastOfRun,
        shellFill = fill,
        shellRing = railRingColor(ring, palette),
        // The interior divider is the merged form of 「每卡描边 1px 状态色 35%」: the line
        // between two rows is drawn by the **lower** row in its own state colour, so a green
        // row under a red one changes the line before either row's body is read.
        divider = toolAccentColor(state, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA),
    ) {
        BlockCard(
            color = fill,
            modifier = Modifier.toggleContent(expanded, onToggle, rowKey = item.key),
            shape = runCardShape(firstOfRun, lastOfRun),
            padding = ToolCardRowPadding,
            content = content,
        )
    }
}

/** `06 §2` 工具卡: the status-coloured border's alpha, in all four states. */
internal const val TOOL_CARD_BORDER_ALPHA: Float = 0.35f

/**
 * The colour a **run's** 1 px ring is drawn with (`差异表` §2 第 9 行; v5's
 * `.run[data-ring=…] .plate`).
 *
 * - a run whose rows all read the same state → **that state's colour @35 %**, exactly the
 *   per-card border one row of it used to draw, so a lone tool call is unchanged;
 * - a run that **mixes** states (`null`) → `borderMuted @35 %`, because the shell must not speak
 *   for one of its rows: a run holding a green row and a red row has no state of its own;
 * - a run of diff cards → `borderMuted`, the diff card's own border value (`06 §2` 颜色行: a diff
 *   is neutral by definition) — identical to what that card drew before, since a diff card is
 *   usually alone in its run.
 *
 * Every value is either a token or a token `copy(alpha = …)` — the derivation the cards already
 * used, so an imported theme controls all of it (no new hue, no invented grey).
 */
internal fun railRingColor(ring: RailState?, palette: PiPalette): Color = when (ring) {
    null -> palette.borderMuted.copy(alpha = TOOL_CARD_BORDER_ALPHA)
    RailState.Diff -> palette.borderMuted
    RailState.Running -> toolAccentColor(ToolState.Running, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA)
    RailState.Success -> toolAccentColor(ToolState.Success, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA)
    RailState.Failed -> toolAccentColor(ToolState.Failed, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA)
    RailState.Rejected -> toolAccentColor(ToolState.Rejected, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA)
}

/**
 * The long-press actions of §4.8, shared by every tool card: the command, the output, and
 * pi's own record of where a truncated result's remainder went.
 *
 * [command] is the tool's own argument (see [toolCommandText]); a tool whose arguments are
 * none of pi's spellings gets no command entry rather than a wrong one.
 *
 * **Every entry copies the whole field, never the preview on screen.** The card shows
 * `headLines` / the renderer's preview count and a `已截断` footer when that is less than
 * what pi returned, so `复制输出` taking `output` — pi's full result — is the only thing
 * that makes the truncated card safe to work from. That was already true and stays true;
 * the distinction matters more now that the body is a selection scope, because a selection
 * can only ever grab the preview on screen.
 *
 * The body is a selection scope (`BlockCard` → [SelectableContent]), so a long press on the
 * output selects it and the menu is reached by long-pressing the card's **chrome** — the
 * rail, the padding outside a text node. There is no ⋮ button: it would cost 32 dp of width
 * on every tool card, which on a phone is width the command line and the output do not have
 * (see [BlockActionMenu]).
 */
@Composable
internal fun ToolActionMenu(
    command: String?,
    output: String,
    fullOutputPath: String?,
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    BlockActionMenu(
        actions = buildList {
            if (command != null) {
                add(BlockAction("复制命令") { clipboard.setText(AnnotatedString(command)) })
            }
            if (output.isNotEmpty()) {
                add(BlockAction("复制输出") { clipboard.setText(AnnotatedString(output)) })
            }
            if (fullOutputPath != null) {
                add(BlockAction("复制完整输出路径") { clipboard.setText(AnnotatedString(fullOutputPath)) })
            }
        },
        content = content,
    )
}

/**
 * The command-ish argument of a tool call, in pi's own field names, or null.
 *
 * pi's schemas use `command` for the shell tools, `path` for `read`/`write`/`ls`, `pattern`
 * for the searches, and `file_path` as `edit`'s alternative spelling
 * (`core/tools/renderers/edit.ts:59-64` keeps both).
 */
internal fun toolCommandText(args: JsonObject?): String? =
    listOf("command", "file_path", "path", "pattern").firstNotNullOfOrNull { key ->
        (args?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

