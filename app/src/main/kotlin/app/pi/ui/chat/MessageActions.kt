package app.pi.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.rpc.TranscriptItem
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

class MessageActions(
    val quote: (String) -> Unit,
    val continueFrom: (TranscriptItem) -> Unit,
    val label: (TranscriptItem, String?) -> Unit,
    val hideFromContext: (TranscriptItem) -> Unit,
    val streaming: Boolean,
    val retryKey: String? = null,
    val retry: () -> Unit = {},
)

val LocalMessageActions = staticCompositionLocalOf<MessageActions?> { null }

fun quoted(text: String): String =
    text.trimEnd().lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" } + "\n\n"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageActionTarget(
    item: TranscriptItem,
    role: MessageRole,
    text: String,
    modifier: Modifier = Modifier,
    onEditAndFork: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(
        modifier.combinedClickable(
            onClickLabel = null,
            onLongClickLabel = "消息操作",
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                open = true
            },
            onClick = {},
        ),
    ) {
        content()
    }
    if (open) {
        MessageActionSheet(
            item = item,
            role = role,
            text = text,
            onEditAndFork = onEditAndFork,
            onDismiss = { open = false },
        )
    }
}

enum class MessageRole(val wire: String, val title: String) {
    User("user", "你的消息"),
    Assistant("assistant", "pi 的回复"),
}

private enum class Mode { Menu, Select, Label }

@Composable
private fun MessageActionSheet(
    item: TranscriptItem,
    role: MessageRole,
    text: String,
    onEditAndFork: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val actions = LocalMessageActions.current
    val clipboard = LocalClipboardManager.current
    val palette = PiTheme.palette
    var mode by remember { mutableStateOf(Mode.Menu) }

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (mode) {
                Mode.Menu -> {
                    Text(role.title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    if (text.isNotBlank()) {
                        Text(
                            text = text.trim(),
                            maxLines = PREVIEW_LINES,
                            overflow = TextOverflow.Ellipsis,
                            style = PiTheme.text.meta,
                            color = palette.muted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }

                    // 高频 3/4 列快捷操作网格
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MessageQuickTile(
                            icon = Icons.Filled.ContentCopy,
                            label = "复制全部",
                            tint = palette.accent,
                            modifier = Modifier.weight(1f),
                        ) {
                            clipboard.setText(AnnotatedString(text))
                            onDismiss()
                        }
                        MessageQuickTile(
                            icon = Icons.Filled.SelectAll,
                            label = "选择文字",
                            tint = palette.border,
                            modifier = Modifier.weight(1f),
                        ) { mode = Mode.Select }
                        if (actions != null) {
                            MessageQuickTile(
                                icon = Icons.Filled.FormatQuote,
                                label = "引用回复",
                                tint = palette.success,
                                modifier = Modifier.weight(1f),
                            ) {
                                actions.quote(quoted(text))
                                onDismiss()
                            }
                        }
                    }

                    if (actions != null && !actions.streaming) {
                        val cardShape = RoundedCornerShape(14.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .border(1.dp, palette.borderMuted.copy(alpha = 0.4f), cardShape),
                        ) {
                            if (onEditAndFork != null) {
                                MessageSheetRow(
                                    icon = Icons.Filled.Edit,
                                    label = "编辑并从此分叉",
                                    subtitle = "回到此处重新编辑，保留原分支",
                                ) {
                                    onEditAndFork()
                                    onDismiss()
                                }
                                MessageSheetDivider()
                            }
                            MessageSheetRow(
                                icon = Icons.Filled.AccountTree,
                                label = "从这里继续（开新分支）",
                                subtitle = "在会话树中以此消息为起点继续对话",
                            ) {
                                actions.continueFrom(item)
                                onDismiss()
                            }
                            MessageSheetDivider()
                            MessageSheetRow(
                                icon = Icons.Filled.BookmarkBorder,
                                label = "添加 / 修改标签",
                                subtitle = "在会话树中快速定位此节点",
                            ) { mode = Mode.Label }
                            MessageSheetDivider()
                            MessageSheetRow(
                                icon = Icons.Filled.VisibilityOff,
                                label = "从模型上下文隐藏",
                                subtitle = "保留显示，但后续请求不再发给模型",
                                tint = palette.warning,
                            ) {
                                actions.hideFromContext(item)
                                onDismiss()
                            }
                        }
                    }
                }

                Mode.Select -> {
                    Text("自由选择文字", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    SelectionContainer {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = PiSpacing.unit * SELECT_MAX_UNITS)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = PiTheme.text.prose,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { mode = Mode.Menu }) { Text("返回") }
                    }
                }

                Mode.Label -> {
                    var label by remember { mutableStateOf("") }
                    Text("给这条消息加标签", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "标签写进会话树，在「树」里能一眼找到这里；留空保存即清除已有标签。",
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入标签名称") },
                        shape = PiShapes.input,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { mode = Mode.Menu }) { Text("返回") }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                actions?.label?.invoke(item, label.trim().takeIf { it.isNotEmpty() })
                                onDismiss()
                            },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageQuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, PiTheme.palette.borderMuted.copy(alpha = 0.42f), shape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tint,
            )
        }
        Text(
            text = label,
            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageSheetRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val labelColor = tint ?: MaterialTheme.colorScheme.onSurface
    val iconColor = tint ?: PiTheme.palette.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = iconColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = PiType.rowTitle, color = labelColor)
            if (subtitle != null) {
                Text(subtitle, style = PiTheme.text.meta, color = PiTheme.palette.muted)
            }
        }
    }
}

@Composable
private fun MessageSheetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 45.dp),
        thickness = PiSpacing.hairlineThin,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

private const val PREVIEW_LINES = 3
private const val SELECT_MAX_UNITS = 14
