package app.pi.ui.blocks

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import app.pi.rpc.ToolCall
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `tool-execution` (docs/pi-android-ui-spec.md §7.4): the **generic** tool card, used for
 * every tool pi itself has no renderer for — the extensions' tools, and anything a newer pi
 * adds (`packages/coding-agent/src/core/tools/renderers/index.ts:51-63` is pi's own fallback
 * of the same shape). The eight built-ins each have their own block; see `BlockRenderer`'s
 * `ToolCall` branch and `docs/capability-gap.md` §4.9.
 *
 * The container colour is the status (`toolPendingBg` / `toolSuccessBg` / `toolErrorBg`), the
 * title row is the mono tool name plus a one-line argument summary, and the output is machine
 * text (mono, `toolOutput`). Status is a glyph plus Chinese wording, so it survives colour
 * blindness. The card toggles its output.
 *
 * The shared half of this card — status colours, the title row, the footer row, the long-press
 * actions, the warning line — lives in `ToolBlockChrome.kt`, so a per-tool block and this one
 * cannot drift apart; the pure helpers it calls (truncation, the full-output path, the warning
 * line's wording) live in `ToolOutputParse.kt` for the same reason.
 */
@Composable
fun ToolCallBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    firstOfRun: Boolean = true,
    lastOfRun: Boolean = true,
) {
    val palette = PiTheme.palette
    // The card's state, derived once: `06 §4`'s fourth state (被拒) is read from the
    // result text, so every part of the card must agree on the same reading.
    val state = toolStateOf(item)
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by remember(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // F17 (`docs/rendering-review.md`): the card's own toggle and the spec's
    // 「展开全部」 are two different questions — the first asks "show the output at
    // all", the second "stop cutting it at 200 lines". Collapsing the card resets
    // nothing: the user asked to see the tail once and it stays visible.
    var fullOutput by remember { mutableStateOf(false) }
    // F31 (`docs/rendering-review.md`): these are O(output) scans on a row that
    // recomposes for every streamed chunk (F8's 200 ms throttle bounds how often),
    // so they are keyed on the value they scan rather than re-run per composition.
    val outputLineCount = remember(item.output) { lineCount(item.output) }
    val outputOversize = remember(item.output) { item.output.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES }
    // pi makes two separate decisions about a truncated result, and this mirrors
    // both. It *prints* a warning line whenever the result was truncated or names a
    // full-output path (`core/tools/renderers/bash.ts:100`), and it *strips* that
    // same sentence out of the body only when it is about to print it (`:59-64`) —
    // never unconditionally. Without the strip the sentence would appear twice: once
    // in the body the tool wrote and once in the warning.
    val truncation = remember(item.details) { truncationOf(item.details) }
    // Where the whole of a truncated result went. pi says it twice over: as the
    // structured `details.fullOutputPath` its bash tool builds
    // (`packages/coding-agent/src/core/tools/bash.ts:321`) and as the sentence it
    // appends to the text itself (`:326`, `:328`, and
    // `packages/coding-agent/src/core/messages.ts:94-95` for the message form).
    // Both already reach this row — `rpc/Events.kt:468` copies pi's text block
    // verbatim through `contentText` and `rpc/Transcript.kt:1223` keeps `details`
    // whole — so the only place the path could be lost is this file.
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item.details, item.output) }
    val notice = remember(fullOutputPath, truncation) { truncationNotice(fullOutputPath, truncation) }
    // F17 + spec §4.2: 默认渲染前 200 行 + 「展开全部」; 单块 >200 KB 时不画正文
    // （正文仍可由长按菜单的「复制输出」整份取出），改给 pi 记录的完整输出路径
    // —— spec §4.2 原文的「前往工作区」是 App 自己发明的动作，pi 没有它。
    //
    // `bodyText` is the body as it will be painted: pi's own text, minus the trailing
    // sentence the notice below repeats. The strip is pi's rule verbatim and refuses
    // far more often than it cuts (see `stripFullOutputFooter`), so a body that does
    // not end in that bracket block is left byte-for-byte alone.
    val bodyText = remember(item.output, fullOutputPath, truncation, item.status) {
        // pi's `!options.isPartial`: a still-streaming row keeps its text untouched.
        if (state != ToolState.Running && truncation != null) {
            stripFullOutputFooter(item.output, fullOutputPath)
        } else {
            item.output
        }
    }
    val previewText = remember(bodyText, expanded, fullOutput, outputOversize) {
        when {
            !expanded || outputOversize -> ""
            fullOutput -> bodyText
            else -> headLines(bodyText, COLLAPSED_OUTPUT_LINES)
        }
    }
    // F31 (`docs/rendering-review.md`): the footer's parts list + `joinToString`
    // used to be rebuilt on every composition, and its `lineCount` scanned the
    // whole output each time. It depends only on the row's scalar fields, so it is
    // built once per output change. `outputLineCount` is deliberately not a key —
    // it is derived from `item.output`, which is.
    val footer = remember(
        item.output,
        item.exitCode,
        item.elapsedMs,
        item.outputTruncated,
        state,
    ) {
        toolFooterText(item, state, outputLineCount)
    }
    // §4.8: 工具卡长按 → 复制命令 / 复制输出. The command is the tool's own argument
    // (`command` for bash, `file_path`/`path` for the file tools); a tool whose
    // arguments are neither gets no command entry rather than a wrong one.
    val commandText = remember(item.args) { toolCommandText(item.args) }
    ToolActionMenu(command = commandText, output = item.output, fullOutputPath = fullOutputPath) {
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
                    title = item.toolName.ifEmpty { "工具" },
                    subject = item.argsSummary,
                    expanded = expanded,
                    expandable = item.output.isNotEmpty(),
                )

                if (expanded && item.output.isNotEmpty()) {
                    if (outputOversize) {
                        // The 200 KB ceiling is *ours* — a rendering budget, not a pi
                        // rule — and pi has usually already truncated this result at
                        // its own limit (50 KB / 2000 lines for bash,
                        // `core/tools/truncate.ts:11-12`) and named the file holding
                        // the rest. The wording this replaced
                        // (「输出超过 200 KB，请前往工作区查看完整日志」) did two wrong
                        // things: it hid the path pi had just printed, and it named an
                        // action pi does not have — `grep Re-run|rerun` over pi's tree
                        // finds only an installer string, and nothing opens a tool log
                        // from pi's TUI. So state the omission; the notice below carries
                        // pi's own answer to "where is the rest". No action is invented
                        // for it: the body itself is still reachable through
                        // 「复制输出」 above.
                        Text(
                            text = "输出超过 200 KB，正文未展开。",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                        )
                    } else {
                        MonoText(
                            text = previewText,
                            // F13: the tool body is read as text, so it takes the
                            // variant that clears §9's 4.5:1 floor (dark 3.69:1 →
                            // 4.56:1, light 4.31:1 → 4.52:1).
                            color = palette.bodyOnTool,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                        if (!fullOutput && outputLineCount > COLLAPSED_OUTPUT_LINES) {
                            ExpandAllLabel(
                                text = "展开全部（共 $outputLineCount 行）",
                                onClick = { fullOutput = true },
                            )
                        }
                    }
                    if (notice != null) {
                        // pi's warning line, printed on pi's condition and in pi's
                        // colour (`renderers/bash.ts:100-118`: `theme.fg("warning", …)`),
                        // placed where pi places it — after the output, before the
                        // elapsed footer. The body above already had this sentence
                        // stripped out when it was the same one, so it is not repeated.
                        //
                        // The whole line copies the path when it has one: a path is the
                        // one part of a truncation report a user needs elsewhere, and
                        // the long-press menu carries the same action for discoverability.
                        ToolNotice(
                            text = notice,
                            copyOnTap = fullOutputPath,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                }

                // F16 (`docs/rendering-review.md`): the images a tool returned were
                // parsed into `ToolCall.images` and then never painted, so the row
                // showed only the `[image]` marker `rpc/Events.kt`'s `contentText`
                // substitutes. pi paints them — `components/tool-execution.ts:379-388`
                // adds a real `Image` child for every `content[type=image]` block —
                // and where the terminal cannot show graphics it prints fallback text
                // instead (`packages/tui/src/components/image.ts:97-104`,
                // `packages/tui/src/terminal-image.ts:683-696`: `[Image: <mime> <WxH>]`).
                // A phone can always show them, so this is pi's graphics branch, and
                // the grid's labelled placeholder is the decode-failure fallback that
                // stands in for pi's text branch.
                //
                // The tap is swallowed here on purpose: pi wraps only the *text* of the
                // result in the region that toggles the card (`tool-execution.ts:172-178`),
                // so tapping a screenshot must not collapse the card in this layout.
                if (item.images.isNotEmpty()) {
                    ImageGridBlock(
                        images = item.images,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = PiSpacing.tiny)
                            .pointerInput(item.key) { detectTapGestures { } },
                    )
                }

                ToolFooter(
                    text = footer,
                    state = state,
                    elapsedMs = item.elapsedMs,
                )
            }
        }
    }
}

/**
 * Spec §4.2's collapsed budget for a long tool result, and its hard ceiling for
 * the inline surface. F17 (`docs/rendering-review.md`): this used to be 400 with
 * no path to the remainder — a `bash` log past the cap was silently incomplete.
 * The card's toggle now shows these lines and 「展开全部」 shows the rest; past
 * [MAX_OUTPUT_BYTES] the block no longer paints the body, but it *does* keep pi's
 * own record of where the complete output is (see `fullOutputPathOf`).
 *
 * [MAX_OUTPUT_BYTES] is this app's rendering budget, **not** a pi rule: pi's own
 * ceiling is 2000 lines / 50 KB (`core/tools/truncate.ts:11-12`), which is what
 * the notice's numbers report. Nothing about the 200 KB limit is presented as pi's
 * information.
 */
private const val COLLAPSED_OUTPUT_LINES = 200
private const val MAX_OUTPUT_BYTES = 200 * 1024
