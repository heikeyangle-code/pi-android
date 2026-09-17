package app.pi.ui.blocks

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 *  - `[Full output: <path>. Truncated: …]` as a warning line (`:78-90`) — the same sentence in
 *    the same place, but **inside the expanded branch** here, for the same reason (D45): the
 *    footer's 「已截断」 carries the fact while collapsed, and the notice is what names the file
 *    to copy from. The sentence the tool already appended to its own text is stripped first
 *    (`:59-64`), which is what [stripFullOutputFooter] does, so the fact is printed once;
 *  - `Elapsed 12.3s` while the command runs and `Took 12.3s` afterwards (`:91-96`), pi
 *    refreshing it once a second (`:113-115`).
 *
 * > Line numbers are the shipped build's (`dist/core/tools/renderers/bash.js`, v0.85.1), which
 * > is the copy this was verified against; the older `.ts` numbers elsewhere in this file are
 * > the upstream TypeScript source and point at the same statements.
 *
 * **The running clock is read, not owned.** pi starts an interval for it; this app has no
 * timer to start or stop, because a streaming row is already republished every 200 ms
 * (`rpc/.../Transcript.kt:618`, spec §4.3's `tool_execution_update` throttle) — the footer
 * reads the clock once per composition, so the number advances with the output and a command
 * that prints nothing simply holds the number it last showed. No new loop, no new thread,
 * nothing to cancel when the card scrolls away.
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
        Ansi.strip(text)
    }
    val lines = remember(bodyText) { lineCount(bodyText) }
    // The window the **expanded** card paints: five rows until 「展开全部」 is taken
    // (`fullOutput`), the app's own budget after that. `SHELL_PREVIEW_LINES` is pi's
    // `BASH_PREVIEW_LINES`, and pi also uses it for the **collapsed** card — which this app
    // deliberately does not draw (D45, see the class KDoc): a collapsed shell call shows its
    // header and footer and nothing else. `painted` is still computed while collapsed (it is
    // the same `remember` either way, and the first expand must not pay for the tail twice),
    // it is simply not placed.
    val painted = remember(bodyText, fullOutput) {
        tailLines(bodyText, if (fullOutput) TOOL_BODY_MAX_LINES else SHELL_PREVIEW_LINES)
    }
    val hidden = remember(bodyText, painted) { hiddenLineCount(lines, painted) }
    val subject = remember(command, timeout) { shellSubject(command, timeout) }
    // The live clock: one read per composition, no timer of its own. See the KDoc. The
    // same number feeds the footer's text and its tick (`04 §1.1`: 刻度与读数同源), so a
    // running command's meter grows with the seconds beside it.
    val elapsedMs = if (pending) {
        (System.currentTimeMillis() - item.ts).coerceAtLeast(0)
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
                if (expanded && bodyText.isNotEmpty()) {
                    MonoText(
                        text = painted,
                        color = palette.bodyOnTool,
                        modifier = Modifier.padding(top = PiSpacing.tiny),
                    )
                    if (hidden > 0 && !fullOutput) {
                        // The **second** level of disclosure, and this app's own: while expanded
                        // the card still paints five rows, so this label is the way to the rest of
                        // the budget. pi has a single level (its expanded card is the whole
                        // output), so pi draws no such label — it is kept here because the app
                        // caps a body at [TOOL_BODY_MAX_LINES] and must say so.
                        ExpandAllLabel(
                            text = "展开全部（上方还有 $hidden 行）",
                            onClick = { fullOutput = true },
                        )
                    } else if (hidden > 0) {
                        // pi's hint line, minus the key hint a phone does not have:
                        // `theme.fg("muted", `... (${skipped} earlier lines,`) + … + fg("muted", ")")`
                        // (`renderers/bash.js:63-64`). The words are this app's («上方还有 N 行
                        // 未显示» — the same sentence the expanded state already used).
                        //
                        // **Order differs from pi on purpose.** pi returns `[hint, …preview]`, so
                        // its hint sits *above* the preview; ours says 「**上方**还有 N 行」, which
                        // is only true when it sits *below* it. Keeping the sentence we already
                        // ship (rather than pi's 「N earlier lines」) is what pins the order.
                        Text(
                            text = "上方还有 $hidden 行未显示",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
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
 * [ToolOutputParse.elapsedLabel], which is pi's `formatDuration` (`renderers/bash.ts:32-34`).
 *
 * [elapsedMs] is handed in by the caller: while the command runs this is the app's
 * recomposition beat, not a clock of its own, and the same value goes to the tick. A
 * blocked call has no such reading — nothing ran — so it takes the fourth state's own
 * footer instead.
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
 * Shell output lines painted while the card is expanded but has not taken 「展开全部」 yet —
 * pi's five terminal rows: `const BASH_PREVIEW_LINES = 5;`
 * (`dist/core/tools/renderers/bash.js:14`, the same number the app has always used).
 *
 * pi uses the same five rows for its **collapsed** card as well (`:56-70`); this app draws no
 * body at all while collapsed, which is the deliberate deviation D45 records.
 */
private const val SHELL_PREVIEW_LINES = 5
