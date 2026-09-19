package app.pi.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.pi.rpc.DiffHunk
import app.pi.rpc.DiffLine
import app.pi.rpc.DiffLineKind
import app.pi.rpc.ToolDiff
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.StateTone

/**
 * `tool-diff` (docs/pi-android-ui-spec.md §7.4): path plus `+N −N` up top, then
 * the hunks. Every line carries a 16dp symbol column (`+`, `-`, space) in
 * addition to its colour, and the two **changed** kinds carry an 8% wash — the
 * spec requires the symbol because colour alone must never be the only signal.
 * Long runs of unchanged lines fold to "… N 行未变", and the whole block folds to
 * a stats line past 200 lines.
 *
 * The colouring is pi's own, from `components/diff.ts`:
 *
 *  - **the whole line** — number and body — is painted in `toolDiffAdded` /
 *    `toolDiffRemoved` / `toolDiffContext` (`:127-152`), not just the marker;
 *  - a removed line paired with exactly one added line gets **intra-line change
 *    highlighting**: the changed runs are reverse-video'd (`:24-79`). On the phone
 *    that is a text background of the line's colour with the card's colour as the
 *    text (see [diffBody]) — the only place this app paints a text background.
 *
 * Three things here are the app's, not pi's, and are deliberate: the 8% wash on
 * the two changed kinds (a terminal has no background wash, and the spec asks for
 * a non-colour signal), the separate symbol column, and the line-number column
 * (pi carries the number inside the coloured line, `diff.ts:127-152`; a phone
 * column reads better and keeps the numbers aligned).
 *
 * The rows themselves are untouched by the v2 batch — `09-highlight-fidelity.md`
 * settled them 1:1 with pi, so their colours and the 8 % wash (on the changed rows
 * v2 gives it to, and only those) stay byte for byte.
 */

/** `06 §2` diff 卡's rail node: `±`, the one node that is not a state. */
private const val DIFF_NODE_GLYPH = "±"

/**
 * v2's line wash for the two changed kinds — `rgba(181,189,104,.08)` on an added
 * line, `rgba(204,102,102,.08)` on a removed one, which is each token's own colour
 * at 8%. Context rows carry no wash at all; see [DiffLineRow].
 */
private const val DIFF_LINE_WASH_ALPHA = 0.08f

@Composable
fun DiffBlock(
    item: ToolDiff,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) {
    val palette = PiTheme.palette
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // **`expanded` is a key, so a collapsed diff card plans nothing.** `plan` is read only
    // inside the `if (expanded)` branch below, yet it used to be built here unconditionally —
    // and building it is not cheap: it walks every hunk, pairs removed/added runs, and runs the
    // intra-line LCS (`wordTokens` + a table per pair) for a 1-to-1 pair. Measured on pi's own
    // 200-row cap: ~3.9 ms for a 60-row diff with 15 changed pairs, ~13.2 ms for a 400-row diff
    // with 80 pairs, and ~11.8 ms for a single pair whose lines carry 512 tokens each (probe:
    // `docs/scroll-perf-items.md` §2). `toolsDefaultExpanded` is false by default, so this was
    // paid by *every diff card entering the viewport* and every time one was composed fresh
    // after scrolling away and back (`remember` is not a cross-composition cache).
    //
    // The cost moves to the first expand of each card, where it happens once, hidden behind a
    // tap the user just made. Nothing user-visible changes: the same rows are built for the
    // same input, and `plan.isEmpty()` (the unparsable-diff branch) is only consulted while
    // expanded.
    //
    // And it goes through [DiffPlanCache], because "the first expand of each card" is per
    // *composition*: a diff card that scrolls out of the list's reuse pool and back is
    // expanded again with no plan in hand, which paid the 3.9–13.2 ms a second time. The cache
    // is keyed on the same two values this `remember` is, so a hit is the same list of rows.
    val plan = remember(item.key, item.diffText, expanded) {
        if (expanded) DiffPlanCache.plan(item.key, item.diffText, item.hunks, MAX_DIFF_ROWS) else emptyList()
    }
    val omitted = item.lineCount > MAX_DIFF_ROWS || item.truncated

    BlockColumn(modifier) {
        // The diff sits on the same rail as the calls beside it (`06 §3` 构件 1), with the
        // one node that is not a state: `±`. It carries **no duration tick** (`06 §2`:
        // diff 卡不显示 — nothing here was timed, and the row is already the densest in
        // the stream).
        //
        // `06 §2` gives this node the plain tokens rather than a state's: a solid
        // `borderMuted` ring and a `bodyOnTool` glyph, which is exactly the pair v2 draws
        // (`direction-b-v2.html:1516-1520`: `border:'1px solid var(--border-muted)'` and
        // `color:'var(--body-on-tool)'`). `StateTone.Muted` alone cannot express it — it
        // resolves one colour for *both* parts (ring at 45 %, glyph `muted`) — which is
        // why `PiStateNode` takes the two overrides.
        ToolRailFrame(
            glyph = DIFF_NODE_GLYPH,
            tone = StateTone.Muted,
            label = "差异",
            firstOfRun = firstOfRun,
            lastOfRun = lastOfRun,
            strokeAlpha = 1f,
            ringColor = palette.borderMuted,
            glyphColor = palette.bodyOnTool,
        ) {
            BlockCard(
                color = palette.toolPendingBg,
                // F28: same content-region gesture as the tool card.
                modifier = Modifier.toggleContent(expanded, { expanded = !expanded }),
                // `06 §2` 颜色行 gives this card the plain hairline token rather than a
                // state colour at 35 %: the diff is neutral by definition, and v2 draws it
                // `1px solid var(--border-muted)`.
                borderColor = palette.borderMuted,
                // `06 §2` 工具卡 / diff 卡: this card's rows inset by 10, not 12.
                padding = BlockCardRowPadding,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.toolName.ifEmpty { "diff" },
                        style = PiTheme.text.monoSmall,
                        // The leading cell of a tool card is the tool's **name**, and pi paints every
                        // tool name with `toolTitle` (`core/tools/renderers/edit.js:53`, `write.js:90`,
                        // `read.js:27`, and the fallback header at
                        // `components/tool-execution.js:91`). This card's name is the one from the call
                        // it was lifted out of (`rpc/.../Transcript.kt:541-564` passes `toolName`
                        // through), so it is the same cell and takes the same token. It used to be
                        // `muted` because v2's prototype draws it so; the 「全修的一致」 ruling puts
                        // pi's semantics first (`07` D40.1).
                        color = palette.toolTitle,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
                    Text(
                        text = item.path.ifEmpty { "未命名文件" },
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.monoSmall,
                        // A path in a tool card is `accent`: pi's `renderToolPath` returns
                        // `theme.fg("accent", shortenPath(value))`
                        // (`core/tools/render-utils.js:57-63`), and `edit`/`write` hand that to the
                        // card's path cell (`renderers/edit.js:52`, `write.js:89`). `text` was v2's
                        // `c-text`; pi's token wins on the same ruling as the name above. Same shape
                        // as `ToolHeader`'s `toolPathPart`, spelled out here because this card has its
                        // own header row instead of going through `ToolHeader`.
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(PiSpacing.inline))
                    Text("+${item.added}", style = PiTheme.text.monoSmall, color = palette.toolDiffAdded)
                    Spacer(Modifier.width(PiSpacing.gutter))
                    Text("−${item.removed}", style = PiTheme.text.monoSmall, color = palette.toolDiffRemoved)
                    Spacer(Modifier.width(PiSpacing.inline))
                    // v2's diff card carries the same disclosure chevron as a tool card's
                    // header (`direction-b-v2.html:1523`); its *label* below is plain text.
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.Filled.KeyboardArrowRight
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = palette.bodyOnTool,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "新增 ${item.added} 行 · 删除 ${item.removed} 行",
                        modifier = Modifier.weight(1f),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    // F19 (`docs/rendering-review.md`): the 「全屏」 label used to sit
                    // behind an `onOpenFull` the host never supplied — the app has no
                    // full-screen diff route — so both are deleted rather than left
                    // claiming the affordance (spec §4.8 still asks for one; that is
                    // new UI, recorded in the review's F19 row).
                    //
                    // A plain label, as v2 draws it: the chevron lives on the header row
                    // above (`direction-b-v2.html:1523`) and this row's word is inert —
                    // the card's own surface is the hit target (`toggleContent`).
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }

                if (expanded) {
                    if (plan.isEmpty()) {
                        // A diff `parseDiff` could not structure (neither unified nor pi's
                        // readable shape). Its text has no cap of its own — it comes from
                        // the tool's `details`, not from a truncated tool result — so it
                        // is painted through the same app-side budget the other bodies
                        // use, and the omission is said in the app's existing sentence
                        // (`ShellBlock`'s 「上方还有 N 行未显示」). Without this, one
                        // unparsable diff was the only transcript body with no ceiling:
                        // a single `Text` holding all of it, re-measured on every
                        // publication while the turn streamed.
                        val raw = item.diffText
                        val painted = remember(raw) { tailLines(raw, TOOL_BODY_MAX_LINES) }
                        val hidden = remember(raw, painted) { hiddenLineCount(lineCount(raw), painted) }
                        MonoText(
                            text = painted.ifEmpty { "（无差异内容）" },
                            // F13: `toolOutput` is 3.37:1 on the success card; the
                            // derived variant clears §9's 4.5:1 body floor.
                            color = palette.bodyOnTool,
                        )
                        if (hidden > 0) {
                            Text(
                                text = "上方还有 $hidden 行未显示",
                                style = PiTheme.text.meta,
                                color = palette.muted,
                                modifier = Modifier.padding(top = PiSpacing.tiny),
                            )
                        }
                    } else {
                        for (row in plan) {
                            when (row) {
                                is DiffRow.Header -> Text(
                                    text = row.text,
                                    style = PiTheme.text.monoSmall,
                                    color = palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                is DiffRow.Fold -> Text(
                                    text = "… ${row.count} 行未变",
                                    // `06 §2` diff 卡: the fold line lines up with the code
                                    // column, i.e. past the symbol column (16) and the
                                    // number column (30) — v2 draws it behind a 46 px
                                    // spacer for the same reason (`direction-b-v2.html:766`).
                                    modifier = Modifier.padding(
                                        start = PiSpacing.symbolColumn + PiSpacing.lineNumberColumn,
                                        top = PiSpacing.tiny,
                                        bottom = PiSpacing.tiny,
                                    ),
                                    // The fold is the one line of this body that is not code, but it
                                    // is still machine output: v2 draws it **inside** the body's own
                                    // monospace container and only overrides the size to 12
                                    // (`direction-b-v2.html:762-768`, `06 §2`「折叠行 12」), and its
                                    // number is a row count — one of the six readings `05 §4.2` keeps
                                    // on the machine face (「耗时/退出码/行数/token/费用/秒数 六类改成
                                    // `numeric`」). `meta` was the only mono-block line in the UI face,
                                    // which broke the column rhythm the comment above protects.
                                    style = PiTheme.text.monoSmall,
                                    color = palette.muted,
                                )

                                is DiffRow.Line -> DiffLineRow(row)
                            }
                        }
                        if (omitted) {
                            Text(
                                text = "差异过长，仅显示前 $MAX_DIFF_ROWS 行",
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(row: DiffRow.Line) {
    val line = row.line
    val palette = PiTheme.palette
    val symbol = when (line.kind) {
        DiffLineKind.Added -> "+"
        DiffLineKind.Removed -> "-"
        else -> " "
    }
    val lineColor = when (line.kind) {
        DiffLineKind.Added -> palette.toolDiffAdded
        DiffLineKind.Removed -> palette.toolDiffRemoved
        // F13: the context lines are read as body text (`PiTheme.text.code`, 13/19),
        // so they take the corrected variant, not pi's 3.69:1 value.
        else -> palette.contextOnTool
    }
    // The 8% wash belongs to the changed rows only. v2 gives the two changed kinds
    // their own tint and **context rows `transparent`**
    // (`direction-b-v2.html:773`: `const tint = l.t==='add' ? 'rgba(181,189,104,.08)'
    // : l.t==='del' ? 'rgba(204,102,102,.08)' : 'transparent'`;
    // `06-v2-construction-reference.md:76` states the same two values and no third).
    // Painting it on every row tinted unchanged lines with a lifted grey, which
    // both invented a texture v2 does not have and softened the two bands that make
    // a diff readable at a glance. `Color.Transparent.copy(alpha = …)` would still
    // be a *black* wash, so the context case is spelled out rather than left to the
    // alpha arithmetic.
    val wash = when (line.kind) {
        DiffLineKind.Added, DiffLineKind.Removed -> lineColor.copy(alpha = DIFF_LINE_WASH_ALPHA)
        else -> Color.Transparent
    }
    val body = remember(row, lineColor, palette.toolPendingBg) {
        diffBody(line.text.ifEmpty { " " }, row.changed, lineColor, palette.toolPendingBg)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(wash)
            .padding(vertical = PiSpacing.hairline),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = symbol,
            modifier = Modifier.width(PiSpacing.symbolColumn),
            style = PiTheme.text.code,
            color = lineColor,
        )
        Text(
            text = lineNumber(line),
            // `06 §2` diff 卡「行号列 30（右对齐 + 右内边距 8）」: the 8 dp is *inside*
            // the 30, so the code body starts at 16 + 30 from the row's left edge with
            // no extra gap — v2's `width:30;textAlign:right;paddingRight:8`
            // (`direction-b-v2.html:775`).
            modifier = Modifier.width(PiSpacing.lineNumberColumn).padding(end = 8.dp),
            style = PiTheme.text.code,
            // pi colours the number with the line it belongs to (`diff.ts:127-152`
            // builds `"-123 content"` inside one `theme.fg`): the column keeps its
            // own layout here, but not its own colour.
            color = lineColor,
            maxLines = 1,
            textAlign = TextAlign.End,
        )
        Text(
            text = body,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.code,
            color = lineColor,
        )
    }
}

/**
 * One diff line's body: the line's own colour, with pi's intra-line change
 * highlighting applied on top.
 *
 * pi marks a changed word pair by wrapping the changed runs in `theme.inverse()`
 * (`components/diff.ts:24-79`) — reverse video, which on a terminal swaps the
 * foreground and the background of the cell. A phone has no reverse-video
 * attribute, so the closest faithful rendering is the one Compose can express: the
 * run's **text takes the card's background colour** and its **background takes the
 * line's colour** — a solid bar of the diff colour with the word knocked out of
 * it, which is exactly what the reverse-video cell looks like in the terminal pi
 * draws into. It is deliberately the only text background in this app's code path.
 *
 * [changed] is empty for every line pi would not pair (see [diffPlan]), and then
 * this is one unbroken run in [lineColor].
 */
private fun diffBody(
    text: String,
    changed: List<ChangedRun>,
    lineColor: Color,
    cardColor: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (run in changed) {
        val start = run.start.coerceIn(cursor, text.length)
        val end = run.end.coerceIn(start, text.length)
        if (start > cursor) append(text.substring(cursor, start))
        if (start < end) {
            withStyle(SpanStyle(color = cardColor, background = lineColor)) {
                append(text.substring(start, end))
            }
        }
        cursor = end
    }
    if (cursor < text.length) append(text.substring(cursor))
}

private fun lineNumber(line: DiffLine): String = when {
    line.kind == DiffLineKind.Removed -> line.oldLine?.toString().orEmpty()
    line.newLine != null -> line.newLine.toString()
    line.oldLine != null -> line.oldLine.toString()
    else -> ""
}

// ------------------------------------------------------------ render planning

private sealed interface DiffRow {
    data class Header(val text: String) : DiffRow
    data class Fold(val count: Int) : DiffRow

    /** One diff line, plus the runs pi would reverse-video inside it. */
    data class Line(val line: DiffLine, val changed: List<ChangedRun> = emptyList()) : DiffRow
}

/**
 * Flatten hunks into rows the renderer can emit directly: file headers, hunk
 * headers, folded context runs and real lines, capped at [maxLines].
 *
 * The intra-line pairing rule is pi's (`components/diff.ts:105-135`): consecutive
 * removed lines are followed by consecutive added lines, and a word-level diff is
 * computed **only when each run is exactly one line**. A 1-to-2 or 2-to-2 change
 * is shown as whole coloured lines, exactly as pi shows it.
 */
private fun diffPlan(hunks: List<DiffHunk>, maxLines: Int): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    var budget = maxLines
    for (hunk in hunks) {
        if (budget <= 0) break
        // A file-level header hunk is recognised by its **lines**, not by its blank
        // header: `parsePiDiff` (`rpc/.../Transcript.kt`) also returns a blank-headed
        // hunk, but that one carries real `+`/`-` lines — pi's readable diff has no
        // `@@` to name a hunk with. `DiffHunk.isFileHeader` cannot tell the two
        // apart, so the kind test does.
        if (hunk.lines.isNotEmpty() && hunk.lines.all { it.kind == DiffLineKind.Header }) {
            for (line in hunk.lines) {
                if (budget <= 0) break
                rows += DiffRow.Header(line.text)
                budget--
            }
            continue
        }
        // An empty header has nothing to print; only a `@@`-named hunk gets a row.
        if (hunk.header.isNotEmpty()) rows += DiffRow.Header(hunk.header)
        var i = 0
        while (i < hunk.lines.size) {
            if (budget <= 0) break
            val line = hunk.lines[i]
            if (line.kind == DiffLineKind.Context) {
                var j = i
                while (j < hunk.lines.size && hunk.lines[j].kind == DiffLineKind.Context) j++
                val run = j - i
                if (run > CONTEXT_FOLD_THRESHOLD) {
                    rows += DiffRow.Line(hunk.lines[i])
                    rows += DiffRow.Fold(run - 2)
                    rows += DiffRow.Line(hunk.lines[j - 1])
                    budget -= 2
                    i = j
                    continue
                }
            }
            if (line.kind == DiffLineKind.Removed) {
                var j = i
                while (j < hunk.lines.size && hunk.lines[j].kind == DiffLineKind.Removed) j++
                var k = j
                while (k < hunk.lines.size && hunk.lines[k].kind == DiffLineKind.Added) k++
                val removedRun = hunk.lines.subList(i, j)
                val addedRun = hunk.lines.subList(j, k)
                if (removedRun.size == 1 && addedRun.size == 1) {
                    if (budget >= 2) {
                        val change = intraLineChange(removedRun[0].text, addedRun[0].text)
                        rows += DiffRow.Line(removedRun[0], change.removed)
                        rows += DiffRow.Line(addedRun[0], change.added)
                        budget -= 2
                    } else {
                        // The cap landed inside the pair; a half pair cannot be read
                        // as one edit, so only the removed line is emitted.
                        rows += DiffRow.Line(removedRun[0])
                        budget--
                    }
                    i = k
                    continue
                }
                for (removed in removedRun) {
                    if (budget <= 0) break
                    rows += DiffRow.Line(removed)
                    budget--
                }
                for (added in addedRun) {
                    if (budget <= 0) break
                    rows += DiffRow.Line(added)
                    budget--
                }
                i = k
                continue
            }
            rows += DiffRow.Line(line)
            budget--
            i++
        }
    }
    return rows
}

// --------------------------------------------------- intra-line change (pi's)

/** One changed run of characters, as half-open `[start, end)` offsets into a line. */
private data class ChangedRun(val start: Int, val end: Int)

/** The runs to reverse-video on each side of one removed/added line pair. */
private data class IntraLineChange(val removed: List<ChangedRun>, val added: List<ChangedRun>)

/**
 * One jsdiff token, kept as a range into its own line rather than as a string:
 * its content is always `line.substring(start, end)`, and the ranges are what the
 * renderer needs anyway. See [wordTokens] for why adjacent ranges can overlap
 * before [wordTokens] resolves them.
 */
private class WordToken(val start: Int, val end: Int)

/**
 * pi's `renderIntraLineDiff` (`components/diff.ts:24-79`), ported.
 *
 * Two of its three ingredients are ported exactly, the third cannot be without
 * shipping a JavaScript diff engine on the phone:
 *
 *  - **the tokenizer is jsdiff's**, rule for rule: `diff@8.0.4`'s
 *    `WordDiff.tokenize` (`lib/diff/word.js`) matches `[wordChars]+|\s+|[^wordChars]`
 *    and then stitches each whitespace run onto its neighbours, which leaves
 *    adjacent tokens *overlapping* by that whitespace ([wordTokens] keeps the same
 *    overlap and resolves it the way jsdiff's `join` does — every token but the
 *    first gives up its leading whitespace — so the ranges tile the line again).
 *  - **token equality is jsdiff's `equals`**: a trimmed comparison, so `"foo "` and
 *    `" foo"` are the same token.
 *  - **the edit script is an LCS, not Myers.** jsdiff finds a minimal edit script
 *    with Myers' algorithm; a longest-common-subsequence walk produces the same
 *    changed/kept partition for the single-line modifications this is used on, but
 *    when several different minimal scripts exist (repeated words, reordered
 *    clauses) the choice of *which* occurrence counts as changed can differ from
 *    pi's. Both are minimal and both are read the same way; the exact words are not
 *    guaranteed to match pi to the token.
 */
private fun intraLineChange(old: String, new: String): IntraLineChange {
    val oldTokens = wordTokens(old)
    val newTokens = wordTokens(new)
    if (oldTokens.size > MAX_INTRA_LINE_TOKENS || newTokens.size > MAX_INTRA_LINE_TOKENS) {
        // That many tokens on one line is a minified bundle, not code someone is
        // reading; an O(n·m) table is not worth building to colour it.
        return IntraLineChange(emptyList(), emptyList())
    }
    val kept = longestCommonTokens(
        oldTokens.map { old.substring(it.start, it.end).trim() },
        newTokens.map { new.substring(it.start, it.end).trim() },
    )
    return IntraLineChange(
        removed = changedRuns(oldTokens, kept.first, old),
        added = changedRuns(newTokens, kept.second, new),
    )
}

/**
 * jsdiff's tokenizer, as ranges.
 *
 * Step 1 takes the regex parts (which tile the line). Step 2 is jsdiff's stitching:
 * a whitespace part is appended to the token before it, and a non-whitespace part
 * that follows one **opens a new token that starts at that whitespace** — hence the
 * overlap. Step 3 is `join`'s compensation.
 */
private fun wordTokens(line: String): List<WordToken> {
    if (line.isEmpty()) return emptyList()
    val parts = ArrayList<WordToken>(8)
    for (match in WORD_TOKEN_PATTERN.findAll(line)) {
        parts += WordToken(match.range.first, match.range.last + 1)
    }
    val stitched = ArrayList<WordToken>(parts.size)
    var previous: WordToken? = null
    var previousBlank = false
    for (part in parts) {
        val partBlank = line.substring(part.start, part.end).isBlank()
        val prior = previous
        when {
            partBlank -> {
                if (prior == null) {
                    stitched += part
                } else {
                    val last = stitched.removeAt(stitched.size - 1)
                    stitched += WordToken(last.start, part.end)
                }
            }
            prior != null && previousBlank -> {
                val last = stitched.lastOrNull()
                if (last != null && last.start == prior.start && last.end == prior.end) {
                    stitched.removeAt(stitched.size - 1)
                    stitched += WordToken(last.start, part.end)
                } else {
                    stitched += WordToken(prior.start, part.end)
                }
            }
            else -> stitched += part
        }
        previous = part
        previousBlank = partBlank
    }
    return stitched.mapIndexed { index, token ->
        if (index == 0) {
            token
        } else {
            // `join`'s rule: every token but the first drops its leading whitespace.
            val leading = line.substring(token.start, token.end).takeWhile { it.isWhitespace() }.length
            WordToken(token.start + leading, token.end)
        }
    }
}

/** LCS over token text; returns the tokens that are kept on each side. */
private fun longestCommonTokens(a: List<String>, b: List<String>): Pair<BooleanArray, BooleanArray> {
    val n = a.size
    val m = b.size
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            table[i][j] = if (a[i] == b[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }
    val keptA = BooleanArray(n)
    val keptB = BooleanArray(m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                keptA[i] = true
                keptB[j] = true
                i++
                j++
            }
            table[i + 1][j] >= table[i][j + 1] -> i++
            else -> j++
        }
    }
    return keptA to keptB
}

/**
 * The changed tokens of one side, as ranges — with pi's own first-part rule:
 * the leading whitespace of the **first** changed part stays out of the inverse
 * so indentation is never highlighted (`components/diff.ts:44-60`).
 */
private fun changedRuns(tokens: List<WordToken>, kept: BooleanArray, line: String): List<ChangedRun> {
    val runs = mutableListOf<ChangedRun>()
    var firstChanged = true
    tokens.forEachIndexed { index, token ->
        if (index < kept.size && kept[index]) return@forEachIndexed
        var start = token.start
        val end = token.end
        if (firstChanged) {
            start += line.substring(start, end).takeWhile { it.isWhitespace() }.length
            firstChanged = false
        }
        if (start < end) runs += ChangedRun(start, end)
    }
    return runs
}

/**
 * jsdiff 8.0.4's `tokenizeIncludingWhitespace` character classes
 * (`diff/lib/diff/word.js`): word characters, whitespace, or one other character.
 */
private const val WORD_CHAR_CLASS =
    "a-zA-Z0-9_\\u00AD\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u02C6\\u02C8-\\u02D7\\u02DE-\\u02FF\\u1E00-\\u1EFF"

private val WORD_TOKEN_PATTERN = Regex("[$WORD_CHAR_CLASS]+|\\s+|[$WORD_CHAR_CLASS]")

/** Above this many tokens a single line is not worth an O(n·m) table. */
private const val MAX_INTRA_LINE_TOKENS = 512

private const val CONTEXT_FOLD_THRESHOLD = 4
private const val MAX_DIFF_ROWS = 200

/**
 * The process-wide memo of [diffPlan] results — the cross-composition half of `DiffBlock`'s
 * `remember`.
 *
 * `diffPlan` is the most expensive single parse in the transcript (measured 3.9 ms for a
 * 60-row diff with 15 changed pairs, 13.2 ms for a 400-row diff with 80 pairs, 11.8 ms for
 * one pair whose lines carry 512 tokens each — `docs/scroll-perf-items.md` §2), and its
 * result was state of one composition: a diff card the `LazyColumn` disposed and recomposed
 * paid it again even though nothing about its input had changed. Diff cards are also the
 * rows most likely to be recycled in a run of tool calls, which is what made this the
 * largest item in the ranking.
 *
 * Bounded by [MAX_PLAN_BYTES] with the same rules as the other caches: bytes, honest
 * accounting, a hit that is the same list of rows, an entry that cannot fit is not stored,
 * and no failure to cache (the function cannot fail). The budget is 4 MiB — a quarter of
 * what the pictures may hold, because a plan is cheap next to a decode; a 200-row plan with
 * 60-character lines weighs roughly 74 KB (rows plus the diff text key), so this holds
 * several dozen diffs.
 *
 * The key is `(row key, diff text, max rows)`. `hunks` is deliberately **not** part of it:
 * it is `parsePiDiff(diffText)`'s projection, which is exactly the assumption the
 * `remember` this cache mirrors already made. If the two ever disagreed, the reducer —
 * which builds both from the same entry — would be the bug, not this cache.
 */
private object DiffPlanCache {

    /** Bytes the plans may hold, keys included. See the KDoc for the number. */
    private const val MAX_PLAN_BYTES: Long = 4L * 1024 * 1024

    private val lru = ByteBoundedLru<PlanKey, List<DiffRow>>(MAX_PLAN_BYTES) { key, plan ->
        key.bytes + planWeight(plan)
    }

    /** [diffPlan] of `(key, diffText)`, from the memo when it has it. */
    fun plan(rowKey: String, diffText: String, hunks: List<DiffHunk>, maxRows: Int): List<DiffRow> =
        lru.getOrCompute(PlanKey(rowKey, diffText, maxRows)) { diffPlan(hunks, maxRows) }

    /**
     * What one plan costs, in bytes: every row's own text at two bytes a character, plus a
     * flat [ROW_OVERHEAD] for the row object, its `changed` list and the list slot. `DiffRow`
     * holds no other string — the numbers it prints are derived at draw time — so this is an
     * estimate with a stated constant rather than a guess.
     */
    private fun planWeight(plan: List<DiffRow>): Long {
        var weight = ROW_OVERHEAD * plan.size
        for (row in plan) {
            val text = when (row) {
                is DiffRow.Header -> row.text
                is DiffRow.Fold -> null
                is DiffRow.Line -> row.line.text
            }
            if (text != null) weight += text.length.toLong() * 2
            if (row is DiffRow.Line) weight += row.changed.size.toLong() * CHANGED_RUN_BYTES
        }
        return weight
    }

    /** Per-row overhead: two object headers, the list slot and the `changed` list. */
    private const val ROW_OVERHEAD = 64L

    /** Per intra-line run: two ints and a list slot. */
    private const val CHANGED_RUN_BYTES = 20L

    /**
     * A plan's key. `equals` compares the diff text without hashing it (the map never calls
     * `hashCode`); [hashCode] is built from the (short) transcript key and the row cap, so it
     * is O(1) — the same pair of rules `PiImageCache.Key` documents.
     */
    private class PlanKey(val rowKey: String, val diffText: String, val maxRows: Int) {
        val bytes: Long = (rowKey.length.toLong() + diffText.length.toLong()) * 2

        override fun equals(other: Any?): Boolean = other is PlanKey &&
            maxRows == other.maxRows &&
            rowKey == other.rowKey &&
            diffText == other.diffText

        override fun hashCode(): Int = 31 * rowKey.hashCode() + maxRows
    }
}
