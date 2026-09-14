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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.components.PiAutoFocus
import app.pi.ui.components.PiDialog
import app.pi.ui.components.PiDialogAction
import app.pi.ui.components.PiDialogActions
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
            ExtensionDialogMethod.Editor -> EditorBody(dialog, draft, { draft = it }, onAnswer)
            ExtensionDialogMethod.Confirm -> Unit
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
            .border(PiSpacing.hairline, palette.borderMuted, shape),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            dialog.options.forEachIndexed { index, option ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(PiSpacing.hairline)
                            .background(palette.borderMuted),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            // The label is *painted* with the extension's colour but
                            // *answered* plain: `buildAnswer` strips escapes from a
                            // select answer, so a colour the extension wrapped its
                            // own option in cannot come back as the user's choice.
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
                    ExtensionSpans(
                        spans = chromeSpans(option),
                        defaultColor = palette.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
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
        // The heading is chrome, so it keeps whatever colour the extension gave
        // the title. The countdown suffix is appended *after* the title text, and
        // pi's `theme.fg` closes every coloured run with `\x1b[39m`
        // (`theme.ts:326`), so the suffix parses as its own uncoloured span and
        // takes `palette.text` rather than borrowing the title's colour.
        val label = buildString {
            append(dialog.title.ifBlank { dialog.method.fallbackTitle })
            // `<title> (5s)` —— pi 自己的写法（`docs/extensions.md` §"Timed
            // Dialogs with Countdown"、`02-real-content.md:572`），v2 的
            // `phone13` / `phone57` 也照抄。原先这里写的是「（5 秒）」，与稿子和
            // pi 的原文都不一致。
            dialog.remainingSeconds()?.let { append(" (").append(it).append("s)") }
        }
        ExtensionSpans(
            spans = chromeSpans(label),
            defaultColor = palette.text,
            style = PiTheme.text.mono,
            modifier = Modifier.weight(1f),
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
    // `palette.text` when the extension coloured nothing. Note the guard above
    // tests the *raw* string, so a message that is only colour escapes is not
    // blank here and reserves its gap — which is what pi draws for it too.
    ExtensionSpans(
        spans = chromeSpans(message),
        defaultColor = PiTheme.palette.text,
        style = MaterialTheme.typography.bodyMedium,
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
    // 按钮用共享构件（`ui/components/PiDialog.kt` 的 `PiDialogAction`）：v2 的对话框
    // 按钮只有一套取值（`padding:7px 12px`、圆角 8、主按钮 accent + 600）。
    PiDialogActions {
        when (dialog.method) {
            ExtensionDialogMethod.Select -> PiDialogAction(
                label = "取消",
                primary = false,
                onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
            )

            ExtensionDialogMethod.Confirm -> {
                PiDialogAction(
                    label = "拒绝",
                    primary = false,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(false)) },
                )
                PiDialogAction(
                    label = "允许",
                    primary = true,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(true)) },
                )
            }

            ExtensionDialogMethod.Input,
            ExtensionDialogMethod.Editor,
            -> {
                PiDialogAction(
                    label = "取消",
                    primary = false,
                    onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
                )
                PiDialogAction(
                    label = "确定",
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
) {
    val palette = PiTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    PiAutoFocus(focusRequester)
    val shape = RoundedCornerShape(FieldRadius)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                width = PiSpacing.hairline,
                color = if (focused) palette.borderAccent else palette.borderMuted,
                shape = shape,
            )
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
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
                    // A placeholder is chrome, so it keeps the extension's colour;
                    // `value` itself does not, and cannot — see this function's KDoc.
                    ExtensionSpans(
                        spans = chromeSpans(placeholder),
                        defaultColor = palette.muted,
                        style = if (singleLine) MaterialTheme.typography.bodyMedium else PiTheme.text.mono,
                        maxLines = 1,
                    )
                }
                inner()
            },
        )
    }
}

// -------------------------------------------------------------- the board's numbers

/**
 * `06 §2` 的对话框外壳在 `ui/components/PiDialog.kt`（330 / 圆角 14 / `padding:18px
 * 16px 12px` / surf-high / 1px borderMuted）；这里只剩扩展对话框自己的构件：动作行
 * 上方 14 的空隙、`select` 的容器、两种输入框的形状。
 *
 * Literals in this file rather than members of a theme object because `ui/theme/`
 * belongs to another batch in this same round; they are the board's own numbers,
 * each cited below.
 */

/** The gap every control and the footer keep from the line above them. */
private val DialogControlGap = 10.dp

/** `.b-dlg` actions row 的 `gap:6` 与 `margin-top:14` 由 `PiDialogActions` 画。 */

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
