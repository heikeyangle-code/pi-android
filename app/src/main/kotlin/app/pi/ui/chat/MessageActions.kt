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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
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
import app.pi.i18n.tr
import app.pi.protocol.TranscriptItem
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
    var selectOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val actions = LocalMessageActions.current
    Box(
        modifier.combinedClickable(
            onClickLabel = null,
            onLongClickLabel = tr("消息操作", "Message actions"),
            onLongClick = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
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
            onOpenSelect = {
                open = false
                selectOpen = true
            },
            onDismiss = { open = false },
        )
    }
    if (selectOpen) {
        MessageTextSelectDialog(
            role = role,
            text = text,
            actions = actions,
            onDismiss = { selectOpen = false },
        )
    }
}

enum class MessageRole(val wire: String) {
    User("user"),
    Assistant("assistant");

    val title: String
        get() = when (this) {
            User -> tr("你的消息", "Your message")
            Assistant -> tr("pi 的回复", "pi reply")
        }
}

private enum class Mode { Menu, Label }

@Composable
private fun MessageActionSheet(
    item: TranscriptItem,
    role: MessageRole,
    text: String,
    onEditAndFork: (() -> Unit)?,
    onOpenSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = LocalMessageActions.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val palette = PiTheme.palette
    var mode by remember { mutableStateOf(Mode.Menu) }

    PiSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
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
                                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }

                    // 高频 3 列快捷操作网格
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MessageQuickTile(
                            icon = Icons.Filled.ContentCopy,
                            label = tr("复制全部", "Copy all"),
                            tint = palette.accent,
                            modifier = Modifier.weight(1f),
                        ) {
                            clipboard.setText(AnnotatedString(text))
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        }
                        MessageQuickTile(
                            icon = Icons.Filled.SelectAll,
                            label = tr("选择复制", "Select & copy"),
                            tint = palette.mdLink,
                            modifier = Modifier.weight(1f),
                        ) { onOpenSelect() }
                        if (actions != null) {
                            MessageQuickTile(
                                icon = Icons.Filled.FormatQuote,
                                label = tr("引用回复", "Quote"),
                                tint = palette.success,
                                modifier = Modifier.weight(1f),
                            ) {
                                actions.quote(quoted(text))
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDismiss()
                            }
                        }
                    }

                    if (actions != null && !actions.streaming) {
                        val cardShape = RoundedCornerShape(16.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                                .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.45f), cardShape),
                        ) {
                            if (onEditAndFork != null) {
                                MessageSheetRow(
                                    icon = Icons.Filled.Edit,
                                    label = tr("编辑并从此分叉", "Edit & fork from here"),
                                    subtitle = tr("回到此处重新编辑，保留原分支", "Re-edit from this point while keeping the original branch"),
                                ) {
                                    onEditAndFork()
                                    onDismiss()
                                }
                                MessageSheetDivider()
                            }
                            MessageSheetRow(
                                icon = Icons.Filled.AccountTree,
                                label = tr("从这里继续（开新分支）", "Continue from here (new branch)"),
                                subtitle = tr("在会话树中以此消息为起点继续对话", "Branch the session tree from this message"),
                            ) {
                                actions.continueFrom(item)
                                onDismiss()
                            }
                            MessageSheetDivider()
                            MessageSheetRow(
                                icon = Icons.Filled.BookmarkBorder,
                                label = tr("添加 / 修改标签", "Add / edit label"),
                                subtitle = tr("在会话树中快速定位此节点", "Bookmark this node in the session tree"),
                            ) { mode = Mode.Label }
                            MessageSheetDivider()
                            MessageSheetRow(
                                icon = Icons.Filled.VisibilityOff,
                                label = tr("从模型上下文隐藏", "Hide from model context"),
                                subtitle = tr("保留显示，但后续请求不再发给模型", "Keep visible in chat, omit from future model requests"),
                                tint = palette.warning,
                            ) {
                                actions.hideFromContext(item)
                                onDismiss()
                            }
                        }
                    }
                }

                Mode.Label -> {
                    var label by remember { mutableStateOf("") }
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    app.pi.ui.components.PiAutoFocus(focus)
                    Text(tr("给这条消息加标签", "Label this message"), style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        tr(
                            "标签写进会话树，在「树」里能一眼找到这里；留空保存即清除已有标签。",
                            "Labels appear as bookmarks in the session tree. Save blank to clear an existing label.",
                        ),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                            .border(1.dp, palette.borderAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = label,
                            onValueChange = { label = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focus),
                            singleLine = true,
                            textStyle = PiTheme.text.prose.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = {
                                    actions?.label?.invoke(item, label.trim().takeIf { it.isNotEmpty() })
                                    onDismiss()
                                },
                            ),
                            decorationBox = { inner ->
                                Box {
                                    if (label.isEmpty()) {
                                        Text(
                                            tr("输入标签名称…", "Enter label name…"),
                                            style = PiTheme.text.prose,
                                            color = palette.muted,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SheetActionPill(text = tr("返回", "Back"), primary = false, onClick = { mode = Mode.Menu })
                        Spacer(Modifier.weight(1f))
                        SheetActionPill(
                            text = tr("保存", "Save"),
                            primary = true,
                            onClick = {
                                actions?.label?.invoke(item, label.trim().takeIf { it.isNotEmpty() })
                                onDismiss()
                            },
                        )
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
            .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
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
