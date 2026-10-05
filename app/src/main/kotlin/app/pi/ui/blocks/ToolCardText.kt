package app.pi.ui.blocks

import app.pi.rpc.ToolCall

/**
 * 工具卡的**读数与页脚文案**：一个纯文件，和 [ToolState] 同一个理由 —— 这些字决定卡片上看到
 * 什么，而决定它们的代码在 Compose 里（本机编译不了，CI 才编）。把不需要 Compose 的部分搬出来，
 * 裸 JVM 的 harness（`tools/run-app-pure-checks.sh` 的 `tool-state` 一项）就能把「措辞与 ` · `
 * 顺序逐字」变成一条真的断言，而不是一句承诺。
 *
 * 上色、布局与 Composables 全部留在 `ToolBlockChrome.kt`；两个文件同属 `app.pi.ui.blocks`，
 * 调用点一个都没改。
 */

/** Durations the way pi prints them: ms under a second, then s, then m s. */
internal fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    return when {
        safe < 1_000 -> "${safe}ms"
        safe < 60_000 -> "${safe / 1_000}s"
        else -> "${safe / 60_000}m${(safe % 60_000) / 1_000}s"
    }
}

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
 * nothing to measure ([toolRowReading] is what puts `没有执行` in that cell).
 *
 * @param elapsedMs which number to spell. The default is pi's own settled figure
 *   (`ToolCall.elapsedMs`, non-null only once the call ended); `ShellBlock` passes the
 *   **live** value it already measures against the ViewModel's 1 Hz clock, so a running
 *   command's reading ticks beside its own tick instead of staying blank until the call
 *   ends. Reading and tick must come from one number — that is why the override exists
 *   rather than a second formatter at the call site.
 */
internal fun toolHeaderReading(item: ToolCall, elapsedMs: Long? = item.elapsedMs): String? {
    if (toolStateOf(item) == ToolState.Rejected) return null
    val ms = elapsedMs ?: return null
    return if (item.toolName == "bash" || item.toolName == "powershell") {
        ToolOutputParse.formatDuration(ms)
    } else {
        formatDuration(ms)
    }
}

/**
 * The second half of a blocked call's sentence, and the only thing that may stand in a
 * tool row's reading cell without a number.
 *
 * `06 §4` / `06 §3` 构件 5 spell the 被拒 case out as `被拒 · 没有执行`, and the row's two
 * cells keep both halves readable without opening the card: the verdict cell takes the word
 * 被拒, this cell takes 没有执行. `差异表` §1 第 7 行 keeps 「被拒没有读数」 intact — there is no
 * duration and no count here, which is exactly why the phrase has to be spelled out.
 */
internal const val TOOL_NOT_EXECUTED = "没有执行"

/** The reading cell's text: the caller's [right], or [TOOL_NOT_EXECUTED] for a blocked call. */
internal fun toolRowReading(state: ToolState, right: String?): String? =
    if (state == ToolState.Rejected) TOOL_NOT_EXECUTED else right

/**
 * The verdict cell's text: the state's word, plus `已截断` when the result was cut.
 *
 * The word is `06 §4`'s second channel and is never dropped — a card whose state is only
 * visible after a tap is not a state indicator. `已截断` rides along because it is the one
 * footer fact that changes how the *body* should be read (the card on screen is not all of
 * the answer), and the v5 board draws the same pair (`成功 · 已截断`). A blocked call never
 * shows it: nothing ran, so nothing was truncated.
 */
internal fun toolRowVerdict(state: ToolState, truncated: Boolean): String = when {
    state == ToolState.Rejected -> toolStateLabel(state)
    truncated -> "${toolStateLabel(state)} · 已截断"
    else -> toolStateLabel(state)
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
internal fun toolRejectedFooter(): String = "${toolStateLabel(ToolState.Rejected)} · $TOOL_NOT_EXECUTED"

/**
 * pi's `[invalid content arg - expected string]` case
 * (`core/tools/renderers/write.ts:108-109`), said in this app's words: a settled `write`
 * whose arguments carried no content.
 */
internal const val TOOL_INVALID_CONTENT = "参数里没有文件内容。"

/** A result whose text was cut by our own scan budget, so a block can say so. */
internal const val TOOL_SCAN_CAPPED_HINT = "输出过长，只解析了前面一部分。"
