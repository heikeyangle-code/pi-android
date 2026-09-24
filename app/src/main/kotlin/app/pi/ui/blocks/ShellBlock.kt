package app.pi.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import app.pi.rpc.Ansi
import app.pi.rpc.ToolCall
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `bash` and `powershell` — pi's shell renderers (`core/tools/renderers/bash.ts`).
 *
 * pi builds both from one factory: `bash` gets the `$` prompt and `powershell` the `PS>`
 * prompt (`core/tools/renderers/index.ts:35-36` → `createShellRenderers`), which is the only
 * difference between the two definitions. The card is:
 *
 *  - the command as the title (`formatShellCall`, `:26-32`: `${prompt} ${command}`, with
 *    ` (timeout Ns)` when the call set one);
 *  - the output as **the tail**, not the head (`:53-55`): a command's outcome is at the end, so
 *    the body is the last lines, and pi's own bash tool truncates to the *last* lines for the
 *    same reason (`core/tools/bash.ts:234`);
 *  - the output **only while the card is expanded** — a deliberate deviation from pi, and the
 *    one place in this card where the shape is not pi's. pi's collapsed card is not blank: it
 *    paints the last `BASH_PREVIEW_LINES` rows (`truncateToVisualLines(styledOutput,
 *    BASH_PREVIEW_LINES, width)`, `:56-70`) plus a `muted` 「… (N earlier lines, … to expand)」
 *    hint. That is five tail rows *and* a hint line on **every** collapsed shell call, and a
 *    run of shell calls is where a transcript spends most of its height — on a phone that is
 *    the difference between seeing four turns at once and seeing two. The header line already
 *    says what ran and the footer already says how it ended (state, exit code, line count,
 *    「已截断」), so a collapsed card is readable without the tail. Going back to pi's shape is
 *    the single guard around the body below. Ledger: `07-construction-decisions.md` D45;
 *  - `[Full output: <path>. Truncated: …]` as a warning line (`:86-98`) — the same sentence in
 *    the same place, but **inside the expanded branch** here, for the same reason (D45): the
 *    footer's 「已截断」 carries the fact while collapsed, and the notice is what names the file
 *    to copy from. The sentence the tool already appended to its own text is stripped first
 *    (`:47-53`), which is what [stripFullOutputFooter] does, so the fact is printed once;
 *  - `Elapsed 12.3s` while the command runs and `Took 12.3s` afterwards (`:100-105`), pi
 *    refreshing it once a second (`:122`).
 *
 * > Line numbers are the shipped build's (`dist/core/tools/renderers/bash.js`, v0.86.1), which
 * > is the copy this was verified against; the older `.ts` numbers elsewhere in this file are
 * > the upstream TypeScript source and point at the same statements.
 *
 * **The running clock is pushed, not read here.** pi starts an interval for it
 * (`:122`); this app has one clock for the whole transcript, owned by the ViewModel
 * (`UiState.nowMs`, ticking once a second while some tool card is pending) and handed
 * to this card as [nowMs]. The version of this that read `System.currentTimeMillis()`
 * during composition was a real bug, not just a shortcut: a row only recomposes when
 * something pushes it (streamed text, a bash output chunk), so a command that printed
 * nothing froze its number until a tap or a screen switch forced a composition — the
 * same number pi's interval keeps moving. The 200 ms `tool_execution_update` throttle
 * is no substitute: it drives publications, and a silent command has none.
 * Nothing here starts or cancels a timer.
 *
 * Two of pi's numbers are not available from the protocol and are read from pi's own text:
 * the exit code, because `BashToolDetails` does not carry one (`core/tools/bash.ts:49-52`) —
 * a non-zero exit throws and the number survives only in `Command exited with code N`
 * (`:363-364`) — and the truncation/complete-output report, as in the generic card.
 */
@Composable
internal fun ShellBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
    /**
     * `UiState.nowMs`: the ViewModel's 1 Hz coarse clock, non-null exactly while a
     * tool card is pending. Null keeps the pre-clock behaviour (one read per
     * composition) rather than producing no reading at all.
     */
    nowMs: Long? = null,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var fullOutput by remember { mutableStateOf(false) }
    val pending = state == ToolState.Running
    val command = remember(item.args) { argString(item.args, "command").orEmpty() }
    val timeout = remember(item.args) { argInt(item.args, "timeout") }
    val exitCode = remember(item.exitCode, item.output) {
        ToolOutputParse.shellExitCode(item.exitCode, item.output)
    }
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item.details, item.output) }
    val truncation = remember(item.details) { truncationOf(item.details) }
    val notice = remember(fullOutputPath, truncation) { truncationNotice(fullOutputPath, truncation) }
    // pi strips the footer sentence only when it is about to print the same thing as its
    // warning line, and never while the result is still partial (`renderers/bash.ts:59-64`,
    // `:142`).
    val bodyText = remember(item.output, fullOutputPath, truncation, item.status) {
        val text =
            if (!pending && truncation != null) stripFullOutputFooter(item.output, fullOutputPath) else item.output
        // pi drops ANSI **at the source**, twice: the bash executor strips every chunk
        // before it is buffered or streamed (`core/bash-executor.ts:82`), and the display
        // path strips again on the way out (`core/tools/render-utils.ts:48`). So a pi
        // surface never shows an escape sequence, and it never invents its own ANSI
        // renderer either — the 16/256/RGB palette lives only in the HTML exporter
        // (`core/export-html/ansi-to-html.ts:15-31`). This is the second of those two
        // strips, in the one place this app draws captured shell text, which is what
        // makes the two agree instead of relying on the guest having done it.
        //
        // `ParseCaches.stripped` rather than `Ansi.strip` directly: this `remember` dies
        // with the composition, and a card the list disposed and recomposed used to pay the
        // strip again (2.4 ms on a 2000-line coloured result) every time the reader scrolled
        // back to it. A body with no escapes is returned untouched, memo or not.
        ParseCaches.stripped(text)
    }
    val lines = remember(bodyText) { lineCount(bodyText) }
    // The window the **expanded** card paints: five rows until 「展开全部」 is taken
    // (`fullOutput`), the app's own budget after that. `SHELL_PREVIEW_LINES` is pi's
    // `BASH_PREVIEW_LINES`, and pi also uses it for the **collapsed** card — which this app
    // deliberately does not draw (D45, see the class KDoc): a collapsed shell call shows its
    // header and footer and nothing else.
    //
    // **`expanded` is a key, and that is the point**: while the card is collapsed the tail is
    // neither painted nor needed, and this used to compute it anyway ("the first expand must
    // not pay for the tail twice") — on a row that recomposes for every 200 ms publication
    // while a command streams. Measured on pi's own 2000-line / ~68 KB result cap, the split
    // and re-join is ~1.9–2.0 ms per call on a phone, per publication, for a value nothing
    // reads (probe and numbers: `docs/scroll-perf-items.md` §2). The trade is the other way
    // round from the old comment: the *common* case (collapsed, streaming) pays nothing and a
    // re-expand pays one tail, once. Nothing user-visible changes — the same text is painted
    // when `expanded` is true, which is the only place `painted` is read.
    //
    // This is the **logical** tail, and while the card is merely expanded it is only the input
    // to pi's visual-line window ([ShellPreviewBody]): the last five *logical* lines always
    // contain the last five *visual* ones (a visual line never spans two logical lines), so the
    // window taken from this substring is exactly the window pi takes from the whole body.
    //
    // `ParseCaches.tail` rather than `tailLines` directly, for the same reason as the strip
    // above: a collapsed-then-expanded card that was disposed in between re-split the whole
    // result for a 5-line answer, and this is the row most likely to be recycled while the
    // reader scrolls a run of shell calls.
    val painted = remember(bodyText, fullOutput, expanded) {
        if (!expanded) {
            ""
        } else {
            ParseCaches.tail(bodyText, if (fullOutput) TOOL_BODY_MAX_LINES else SHELL_PREVIEW_LINES)
        }
    }
    val subject = remember(command, timeout) { shellSubject(command, timeout) }
    // The live clock comes **from the ViewModel** ([nowMs], `UiState.nowMs`), which ticks
    // once a second for exactly as long as some tool card is pending. Reading
    // `System.currentTimeMillis()` here instead is the bug this replaces: that number is a
    // function of *when this row last recomposed*, and a command that prints nothing gets
    // no recomposition at all — the reading froze until a tap or a screen switch forced
    // one. The same number feeds the footer's text and its tick (`04 §1.1`: 刻度与读数
    // 同源), so a running command's meter grows with the seconds beside it.
    //
    // `nowMs` is null while nothing is pending, which is the only case the fallback below
    // covers: the ViewModel's clock publishes its first tick on the main loop *after* the
    // row that arms it, so for that one hop (and for any caller that passes no clock at
    // all — the default) one read per composition is still the correct number. It is not a
    // second clock: once the row is armed, the value comes from the tick.
    val elapsedMs = if (pending) {
        ((nowMs ?: System.currentTimeMillis()) - item.ts).coerceAtLeast(0)
    } else {
        item.elapsedMs
    }
    val footer = shellFooter(item, state, exitCode, lines, elapsedMs)
    ToolActionMenu(command.ifEmpty { null }, item.output, fullOutputPath) {
        BlockColumn(modifier) {
            ToolCard(
                item,
                expanded,
                { expanded = !expanded },
                firstOfRun = firstOfRun,
                lastOfRun = lastOfRun,
            ) {
                ToolHeader(
                    item = item,
                    title = "$",
                    subject = subject,
                    expanded = expanded,
                    expandable = bodyText.isNotEmpty() || notice != null,
                )
                // The body is drawn **only while expanded** (D45): pi paints the collapsed tail
                // too (`renderers/bash.js:56-70`), and that is the one shape here this app does
                // not copy — see the class KDoc for why (phone height, and the footer already
                // carries the outcome). Everything the guard controls is pi's: the tail, the
                // five-row window and the second disclosure level.
                //
                // While merely expanded the window is pi's, **in visual lines**: five wrapped
                // rows, tail-anchored ([ShellPreviewBody]). pi takes five *visual* lines
                // (`renderers/bash.ts:78` → `visual-truncate.ts:27-48`), and the difference is
                // not cosmetic — five *logical* lines wrap to a varying number of rows as the
                // tail slides, so the card's height used to change while a command streamed and
                // everything below it moved with it.
                if (expanded && bodyText.isNotEmpty()) {
                    ShellPreviewBody(
                        text = painted,
                        totalLines = lines,
                        fullOutput = fullOutput,
                        color = palette.bodyOnTool,
                        onExpandAll = { fullOutput = true },
                    )
                }
                // A settled command with no output says so in the footer ([shellFooter]),
                // which is where pi's own card reports the same thing; the body has nothing
                // to print either way.
                //
                // pi's warning line, at pi's own place *within the expanded body*
                // (`renderers/bash.js:78-90`: `component.addChild(new Text(`\n${theme.fg("warning",
                // `[${warnings.join(". ")}]`)}`, 0, 0))`), but **inside the expanded branch** here:
                // pi's condition (`if (truncation?.truncated || fullOutputPath)`) is independent
                // of `expanded`, so its collapsed card reports a truncated result too (D45 — the
                // same decision that hides the collapsed tail hides its notice; the footer's
                // 「已截断」 is what carries the fact while collapsed).
                //
                // It is the same information as the sentence pi's bash tool appends to its own
                // output, so it appears once: [stripFullOutputFooter] removes that sentence from
                // the body first (`:59-64`), and nothing else in this card prints it.
                if (expanded && notice != null) {
                    ToolNotice(text = notice, copyOnTap = fullOutputPath)
                }
                ToolFooter(
                    text = footer,
                    state = state,
                    elapsedMs = elapsedMs,
                )
            }
        }
    }
}

/**
 * pi's shell subject: the command, with pi's ` (timeout Ns)` suffix
 * (`core/tools/renderers/bash.js:26-32`). The card's title slot carries pi's prompt
 * (`$`, or `PS>` for the powershell variant — `renderers/index.js:35-36` is the only thing
 * that differs between the two definitions).
 *
 * ## The two runs are pi's own tokens, and the command keeps pi's bold
 *
 * ```js
 * function formatShellCall(args, prompt) {
 *     const command = str(args?.command);
 *     const timeout = args?.timeout;
 *     const timeoutSuffix = timeout ? theme.fg("muted", ` (timeout ${timeout}s)`) : "";
 *     const commandDisplay = command === null ? invalidArgText(theme) : command ? command : theme.fg("toolOutput", "...");
 *     return theme.fg("toolTitle", theme.bold(`${prompt} ${commandDisplay}`)) + timeoutSuffix;
 * }
 * ```
 *
 * So a shell card is the **one** call line whose whole text — prompt *and* command — is
 * `toolTitle`, and it is `bold()` as a whole; only the timeout suffix is `muted`. The prompt
 * itself is the title cell here (already `toolTitle` + Bold), and the command is this
 * subject, so it carries the same token **and the same bold**, or the two halves of one
 * pi-identical line would disagree. (An empty command keeps our empty text rather than pi's
 * `...`: the text is ours to keep, the token is pi's — `toolOutput`, which is the branch pi
 * takes for a present-but-empty command.)
 *
 * The command is one line on the card; a multi-line command is copied in full from the
 * action menu rather than wrapped into the title.
 */
private fun shellSubject(command: String, timeout: Int?): List<ToolCallPart> = buildList {
    val head = command.lineSequence().firstOrNull().orEmpty()
    add(ToolCallPart(head, ToolCallToken.ToolTitle, bold = true))
    if (timeout != null) add(ToolCallPart(" (timeout ${timeout}s)", ToolCallToken.Muted))
}

/**
 * The footer line: state, pi's exit code, pi's elapsed number, and the row's size.
 *
 * pi splits these across two lines (its card's title, and `Elapsed`/`Took` under the body);
 * the app's card has one footer row, so they are joined in pi's order. The elapsed part is
 * [ToolOutputParse.elapsedLabel], which is pi's `formatDuration` (`renderers/bash.ts:32-42`,
 * including 0.86.1's switch to minutes and hours past a minute).
 *
 * [elapsedMs] is handed in by the caller: while the command runs it is the ViewModel's
 * 1 Hz clock minus the row's timestamp (see [ShellBlock]), and the same value goes to the
 * tick. A blocked call has no such reading — nothing ran — so it takes the fourth state's
 * own footer instead.
 */
private fun shellFooter(item: ToolCall, state: ToolState, exitCode: Int?, lines: Int, elapsedMs: Long?): String {
    if (state == ToolState.Rejected) return toolRejectedFooter()
    val pending = state == ToolState.Running
    val parts = mutableListOf(toolStateLabel(state))
    if (!pending) exitCode?.let { parts += "退出码 $it" }
    if (elapsedMs != null) parts += ToolOutputParse.elapsedLabel(pending, elapsedMs)
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (!pending && item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/**
 * The card's body while it is expanded: pi's preview window, and the app's own second
 * level of disclosure underneath it.
 *
 * ## The window is pi's, in visual lines
 *
 * pi's collapsed card paints the tail of a shell result as **five wrapped rows** —
 * `truncateToVisualLines(styledOutput, BASH_PREVIEW_LINES, width)`
 * (`core/tools/renderers/bash.ts:78`, the rule itself in
 * `modes/interactive/components/visual-truncate.ts:27-48`): it renders the whole body at
 * the terminal width, keeps the last five **visual** lines and reports how many it
 * skipped. This app paints the same window on the *expanded* card (D45: the collapsed
 * card has no body at all), so the rows must be visual here too — [painted] is the
 * logical tail, whose last five visual lines are exactly pi's
 * ([visualTailWindowStart]).
 *
 * **Why this is not cosmetic.** Five *logical* lines wrap to a number of rows that
 * changes as the tail slides: a command whose newest lines are long used to make the
 * card one row taller, then one row shorter, on every 200 ms publication — and every
 * row below it moved with the card. With the visual window the body's height is
 * exactly [SHELL_PREVIEW_LINES] rows for as long as the command runs, which is the
 * stability pi gets from its terminal grid.
 *
 * ## What the label says, and where it differs from pi
 *
 * pi's hint is part of its *collapsed* preview: `... (N earlier lines, to expand)` with
 * `N = skippedCount`, a count of **visual** lines (`renderers/bash.ts:63-64`,
 * `visual-truncate.ts:39-42`). This app's sentence is its own («上方还有 N 行未显示»,
 * and the order note below) and its `N` is a count of
 * **logical** lines still fully above the window — the number of lines the reader is
 * missing, which is what the sentence claims. Counting visual lines instead would mean
 * laying the whole body out at the current width on every publication (pi does that per
 * render; a 2000-line Compose layout is not something to do every 200 ms), so the app
 * keeps its own sentence and its own count. The window is pi's; the sentence is ours.
 *
 * @param text the logical tail to window: [tailLines] of the body at
 *   [SHELL_PREVIEW_LINES] logical lines, or the app's [TOOL_BODY_MAX_LINES] budget once
 *   「展开全部」 has been taken.
 * @param totalLines the body's logical line count, for the label.
 */
@Composable
private fun ShellPreviewBody(
    text: String,
    totalLines: Int,
    fullOutput: Boolean,
    color: Color,
    onExpandAll: () -> Unit,
) {
    val style = PiTheme.text.mono
    // 「展开全部」 is the app's own budget, not a window: the whole point of taking it is to
    // stop cutting the body at the preview, so no visual window applies and the label below
    // is the app's plain hint (pi's expanded card is its whole output and has no window at
    // all). Its height is allowed to change with the content, exactly as pi's is.
    if (fullOutput) {
        MonoText(text = text, color = color, modifier = Modifier.padding(top = PiSpacing.tiny))
        val hidden = remember(text, totalLines) { hiddenLineCount(totalLines, text) }
        if (hidden > 0) {
            // pi's hint line, minus the key hint a phone does not have. The words are this
            // app's («上方还有 N 行未显示» — the same sentence the preview state uses).
            //
            // **Order differs from pi on purpose.** pi returns `[hint, …preview]`, so its hint
            // sits *above* the preview; ours says 「**上方**还有 N 行」, which is only true when
            // it sits *below* it. Keeping the sentence we already ship (rather than pi's
            // 「N earlier lines」) is what pins the order.
            Text(
                text = "上方还有 $hidden 行未显示",
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                modifier = Modifier.padding(top = PiSpacing.tiny),
            )
        }
        return
    }

    // The width a body line actually gets is a layout fact, so it has to be read before the
    // window can be computed: `BoxWithConstraints` is the one place Compose exposes it
    // (`ImageGridBlock`'s cell reads its own box the same way). The measurement below is a
    // *pre*-measure with the same width, style and layout direction the `Text` underneath
    // will be laid out with, which is what makes the window's first character a line
    // boundary for that `Text` too.
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.padding(top = PiSpacing.tiny)) {
        val widthPx = constraints.maxWidth
        val window = remember(text, widthPx, style, measurer) {
            val layout = measurer.measure(
                text = text,
                style = style,
                constraints = Constraints(maxWidth = widthPx.coerceAtLeast(0)),
            )
            val starts = IntArray(layout.lineCount) { layout.getLineStart(it) }
            val start = visualTailWindowStart(starts, layout.lineCount, SHELL_PREVIEW_LINES)
            if (start <= 0) text else text.substring(start)
        }
        Column(verticalArrangement = Arrangement.spacedBy(PiSpacing.gutter)) {
            Text(text = window, style = style, color = color)
            // The **second** level of disclosure, and this app's own: while expanded the card
            // still paints one window, so this label is the way to the rest of the budget. pi
            // has a single level (its expanded card is the whole output), so pi draws no such
            // label — it is kept here because the app caps a body at [TOOL_BODY_MAX_LINES] and
            // must say so. The count is the logical lines fully above the window (see the KDoc).
            val hidden = remember(window, totalLines) { hiddenLineCount(totalLines, window) }
            if (hidden > 0) {
                ExpandAllLabel(
                    text = "展开全部（上方还有 $hidden 行）",
                    onClick = onExpandAll,
                )
            }
        }
    }
}

/**
 * Shell output lines painted while the card is expanded but has not taken 「展开全部」 yet —
 * pi's five terminal rows: `const BASH_PREVIEW_LINES = 5;`
 * (`dist/core/tools/renderers/bash.js:14`, the same number the app has always used).
 *
 * pi uses the same five rows for its **collapsed** card as well (`:56-70`); this app draws no
 * body at all while collapsed, which is the deliberate deviation D45 records.
 */
private const val SHELL_PREVIEW_LINES = 5
