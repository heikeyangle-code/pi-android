package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
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

/**
 * The four states a tool card can be in — three from pi, one the app has to read.
 *
 * pi's wire carries three ([ToolStatus]). The fourth is what a call a **policy or
 * approval layer stopped before it ran** looks like: pi has no "rejected" axis at
 * all, and reports the blocker's own sentence as an *error* result with no execution
 * behind it (`packages/agent/src/agent-loop.ts:644-655`; the app's own gate is the
 * blocker in this build, `app/src/main/assets/pi-extensions/pi-android-permission-gate.ts`).
 * `06 §4` gives that case its own word, glyph and colour because it is not a
 * failure — nothing ran — and `docs/extension-compatibility.md` §6.2 already asks the
 * app to render it as policy rather than error. This is the rendering half of that
 * gap; the other half (a transcript item that carries the distinction) would live in
 * the protocol layer.
 */
internal enum class ToolState { Running, Success, Failed, Rejected }

/** Status is a word, never only a colour (docs/pi-android-ui-spec.md §9). */
internal fun toolStateLabel(state: ToolState): String = when (state) {
    ToolState.Running -> "运行中"
    ToolState.Success -> "成功"
    ToolState.Failed -> "失败"
    ToolState.Rejected -> "被拒"
}

/** The glyph at the head of a tool card's header row (`06 §4`'s first channel). */
internal fun toolStateGlyph(state: ToolState): String = when (state) {
    ToolState.Running -> "…"
    ToolState.Success -> "✓"
    ToolState.Failed -> "✗"
    ToolState.Rejected -> "⊘"
}

/**
 * The **word** the header prints beside the glyph, or null when the glyph says it alone
 * (decision D4).
 *
 * `06 §4` gives each state a word as well as a symbol, and the pair used to be printed
 * unconditionally in the footer. Two of the four glyphs are ambiguous on their own — `…`
 * is also an ordinary prose ellipsis, and `⊘` is a policy stop that is deliberately not
 * the failure's `✗` — so those two keep their word beside the glyph. `✓` and `✗` are
 * self-evident, and the word is **not lost** for them: the glyph announces
 * [toolStateLabel] as its `contentDescription`, so a screen reader hears 「成功」 / 「失败」
 * where the row no longer paints them.
 */
internal fun toolStateWord(state: ToolState): String? = when (state) {
    ToolState.Running, ToolState.Rejected -> toolStateLabel(state)
    ToolState.Success, ToolState.Failed -> null
}

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

/** The glyph, stripe and tick colour of a card: its state colour. */
internal fun toolAccentColor(state: ToolState, palette: PiPalette): Color =
    stateToneColor(toolStateTone(state), palette)

/**
 * The sentences that mean "this call never ran", in the words the layer that stopped
 * it used.
 *
 * pi's own fallback is `Tool execution was blocked` (`agent-loop.ts:644`), and the
 * app's permission gate writes the rest — its deny branch, its hard shell refusal,
 * its no-dialog branch and its headless branch. They are matched as a **prefix** of
 * the result text, which is exactly what pi puts there (`createErrorToolResult` sets
 * the whole text to the reason), so a tool that merely *mentions* one of these
 * sentences in its output cannot be mistaken for a blocked call.
 *
 * The list is deliberately conservative: an unrecognised error is a failure
 * (`06 §4`'s `✗ 失败`), never a guess at 被拒.
 */
private val BLOCKED_REASONS = listOf(
    "Tool execution was blocked",
    "用户拒绝了这个设备操作：",
    "设备策略拒绝这条 Shell 命令",
    "确认对话框不可用",
    "没有确认通道（ctx.hasUI=false）时默认拒绝",
)

/** Whether a result text is one of [BLOCKED_REASONS] — see that list for the rule. */
internal fun toolBlocked(output: String): Boolean {
    val text = output.trimStart()
    return BLOCKED_REASONS.any { text.startsWith(it) }
}

/**
 * Which of the four states a row is in.
 *
 * A blocked call reaches the app as `isError = true` (`docs/extension-compatibility.md`
 * §6.2), so the only signal separating [ToolState.Rejected] from [ToolState.Failed] is
 * the blocker's sentence — the same kind of read pi's text already needs elsewhere in
 * `ToolOutputParse.kt`, and the one this doc recommends.
 */
internal fun toolStateOf(item: ToolCall): ToolState = when {
    item.status == ToolStatus.Pending -> ToolState.Running
    item.status != ToolStatus.Error -> ToolState.Success
    toolBlocked(item.output) -> ToolState.Rejected
    else -> ToolState.Failed
}

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
 * The card's title row, transcribed from v2's `ToolCard` header
 * (`direction-b-v2.html:740-747`, and `06 §2` 工具卡):
 *
 * ```
 * padding:7px 10px, gap 7
 *   字形      mono 12 状态色            <- `06 §4`'s glyph, always drawn
 *   工具名    mono 12 toolTitle + Bold  <- pi: fg("toolTitle", bold(toolName))
 *   主体      mono 12 分段取色          <- pi: the renderer's own fg(token, …) runs
 *   状态词    mono 12 text              <- only `…` / `⊘`; see [toolStateWord]
 *   右读数    mono 12 muted tab         <- the call's own reading (a duration, or a count)
 *   chevron   14, bodyOnTool            <- right while collapsed, down while expanded
 * ```
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
 * **The state is on this row now.** `06 §4`'s triple encoding used to live in
 * [ToolFooter] — 「页脚永远同时有符号与状态词」 — which spent a whole extra row (plus the
 * row gap above it) on every card saying what the header has room for. The glyph has
 * therefore moved here: always drawn, in [toolAccentColor]'s state colour, with
 * [toolStateLabel] as its `contentDescription`. The **word** follows it for the two
 * states whose glyph does not say enough on its own — `…` is also a prose ellipsis, and
 * `⊘` is a policy stop rather than a failure (decision D4) — while `✓` / `✗` print no
 * word, because a second channel that always repeats the first is not a second channel.
 * Nothing is lost to a screen reader: every state's glyph carries the word, so `✓` / `✗`
 * are still announced where they are no longer painted ([toolStateWord]).
 *
 * Nothing shifts under pi's own two themes: `toolTitle`, `text` and `accent` are the same
 * value there (`theme.ts`'s dark/light `toolTitle = text`, and both ship `accent` as the one
 * non-neutral hue), so this — like the name's `toolTitle` — only becomes visible under an
 * imported theme that distinguishes them. That is exactly the drift the palette exists to
 * prevent, and exactly the case the ruling is about.
 *
 * @param subject the call line's runs, in pi's order. A block with no pi recipe for its
 *   arguments passes one [ToolCallToken.Uncoloured] run.
 * @param state the call's state, which paints the glyph and decides whether the word is
 *   printed. Passed in rather than re-derived here: every block computes it once per
 *   composition with [toolStateOf], and one derivation is what keeps the glyph, the word
 *   and the card's own colours from disagreeing.
 * @param right the right-hand reading. Defaults to [toolHeaderReading] of [item];
 *   a block whose tool reads out as a *count* rather than a duration (`find`, `ls`)
 *   passes its own string.
 * @param expanded whether the card is open, which only turns the chevron.
 * @param expandable false for a card with no body: the chevron is then not drawn at
 *   all, because an indicator that never changes is a lie.
 */
@Composable
internal fun ToolHeader(
    item: ToolCall,
    title: String,
    subject: List<ToolCallPart>,
    state: ToolState,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    expandable: Boolean = true,
    right: String? = toolHeaderReading(item),
) {
    val palette = PiTheme.palette
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
    // The two states that spell themselves out (see the KDoc above): `…` and `⊘`.
    val word = toolStateWord(state)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // **Always drawn, in the state's own colour** (`06 §4`): the icon is the card's
        // permanent state channel, and `clearAndSetSemantics` is what makes it a *word*
        // for a screen reader instead of a bare symbol — the glyph character itself is
        // dropped from the semantics, so TalkBack says 「运行中」 and not 「…」.
        Text(
            text = toolStateGlyph(state),
            modifier = Modifier.clearAndSetSemantics { contentDescription = toolStateLabel(state) },
            style = PiTheme.text.monoSmall,
            color = toolAccentColor(state, palette),
            maxLines = 1,
        )
        Spacer(Modifier.width(TOOL_HEADER_GAP))
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
        Spacer(Modifier.width(TOOL_HEADER_GAP))
        Text(
            text = subjectText,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.monoSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (word != null) {
            Spacer(Modifier.width(TOOL_HEADER_GAP))
            Text(
                text = word,
                style = PiTheme.text.monoSmall,
                // The same colour the word carried in [ToolFooter] (`palette.text`, not
                // `muted`): it is a fact the user reads, not a caption — and the glyph
                // beside it already carries the state's colour, so painting the word too
                // would be the same channel twice (`06 §4`: 颜色只是第三层).
                color = palette.text,
                maxLines = 1,
            )
        }
        if (right != null) {
            Spacer(Modifier.width(TOOL_HEADER_GAP))
            Text(
                text = right,
                style = PiTheme.text.monoSmall,
                color = palette.muted,
                maxLines = 1,
            )
        }
        if (expandable) {
            Spacer(Modifier.width(TOOL_HEADER_GAP))
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = palette.bodyOnTool,
            )
        }
    }
}

/** `06 §2` 工具卡 标题行 `gap:7`. One consumer, one spelling. */
private val TOOL_HEADER_GAP = 7.dp

/**
 * The reading v2 prints at a tool card header's right end.
 *
 * v2 hands each card that string by hand (`right="132ms"`, `"96ms"`, `"12.3s"`,
 * `"6.4s"`, `"0.4s"`, `"2.1s"`); the rule that produces all of them is the one the
 * app already uses in its footers — **milliseconds for the file tools, pi's own
 * duration spelling for the two shells** — because pi measures those two families
 * with two different formatters (`core/tools/renderers/read.ts:129` prints its own
 * line count; `renderers/bash.ts:32-42` formats `Elapsed`/`Took` and switches to
 * `1m 30s` / `1h 5m 30s` once the call passes a minute, which is 0.86.1's change).
 *
 * Nothing here is a new number: [formatDuration] is the file-tool spelling and
 * `ToolOutputParse.formatDuration` is pi's shell formatter, verbatim — including the
 * unit switch, so an hour-long `bash` card reads `1h 5m 30s` and not `3930.0s`. A
 * call with no measured duration has no reading (v2 passes `right={null}` for
 * exactly that case), and a **被拒** call has none either — nothing ran, so there is
 * nothing to measure.
 */
internal fun toolHeaderReading(item: ToolCall): String? {
    if (toolStateOf(item) == ToolState.Rejected) return null
    val ms = item.elapsedMs ?: return null
    return if (item.toolName == "bash" || item.toolName == "powershell") {
        ToolOutputParse.formatDuration(ms)
    } else {
        formatDuration(ms)
    }
}

/**
 * The card's footer row: the state's **glyph and word**, then the duration tick.
 *
 * `06 §4`「页脚永远同时有符号与状态词」— `… 运行中`, `✓ 成功`, `✗ 失败`,
 * `⊘ 被拒` — and `06 §2` 工具卡's footer row is `padding:0 10px 8px`, `gap 6`:
 * `状态字形 + 状态词 + 右侧耗时刻度`. v2 draws it the same way
 * (`direction-b-v2.html:748-754`). The word arrives inside [text] (the callers build
 * it through [toolFooterText] / `shellFooter`), so the glyph is prepended here and
 * the pair is one glance apart.
 *
 * The row is monospace (`mono t12`) and in the **normal** text colour, not `muted`:
 * v2's footer line is `color:'var(--text)'`, and it is a reading the user actually
 * reads, not a caption. The tick keeps the state colour, which is the one channel
 * the glyph's own colour also carries.
 *
 * [DurationMeter] sits at the end of the readings: the tick is the *ordinal* reading
 * of the same number the text just printed (`04 §1.1`: 刻度是补充，不是替代), so it
 * belongs beside it rather than beside the affordance. It draws nothing when
 * [elapsedMs] is null, which is what keeps a card with no measured duration — and
 * every diff card, which has no duration of its own (`06 §2`: diff 卡不显示) — free
 * of an empty tick.
 *
 * [elapsedMs] is passed in rather than read from [item] because one caller has a
 * *live* number: a running shell command's elapsed time is `UiState.nowMs - item.ts`,
 * measured by [ShellBlock] against the ViewModel's 1 Hz clock, not read from
 * `ToolCall.elapsedMs` (which only exists once the call has ended). Nothing here scans
 * a result — the F31/F8 rule at the top of this file.
 *
 * There is **no expand label** on this row: v2's disclosure is the header chevron
 * (`direction-b-v2.html:746`), and the card body is itself the hit target
 * (`Modifier.toggleContent`), so a second label-sized affordance would be a fourth
 * place to tap for the same gesture.
 */
@Composable
internal fun ToolFooter(
    text: String,
    state: ToolState,
    elapsedMs: Long?,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
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
        DurationMeter(
            ms = elapsedMs,
            color = toolAccentColor(state, palette),
            modifier = Modifier.padding(start = PiSpacing.gutter),
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
 * The card itself: the shared left inset, pi's status container, the one content-region
 * gesture (`Modifier.toggleContent`, F28), and the block's own column of rows.
 *
 * [ToolRailFrame] wraps the card rather than the card wrapping the frame, because the
 * inset is the card's **outer** edge — outside the surface that carries the tap gesture,
 * exactly as v2 draws it (`marginLeft:-26; paddingLeft:26`). There is no rail line and no
 * node in that margin any more: the state glyph is the header row's first cell
 * ([ToolHeader]), and the rail was removed with it (`ToolRail.kt`'s KDoc).
 *
 * The rows inset by [BlockCardRowPadding] (10 horizontally), which is v2's own
 * `padding:7px 10px` on this card's rows rather than the default card's 12.
 *
 * The container is **not bordered**: `06 §2` gives it a 1 px status-coloured outline at
 * 35 %, and the official renderer's card has no border at all — a `Box` with the status
 * background and nothing else (`modes/interactive/components/tool-execution.ts:172-178`).
 * The fill is the state's first channel and the header glyph is its second, so the third
 * was a frame around an already-coloured card; on a phone that frame is the width of the
 * whole row's silhouette, which is the one thing the card does not need to spend.
 *
 * The corner radius is v2's original tool-card radius of **8** (`TOOL_CARD_SHAPE` below),
 * passed explicitly because [BlockCardShape] — the default the diff, error and custom cards
 * share — is 10.
 */
@Composable
internal fun ToolCard(
    item: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    ToolRailFrame(modifier = modifier) {
        BlockCard(
            color = toolContainerColor(state, palette),
            modifier = Modifier.toggleContent(expanded, onToggle),
            // No border: `06 §2`'s 1 px status outline is gone (see this composable's KDoc
            // for the decision). The fill alone is the card's ground.
            borderColor = null,
            padding = BlockCardRowPadding,
            shape = TOOL_CARD_SHAPE,
            content = content,
        )
    }
}

/**
 * `06 §2` 工具卡's corner radius, decision D2: v2's board draws this card's shell square
 * (a terminal has no rounds), and the app keeps **8 dp** because a transcript card is a
 * phone container rather than a terminal cell. It is passed to [BlockCard] explicitly,
 * since that card's own default ([BlockCardShape]) is 10 and is shared with the diff,
 * error and custom cards.
 */
private val TOOL_CARD_SHAPE = RoundedCornerShape(8.dp)

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
 * inset outside the container, the padding outside a text node. There is no ⋮ button: it
 * would cost 32 dp of width on every tool card, which on a phone is width the command line
 * and the output do not have (see [BlockActionMenu]).
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

/**
 * The card's footer parts, in pi's order: state, pi's exit code, the duration pi measures,
 * the row's size, and whether the result was truncated or empty.
 *
 * [lines] is passed in rather than recomputed (F31): the caller already holds a remembered
 * count for the same output, and this used to scan the whole (possibly megabyte) string on
 * every composition.
 *
 * A blocked call short-circuits every part: pi measured nothing, returned no exit code and
 * produced no rows, so the settled parts below would report `0ms · 1 行` about a call that
 * never ran. `06 §3` 构件 5 states the fact instead (「被拒 · 这次写入没有执行 · 0 行」);
 * the wording here is tool-agnostic, because the blocked call can be any tool.
 */
internal fun toolFooterText(item: ToolCall, state: ToolState, lines: Int): String {
    if (state == ToolState.Rejected) return toolRejectedFooter()
    val parts = mutableListOf(toolStateLabel(state))
    item.exitCode?.let { parts += "退出码 $it" }
    item.elapsedMs?.let { parts += formatDuration(it) }
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/** The one footer a blocked call has (`06 §3` 构件 5), shared by every tool card. */
internal fun toolRejectedFooter(): String = "${toolStateLabel(ToolState.Rejected)} · 没有执行"


/**
 * pi's `[invalid content arg - expected string]` case
 * (`core/tools/renderers/write.ts:108-109`), said in this app's words: a settled `write`
 * whose arguments carried no content.
 */
internal const val TOOL_INVALID_CONTENT = "参数里没有文件内容。"

/** A result whose text was cut by our own scan budget, so a block can say so. */
internal const val TOOL_SCAN_CAPPED_HINT = "输出过长，只解析了前面一部分。"
