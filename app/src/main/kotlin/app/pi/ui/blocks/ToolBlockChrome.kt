package app.pi.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import app.pi.ui.theme.PiPalette
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
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

/** The container colour of a tool card: pi's three status backgrounds. */
internal fun toolContainerColor(status: ToolStatus, palette: PiPalette): Color = when (status) {
    ToolStatus.Pending -> palette.toolPendingBg
    ToolStatus.Success -> palette.toolSuccessBg
    ToolStatus.Error -> palette.toolErrorBg
}

/** The border/stripe colour of a tool card: pi's status colour, one step stronger. */
internal fun toolAccentColor(status: ToolStatus, palette: PiPalette): Color = when (status) {
    ToolStatus.Pending -> palette.warning
    ToolStatus.Success -> palette.success
    ToolStatus.Error -> palette.error
}

/** Status is a word, never only a colour (docs/pi-android-ui-spec.md §9). */
internal fun toolStatusLabel(status: ToolStatus): String = when (status) {
    ToolStatus.Pending -> "运行中"
    ToolStatus.Success -> "成功"
    ToolStatus.Error -> "失败"
}

/** The glyph beside the status word, for the same reason. */
internal fun toolStatusGlyph(status: ToolStatus): String = when (status) {
    ToolStatus.Pending -> "…"
    ToolStatus.Success -> "✓"
    ToolStatus.Error -> "✗"
}

/**
 * The card's title row: the tool (or its prompt) in mono, its subject in the tool title
 * colour, and the status glyph.
 *
 * pi's call line is exactly this shape — `theme.fg("toolTitle", theme.bold("read"))` then
 * the path (`core/tools/renderers/read.ts:34-37`), `$ <command>` for the shell
 * (`renderers/bash.ts:35-41`), `grep /pattern/ in <path>` (`renderers/grep.ts:17-35`).
 */
@Composable
internal fun ToolHeader(
    title: String,
    subject: String,
    status: ToolStatus,
    modifier: Modifier = Modifier,
) {
    val palette = PiTheme.palette
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
        Text(
            text = toolStatusGlyph(status),
            style = PiTheme.text.monoSmall,
            color = toolAccentColor(status, palette),
        )
    }
}

/**
 * The card's footer row: pi's elapsed/status line plus the expand affordance.
 *
 * pi prints the same line under every result (`renderers/bash.ts:117-121`:
 * `Elapsed`/`Took`), and this app assembles its parts in [toolFooterText]. The expand label
 * is a label, not a hit target — the card's content region is the target (F28).
 */
@Composable
internal fun ToolFooter(
    text: String,
    expanded: Boolean,
    expandable: Boolean,
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
        if (expandable) {
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
 * The card itself: pi's status container, the one content-region gesture
 * (`Modifier.toggleContent`, F28), and the block's own column of rows.
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
    BlockCard(
        color = toolContainerColor(item.status, palette),
        modifier = modifier.toggleContent(expanded, onToggle),
        borderColor = toolAccentColor(item.status, palette).copy(alpha = 0.35f),
        content = content,
    )
}

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
 * The card's footer parts, in pi's order: status, pi's exit code, the duration pi measures,
 * the row's size, and whether the result was truncated or empty.
 *
 * [lines] is passed in rather than recomputed (F31): the caller already holds a remembered
 * count for the same output, and this used to scan the whole (possibly megabyte) string on
 * every composition.
 */
internal fun toolFooterText(item: ToolCall, statusLabel: String, lines: Int): String {
    val parts = mutableListOf(statusLabel)
    item.exitCode?.let { parts += "退出码 $it" }
    item.elapsedMs?.let { parts += formatDuration(it) }
    if (lines > 0) parts += "$lines 行"
    if (item.outputTruncated) parts += "已截断"
    if (item.output.isEmpty()) parts += "无输出"
    return parts.joinToString(" · ")
}

/**
 * pi's `[invalid content arg - expected string]` case
 * (`core/tools/renderers/write.ts:108-109`), said in this app's words: a settled `write`
 * whose arguments carried no content.
 */
internal const val TOOL_INVALID_CONTENT = "参数里没有文件内容。"

/** A result whose text was cut by our own scan budget, so a block can say so. */
internal const val TOOL_SCAN_CAPPED_HINT = "输出过长，只解析了前面一部分。"
