package app.pi.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.protocol.PiResponses
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 命令名之后的参数面板：先给一行用法（`argumentHint`，仅在还没写参数时），
 * 再列官方内置或扩展给的参数候选（`getArgumentCompletions`）。点候选把它写进草稿，不发送——
 * 与 pi 终端里对参数补全的 Tab 一致。两样都没有就不画。
 */
@Composable
fun ArgumentPalette(
    command: PiSlashCommand,
    prefix: String,
    completions: List<PiResponses.CommandCompletion>,
    onPick: (PiResponses.CommandCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = command.argumentHint?.takeIf { prefix.isBlank() }
    if (hint == null && completions.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.55f)),
    ) {
        Column {
            if (hint != null) {
                Text(
                    text = "/${command.name} $hint",
                    modifier = Modifier.padding(horizontal = PiSpacing.pageHorizontal, vertical = 9.dp),
                    style = PiTheme.text.monoSmall,
                    color = PiTheme.palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hint != null && completions.isNotEmpty()) {
                HorizontalDivider(
                    thickness = PiSpacing.hairlineThin,
                    color = PiTheme.palette.borderMuted.copy(alpha = 0.45f),
                )
            }
            if (completions.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 260.dp).padding(vertical = 4.dp)) {
                    itemsIndexed(completions, key = { idx, it -> "$idx:${it.value}" }) { _, item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPick(item)
                                }
                                .padding(horizontal = PiSpacing.pageHorizontal, vertical = 9.dp),
                        ) {
                            Text(
                                text = item.label.ifBlank { item.value },
                                style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = PiTheme.text.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 扩展通过 `ctx.ui.addAutocompleteProvider` 提供的自动补全候选面板。
 * 点击一项时将当前草稿尾部的 [prefix] 替换为 `item.value`（与 Pi 编辑器 Tab 接受补全语义一致）。
 */
@Composable
fun ExtensionAutocompletePalette(
    items: List<app.pi.host.HostCommands.ExtensionCompletion>,
    onPick: (app.pi.host.HostCommands.ExtensionCompletion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PiShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(PiSpacing.hairlineThin, PiTheme.palette.borderMuted.copy(alpha = 0.55f)),
    ) {
        LazyColumn(Modifier.heightIn(max = 260.dp).padding(vertical = 4.dp)) {
            itemsIndexed(items, key = { idx, it -> "$idx:${it.value}" }) { _, item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(item)
                        }
                        .padding(horizontal = PiSpacing.pageHorizontal, vertical = 9.dp),
                ) {
                    Text(
                        text = item.label.ifBlank { item.value },
                        style = PiTheme.text.mono.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = PiTheme.text.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
