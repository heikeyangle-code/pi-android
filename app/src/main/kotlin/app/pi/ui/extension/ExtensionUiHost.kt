package app.pi.ui.extension

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.rpc.Notice
import app.pi.ui.PiSessionViewModel
import app.pi.ui.theme.PiTheme

/**
 * The complete extension UI overlay: notification snackbars plus the blocking
 * dialog host, driven by [PiSessionViewModel]'s extension state.
 *
 * Mounted **exactly once**, by PiRoot, above whichever destination is active.
 * That single mount is load-bearing: this composable owns the only
 * `SnackbarHostState` and the only dialog instance for the pending request, so a
 * second mount inside a screen would render two dialogs for one request and let
 * two snackbar hosts race for the same notice queue. Destination screens must
 * therefore rely on PiRoot's mount rather than adding their own.
 *
 * [session] defaults to the Activity-scoped [viewModel]; PiRoot already creates
 * that same instance, so passing nothing reuses the one engine rather than
 * booting a second one. That equivalence holds only while the call site shares a
 * ViewModelStoreOwner — true today, and the reason the parameter exists at all.
 */
@Composable
fun ExtensionUiHost(
    session: PiSessionViewModel = viewModel(),
    // Full size by default: the snackbar is aligned to the bottom of this Box, so
    // a wrap-content default would anchor it to the top of the parent instead.
    modifier: Modifier = Modifier.fillMaxSize(),
    snackbarBottomPadding: Dp = 0.dp,
) {
    val state by session.state.collectAsState()
    val hostState = remember { SnackbarHostState() }

    // The tone of the message currently on screen. Kept outside the state flow
    // because it is presentation, not engine state: once the notice is consumed
    // the snackbar may still be animating out and must not change colour.
    var shownTone by remember { mutableStateOf(Notice.Tone.Info) }
    val head = state.notices.firstOrNull()

    // One snackbar at a time, oldest first. The effect is keyed on the sequence
    // and the notice is consumed only after it has actually been shown, so a
    // message is never silently dropped by a newer one arriving — `notify` is
    // fire-and-forget for pi, but losing it would still be a lie to the user.
    LaunchedEffect(head?.seq) {
        val current = head ?: return@LaunchedEffect
        shownTone = current.tone
        hostState.showSnackbar(
            message = current.message,
            actionLabel = if (current.tone == Notice.Tone.Error) "知道了" else null,
            duration = if (current.tone == Notice.Tone.Error) {
                SnackbarDuration.Long
            } else {
                SnackbarDuration.Short
            },
        )
        session.consumeNotice(current.seq)
    }

    Box(modifier) {
        SnackbarHost(
            hostState = hostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = SnackbarSideInset,
                    end = SnackbarSideInset,
                    // PiRoot's inset is the bottom bar (56) plus an 8 dp margin;
                    // the board puts the snackbar 14 above the bar, so the missing
                    // 6 is added here rather than by editing that shared constant.
                    bottom = snackbarBottomPadding + SnackbarBarGap,
                ),
        ) { data ->
            // The tone belongs to the message being drawn. `shownTone` alone was the
            // answer until the notice queue filled: the queue is capped
            // (`MAX_PENDING_NOTICES`) and the *oldest* entry is the one dropped, so a
            // ninth notice can make `head` the next one while the current snackbar is
            // still on screen — and the effect below writes `shownTone` for its own
            // notice, repainting the visible one in the wrong tone. Looking the tone up by
            // the message the host is actually drawing closes that, and `shownTone` stays
            // as the fallback for the frames after a notice was consumed, when the
            // snackbar is still animating out and must keep its colour.
            val tone = state.notices.firstOrNull { it.message == data.visuals.message }?.tone ?: shownTone
            ExtensionSnack(
                tone = tone,
                // `data.visuals.message` is pi's `notify` text verbatim: the tone
                // glyph is drawn beside it, never prepended to it.
                message = data.visuals.message,
                actionLabel = data.visuals.actionLabel,
                onAction = { data.performAction() },
            )
        }

        ExtensionDialogHost(
            dialog = state.extensionDialog,
            backlog = state.extensionDialogBacklog,
            onAnswer = { id, answer -> session.answerDialog(id, answer) },
        )
    }
}

/**
 * The three tones of `06 §4` / `phone52`–`phone54`: **symbol + word + colour**,
 * with the symbol carrying the tone.
 *
 *   Info    `·` cardBg + text
 *   Warning `!` infoBg + warning
 *   Error   `✗` toolErrorBg + error (always with 「知道了」)
 *
 * The *message* stays in the text colour in all three: `error` on `toolErrorBg`
 * measures ~3.6:1, under the 4.5:1 body floor `PiPalette` holds every other body
 * line to, so the colour lives on the one-glyph prefix — which is also the point of
 * the encoding, since the glyph survives a greyscale screenshot on its own.
 * That is the *default*: an extension that coloured its own sentence with
 * `ctx.ui.theme.fg(...)` keeps that colour, which is what it would get in pi.
 *
 * Built here rather than through M3's `Snackbar` because M3's layout has no slot
 * for a leading glyph, and the tone prefix is the whole change. The action is the
 * text colour at weight 500 (`phone54`), not the accent: the accent is the app's
 * *primary action* colour and a snackbar's 「知道了」 only dismisses.
 *
 * Both the glyph and the action use the app's nearest roles — `mono` (13, where the
 * board's glyph is 14) and `bodyMedium` (14/500, where its action is 13/500): the
 * app's sans scale has no 13, and `B7` owns the type scale.
 */
@Composable
private fun ExtensionSnack(
    tone: Notice.Tone,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val palette = PiTheme.palette
    val container = when (tone) {
        Notice.Tone.Info -> palette.cardBg
        Notice.Tone.Warning -> palette.infoBg
        Notice.Tone.Error -> palette.toolErrorBg
    }
    val glyphColor = when (tone) {
        Notice.Tone.Info -> palette.text
        Notice.Tone.Warning -> palette.warning
        Notice.Tone.Error -> palette.error
    }
    val glyph = when (tone) {
        Notice.Tone.Info -> "·"
        Notice.Tone.Warning -> "!"
        Notice.Tone.Error -> "✗"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SnackbarRadius))
            .background(container)
            .padding(horizontal = SnackbarPaddingHorizontal, vertical = SnackbarPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(SnackbarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = glyph,
            style = PiTheme.text.mono,
            color = glyphColor,
        )
        // `palette.text` is the default; a colour the extension asked for wins,
        // exactly as it would in pi's TUI. The contrast argument above is about
        // the *tone* colour, which the app chooses — not about a colour the
        // extension chose for its own sentence.
        ExtensionSpans(
            spans = chromeSpans(message),
            defaultColor = palette.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(SnackbarActionRadius))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = palette.text,
            )
        }
    }
}

// -------------------------------------------------------------- the board's numbers

/** `06 §2`「Snackbar：左右 12、距底 14、圆角 12、`padding:10px 12px` gap 10」. */
private val SnackbarSideInset = 12.dp

/** The missing part of 距底 14 once PiRoot's 64 dp (56 bar + 8) is counted. */
private val SnackbarBarGap = 6.dp
private val SnackbarRadius = 12.dp
private val SnackbarPaddingHorizontal = 12.dp
private val SnackbarPaddingVertical = 10.dp
private val SnackbarGap = 10.dp

/** `.b-dlg`-sized corner for the one action; keeps its ripple inside the label. */
private val SnackbarActionRadius = 8.dp
