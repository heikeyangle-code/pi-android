package app.pi.ui.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pi.rpc.PiImage
import app.pi.rpc.ToolCall
import app.pi.ui.render.rememberPiHighlightedCode
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * `tool-execution` (docs/pi-android-ui-spec.md §7.4): the **generic** tool card, used for
 * every tool pi itself has no renderer for — the extensions' tools, and anything a newer pi
 * adds (`dist/core/tools/renderers/index.js:35-46` is pi's own fallback
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
    /** A tap on a returned image opens it full screen ([PiImageViewer]). */
    onImageClick: ((PiImage) -> Unit)? = null,
) {
    val palette = PiTheme.palette
    // The card's state, derived once: `06 §4`'s fourth state (被拒) is read from the
    // result text, so every part of the card must agree on the same reading.
    val state = toolStateOf(item)
    // Keyed on the parameter so the AppBar's expand/collapse-all switch (pi's
    // `app.tools.expand`, interactive-mode.ts `setToolsExpanded`) reaches every
    // row, exactly as pi re-applies expansion to all of its children.
    var expanded by rememberSaveable(defaultExpanded) { mutableStateOf(defaultExpanded) }
    // F17 (`docs/rendering-review.md`): the card's own toggle and the spec's
    // 「展开全部」 are two different questions — the first asks "show the output at
    // all", the second "stop cutting it at 200 lines". Collapsing the card resets
    // nothing: the user asked to see the tail once and it stays visible.
    var fullOutput by rememberSaveable { mutableStateOf(false) }
    // F31 (`docs/rendering-review.md`): these are O(output) scans on a row that
    // recomposes for every streamed chunk (F8's 200 ms throttle bounds how often),
    // so they are keyed on the value they scan rather than re-run per composition.
    //
    // The counter carries its state across those key changes: a result only ever grows
    // while a call streams, so `IncrementalLineCount` adds the newlines in the appended
    // suffix instead of walking the whole body again (0.3 ms at pi's 2000-line cap, 4.1 ms
    // for 200 KB — per publication, on the frame thread). The `remember` key is still
    // `item.output`, so a recycled row that now holds another call recounts from scratch:
    // the counter recognises the discontinuity and never lies about a body it has not seen.
    val lineCounter = remember { IncrementalLineCount() }
    val outputLineCount = remember(item.output) { lineCounter.of(item.output) }
    // `utf8ByteSizeExceeds` rather than `output.toByteArray(Charsets.UTF_8).size >
    // MAX_OUTPUT_BYTES`: the same answer with no allocation. The byte array was built
    // on the frame thread once per publication (every 200 ms while the row streams) and
    // dropped immediately — see that function's KDoc in `BlockChrome.kt`.
    val outputOversize = remember(item.output) { utf8ByteSizeExceeds(item.output, MAX_OUTPUT_BYTES) }
    // pi makes two separate decisions about a truncated result, and this mirrors
    // both. It *prints* a warning line whenever the result was truncated or names a
    // full-output path (`dist/core/tools/renderers/bash.js:53`), and it *strips* that
    // same sentence out of the body only when it is about to print it (`:28-33`) —
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
    // `codemode` has its own rendering inside this generic card (script, nested calls, model
    // cost — see the body below), so the name is read once, here, and used by both the body's
    // text and the blocks that the engine's `details` feeds.
    val isCodemode = item.toolName == "codemode"
    val bodyText = remember(item.output, fullOutputPath, truncation, item.status, isCodemode) {
        // pi's `!options.isPartial`: a still-streaming row keeps its text untouched.
        val text = if (state != ToolState.Running && truncation != null) {
            stripFullOutputFooter(item.output, fullOutputPath)
        } else {
            item.output
        }
        // A `codemode` result's own `Script completed / Wall time N seconds / Output:` header is
        // dropped, exactly as pi's renderer drops it (`codemode/renderer.js:114-121`) — the wall
        // time is on this row's reading already. Only the *displayed* text: `复制输出` still
        // copies `item.output`, pi's own bytes.
        if (isCodemode) codemodeOutput(text) else text
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
    // built once per output change.
    val footer = remember(item.output, item.outputTruncated, state) {
        toolFooterText(item, state)
    }
    // §4.8: 工具卡长按 → 复制命令 / 复制输出. The command is the tool's own argument
    // (`command` for bash, `file_path`/`path` for the file tools); a tool whose
    // arguments are neither gets no command entry rather than a wrong one.
    val commandText = remember(item.args) { toolCommandText(item.args) }
    // **pi's codemode content, and pi's expanded argument lines — both expanded-state only.**
    //
    // The collapsed half of this card is frozen: shape, size, rows, tokens. Everything below
    // is painted inside `if (expanded)`, so none of it can move the collapsed card by a pixel.
    //
    // `codemode`: the engine records every nested tool call a script made on the **parent's**
    // result as `details.calls` (`dist/extensions/codemode/execute.js:394`) and draws one line
    // per call (`extensions/codemode/renderer.js:41-56`). The App's reducer drops a nested
    // `tool_execution_*` lifecycle on purpose, so `details.calls` is the only place those calls
    // survive — and nothing read it, which is why a script's calls, their failures and their
    // model cost were invisible. The script itself (`args.code`) comes with them: pi shows it
    // as the call's body, and this card's one-line summary can only ever carry its first
    // characters.
    val script = remember(isCodemode, item.args) { if (isCodemode) codemodeCode(item.args) else null }
    // **The settled result wins over the live snapshot.** After `tool_execution_end` the
    // result's `details` is complete and authoritative — every call has a final status — while
    // `liveDetails` is only the newest of the throttled partial snapshots. Before the tool ends
    // `details` is null and the snapshot is the only view there is, which is what makes a
    // script's calls appear **one by one while it runs** (the engine publishes each call's
    // `running` status as `details.calls`, `dist/extensions/codemode/execute.js:394-397`).
    val callsSource = item.details ?: item.liveDetails
    val nestedCalls = remember(isCodemode, callsSource) { if (isCodemode) codemodeCallsOf(callsSource) else emptyList() }
    val modelCalls = remember(nestedCalls) { codemodeModelCallsTotal(nestedCalls) }
    // The title and the argument summary are inputs of `ToolHeader`, not Compose state, so
    // without this they would be rebuilt on **every** recomposition — and the summary walks
    // **every** argument (`toolParamSummary`), which is exactly the kind of O(call) work the
    // F31/F8 rule at the top of this file keeps off the 200 ms publication path. Keyed on the
    // value each one reads, so a tool row that republishes while streaming pays nothing.
    val title = remember(item.toolName) { mcpLabel(item.toolName) ?: item.toolName.ifEmpty { "工具" } }
    val paramSummary = remember(item.args) { toolParamSummary(item.args) }
    // The other half of pi's generic call line: `formatToolCallWithArgs(..., expanded = true)`
    // puts one `key: value` per argument, which the one-line title cell cannot (`render-utils.js:60-79`).
    val paramText = remember(item.args) { toolParamLines(item.args).joinToString("\n") }
    ToolActionMenu(command = commandText, output = item.output, fullOutputPath = fullOutputPath) {
        BlockColumn(modifier) {
            ToolCard(
                item,
                expanded,
                { expanded = !expanded },
            ) {
                ToolHeader(
                    item = item,
                    state = state,
                    // **The title rule of this card, and why it is not a literal.**
                    //
                    // Every other tool block names itself with a hardcoded string, exactly as
                    // pi's own renderer for that tool does: `ReadBlock` passes `"read"`,
                    // `WriteBlock` `"write"`, `ShellBlock` `"$"`. This card is the fallback for
                    // tools the app has no block for, so it has no name of its own to write
                    // down — and pi's fallback renderer is in the same position: it draws
                    // `theme.fg("toolTitle", theme.bold(this.toolName))`
                    // (`components/tool-execution.js:312`), i.e. the wire name verbatim.
                    // Deriving the title from `item.toolName` is therefore the faithful
                    // reading for the general case, and the `ifEmpty` is pi's own empty case.
                    //
                    // **Except for an MCP tool, whose wire name pi never shows.** MCP registers
                    // its tools as `mcp__<server>__<tool>` (`dist/extensions/mcp/index.js:9`)
                    // and labels their row `<server>/<tool>` (`:244`), so a card titled
                    // `mcp__docs__search` was showing the one name of the two that pi keeps to
                    // itself. [mcpLabel] is that mapping and answers null for every other tool.
                    // The title cell is unchanged — same row, one line, same tokens.
                    title = title,
                    // **The one card pi leaves uncoloured, and the one that shows every
                    // argument.** pi's fallback path builds the whole body with no `fg` call:
                    // `contentText.setText(this.formatToolExecution())`, which is
                    // `theme.fg("toolTitle", theme.bold(this.toolName))` followed by the raw
                    // JSON arguments (`components/tool-execution.js:311-322`). So the name is
                    // `toolTitle` (the header's title cell, one line up) and the arguments are
                    // the terminal's default foreground — `Uncoloured`, which the palette maps
                    // onto pi's `text` — not `accent` and not a segmented line.
                    //
                    // The text is [toolParamSummary], pi's `key=value` list cut to pi's 100
                    // characters (`render-utils.js:60-79`). This cell used to take
                    // `item.argsSummary`, which is **one** value with no key: an MCP call's real
                    // subject (its named parameters) and every argument but the first were
                    // invisible. `argsSummary` stays where it belongs — the transcript search
                    // (`screens/ChatScreen.kt`) and the two non-card fallbacks that already use
                    // it — and the row's shape does not move: one cell, `maxLines = 1`, built
                    // from arguments whose newlines are JSON-escaped, never a second line.
                    subject = paramSummary
                        ?.let { listOf(ToolCallPart(it, ToolCallToken.Uncoloured)) }
                        .orEmpty(),
                    // The row's own count, from the `remember` above (F31): this card's
                    // result is the one with no parse of its own, so the count it hands
                    // the reading is the incremental one it already keeps.
                    right = toolHeaderReading(item, state, count = outputLineCount),
                    expanded = expanded,
                    expandable = item.output.isNotEmpty(),
                )

                // ---- pi's codemode content: the script, then its nested calls. ----
                //
                // All three blocks live inside `expanded`, so the collapsed card is untouched
                // (see the `isCodemode` note above the menu). Order is pi's: its codemode
                // renderer puts the script in the call half (`renderer.js:58-78`) and the call
                // rows plus `Model calls` in the result half (`:79-101`), which is where the
                // result text follows.
                if (expanded && !script.isNullOrEmpty()) {
                    // Remembered, not computed inline: `headLines` splits the whole script and
                    // `lineCount` walks it, on a row that republishes every 200 ms while the
                    // script runs. Keyed on the script, so a publication costs nothing.
                    val shownScript = remember(script) { headLines(script, TOOL_BODY_MAX_LINES) }
                    // **pi's own highlighting, through the app's existing channel.** pi's codemode
                    // renderer calls `highlightCode(replaceTabs(code), "javascript")`
                    // (`codemode/renderer.js:70-71`) — a hard-coded language, because a script has
                    // no file path to derive one from. That is the only reason this block used to
                    // be plain: every other code body here (`read`/`write`, ``` fences) already
                    // goes through `rememberPiHighlightedCode`, the guest's highlight.js with pi's
                    // theme mapping. The colour branch is `SourceLines`' rule verbatim — a known
                    // language leaves the untokenised characters at pi's default foreground
                    // (`palette.text`), an unknown one takes the body colour.
                    val highlighted = rememberPiHighlightedCode(shownScript, CODEMODE_SCRIPT_LANGUAGE)
                    Text(
                        text = highlighted.text,
                        style = PiTheme.text.mono,
                        color = if (highlighted.languageKnown) palette.text else palette.bodyOnTool,
                        modifier = Modifier.padding(top = PiSpacing.tiny),
                    )
                    val hiddenScript = remember(script, shownScript) {
                        (lineCount(script) - lineCount(shownScript)).coerceAtLeast(0)
                    }
                    if (hiddenScript > 0) {
                        // The app's own second level (see `ToolCallBlock`'s budget note): a
                        // script longer than the body budget says what it left out rather than
                        // growing without a ceiling. pi's row has no such cap.
                        Text(
                            text = "还有 $hiddenScript 行未显示",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                }
                // **pi's expanded call line, for every tool that is not codemode.** Its
                // `formatToolCallWithArgs(…, expanded = true)` puts one `key: value` per
                // argument under the title (`render-utils.js:60-79`); the collapsed title cell
                // can only carry one ellipsized line, so an MCP or extension tool's parameters
                // were unreadable there. Same body budget and the same admission as the script.
                if (expanded && script.isNullOrEmpty() && paramText.isNotEmpty()) {
                    // Same reason as the script above: both calls scan the whole parameter text.
                    val shownParams = remember(paramText) { headLines(paramText, TOOL_BODY_MAX_LINES) }
                    MonoText(
                        text = shownParams,
                        color = palette.bodyOnTool,
                        modifier = Modifier.padding(top = PiSpacing.tiny),
                    )
                    val hiddenParams = remember(paramText, shownParams) {
                        (lineCount(paramText) - lineCount(shownParams)).coerceAtLeast(0)
                    }
                    if (hiddenParams > 0) {
                        Text(
                            text = "还有 $hiddenParams 行未显示",
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier.padding(top = PiSpacing.tiny),
                        )
                    }
                }
                // **The nested calls, one line each, exactly as pi rows them** — status glyph,
                // name, arguments, the call's own duration, and a model call's cost
                // (`codemode/renderer.js:41-56`). The glyph takes the card's own four state
                // colours (pi colours it by status too, `:26-40`), so no new colour enters the
                // palette. Folded, pi keeps the last eight calls and says how many it hid
                // (`:16`, `:50-52`); the card's existing 「展开全部」 is the second level.
                if (expanded && nestedCalls.isNotEmpty()) {
                    // `takeLast` allocates; the row republishes every 200 ms while the script
                    // runs, so the fold is decided once per (list, level) rather than per frame.
                    val shownCalls = remember(nestedCalls, fullOutput) {
                        if (fullOutput) nestedCalls else nestedCalls.takeLast(CODEMODE_CALL_PREVIEW)
                    }
                    Column(modifier = Modifier.padding(top = PiSpacing.tiny)) {
                        if (shownCalls.size < nestedCalls.size) {
                            Text(
                                text = "还有 ${nestedCalls.size - shownCalls.size} 条更早的调用未显示",
                                style = PiTheme.text.meta,
                                color = palette.muted,
                            )
                        }
                        shownCalls.forEach { call ->
                            // One span per run: the name in `toolTitle`, the arguments, duration
                            // and cost in `muted`, a failure in `error` — pi's own per-token
                            // colours (`codemode/renderer.js:41-56`), resolved through the same
                            // table the header row uses.
                            val runs = remember(call, fullOutput) {
                                codemodeCallRuns(call, expanded = fullOutput)
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    text = codemodeCallIcon(call.status),
                                    style = PiTheme.text.monoSmall,
                                    color = toolAccentColor(codemodeCallState(call.status), palette),
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(PiSpacing.inline))
                                Text(
                                    text = remember(runs, palette) { runs.toAnnotatedString(palette) },
                                    style = PiTheme.text.mono,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        // pi's `Model calls: $X` total, on its own condition: more than one
                        // priced call (`codemode/renderer.js:53-59`).
                        modelCalls?.let {
                            MonoText(
                                text = it,
                                color = palette.muted,
                                modifier = Modifier.padding(top = PiSpacing.tiny),
                            )
                        }
                    }
                }

                if (expanded && item.output.isNotEmpty()) {
                    if (outputOversize) {
                        // The 200 KB ceiling is *ours* — a rendering budget, not a pi
                        // rule — and pi has usually already truncated this result at
                        // its own limit (50 KB / 2000 lines for bash,
                        // `dist/core/tools/truncate.js:10-11`) and named the file holding
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
                        // colour (`dist/core/tools/renderers/bash.js:53-67`: `theme.fg("warning", …)`),
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

                // F16: the images a tool returned, after the body and before the footer —
                // position, tap handling and the whole pi argument are [ToolImages]' own
                // KDoc (one place, because every tool card paints them now).
                ToolImages(item.images, onImageClick)

                ToolFooter(text = footer)
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
 * ceiling is 2000 lines / 50 KB (`dist/core/tools/truncate.js:10-11`), which is what
 * the notice's numbers report. Nothing about the 200 KB limit is presented as pi's
 * information.
 */
private const val COLLAPSED_OUTPUT_LINES = 200
private const val MAX_OUTPUT_BYTES = 200 * 1024

/**
 * How many nested `codemode` calls the folded list keeps — pi's `CALL_PREVIEW_COUNT`
 * (`dist/extensions/codemode/renderer.js:15`), and pi keeps the **last** ones for the same
 * reason its shell rows keep the tail: a script's outcome is at the end.
 */
private const val CODEMODE_CALL_PREVIEW = 8

/**
 * The language pi highlights a `codemode` script with — **pi's own literal**
 * (`dist/extensions/codemode/renderer.js:71`: `highlightCode(…, "javascript")`).
 *
 * It is a literal and not `PiCodeLanguage.forPath(…)` on purpose: a script is not a file and has
 * no path to derive a language from, which is exactly why pi hard-codes this one. The value is
 * the same string `PiCodeLanguage`'s table maps `.js`/`.mjs`/`.cjs` onto, so the `read`/`write`
 * bodies and this block ask the guest highlighter for the same grammar.
 */
private const val CODEMODE_SCRIPT_LANGUAGE = "javascript"
