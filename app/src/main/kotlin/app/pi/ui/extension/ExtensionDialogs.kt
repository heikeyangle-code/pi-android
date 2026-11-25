package app.pi.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
import app.pi.ui.settings.PiEditorField
import app.pi.ui.settings.PiSettingsMetrics
import app.pi.ui.components.PiSheet
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlin.math.roundToInt

/**
 * v2 renderings of pi's four blocking dialog methods.
 *
 * The shapes follow `docs/rpc.md` §"Extension UI Requests" one for one:
 *
 *   select  title + options              -> `value` (the chosen option)
 *   confirm title + message              -> `confirmed` true/false
 *   input   title + placeholder          -> `value`
 *   editor  title + prefill (multi-line) -> `value`
 *
 * Every dialog can also be dismissed, which is pi's own Escape semantics: the
 * extension receives `undefined` for select/input/editor and `false` for confirm
 * (`docs/rpc.md` §"Cancellation response", `rpc-mode.ts` `createDialogPromise`).
 * So [onDismissRequest] answers [ExtensionAnswer.Cancelled]; it does not merely
 * close a window. A dialog this app hides without answering is exactly the
 * deadlock this file exists to remove.
 *
 * [onAnswer] receives the request id as well as the answer; the caller must use
 * that id as the correlation key. A timeout can resolve a dialog in the same
 * frame the user taps it, and by then the queue head is already the *next*
 * request — without the id, the tap would answer a different extension's promise
 * with the wrong value.
 *
 * ## Shell (`06 §2` 对话框, `phone55`–`phone58`)
 *
 * Max width 330, radius 14, `padding:18px 16px 12px`, a 1 px `borderMuted` rule, no
 * shadow. The shell is `ui/components/PiDialog.kt`, shared with the settings
 * dialogs; the board draws one `.b-dlg` for both, and the two shells had drifted
 * apart on the background (`surf-low` here, `surf-high` there) before the merge.
 * The board's own scrim, `rgba(0,0,0,.32)`, is drawn by that component.
 *
 * ## The three states this file has to make readable
 *
 *  - **awaiting** — the countdown: ten `3`-high segments (gap 1) above the message,
 *    filled in `warning` (the same tone as the `!`), plus the seconds in the title.
 *  - **queued** — `还有 N 个扩展对话框在排队`, in the footer.
 *  - **timeout** — `超时后自动拒绝/自动取消，晚到的回复会被丢弃。`, beside it.
 *
 * Both footer lines are one paragraph, which is how `phone55`/`phone57` show them
 * and why they are joined with a full stop instead of stacked: they are one
 * sentence about what happens to *this* request, not two unrelated notes.
 *
 * Colours come from [PiTheme.palette] only.
 */
@Composable
fun ExtensionDialogHost(
    dialog: ExtensionDialog?,
    backlog: Int = 0,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    if (dialog == null) return
    // The window, the scrim and the shell all come from `PiDialog` now (the same
    // component the settings dialogs use), so this is one `Dialog` per request, not
    // two nested ones. A dismissal still *answers* the request — pi treats it as
    // Escape — rather than merely closing a window.
    ExtensionDialogShell(dialog, backlog, onAnswer)
}

// ------------------------------------------------------------------- the shell

@Composable
private fun ExtensionDialogShell(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    // Keyed on the id so a promoted request starts empty instead of carrying the
    // previous extension's answer over.
    var draft by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }

    // 长/宽的 select **换壳不换协议**（判定与阈值在 `ExtensionTextLines.selectNeedsSheet`，
    // harness `text-lines` 钉着边界）：9 个以上选项在 330 宽的对话框里列表区（360dp ÷ 43dp
    // 行高 = 8.4 行）必然内滚，任一标签超过 36 列则在对话框行里被省略号截掉 —— 这两种情况
    // 改用 v2 的底部 sheet（正文余量 420dp、412 宽屏上一行约 49 列）。低于阈值仍是 `06 §2`
    // 把扩展 select 归类的那个对话框。回包六条协议逐条不变，input/confirm/editor 不走这里。
    if (dialog.method == ExtensionDialogMethod.Select &&
        dialog.options.isNotEmpty() &&
        selectNeedsSheet(dialog.options.map { visibleColumns(chromeText(it)) })
    ) {
        SelectSheetBody(dialog, backlog, onAnswer)
        return
    }

    // 多行编辑器在 330dp 居中小弹窗里叠加软键盘极难编辑：改用全宽底部面板承载，
    // 保留完整协议（回包 id / value / cancelled / timeout 倒计时完全不变）。
    if (dialog.method == ExtensionDialogMethod.Editor) {
        EditorSheetBody(dialog, draft, { draft = it }, backlog, onAnswer)
        return
    }

    // 外壳与设置页的对话框是**同一个构件**（`ui/components/PiDialog.kt`）：最大宽
    // 330、圆角 14、`surf-high` 底、1px `borderMuted`、`padding:18px 16px 12px`、
    // scrim `rgba(0,0,0,.32)`。两边原先各画一套，底色一个 `surf-low` 一个
    // `surf-high`（`06 §2` 只认 `surf-high`）。
    PiDialog(onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) {
        DialogHeading(dialog)
        DialogMessage(dialog.message)

        when (dialog.method) {
            ExtensionDialogMethod.Select -> SelectBody(dialog, onAnswer)
            ExtensionDialogMethod.Input -> InputBody(dialog, draft, { draft = it }, onAnswer)
            ExtensionDialogMethod.Editor,
            ExtensionDialogMethod.Confirm,
            -> Unit
        }

        DialogActions(dialog, draft, onAnswer)
        DialogFooter(dialog, backlog)
    }
}

// ------------------------------------------------------------------- select

/**
 * A selection list, not a menu: pi's TUI renders select as a focusable list
 * (`docs/tui.md` §SelectList) and one tap must answer, so there is no confirm
 * button — only 取消.
 *
 * The rows are v2's unselected form (`○` + label, 1 px rules between them, radius
 * 10, `padding:10px 12px`). `phone55` also draws one *selected* row, but pi's wire
 * shape has no selected index — `select` carries `options: string[]` and nothing
 * else (`rpc-types.ts` `RpcExtensionUIRequest`) — so marking a row here would be
 * inventing a default the user never chose.
 */
@Composable
private fun SelectBody(
    dialog: ExtensionDialog,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    Spacer(Modifier.height(DialogControlGap))
    if (dialog.options.isEmpty()) {
        Text(
            tr("这个扩展没有给出任何选项。", "This extension provided no options."),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.warning,
        )
        return
    }
    val shape = RoundedCornerShape(DialogListRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = DialogListMaxHeight)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f), shape),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SelectOptionRows(dialog, onAnswer)
        }
    }
}

/**
 * 长/宽 select 的 sheet 外壳 —— 与 [SelectBody] 共用选项行本体，只换容器（使用 LazyColumn 保持长列表 60fps）。
 */
@Composable
private fun SelectSheetBody(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    var query by remember(dialog.id) { mutableStateOf("") }
    val needle = query.trim().lowercase()
    val filtered = remember(dialog.options, needle) {
        if (needle.isEmpty()) dialog.options
        else dialog.options.filter { chromeText(it).lowercase().contains(needle) }
    }
    PiSheet(onDismiss = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                .padding(top = PiSettingsMetrics.sheetHeadTop),
        ) {
            DialogHeading(dialog)
            DialogMessage(dialog.message)
            if (dialog.options.size >= 6) {
                Spacer(Modifier.height(DialogControlGap))
                PiEditorField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = tr("筛选选项（共 ${dialog.options.size} 项）", "Filter options (${dialog.options.size} total)"),
                    height = PiSettingsMetrics.searchFieldHeight,
                )
            }
            Spacer(Modifier.height(DialogControlGap))
            val palette = PiTheme.palette
            val shape = RoundedCornerShape(DialogListRadius)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PiSettingsMetrics.sheetBodyMax)
                    .clip(shape)
                    .background(app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg)
                    .border(PiSpacing.hairlineThin, palette.borderMuted.copy(alpha = 0.55f), shape),
            ) {
                if (filtered.isEmpty()) {
                    Text(
                        tr("没有匹配「$query」的选项。", "No options matching \"$query\"."),
                        style = PiTheme.text.meta,
                        color = palette.muted,
                        modifier = Modifier.padding(PiSpacing.cardPadding),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        itemsIndexed(filtered, key = { index, opt -> "$index:$opt" }) { index, option ->
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(PiSpacing.hairlineThin)
                                        .background(palette.borderMuted.copy(alpha = 0.45f)),
                                )
                            }
                            SelectOptionRow(dialog.id, option, onAnswer)
                        }
                    }
                }
            }
            DialogActions(dialog, dialog.prefill.orEmpty(), onAnswer)
            DialogFooter(dialog, backlog)
        }
    }
}

/** 宽屏多行编辑面板：相比 330dp 小对话框，在手机竖屏叠加软键盘时有充足的横向与纵向编辑空间。 */
@Composable
private fun EditorSheetBody(
    dialog: ExtensionDialog,
    draft: String,
    onDraftChange: (String) -> Unit,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val lines = remember(draft) { if (draft.isEmpty()) 0 else draft.count { it == '\n' } + 1 }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    PiSheet(onDismiss = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSettingsMetrics.pageHorizontal)
                .padding(top = PiSettingsMetrics.sheetHeadTop),
        ) {
            DialogHeading(dialog)
            DialogMessage(dialog.message)
            Spacer(Modifier.height(DialogControlGap))
            ExtField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = dialog.placeholder ?: tr("编辑内容", "Edit text"),
                singleLine = false,
                height = null,
                paddingHorizontal = 12.dp,
                paddingVertical = 10.dp,
                onSubmit = {},
                minLinesOverride = 6,
                maxLinesOverride = 14,
                inSheet = true,
            )
            Spacer(Modifier.height(PiSpacing.small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("$lines 行 · ${draft.length} 字", "$lines lines · ${draft.length} chars"),
                    style = PiTheme.text.meta,
                    color = PiTheme.palette.dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .clip(CircleShape)
                            .background(PiTheme.palette.accent.copy(alpha = 0.12f))
                            .clickable(role = Role.Button) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrEmpty()) onDraftChange(if (draft.isEmpty()) clip else draft + clip)
                            }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            tint = PiTheme.palette.accent,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            tr("粘贴", "Paste"),
                            style = PiTheme.text.meta.copy(fontWeight = FontWeight.Medium),
                            color = PiTheme.palette.accent,
                        )
                    }
                    if (draft.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clip(CircleShape)
                                .background(app.pi.ui.components.PiSheetChromeMetrics.sheetControlBg)
                                .clickable(role = Role.Button) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDraftChange("")
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = PiTheme.palette.muted,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                tr("清空", "Clear"),
                                style = PiTheme.text.meta,
                                color = PiTheme.palette.muted,
                            )
                        }
                    }
                }
            }
            DialogActions(dialog, draft, onAnswer)
            DialogFooter(dialog, backlog)
        }
    }
}

/** 两个外壳共用的选项行本体：扩展着色标签 + 触觉反馈，一点即答（无确认钮）。 */
@Composable
private fun SelectOptionRows(
    dialog: ExtensionDialog,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    dialog.options.forEachIndexed { index, option ->
        if (index > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(PiSpacing.hairlineThin)
                    .background(PiTheme.palette.borderMuted.copy(alpha = 0.45f)),
            )
        }
        SelectOptionRow(dialog.id, option, onAnswer)
    }
}

@Composable
private fun SelectOptionRow(
    dialogId: String,
    option: String,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onAnswer(dialogId, ExtensionAnswer.Value(option))
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .border(1.5.dp, PiTheme.palette.accent.copy(alpha = 0.55f), CircleShape),
        )
        ExtensionSpans(
            spans = remember(option) { chromeSpans(option) },
            defaultColor = PiTheme.palette.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            richStyle = true,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = PiTheme.palette.dim,
            modifier = Modifier.size(16.dp),
        )
    }
}

// -------------------------------------------------------------- input / editor

/** Single line: v2's 38-high field, `padding:0 10px`, radius 9. */
@Composable
private fun InputBody(
    dialog: ExtensionDialog,
    value: String,
    onValueChange: (String) -> Unit,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    Spacer(Modifier.height(DialogControlGap))
    ExtField(
        value = value,
        onValueChange = onValueChange,
        placeholder = dialog.placeholder ?: tr("输入内容", "Enter text"),
        singleLine = true,
        height = FieldHeight,
        paddingHorizontal = 10.dp,
        paddingVertical = 0.dp,
        onSubmit = { onAnswer(dialog.id, ExtensionAnswer.Value(value)) },
    )
}

// ------------------------------------------------------------------- shared

/**
 * pi's timed dialogs show the remaining seconds in the title — "Title (5s)" in
 * `docs/extensions.md` §"Timed Dialogs with Countdown" — and the app copies that
 * because the title is the one line the user is already reading.
 *
 * The title is monospace, as `phone55`–`phone58` draw it: an extension supplies
 * this string, so it is machine text like every other string a machine emitted
 * (`05 §3.10`). It uses `PiTheme.text.mono`, the app's one machine role (13 sp,
 * where the board's `mono` class sits at 14) — the role is used unmodified so the
 * user's font-scale setting still moves it, and `B7` owns the mono scale itself.
 * The `!` is the warning half of `06 §4`'s 三重编码 — every one of the four types
 * is a request waiting on the user, which is what `!` means here.
 */
@Composable
private fun DialogHeading(dialog: ExtensionDialog) {
    val palette = PiTheme.palette
    val (icon, badgeTint) = when (dialog.method) {
        ExtensionDialogMethod.Confirm -> Icons.Filled.Warning to palette.warning
        ExtensionDialogMethod.Select -> Icons.AutoMirrored.Filled.List to palette.accent
        ExtensionDialogMethod.Input -> Icons.Filled.Edit to palette.accent
        ExtensionDialogMethod.Editor -> Icons.Filled.Edit to palette.accent
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(badgeTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = badgeTint,
                modifier = Modifier.size(16.dp),
            )
        }
        val titleText = dialog.title.ifBlank { dialog.method.fallbackTitle }
        ExtensionSpans(
            spans = remember(titleText) { chromeSpans(titleText) },
            defaultColor = palette.text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            richStyle = true,
        )
        dialog.remainingSeconds()?.let { secs ->
            Text(
                text = "${secs}s",
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.warning.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                color = palette.warning,
                maxLines = 1,
            )
        }
    }
    if (dialog.timed) CountdownRow(dialog)
}

@Composable
private fun CountdownRow(dialog: ExtensionDialog) {
    val palette = PiTheme.palette
    val total = (dialog.timeoutMs ?: 1L).coerceAtLeast(1L)
    val remaining = (dialog.remainingMs ?: total).coerceIn(0L, total)
    val fraction = (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Spacer(Modifier.height(DialogControlGap))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(palette.borderMuted.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(CircleShape)
                .background(palette.warning),
        )
    }
}

@Composable
private fun DialogMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(DialogControlGap))
    // 限制消息区最大高度并支持内部滚动与划词复制，防止扩展传入几十行预览/命令把「拒绝/允许」按钮顶出屏幕。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SelectionContainer {
            ExtensionSpans(
                spans = remember(message) { chromeSpans(message) },
                defaultColor = PiTheme.palette.text,
                style = MaterialTheme.typography.bodyMedium,
                richStyle = true,
            )
        }
    }
}

/**
 * The button row, right-aligned with a `6` gap: `拒绝 允许` for a confirm,
 * `取消 确定` for the two editors, and only `取消` for a select (one tap on an
 * option *is* the answer — see [SelectBody]).
 */
@Composable
private fun DialogActions(
    dialog: ExtensionDialog,
    draft: String,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    // 按钮用共享构件（`ui/components/PiDialog.kt` 的 `PiDialogAction`）：v2 的对话框
    // 按钮只有一套取值（`padding:7px 12px`、圆角 8、主按钮 accent + 600）。
    PiDialogActions {
        when (dialog.method) {
            ExtensionDialogMethod.Select -> PiDialogAction(
                label = tr("取消", "Cancel"),
                primary = false,
                onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
            )

            ExtensionDialogMethod.Confirm -> {
                PiDialogAction(
                    label = tr("拒绝", "Decline"),
                    primary = false,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(false)) },
                )
                PiDialogAction(
                    label = tr("允许", "Allow"),
                    primary = true,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(true)) },
                )
            }

            ExtensionDialogMethod.Input,
            ExtensionDialogMethod.Editor,
            -> {
                PiDialogAction(
                    label = tr("取消", "Cancel"),
                    primary = false,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
                )
                PiDialogAction(
                    label = tr("确定", "OK"),
                    primary = true,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Value(draft)) },
                )
            }
        }
    }
}

/**
 * The footer: `还有 N 个扩展对话框在排队` and the timeout sentence, in the app's
 * `meta` role at `muted` (the board's `t12`; `meta` is the same role one step
 * under it and `B7` owns that scale).
 *
 * Both are the product's own strings (`02-real-content.md` §6.3) and both are about
 * the same thing — what happens to a request the user has not answered yet — so
 * they are one paragraph (`phone55`/`phone57`), not two notes. A dialog that is
 * neither queued nor timed gets no footer at all.
 */
@Composable
private fun DialogFooter(dialog: ExtensionDialog, backlog: Int) {
    val parts = buildList {
        if (backlog > 0) {
            add(tr("还有 $backlog 个扩展对话框在排队", "$backlog more extension dialog(s) queued"))
        }
        if (dialog.timed) {
            add(
                tr(
                    "超时后${dialog.method.timeoutVerb}，晚到的回复会被丢弃。",
                    "Will ${dialog.method.timeoutVerb} on timeout; late answers are ignored.",
                ),
            )
        }
    }
    if (parts.isEmpty()) return
    Spacer(Modifier.height(DialogControlGap))
    Text(
        text = parts.joinToString(tr("。", " ")),
        style = PiTheme.text.meta,
        color = PiTheme.palette.muted,
    )
}

// --------------------------------------------------------------- the field

/**
 * The one text field both `input` and `editor` use: v2 draws them as the same
 * box — radius 9, `borderAccent` while focused, the `surf-low` fill, a monospace
 * body for `editor` and the text face for `input`.
 *
 * `BasicTextField` rather than M3's `TextField` because the shell's numbers
 * (`padding:0 10px`, a 38-high single line, a 10/12-padded monospace block) are not
 * M3 slots. The border follows focus: `phone56` captures the field focused, and
 * drawing the focus ring on a field the keyboard is not attached to would claim a
 * focus the user does not have — which is also why the field **asks** for that
 * focus when it appears (`PiAutoFocus`): a request arrives with the keyboard up,
 * and the user's next tap is the answer, not a tap into the field.
 *
 * The editor's vertical scroll is M3-free too: `minLines 4` / `maxLines 10` is the
 * existing behaviour and the field scrolls internally once the text passes ten
 * lines.
 *
 * [placeholder] is drawn with the extension's colour; [value] is **not**, because
 * `BasicTextField` takes one `String` and that string is the answer that goes back
 * to pi on confirm. Painting it would mean either editing a payload or splitting
 * the value from what is displayed, and a draft the user is editing is not a
 * label — so escapes in a `prefill` reach the field verbatim, exactly as they
 * reach pi's own editor.
 */
@Composable
private fun ExtField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    height: Dp?,
    paddingHorizontal: Dp,
    paddingVertical: Dp,
    onSubmit: () -> Unit,
    minLinesOverride: Int = EditorMinLines,
    maxLinesOverride: Int = EditorMaxLines,
    inSheet: Boolean = false,
) {
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    PiAutoFocus(focusRequester)
    val shape = RoundedCornerShape(FieldRadius)
    val fieldBg = if (inSheet) {
        app.pi.ui.components.PiSheetChromeMetrics.sheetCardBg
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(fieldBg)
            .border(
                width = 1.dp,
                color = if (focused) palette.borderAccent else palette.borderMuted.copy(alpha = 0.7f),
                shape = shape,
            )
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = (if (singleLine) MaterialTheme.typography.bodyMedium else PiTheme.text.mono)
                .copy(color = palette.text),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLinesOverride,
            maxLines = if (singleLine) 1 else maxLinesOverride,
            cursorBrush = SolidColor(palette.accent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    ExtensionSpans(
                        spans = remember(placeholder) { chromeSpans(placeholder) },
                        defaultColor = palette.muted,
                        style = if (singleLine) MaterialTheme.typography.bodyMedium else PiTheme.text.mono,
                        maxLines = 1,
                    )
                }
                inner()
            },
        )
        if (singleLine && value.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            val clearLabel = tr("清空", "Clear")
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = clearLabel) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = clearLabel,
                    tint = palette.dim,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private val DialogControlGap = 10.dp
private val DialogListRadius = 12.dp
private val DialogListMaxHeight = 360.dp
private val FieldHeight = 44.dp
private val FieldRadius = 12.dp
private const val EditorMinLines = 4
private const val EditorMaxLines = 10
