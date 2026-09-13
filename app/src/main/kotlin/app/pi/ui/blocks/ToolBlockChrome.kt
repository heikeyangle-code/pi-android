package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolStatus
import app.pi.ui.theme.DurationMeter
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.StateChip
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

/** The glyph beside the status word, for the same reason (`06 §4`). */
internal fun toolStateGlyph(state: ToolState): String = when (state) {
    ToolState.Running -> "…"
    ToolState.Success -> "✓"
    ToolState.Failed -> "✗"
    ToolState.Rejected -> "⊘"
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

/** The border, stripe, node-ring and tick colour of a card: its state colour. */
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

/**
 * The card's title row: the tool (or its prompt) in mono, its subject in the tool title
 * colour, and the state as a [StateChip].
 *
 * pi's call line is exactly this shape — `theme.fg("toolTitle", theme.bold("read"))` then
 * the path (`core/tools/renderers/read.ts:34-37`), `$ <command>` for the shell
 * (`renderers/bash.ts:35-41`), `grep /pattern/ in <path>` (`renderers/grep.ts:17-35`).
 *
 * The right slot used to be a bare state glyph; it is now the **triple encoding** of
 * `06 §4` — the word `运行中 / 成功 / 失败 / 被拒`, the symbol beside it, and the state
 * colour, in one component shared with every other surface that has a state. The card
 * therefore says what happened twice over, in words and in a symbol, and the colour is
 * only the third channel (`theme/PiStateChip.kt`).
 */
@Composable
internal fun ToolHeader(
    item: ToolCall,
    title: String,
    subject: String,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
    val state = toolStateOf(item)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = PiTheme.text.monoSmall,
            color = palette.dim,
            maxLines = 1,
        )
        Spacer(Modifier.width(PiSpacing.inner))
        Text(
            text = subject,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = palette.toolTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(PiSpacing.inline))
        StateChip(
            label = toolStateLabel(state),
            tone = toolStateTone(state),
            glyph = toolStateGlyph(state),
        )
    }
}

/**
 * The card's footer row: pi's elapsed/status line, the duration tick, and the expand
 * affordance.
 *
 * pi prints the same line under every result (`renderers/bash.ts:117-121`:
 * `Elapsed`/`Took`), and this app assembles its parts in [toolFooterText]. The expand label
 * is a label, not a hit target — the card's content region is the target (F28).
 *
 * [DurationMeter] sits at the end of the readings, before the expand label: the tick is
 * the *ordinal* reading of the same number the text just printed
 * (`04 §1.1`: 刻度是补充，不是替代), so it belongs beside it rather than beside the
 * affordance. It draws nothing when [elapsedMs] is null, which is what keeps a card with
 * no measured duration — and every diff card, which has no duration of its own
 * (`06 §2`: diff 卡不显示) — free of an empty tick.
 *
 * [elapsedMs] is passed in rather than read from [item] because one caller has a
 * *live* number: a running shell command's elapsed time is derived from the row's own
 * timestamp by [ShellBlock], not from `ToolCall.elapsedMs` (which only exists once the
 * call has ended). Nothing here scans a result — the F31/F8 rule at the top of this file.
 */
@Composable
internal fun ToolFooter(
    text: String,
    expanded: Boolean,
    expandable: Boolean,
    state: ToolState,
    elapsedMs: Long?,
    modifier: Modifier = Modifier,
    expandText: String = "输出",
) {
    val palette = PiTheme.palette
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = PiTheme.text.meta,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DurationMeter(
            ms = elapsedMs,
            color = toolAccentColor(state, palette),
            modifier = Modifier.padding(start = PiSpacing.inline),
        )
        if (expandable) {
            Spacer(Modifier.width(PiSpacing.inline))
            ExpandLabel(expanded, expandText = expandText, collapseText = "收起")
        }
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
    ToolRailFrame(
        glyph = toolStateGlyph(state),
        tone = toolStateTone(state),
        label = toolStateLabel(state),
        modifier = modifier,
    ) {
        BlockCard(
            color = toolContainerColor(state, palette),
            modifier = Modifier.toggleContent(expanded, onToggle),
            // `06 §2`「描边 1px 状态色 35%」, for all four states: the ring is what the
            // card's state looks like at a glance, and the fill is only its ground.
            borderColor = toolAccentColor(state, palette).copy(alpha = TOOL_CARD_BORDER_ALPHA),
            content = content,
        )
    }
}

/** `06 §2` 工具卡: the status-coloured border's alpha, in all four states. */
internal const val TOOL_CARD_BORDER_ALPHA: Float = 0.35f

/**
 * The long-press actions of §4.8, shared by every tool card: the command, the output, and
 * pi's own record of where a truncated result's remainder went.
 *
 * [command] is the tool's own argument (see [toolCommandText]); a tool whose arguments are
 * none of pi's spellings gets no command entry rather than a wrong one.
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
