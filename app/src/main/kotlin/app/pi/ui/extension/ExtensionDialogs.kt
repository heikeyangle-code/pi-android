package app.pi.ui.extension

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiTheme

/**
 * Material 3 renderings of pi's four blocking dialog methods.
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
 * Colours come from [PiTheme.palette] only.
 */
@Composable
fun ExtensionDialogHost(
    dialog: ExtensionDialog?,
    backlog: Int = 0,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    if (dialog == null) return
    when (dialog.method) {
        ExtensionDialogMethod.Select -> SelectDialog(dialog, backlog, onAnswer)
        ExtensionDialogMethod.Confirm -> ConfirmDialog(dialog, backlog, onAnswer)
        ExtensionDialogMethod.Input -> InputDialog(dialog, backlog, onAnswer)
        ExtensionDialogMethod.Editor -> EditorDialog(dialog, backlog, onAnswer)
    }
}

// ------------------------------------------------------------------- select

/**
 * A selection list, not a menu: pi's TUI renders select as a focusable list
 * (`docs/tui.md` §SelectList) and one tap must answer, so there is no confirm
 * button — only 取消.
 */
@Composable
private fun SelectDialog(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    AlertDialog(
        onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
        title = { DialogHeading(dialog, backlog) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 360.dp)) {
                DialogMessage(dialog.message)
                if (dialog.options.isEmpty()) {
                    Text(
                        "这个扩展没有给出任何选项。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.warning,
                    )
                }
                dialog.options.forEach { option ->
                    Surface(
                        // `selectedBg` is pi's selection surface; each row is a
                        // potential selection, so in a list with no current
                        // selection it is the honest resting colour.
                        color = palette.selectedBg.copy(alpha = 0.45f),
                        shape = PiShapes.cardInner,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { onAnswer(dialog.id, ExtensionAnswer.Value(option)) },
                    ) {
                        Text(
                            text = option,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) { Text("取消") }
        },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
    )
}

// ------------------------------------------------------------------ confirm

/**
 * Yes/no. pi does not distinguish "No" from Escape for a confirm — both resolve
 * the extension's promise with `false` (`rpc-mode.ts` maps `cancelled` to
 * `false`), which is why 拒绝 and the back gesture are allowed to differ on the
 * wire while behaving identically to the extension.
 */
@Composable
private fun ConfirmDialog(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    AlertDialog(
        onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
        title = { DialogHeading(dialog, backlog) },
        text = {
            Column {
                DialogMessage(dialog.message)
                TimeoutNote(dialog)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(true)) }) { Text("允许") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Confirmed(false)) }) { Text("拒绝") }
        },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
    )
}

// -------------------------------------------------------------------- input

@Composable
private fun InputDialog(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    // Keyed on the id so the text field resets when the queue promotes the next
    // request instead of carrying the previous answer over.
    var value by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
        title = { DialogHeading(dialog, backlog) },
        text = {
            Column {
                DialogMessage(dialog.message)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(dialog.placeholder ?: "输入内容") },
                    singleLine = true,
                    shape = PiShapes.inputMultiline,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAnswer(dialog.id, ExtensionAnswer.Value(value)) }),
                    colors = fieldColors(),
                )
                TimeoutNote(dialog)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Value(value)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) { Text("取消") }
        },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
    )
}

// ------------------------------------------------------------------- editor

/**
 * Multi-line editor. pi's `editor()` promise has no timeout at all
 * (`rpc-mode.ts` ignores timeout options for `editor`), so [ExtensionDialog.timeoutMs]
 * is null for these in practice and the countdown never appears.
 */
@Composable
private fun EditorDialog(
    dialog: ExtensionDialog,
    backlog: Int,
    onAnswer: (String, ExtensionAnswer) -> Unit,
) {
    val palette = PiTheme.palette
    var value by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) },
        title = { DialogHeading(dialog, backlog) },
        text = {
            Column {
                DialogMessage(dialog.message)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(dialog.placeholder ?: "编辑内容") },
                    minLines = 4,
                    maxLines = 10,
                    shape = PiShapes.inputMultiline,
                    colors = fieldColors(),
                )
                TimeoutNote(dialog)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Value(value)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(dialog.id, ExtensionAnswer.Cancelled) }) { Text("取消") }
        },
        containerColor = palette.cardBg,
        titleContentColor = palette.text,
        textContentColor = palette.muted,
    )
}

// ------------------------------------------------------------------- shared

/**
 * pi's timed dialogs show the remaining seconds in the title — "Title (5s)" in
 * `docs/extensions.md` §"Timed Dialogs with Countdown" — and the app copies that
 * because the title is the one line the user is already reading.
 */
@Composable
private fun DialogHeading(dialog: ExtensionDialog, backlog: Int) {
    val palette = PiTheme.palette
    Column {
        Text(
            text = buildString {
                append(dialog.title.ifBlank { dialog.method.fallbackTitle })
                dialog.remainingSeconds()?.let { append("（${it} 秒）") }
            },
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
        )
        if (backlog > 0) {
            Text(
                text = "还有 $backlog 个扩展对话框在排队",
                style = PiTheme.text.meta,
                color = palette.muted,
            )
        }
        if (dialog.timed) CountdownBar(dialog)
    }
}

/**
 * The live countdown bar. Its colour warns as the deadline approaches, and it is
 * derived from `remainingMs` on every published tick, so it is truthful rather
 * than an animation: when pi's own timer fires first the dialog still disappears
 * at the moment the ViewModel answers.
 */
@Composable
private fun CountdownBar(dialog: ExtensionDialog) {
    val palette = PiTheme.palette
    val total = (dialog.timeoutMs ?: 1L).coerceAtLeast(1L)
    val remaining = (dialog.remainingMs ?: total).coerceIn(0L, total)
    val fraction = remaining.toFloat() / total.toFloat()
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        color = if (remaining <= TIMEOUT_WARNING_MS) palette.warning else palette.accent,
        trackColor = palette.borderMuted.copy(alpha = 0.35f),
    )
}

@Composable
private fun TimeoutNote(dialog: ExtensionDialog) {
    if (!dialog.timed) return
    val palette = PiTheme.palette
    Spacer(Modifier.height(6.dp))
    Text(
        text = "超时后${dialog.method.timeoutVerb}：pi 侧同时计时，晚到的回复会被丢弃。",
        style = PiTheme.text.meta,
        color = palette.muted,
    )
}

@Composable
private fun DialogMessage(message: String?) {
    if (message.isNullOrBlank()) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = PiTheme.palette.text,
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PiTheme.palette.accent,
    unfocusedBorderColor = PiTheme.palette.borderMuted,
    cursorColor = PiTheme.palette.accent,
)

/** Last stretch of a countdown, where the bar switches to `warning`. */
private const val TIMEOUT_WARNING_MS = 3_000L
