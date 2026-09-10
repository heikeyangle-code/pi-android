package app.pi.ui.extension

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                .padding(start = 12.dp, end = 12.dp, bottom = snackbarBottomPadding),
        ) { data ->
            val palette = PiTheme.palette
            // Tones map onto the palette the transcript already uses for the
            // same three states, so a warning looks the same wherever it appears.
            val container = when (shownTone) {
                Notice.Tone.Info -> palette.cardBg
                Notice.Tone.Warning -> palette.infoBg
                Notice.Tone.Error -> palette.toolErrorBg
            }
            val content = when (shownTone) {
                Notice.Tone.Info -> palette.text
                Notice.Tone.Warning -> palette.warning
                Notice.Tone.Error -> palette.error
            }
            Snackbar(
                snackbarData = data,
                containerColor = container,
                contentColor = content,
                actionColor = palette.accent,
            )
        }

        ExtensionDialogHost(
            dialog = state.extensionDialog,
            backlog = state.extensionDialogBacklog,
            onAnswer = { id, answer -> session.answerDialog(id, answer) },
        )
    }
}
