package app.pi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.pi.ui.theme.PiTheme

/**
 * The one dialog shell the app draws, to v2's `.b-dlg`
 * (`design-demos/direction-b-v2.html:152`, used by every dialog on the board:
 * `phone24`, `phone25`, `phone39`, `phone45`, `phone55`–`phone58`).
 *
 * ## Why this file exists
 *
 * Two dialog shells were in use and they disagreed on the one value that is
 * visible on every dialog: the background. The settings shell
 * (`PiSettingsDialog`, an M3 `AlertDialog`) painted `surfaceContainerLow`
 * (`--surf-low`), the extension shell (a hand-drawn `Dialog` + `Surface`) painted
 * `surfaceContainerHigh` (`--surf-high`). The board is unambiguous — `.b-dlg` is
 * `background:var(--surf-high)` with a `1px solid var(--border-muted)` border — so
 * the settings shell was the wrong one, and the two call sites now share this
 * shell instead of each carrying their own numbers.
 *
 * ## What it draws, and why it is not an `AlertDialog`
 *
 * `06-v2-construction-reference.md` §2 「对话框：最大宽 330、圆角 14、
 * `padding:18px 16px 12px`」plus `.b-dlgwrap`'s `padding:22px`, and §2's
 * 「scrim 一律 `rgba(0,0,0,.32)`」. An M3 `AlertDialog` cannot carry three of those:
 *
 *  - it has **no** border slot, and `Modifier.border` on its `modifier` is drawn
 *    under its own `surface` background (the background modifier comes later in
 *    the chain), so the 1px rule v2 draws on every dialog is unreachable;
 *  - its width is content-driven inside the 280–560 M3 range, while v2's shell is
 *    always `min(330, screen - 44)` — 330 on a 412-wide phone, whatever the copy
 *    inside;
 *  - its buttons are M3 `TextButton`s (40dp tall, ripple), while v2's are a plain
 *    `padding:7px 12px; border-radius:8` text ([PiDialogAction]).
 *
 * The scrim is drawn here too, rather than left to the platform. A Compose
 * `Dialog`'s window dim is whatever the theme's `backgroundDimAmount` happens to
 * be (the activity theme is `@android:style/Theme.Material.NoActionBar`, and
 * `androidx.compose.ui.R.style.DialogWindowTheme` declares no parent), so relying
 * on it means the app's scrim is a value nobody chose. Drawing it makes it v2's
 * 0.32 and lets [PiDialog] state that; the window's own dim is cleared so the two
 * cannot stack.
 *
 * ## What it does not change
 *
 * No colour is invented: the surface is `surfaceContainerHigh`, the border is
 * `borderMuted`, the scrim is `colorScheme.scrim` at the board's 0.32, and the
 * action colours come from [PiDialogAction]. No shadow, as v2 requires.
 *
 * @param onDismissRequest a tap on the scrim or the back gesture. Callers that
 *   answer a request (the extension dialogs) must treat it as a real answer, not
 *   as "close a window" — see `ExtensionDialogs.kt`.
 */
@Composable
fun PiDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // Spelled out because both halves are load-bearing: back and an outside tap
        // must dismiss, and the platform's own width (280–560, content sized) must
        // not constrain the 330 the board draws.
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        ClearPlatformDim()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = PiDialogScrimAlpha))
                // A tap anywhere on the scrim dismisses, which is the half of
                // `dismissOnClickOutside` the platform cannot do once the window is
                // full width.
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismissRequest,
                )
                .padding(PiDialogWrapPadding),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = modifier
                    .widthIn(max = PiDialogMaxWidth)
                    .fillMaxWidth()
                    // Swallows taps so a tap inside the shell is not read as a tap
                    // outside it. No ripple and no semantics: this box is not a
                    // control.
                    .clickable(interactionSource = null, indication = null) {},
                shape = PiDialogShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(PiDialogHairline, PiTheme.palette.borderMuted),
            ) {
                Column(modifier = Modifier.padding(PiDialogContentPadding), content = content)
            }
        }
    }
}

/**
 * v2's dialog title: `t15 w6` (`direction-b-v2.html:692` — the `Dialog` heading
 * row), with the optional leading glyph the danger/approval dialogs carry at
 * `gap:7`.
 */
@Composable
fun PiDialogTitle(
    title: String,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    glyphTone: Color = PiTheme.palette.muted,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PiDialogTitleGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Text(
                text = glyph,
                style = PiTheme.text.mono,
                color = glyphTone,
            )
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * v2's dialog body paragraph: `t14 c-text`, `margin-top:10`, `line-height:1.62`
 * (`direction-b-v2.html:694`).
 */
@Composable
fun PiDialogBody(text: String, modifier: Modifier = Modifier) {
    Spacer(Modifier.height(PiDialogControlGap))
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The row of dialog actions: right-aligned, `gap:6`, `margin-top:14`
 * (`direction-b-v2.html:695`).
 */
@Composable
fun PiDialogActions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(PiDialogActionGap))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PiDialogActionGapInner, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/**
 * One dialog action: v2's `.press` (`direction-b-v2.html:700-702`) —
 * `padding:7px 12px`, `border-radius:8`, `t14`, `font-weight:600` and `accent` for
 * the primary one, `400` and the text colour for the secondary.
 *
 * [tone] is v2's own escape hatch for a primary that is not accent: the delete
 * confirmation paints 删除 `var(--error)` (`direction-b-v2.html:1964`), and its
 * refused (current-session) twin paints it `var(--muted)` (`:1963`).
 *
 * A **disabled** action is `muted`: `phone25` draws the refused delete that way
 * (the current session cannot be deleted), and muting it says "present, not
 * available" without turning it into a failure.
 *
 * The secondary action is deliberately not `error` for the same reason
 * `ExtensionDialogs` records: 拒绝 / 取消 is a legal answer.
 */
@Composable
fun PiDialogAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color? = null,
) {
    Text(
        text = label,
        modifier = modifier
            .clip(PiDialogActionShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                horizontal = PiDialogActionPaddingHorizontal,
                vertical = PiDialogActionPaddingVertical,
            ),
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = when {
            !enabled -> PiTheme.palette.muted
            tone != null -> tone
            primary -> PiTheme.palette.accent
            else -> PiTheme.palette.text
        },
    )
}

/**
 * Clears the platform's dialog dim, because [PiDialog] draws the board's own
 * 0.32 itself. Without this the two would stack, and the app would show a scrim
 * darker than any value in the design.
 */
@Composable
private fun ClearPlatformDim() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
    }
}

/**
 * Puts the caret in a dialog's / sheet's text field and raises the keyboard.
 *
 * v2 draws both text-bearing surfaces focused — `phone41`'s number field and
 * `phone42`'s text field carry a caret and the `borderAccent` ring, `phone56`'s
 * extension `input` and `phone58`'s `editor` the same — so a field the user opened
 * the surface *for* must already have the keys up. The alternative (tap the field
 * first) is one extra tap on every single edit.
 *
 * The request waits one frame: a `Dialog` / `ModalBottomSheet` window is not
 * focusable in the frame its content is composed in, and a `requestFocus()` issued
 * then is dropped. `keyboard?.show()` is nullable for the same reason
 * `ChatScreen`'s dismissal is (`ChatScreen.kt:229-236`): a device with no software
 * keyboard has nothing to raise, and the `?.` says so.
 *
 * @param requester the same instance attached with `Modifier.focusRequester`.
 */
@Composable
fun PiAutoFocus(requester: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        keyboard?.show()
    }
}

// ------------------------------------------------------------- the board's numbers
//
// Literals here rather than members of a theme object, cited one by one, so this
// component carries its own evidence (`06 §2` 的「对话框」一行 + `.b-dlg`).

/** `06 §2`「对话框：最大宽 330」。 */
private val PiDialogMaxWidth = 330.dp

/** `06 §2`「圆角 14」。 */
private val PiDialogShape = RoundedCornerShape(14.dp)

/** `.b-dlgwrap`：`padding:22px`，也就是对话框离屏边的最小距离。 */
private val PiDialogWrapPadding = 22.dp

/** `06 §2`「`padding:18px 16px 12px`」：上 / 左右 / 下三档各一个数。 */
private val PiDialogPaddingTop = 18.dp
private val PiDialogPaddingHorizontal = 16.dp
private val PiDialogPaddingBottom = 12.dp
private val PiDialogContentPadding = PaddingValues(
    start = PiDialogPaddingHorizontal,
    end = PiDialogPaddingHorizontal,
    top = PiDialogPaddingTop,
    bottom = PiDialogPaddingBottom,
)

/** `.b-scrim`：`rgba(0,0,0,.32)`。 */
private const val PiDialogScrimAlpha = 0.32f

/** `06 §2`「线宽：全篇只有 1px」。 */
private val PiDialogHairline = 1.dp

/** `.b-dlg` 标题行 `gap:7`。 */
private val PiDialogTitleGap = 7.dp

/** `.b-dlg` 正文/控件距上一行的 10，以及动作行上方 14、动作之间 6。 */
private val PiDialogControlGap = 10.dp
private val PiDialogActionGap = 14.dp
private val PiDialogActionGapInner = 6.dp

/** `.press` 按钮：`padding:7px 12px`、`border-radius:8`。 */
private val PiDialogActionPaddingHorizontal = 12.dp
private val PiDialogActionPaddingVertical = 7.dp
private val PiDialogActionShape = RoundedCornerShape(8.dp)
