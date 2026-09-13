package app.pi.ui.extension

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.pi.ui.theme.PiTheme
import app.pi.ui.theme.PiV2Layout
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
 * shadow. The board draws this shell itself (`.b-dlg`) rather than through a
 * platform dialog theme, and two of the numbers have no M3 slot — the 18/16/12
 * padding and the footer that sits *below* the button row — so the shell is a plain
 * `Dialog` plus a `Surface`. `Dialog`'s own platform scrim is the board's
 * `rgba(0,0,0,.32)` (`PiSettingsStyle` records the same 0.32 for the M3 dialogs);
 * drawing a second one on top would double it.
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
    Dialog(
        onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
        // Both are the defaults, spelled out because they are load-bearing: the
        // back gesture must answer `cancelled` (pi treats it as Escape), and a tap
        // outside must do the same rather than leave the request unanswered.
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        ExtensionDialogShell(dialog, backlog, onAnswer)
    }
}

// ------------------------------------------------------------------- the shell

@Composable
private fun ExtensionDialogShell(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    // Keyed on the id so a promoted request starts empty instead of carrying the
    // previous extension's answer over.
    var draft by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }

    Surface(
        // `width:100%; max-width:330px` — the cap first, then fill, so a screen
        // narrower than 330 still gets the full width it has.
        modifier = Modifier
            .widthIn(max = DialogMaxWidth)
            .fillMaxWidth(),
        shape = RoundedCornerShape(DialogRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(PiV2Layout.hairline, palette.borderMuted),
    ) {
        Column(
            modifier = Modifier.padding(
                start = DialogPaddingHorizontal,
                end = DialogPaddingHorizontal,
                top = DialogPaddingTop,
                bottom = DialogPaddingBottom,
            ),
        ) {
            DialogHeading(dialog)
            DialogMessage(dialog.message)

            when (dialog.method) {
                ExtensionDialogMethod.Select -> SelectBody(dialog, onAnswer)
                ExtensionDialogMethod.Input -> InputBody(dialog, draft, { draft = it }, onAnswer)
                ExtensionDialogMethod.Editor -> EditorBody(dialog, draft, { draft = it }, onAnswer)
                ExtensionDialogMethod.Confirm -> Unit
            }

            Spacer(Modifier.height(DialogActionGap))
            DialogActions(dialog, draft, onAnswer)
            DialogFooter(dialog, backlog)
        }
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
            "这个扩展没有给出任何选项。",
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
            .border(PiV2Layout.hairline, palette.borderMuted, shape),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            dialog.options.forEachIndexed { index, option ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PiV2Layout.hairline)
                            .background(palette.borderMuted),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            onAnswer(dialog.id, ExtensionAnswer.Value(option))
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "○",
                        style = PiTheme.text.monoSmall,
                        color = palette.muted,
                    )
                    Text(
                        text = option,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.text,
                    )
                }
            }
        }
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
        placeholder = dialog.placeholder ?: "输入内容",
        singleLine = true,
        height = FieldHeight,
        paddingHorizontal = 10.dp,
        paddingVertical = 0.dp,
        onSubmit = { onAnswer(dialog.id, ExtensionAnswer.Value(value)) },
    )
}

// -------------------------------------------------------------------- editor

/** Multi-line, monospace: v2's `mono t13` box with `padding:10px 12px`. */
@Composable
private fun EditorBody(
    dialog: ExtensionDialog,
    value: String,
    onValueChange: (String) -> Unit,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    Spacer(Modifier.height(DialogControlGap))
    ExtField(
        value = value,
        onValueChange = onValueChange,
        placeholder = dialog.placeholder ?: "编辑内容",
        singleLine = false,
        height = null,
        paddingHorizontal = 12.dp,
        paddingVertical = 10.dp,
        // Enter inserts a newline in a multi-line editor, so the IME's done action
        // is not wired for it — only the 确定 button answers.
        onSubmit = {},
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "!",
            style = PiTheme.text.mono,
            color = palette.warning,
        )
        Text(
            text = buildString {
                append(dialog.title.ifBlank { dialog.method.fallbackTitle })
                dialog.remainingSeconds()?.let { append("（${it} 秒）") }
            },
            modifier = Modifier.weight(1f),
            style = PiTheme.text.mono,
            color = palette.text,
        )
    }
    if (dialog.timed) CountdownRow(dialog)
}

/**
 * The live countdown: ten `3`-high segments, `1` apart, rounded 1.
 *
 * Derived from `remainingMs` on every published tick rather than animated, so it is
 * truthful: when pi's own timer fires first the dialog still disappears at the
 * moment the ViewModel answers. Every filled segment is `warning` — the same tone
 * as the `!` beside the title — rather than an accent bar that turns yellow near
 * the deadline: the readable thing is *how much is left*, and one colour reads as
 * one scale (`06 §2` 审批卡 gives the same 10×3 countdown one colour).
 */
@Composable
private fun CountdownRow(dialog: ExtensionDialog) {
    val palette = PiTheme.palette
    val total = (dialog.timeoutMs ?: 1L).coerceAtLeast(1L)
    val remaining = (dialog.remainingMs ?: total).coerceIn(0L, total)
    val filled = ((remaining.toFloat() / total.toFloat()) * CountdownSegments)
        .roundToInt()
        .coerceIn(0, CountdownSegments)
    Spacer(Modifier.height(DialogControlGap))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(CountdownSegments) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(CountdownSegmentHeight)
                    .background(
                        color = if (index < filled) palette.warning else palette.borderMuted,
                        shape = RoundedCornerShape(CountdownSegmentRadius),
                    ),
            )
        }
    }
}

@Composable
private fun DialogMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(DialogControlGap))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = PiTheme.palette.text,
    )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (dialog.method) {
            ExtensionDialogMethod.Select -> DialogAction("取消", primary = false) {
                onAnswer(dialog.id, ExtensionAnswer.Cancelled)
            }

            ExtensionDialogMethod.Confirm -> {
                DialogAction("拒绝", primary = false) {
                    onAnswer(dialog.id, ExtensionAnswer.Confirmed(false))
                }
                DialogAction("允许", primary = true) {
                    onAnswer(dialog.id, ExtensionAnswer.Confirmed(true))
                }
            }

            ExtensionDialogMethod.Input,
            ExtensionDialogMethod.Editor,
            -> {
                DialogAction("取消", primary = false) {
                    onAnswer(dialog.id, ExtensionAnswer.Cancelled)
                }
                DialogAction("确定", primary = true) {
                    onAnswer(dialog.id, ExtensionAnswer.Value(draft))
                }
            }
        }
    }
}

/**
 * One dialog button: `padding:7px 12px`, radius 8, `14`; the primary one is
 * `accent` at weight 600, the secondary is the text colour.
 *
 * The secondary is deliberately *not* `error`: 拒绝/取消 is a legal answer with the
 * same standing as the primary one, and painting it red would make refusing look
 * like a destructive act (pi's own TUI draws both as plain menu entries).
 */
@Composable
private fun DialogAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val palette = PiTheme.palette
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(DialogActionRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (primary) palette.accent else palette.text,
    )
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
        if (backlog > 0) add("还有 $backlog 个扩展对话框在排队")
        if (dialog.timed) add("超时后${dialog.method.timeoutVerb}，晚到的回复会被丢弃。")
    }
    if (parts.isEmpty()) return
    Spacer(Modifier.height(DialogControlGap))
    Text(
        text = parts.joinToString("。"),
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
 * focus the user does not have.
 *
 * The editor's vertical scroll is M3-free too: `minLines 4` / `maxLines 10` is the
 * existing behaviour and the field scrolls internally once the text passes ten
 * lines.
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
) {
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(FieldRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = PiV2Layout.hairline,
                color = if (focused) palette.borderAccent else palette.borderMuted,
                shape = shape,
            )
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = (if (singleLine) MaterialTheme.typography.bodyMedium else PiTheme.text.mono)
                .copy(color = palette.text),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else EditorMinLines,
            maxLines = if (singleLine) 1 else EditorMaxLines,
            cursorBrush = SolidColor(palette.accent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        maxLines = 1,
                        style = if (singleLine) MaterialTheme.typography.bodyMedium else PiTheme.text.mono,
                        color = palette.muted,
                    )
                }
                inner()
            },
        )
    }
}

// -------------------------------------------------------------- the board's numbers

/**
 * `06 §2`「对话框：最大宽 330、圆角 14、`padding:18px 16px 12px`」and the board's
 * button/field/list geometry (`.b-dlg`, `.press` buttons, `select` container).
 *
 * Literals in this file rather than members of a theme object because `ui/theme/`
 * belongs to another batch in this same round; they are the board's own numbers,
 * each cited below.
 */
private val DialogMaxWidth = 330.dp
private val DialogRadius = 14.dp
private val DialogPaddingHorizontal = 16.dp
private val DialogPaddingTop = 18.dp
private val DialogPaddingBottom = 12.dp

/** The gap every control and the footer keep from the line above them. */
private val DialogControlGap = 10.dp

/** `.b-dlg` actions row: `gap:6; margin-top:14`. */
private val DialogActionGap = 14.dp
private val DialogActionRadius = 8.dp

/** `select`'s container: `border-radius:10`, rows `padding:10px 12px`. */
private val DialogListRadius = 10.dp
private val DialogListMaxHeight = 360.dp

/** `input`'s row `height:38`; both fields' `border-radius:9`. */
private val FieldHeight = 38.dp
private val FieldRadius = 9.dp

/** `editor`'s `minLines 4 / maxLines 10` (the file's pre-existing behaviour). */
private const val EditorMinLines = 4
private const val EditorMaxLines = 10

/** `06 §2` 审批卡倒计时: `10 段（3px 高、段间 1px）`, rounded 1. */
private const val CountdownSegments = 10
private val CountdownSegmentHeight = 3.dp
private val CountdownSegmentRadius = 1.dp
