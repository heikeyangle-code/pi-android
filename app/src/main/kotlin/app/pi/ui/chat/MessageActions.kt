package app.pi.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import app.pi.rpc.TranscriptItem
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme

/**
 * 一条消息能做的事，以及谁来做。
 *
 * 为什么是 CompositionLocal 而不是一路传参：用户消息和助手消息各自在 `BlockRenderer` 下面好几层，
 * 而这几个动作（引用、从此处继续、标签、从上下文隐藏）全是对话页 ViewModel 的事。传参要改五层签名，
 * 而每一层都不关心它们。没提供（预览、测试）时为 null，长按只剩复制和选择文字。
 *
 * [mutating] 为 true 表示 pi 正在生成：宿主会拒绝对会话树的改动（fork / navigate / label / contextEdit），
 * 所以这些行**不显示**，而不是显示出来再弹一条报错。
 */
class MessageActions(
    val quote: (String) -> Unit,
    val continueFrom: (TranscriptItem) -> Unit,
    val label: (TranscriptItem, String?) -> Unit,
    val hideFromContext: (TranscriptItem) -> Unit,
    val streaming: Boolean,
    /**
     * 末尾那张错误卡的行键，只有「现在能重试」时才非 null（没在生成、且错误卡就是最后一行）。
     * 只有最后一行可以重试：更早的错误之后用户已经继续了对话，再把旧提示发一遍没有意义。
     */
    val retryKey: String? = null,
    val retry: () -> Unit = {},
)

val LocalMessageActions = staticCompositionLocalOf<MessageActions?> { null }

/** 把 [text] 变成 Markdown 引用块（每行前加 `> `），末尾留一个空行让用户接着写。 */
fun quoted(text: String): String =
    text.trimEnd().lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" } + "\n\n"

/**
 * 整条消息的长按目标。
 *
 * 旧做法是正文放进系统文本选择容器、卡片边缘挂一个 `DropdownMenu`：长按落在字上就被选择手柄吃掉，
 * 落在边距上才出菜单，**同一个手势两种结果**，而且下拉菜单没有触感、不跟手。现在整条消息只有一个
 * 长按行为：震一下、弹出底部操作面，「选择文字」是面里的一项，进去之后是整条消息的系统自由选择。
 * 点击仍然属于正文里的链接 / 图片 / 折叠，不受影响。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageActionTarget(
    item: TranscriptItem,
    role: MessageRole,
    text: String,
    modifier: Modifier = Modifier,
    /** 用户消息才有：回到这条消息之前分叉，并把原文放回输入框（pi 的 `/fork`）。 */
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
    var mode by remember { mutableStateOf(Mode.Menu) }
    PiSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PiSpacing.pageHorizontal)) {
            when (mode) {
                Mode.Menu -> {
                    Text(role.title, style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    Text(
                        text.trim(),
                        maxLines = PREVIEW_LINES,
                        overflow = TextOverflow.Ellipsis,
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    ActionRow("复制全部") {
                        clipboard.setText(AnnotatedString(text))
                        onDismiss()
                    }
                    ActionRow("选择文字") { mode = Mode.Select }
                    if (actions != null) {
                        ActionRow("引用到输入框") {
                            actions.quote(quoted(text))
                            onDismiss()
                        }
                        if (!actions.streaming) {
                            if (onEditAndFork != null) {
                                ActionRow("编辑并从此分叉") {
                                    onEditAndFork()
                                    onDismiss()
                                }
                            }
                            ActionRow("从这里继续（开新分支）") {
                                actions.continueFrom(item)
                                onDismiss()
                            }
                            ActionRow("加标签") { mode = Mode.Label }
                            ActionRow("从模型上下文隐藏", tone = Tone.Caution) {
                                actions.hideFromContext(item)
                                onDismiss()
                            }
                        }
                    }
                }

                Mode.Select -> {
                    Text("选择文字", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    // 整条消息的 Markdown 原文，一个选择域：手柄可以从头拖到尾。
                    SelectionContainer {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = PiSpacing.unit * SELECT_MAX_UNITS)
                                .verticalScroll(rememberScrollState()),
                            style = PiTheme.text.prose,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    ActionRow("返回") { mode = Mode.Menu }
                }

                Mode.Label -> {
                    var label by remember { mutableStateOf("") }
                    Text("给这条消息加标签", style = PiType.title, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    Text(
                        "标签写进会话树，在「树」里能一眼找到这里；留空保存 = 清除已有标签。",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.muted,
                    )
                    Spacer(Modifier.height(PiSpacing.blockGap))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("标签") },
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
            Spacer(Modifier.height(PiSpacing.unit))
        }
    }
}

private enum class Tone { Normal, Caution }

@Composable
private fun ColumnScope.ActionRow(label: String, tone: Tone = Tone.Normal, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = PiSpacing.cardPadding),
        style = PiType.rowTitle,
        color = if (tone == Tone.Caution) PiTheme.palette.warning else MaterialTheme.colorScheme.onSurface,
    )
}

private const val PREVIEW_LINES = 3
private const val SELECT_MAX_UNITS = 14
