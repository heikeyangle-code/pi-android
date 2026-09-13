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
import app.pi.rpc.ToolStatus
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `bash` and `powershell` — pi's shell renderers (`core/tools/renderers/bash.ts`).
 *
 * pi builds both from one factory: `bash` gets the `$` prompt and `powershell` the `PS>`
 * prompt (`core/tools/renderers/index.ts:35-36` → `createShellRenderers`), which is the only
 * difference between the two definitions. The card is:
 *
 *  - the command as the title (`formatShellCall`, `:35-41`: `${prompt} ${command}`, with
 *    ` (timeout Ns)` when the call set one);
 *  - the output as **the tail**, not the head, when collapsed (`:75-89`;
 *    `truncateToVisualLines(styledOutput, BASH_PREVIEW_LINES, width)`, pi's five terminal
 *    rows) — a command's outcome is at the end, and pi's own bash tool truncates to the *last*
 *    lines for the same reason (`core/tools/bash.ts:234`);
 *  - `[Full output: <path>. Truncated: …]` as a warning line (`:100-118`) — the sentence the
 *    tool already appended to its own text is stripped first (`:59-64`), which is what
 *    [stripFullOutputFooter] does;
 *  - `Elapsed 12.3s` while the command runs and `Took 12.3s` afterwards (`:117-121`), pi
 *    refreshing it once a second (`:139-141`).
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
) {
    val palette = PiTheme.palette
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    var fullOutput by remember { mutableStateOf(false) }
    val pending = item.status == ToolStatus.Pending
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
    val painted = remember(bodyText, fullOutput) {
        tailLines(bodyText, if (fullOutput) TOOL_BODY_MAX_LINES else SHELL_PREVIEW_LINES)
    }
    val hidden = remember(bodyText, painted) { hiddenLineCount(lines, painted) }
    val subject = remember(command, timeout) { shellSubject(command, timeout) }
    // The live clock: one read per composition, no timer of its own. See the KDoc.
    val footer = shellFooter(item, exitCode, lines, System.currentTimeMillis())
    ToolActionMenu(command.ifEmpty { null }, item.output, fullOutputPath) {
        BlockColumn(modifier) {
            ToolCard(item, expanded, { expanded = !expanded }) {
                ToolHeader(title = "$", subject = subject, status = item.status)
                if (expanded && bodyText.isNotEmpty()) {
                    MonoText(
                        text = painted,
                        color = palette.bodyOnTool,
                        modifier = Modifier.padding(top = PiSpacing.tiny),
                    )
                    if (hidden > 0 && !fullOutput) {
                        ExpandAllLabel(
                            text = "展开全部（上方还有 $hidden 行）",
                            onClick = { fullOutput = true },
                        )
                    } else if (hidden > 0) {
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
                if (expanded && notice != null) {
                    ToolNotice(text = notice, copyOnTap = fullOutputPath)
                }
                ToolFooter(text = footer, expanded = expanded, expandable = bodyText.isNotEmpty() || notice != null)
            }
        }
    }
}

/**
 * pi's shell subject: the command, with pi's ` (timeout Ns)` suffix
 * (`core/tools/renderers/bash.ts:35-41`, `:38`). The card's title slot carries pi's prompt
 * (`$`, or `PS>` for the powershell variant — `renderers/index.ts:35-36` is the only thing
 * that differs between the two definitions).
 *
 * The command is one line on the card; a multi-line command is copied in full from the
 * action menu rather than wrapped into the title.
 */
private fun shellSubject(command: String, timeout: Int?): String {
    val head = command.lineSequence().firstOrNull().orEmpty()
    val suffix = if (timeout != null) " (timeout ${timeout}s)" else ""
    return head + suffix
}

/**
 * The footer line: status, pi's exit code, pi's elapsed number, and the row's size.
 *
 * pi splits these across two lines (its card's title, and `Elapsed`/`Took` under the body);
 * the app's card has one footer row, so they are joined in pi's order. The elapsed part is
 * [ToolOutputParse.elapsedLabel], which is pi's `formatDuration` (`renderers/bash.ts:32-34`).
 *
 * [nowMs] is read once by the caller: while the command runs this is the app's recomposition
 * beat, not a clock of its own.
 */
private fun shellFooter(item: ToolCall, exitCode: Int?, lines: Int, nowMs: Long): String {
    val pending = item.status == ToolStatus.Pending
    val parts = mutableListOf(toolStatusLabel(item.status))
    if (!pending) exitCode?.let { parts += "退出码 $it" }
    val elapsed = if (pending) (nowMs - item.ts).coerceAtLeast(0) else item.elapsedMs
    if (elapsed != null) parts += ToolOutputParse.elapsedLabel(pending, elapsed)
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (!pending && item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/** Shell output lines painted before 「展开全部」 — pi's five terminal rows (`bash.ts:18`). */
private const val SHELL_PREVIEW_LINES = 5
