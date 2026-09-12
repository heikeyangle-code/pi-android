package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.ui.theme.PiTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `tool-execution` (docs/pi-android-ui-spec.md §7.4). The container colour is
 * the status (`toolPendingBg` / `toolSuccessBg` / `toolErrorBg`), the title row
 * is the mono tool name plus a one-line argument summary, and the output is
 * machine text (mono, `toolOutput`). Status is a glyph plus Chinese wording, so
 * it survives colour blindness. The card toggles its output.
 */
@Composable
fun ToolCallBlock(
    item: ToolCall,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
) {
    val palette = PiTheme.palette
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
    val truncation = remember(item.details) { truncationOf(item) }
    // Where the whole of a truncated result went. pi says it twice over: as the
    // structured `details.fullOutputPath` its bash tool builds
    // (`packages/coding-agent/src/core/tools/bash.ts:321`) and as the sentence it
    // appends to the text itself (`:326`, `:328`, and
    // `packages/coding-agent/src/core/messages.ts:94-95` for the message form).
    // Both already reach this row — `rpc/Events.kt:468` copies pi's text block
    // verbatim through `contentText` and `rpc/Transcript.kt:1223` keeps `details`
    // whole — so the only place the path could be lost is this file.
    val fullOutputPath = remember(item.details, item.output) { fullOutputPathOf(item) }
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
        if (item.status != ToolStatus.Pending && truncation != null) {
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
    val container = when (item.status) {
        ToolStatus.Pending -> palette.toolPendingBg
        ToolStatus.Success -> palette.toolSuccessBg
        ToolStatus.Error -> palette.toolErrorBg
    }
    val accent = when (item.status) {
        ToolStatus.Pending -> palette.warning
        ToolStatus.Success -> palette.success
        ToolStatus.Error -> palette.error
    }
    val statusLabel = when (item.status) {
        ToolStatus.Pending -> "运行中"
        ToolStatus.Success -> "成功"
        ToolStatus.Error -> "失败"
    }
    val statusGlyph = when (item.status) {
        ToolStatus.Pending -> "…"
        ToolStatus.Success -> "✓"
        ToolStatus.Error -> "✗"
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
        statusLabel,
    ) {
        toolFooter(item, statusLabel, outputLineCount)
    }

    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // §4.8: 工具卡长按 → 复制命令 / 复制输出. The command is the tool's own argument
    // (`command` for bash, `file_path`/`path` for the file tools); a tool whose
    // arguments are neither gets no command entry rather than a wrong one.
    val commandText = remember(item.args) {
        listOf("command", "file_path", "path", "pattern")
            .firstNotNullOfOrNull { key ->
                (item.args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
            }
    }
    BlockColumn(modifier) {
        BlockActionMenu(
            actions = buildList {
                if (commandText != null) {
                    add(BlockAction("复制命令") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(commandText))
                    })
                }
                if (item.output.isNotEmpty()) {
                    add(BlockAction("复制输出") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(item.output))
                    })
                }
                if (fullOutputPath != null) {
                    add(BlockAction("复制完整输出路径") {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(fullOutputPath))
                    })
                }
            },
        ) {
        BlockCard(
            color = container,
            modifier = Modifier.clickable(
                onClickLabel = if (expanded) "收起工具输出" else "展开工具输出",
            ) { expanded = !expanded },
            borderColor = accent.copy(alpha = 0.35f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.toolName.ifEmpty { "工具" },
                    style = PiTheme.text.monoSmall,
                    color = palette.dim,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = item.argsSummary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.toolTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(text = statusGlyph, style = PiTheme.text.monoSmall, color = accent)
            }

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
                        color = palette.toolOutput,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (!fullOutput && outputLineCount > COLLAPSED_OUTPUT_LINES) {
                        Text(
                            text = "展开全部（共 $outputLineCount 行）",
                            modifier = Modifier
                                .clickable(onClickLabel = "展开全部输出") { fullOutput = true }
                                .padding(vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
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
                    Text(
                        text = notice,
                        style = PiTheme.text.meta,
                        color = palette.warning,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (fullOutputPath == null) {
                                    Modifier
                                } else {
                                    Modifier.clickable(onClickLabel = "复制完整输出路径") {
                                        clipboard.setText(
                                            androidx.compose.ui.text.AnnotatedString(fullOutputPath),
                                        )
                                    }
                                },
                            ),
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
                        .padding(top = 2.dp)
                        .pointerInput(item.key) { detectTapGestures { } },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = footer,
                    modifier = Modifier.weight(1f),
                    style = PiTheme.text.meta,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.output.isNotEmpty()) {
                    ExpandLabel(expanded, expandText = "输出", collapseText = "收起")
                }
            }
        }
        }
    }
}

/**
 * The card's footer: status, exit code, duration and line count.
 *
 * [lines] is passed in rather than recomputed (F31 in `docs/rendering-review.md`):
 * the caller already holds a remembered [lineCount] for the same output, and this
 * function used to scan the whole (possibly megabyte) string on every
 * composition.
 */
private fun toolFooter(item: ToolCall, statusLabel: String, lines: Int): String {
    val parts = mutableListOf(statusLabel)
    item.exitCode?.let { parts += "退出码 $it" }
    item.elapsedMs?.let { parts += formatDuration(it) }
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/**
 * The path pi recorded for this call's complete output, or null when it recorded
 * none.
 *
 * Two sources, in the order that prefers structure over text: the
 * `details.fullOutputPath` field pi's bash tool sets when it truncates
 * (`packages/coding-agent/src/core/tools/bash.ts:321`), then the sentence pi
 * appends to the result text itself (`:326`/`:328`:
 * `[Showing lines X-Y of N. Full output: <path>]`, and
 * `packages/coding-agent/src/core/messages.ts:94-95`:
 * `[Output truncated. Full output: <path>]`).
 *
 * The text scan reads only the tail: pi appends that sentence, and this runs on a
 * row whose output can be megabytes (the F31 budget in
 * `docs/rendering-review.md` — O(output) work here is keyed, not per-frame).
 *
 * pi's `read` tool truncates without a path (`core/tools/read.ts:156`, `:165` set
 * `details = { truncation }`), which is why the result is nullable rather than a
 * promise.
 */
private fun fullOutputPathOf(item: ToolCall): String? {
    val structured = (item.details as? JsonObject)
        ?.get("fullOutputPath")
        ?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
    if (!structured.isNullOrEmpty()) return structured

    val tail = item.output.takeLast(PATH_SCAN_CHARS)
    val marker = "Full output: "
    val at = tail.lastIndexOf(marker)
    if (at < 0) return null
    return tail.substring(at + marker.length)
        .substringBefore(']')
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * The two numbers and the limit pi reports for a truncated result.
 *
 * pi carries the whole `TruncationResult` in `details.truncation`
 * (`packages/coding-agent/src/core/tools/bash.ts:321`; the shape is
 * `core/tools/truncate.ts:14-40`: `truncated`, `truncatedBy`, `outputLines`,
 * `totalLines`, `maxBytes`, …), and our projection keeps `details` whole
 * (`rpc/Transcript.kt:1223`) — so every number below is pi's own. Nothing here is
 * computed from our rendering, which is why a missing field stays null instead of
 * being filled in with a guess.
 */
private class ToolTruncation(
    val truncatedBy: String?,
    val outputLines: Int?,
    val totalLines: Int?,
    val maxBytes: Int?,
)

/** pi's truncation block for this call, or null when it truncated nothing. */
private fun truncationOf(item: ToolCall): ToolTruncation? {
    val truncation = (item.details as? JsonObject)?.get("truncation") as? JsonObject ?: return null
    val truncated = (truncation["truncated"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    if (!truncated) return null
    fun int(key: String) = (truncation[key] as? JsonPrimitive)?.content?.toIntOrNull()
    return ToolTruncation(
        truncatedBy = (truncation["truncatedBy"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        outputLines = int("outputLines"),
        totalLines = int("totalLines"),
        maxBytes = int("maxBytes"),
    )
}

/**
 * pi's warning line (`core/tools/renderers/bash.ts:100-118`), in this app's
 * language.
 *
 * pi joins the parts with `". "` inside one `[ … ]` and colours the whole line
 * `warning`; the parts, in pi's order, are `Full output: <path>` when a path
 * exists, then the truncation report — `Truncated: showing X of Y lines` for the
 * line limit, `Truncated: X lines shown (<size> limit)` for the byte limit. The
 * separators are the only thing translated here; the numbers are pi's and a part
 * whose number pi did not send is left out rather than estimated.
 *
 * Null when pi would print nothing — its condition is
 * `truncation?.truncated || fullOutputPath`, so a path alone still produces a
 * line.
 */
private fun truncationNotice(path: String?, truncation: ToolTruncation?): String? {
    val parts = mutableListOf<String>()
    if (path != null) parts += "完整输出：$path"
    if (truncation != null) {
        val shown = truncation.outputLines
        val total = truncation.totalLines
        parts += when {
            truncation.truncatedBy == "lines" && shown != null && total != null ->
                "已截断：显示 $shown / $total 行"
            truncation.truncatedBy == "lines" && shown != null -> "已截断：显示 $shown 行"
            shown != null && truncation.maxBytes != null ->
                "已截断：显示 $shown 行（上限 ${formatBytes(truncation.maxBytes)}）"
            shown != null -> "已截断：显示 $shown 行"
            else -> "已截断"
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("；")
}

/** pi's `formatSize` for the byte limit: whole KB when it divides evenly. */
private fun formatBytes(bytes: Int): String =
    if (bytes >= 1024 && bytes % 1024 == 0) "${bytes / 1024} KB" else "$bytes B"

/**
 * Remove the `[ … Full output: <path>]` sentence pi's tool appended to its own
 * text, so the card can print it once as the notice instead of twice.
 *
 * This is pi's `rebuildBashResultRenderComponent` verbatim
 * (`core/tools/renderers/bash.ts:59-64`), including what it refuses to do:
 *
 *  - only when the text *ends* with `]` — a body that merely contains a bracket
 *    block keeps every byte;
 *  - only the block after the **last** `\n\n[`, so an earlier bracket block in the
 *    output is never touched;
 *  - and only if that block actually names [path]. An unrelated trailing
 *    `[ … ]` therefore survives, which is the whole reason pi checks it.
 *
 * Everything before that boundary is returned unchanged apart from the trailing
 * whitespace pi also trims (`trimEnd`, matching `:63`).
 */
private fun stripFullOutputFooter(text: String, path: String?): String {
    if (path == null) return text
    if (!text.endsWith("]")) return text
    val footerStart = text.lastIndexOf("\n\n[")
    if (footerStart < 0) return text
    if (!text.substring(footerStart).contains(path)) return text
    return text.substring(0, footerStart).trimEnd()
}

/**
 * Spec §4.2's collapsed budget for a long tool result, and its hard ceiling for
 * the inline surface. F17 (`docs/rendering-review.md`): this used to be 400 with
 * no path to the remainder — a `bash` log past the cap was silently incomplete.
 * The card's toggle now shows these lines and 「展开全部」 shows the rest; past
 * [MAX_OUTPUT_BYTES] the block no longer paints the body, but it *does* keep pi's
 * own record of where the complete output is (see [fullOutputPathOf]).
 *
 * [MAX_OUTPUT_BYTES] is this app's rendering budget, **not** a pi rule: pi's own
 * ceiling is 2000 lines / 50 KB (`core/tools/truncate.ts:11-12`), which is what
 * the notice's numbers report. Nothing about the 200 KB limit is presented as pi's
 * information.
 */
private const val COLLAPSED_OUTPUT_LINES = 200
private const val MAX_OUTPUT_BYTES = 200 * 1024

/** How much of the end of an output is scanned for pi's `Full output:` sentence. */
private const val PATH_SCAN_CHARS = 4096
