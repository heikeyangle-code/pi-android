package app.pi.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import app.pi.host.ExtUiState
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.protocol.PiEvent
import app.pi.service.PiEngineLifecyclePolicy
import app.pi.ui.PiSessionViewModel.UiState
import app.pi.ui.extension.ComposerFill
import app.pi.ui.extension.ExtensionAnswer
import app.pi.ui.extension.ExtensionDialog
import app.pi.ui.extension.ExtensionDialogMethod
import app.pi.ui.extension.ExtensionDialogQueue
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.noticeToneOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Route one `extension_ui_request`.
 *
 * Four methods block the extension until this app replies (`docs/rpc.md`
 * §"Extension UI Protocol"), so they are queued and answered by id; every
 * other method is fire-and-forget and updates chrome. An unknown method is
 * ignored on purpose: `docs/rpc.md` lists the RPC-mode no-ops (`custom`,
 * `setWorkingMessage`, `setFooter`, ...) which pi toggles directly, and a
 * method from a newer pi must degrade to "not displayed", never to an error.
 */
internal fun PiSessionViewModel.onExtensionUi(request: PiEvent.ExtensionUiRequest) {
    val method = ExtensionDialogMethod.fromWire(request.method)
    if (method == null) {
        onExtensionChrome(request)
        return
    }

    // Every response is addressed by the request's id, so a request without
    // one can never be answered and showing a dialog for it would only hide
    // pi's hang behind a button that cannot work. Say so instead.
    if (request.uiId.isEmpty()) {
        pushNotice(
            message = tr("扩展发来一个没有编号的「${request.method}」对话框，无法回复；这个扩展的弹窗在当前引擎上无法使用。", "Extension sent a \"${request.method}\" dialog without a request ID and cannot be answered."),
            tone = Notice.Tone.Warning,
        )
        return
    }

    val dialog = ExtensionDialog(
        id = request.uiId,
        method = method,
        // Labels keep their bytes: an extension colours them with
        // `ctx.ui.theme.fg(...)`, whose SGR bytes are the only colour channel
        // the wire has (`rpc-types.ts:246-281` carries one plain string each).
        // The dialog host turns them back into colour with `chromeSpans` +
        // `PiPalette.tokenColorFor`; stripping here would destroy the colour
        // before anything could paint it.
        title = request.title.orEmpty(),
        message = request.message,
        options = request.options,
        placeholder = request.placeholder,
        // `docs/rpc.md`: `editor` carries its initial content in `prefill`.
        // A value, not a label: the app hands it back on confirm, and it is
        // drawn in a `BasicTextField`, which takes one plain string. See
        // `ExtField`'s KDoc — no stripping and no painting, both deliberate.
        prefill = request.text,
        timeoutMs = request.timeoutMs,
        // Seed the countdown so the first frame shows the full duration
        // before the timer's first tick lands.
        remainingMs = request.timeoutMs,
    )
    // A duplicate id is a protocol violation: answering it would resolve the
    // wrong request (see ExtensionDialogQueue).
    if (dialogs.enqueue(dialog)) publishDialogs()
}

/** The fire-and-forget methods, plus a no-op for everything else. */
private fun PiSessionViewModel.onExtensionChrome(request: PiEvent.ExtensionUiRequest) {
    when (request.method) {
        "notify" -> pushNotice(
            // Chrome, so the message keeps the extension's escapes for the
            // snackbar to paint (`ExtensionUiHost`). `chromeText` would be the
            // wrong call: it removes the colour rather than rendering it.
            message = request.message.orEmpty(),
            // rpc.md: notifyType is info | warning | error, default info.
            tone = noticeToneOf(request.notifyType),
        )

        "setTitle" -> _state.value = _state.value.copy(
            // The one chrome surface that *is* stripped, because it has no
            // span host: `windowTitle` becomes the Chat AppBar's `Text`, which
            // lives in `ChatScreen.kt` and takes one string. Stripped before
            // the blank check, so a title that is nothing but escapes falls
            // back to the screen's own name instead of occupying the bar
            // invisibly.
            windowTitle = request.title?.let { chromeText(it) }?.takeIf { it.isNotBlank() },
        )

        "set_editor_text" -> request.text?.let { text ->
            // Last write wins, mirrored into the composer on the next frame.
            // `windowTitle`/`set_editor_text` with a missing field are ignored
            // rather than treated as "clear": the parser cannot tell an absent
            // `text` from an empty one, and guessing "clear" would silently
            // wipe whatever the user had typed.
            //
            // Neither stripped nor painted, for the same reason as `editor`'s
            // prefill: this text is a draft the user may send to the model.
            composerFillSeq += 1
            _state.value = _state.value.copy(composerFill = ComposerFill(composerFillSeq, text))
        }

        // 宿主的 `pasteToEditor`：接在现有草稿后面。
        "paste_editor_text" -> request.text?.let { text ->
            composerFillSeq += 1
            _state.value = _state.value.copy(composerFill = ComposerFill(composerFillSeq, text, append = true))
        }

        else -> Unit
    }
}

/**
 * Answer the dialog with [id] using exactly one wire response.
 *
 * The id is not decoration. A timeout can resolve a dialog in the same frame
 * the user taps its button, and [settleDialog] promotes the next request
 * immediately — so without this guard the tap would land on the *next*
 * extension's promise and resolve it with the wrong value. Any answer whose
 * id is not the current head is therefore dropped: one response per id, and
 * only for the request that is actually on screen.
 *
 * The answer type is checked against the method because the two are not
 * interchangeable on the wire (`docs/rpc.md` §"Extension UI Responses"): a
 * `value` for a confirm, or `confirmed` for an input, is a protocol error pi
 * cannot recover from, and the only safe handling is to refuse to send it.
 */
private sealed interface UiAnswer {
    data class Value(val value: String) : UiAnswer
    data class Confirmed(val confirmed: Boolean) : UiAnswer
    data object Cancelled : UiAnswer
}

internal fun PiSessionViewModel.answerDialogImpl(id: String, answer: ExtensionAnswer) {
    val dialog = dialogs.active ?: return
    if (dialog.id != id) return
    val resolved = buildAnswer(dialog, answer) ?: return
    settleDialog(dialog, resolved)
}

/**
 * The one place a wire response is built, and therefore the one place that
 * decides what an answer *is*.
 *
 * A `select` answer is sent **plain** (`chromeText`): the option label is
 * painted with whatever colour the extension wrapped it in, but the answer
 * must be the label, not the escape bytes that painted it. `input` and
 * `editor` values are *not* stripped — those are text the user typed or kept
 * from a `prefill`, and editing them here would change the user's own answer
 * rather than decline to paint a label.
 */
private fun PiSessionViewModel.buildAnswer(dialog: ExtensionDialog, answer: ExtensionAnswer): UiAnswer? = when (answer) {
    is ExtensionAnswer.Value ->
        if (dialog.method == ExtensionDialogMethod.Confirm) {
            null
        } else {
            val value = if (dialog.method == ExtensionDialogMethod.Select) {
                chromeText(answer.value)
            } else {
                answer.value
            }
            UiAnswer.Value(value)
        }

    is ExtensionAnswer.Confirmed ->
        if (dialog.method == ExtensionDialogMethod.Confirm) {
            UiAnswer.Confirmed(answer.confirmed)
        } else {
            null
        }

    ExtensionAnswer.Cancelled -> UiAnswer.Cancelled
}

private fun PiSessionViewModel.settleDialog(dialog: ExtensionDialog, answer: UiAnswer) {
    // Remove the entry FIRST. The queue is the single source of truth for
    // "still answerable", so a second tap, a Compose frame already queued
    // behind this one, or the countdown firing in the same millisecond can no
    // longer produce a second response for the same id. One response per id
    // is the contract (`docs/rpc.md`: "The `id` must match the request").
    if (dialogs.remove(dialog.id) == null) return
    // Promoting the next request also re-arms the countdown for it.
    publishDialogs()
    sendExtensionResponse(dialog.id, answer)
}

/** Apply one `set_editor_text` to the composer, then forget it. */
internal fun PiSessionViewModel.consumeComposerFillImpl(seq: Long) {
    if (_state.value.composerFill?.seq == seq) {
        _state.value = _state.value.copy(composerFill = null)
    }
}

/** Drop the snackbar that was just shown, promoting the next notice. */
internal fun PiSessionViewModel.consumeNoticeImpl(seq: Long) {
    _state.value = _state.value.copy(notices = _state.value.notices.filterNot { it.seq == seq })
}

// ------------------------------------------------------- extension timers

/** Project the queue into [UiState] and (re)arm the countdown if the head changed. */
internal fun PiSessionViewModel.publishDialogs() {
    publishDialogState()
    val active = dialogs.active
    if (active?.id != armedDialogId) armDialogTimer(active)
}

private fun PiSessionViewModel.publishDialogState() {
    _state.value = _state.value.copy(
        extensionDialog = dialogs.active,
        extensionDialogBacklog = dialogs.backlog,
    )
}

/**
 * Start the live countdown for a timed dialog.
 *
 * The tick publishes once per *displayed second*, and it now also **sleeps** to
 * the next displayed second ([PiEngineLifecyclePolicy.nextCountdownDelayMs]):
 * the dialog shows whole seconds (pi's TUI shows "Title (5s)"), so a fixed
 * 200 ms poll wrote the same number five times and woke the process five times
 * for each visible change. pi's own countdown for the same thing is
 * `setInterval(…, 1000)` (`modes/interactive/components/countdown-timer.ts:21`,
 * disposed at the zero crossing `:26-29`), i.e. one wake-up per displayed
 * second — which is what this now costs, with the deadline still hit exactly
 * (the loop breaks on `remaining <= 0`, and the sleeps sum to the deadline).
 *
 * pi runs its own timer for the same deadline (`rpc-mode.ts:115-120`
 * `createDialogPromise` → `setTimeout` → resolve with the default), so this
 * countdown is presentation; the answer at zero is belt-and-braces, not the
 * mechanism pi relies on. That is also why a coarse tick is acceptable here: a
 * late answer is dropped by pi (the request is no longer pending), and only
 * `editor` has no agent-side timer at all (`rpc-mode.ts:254-271`) — but an
 * `editor` request carries no `timeout`, so it never reaches this loop.
 */
private fun PiSessionViewModel.armDialogTimer(dialog: ExtensionDialog?) {
    dialogTimeoutJob?.cancel()
    dialogTimeoutJob = null
    armedDialogId = dialog?.id
    val timeout = dialog?.timeoutMs ?: return
    if (timeout <= 0L) return
    val id = dialog.id
    dialogTimeoutJob = viewModelScope.launch {
        val startedAt = SystemClock.elapsedRealtime()
        var shownSeconds = (timeout + 999L) / 1000L
        while (true) {
            val remaining = timeout - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining <= 0L) break
            val seconds = (remaining + 999L) / 1000L
            if (seconds != shownSeconds) {
                shownSeconds = seconds
                if (dialogs.tick(id, remaining)) publishDialogState()
            }
            delay(PiEngineLifecyclePolicy.nextCountdownDelayMs(remaining))
        }
        onDialogTimedOut(id)
    }
}

/**
 * Resolve a dialog whose countdown reached zero with pi's documented default
 * (`docs/extensions.md` §"Timed Dialogs with Countdown"): `confirm()` returns
 * `false`, `select()`/`input()`/`editor()` return `undefined`.
 *
 * `undefined` has no JSON form — `cancelled: true` is how the protocol
 * carries it, and `rpc-mode.ts` parses a cancellation back into `undefined`
 * for exactly those three, so cancelling is not an approximation of the
 * default, it *is* the default's wire encoding.
 */
private fun PiSessionViewModel.onDialogTimedOut(id: String) {
    // Guard against a stale timer: the dialog may have been answered, or
    // cancelled by an engine death, while this coroutine was suspended.
    val dialog = dialogs.active ?: return
    if (dialog.id != id) return
    when (dialog.method) {
        ExtensionDialogMethod.Confirm -> answerDialog(id, ExtensionAnswer.Confirmed(false))
        ExtensionDialogMethod.Select,
        ExtensionDialogMethod.Input,
        ExtensionDialogMethod.Editor -> answerDialog(id, ExtensionAnswer.Cancelled)
    }
}

/**
 * Dismiss everything pending and answer each one `cancelled`, oldest first.
 *
 * Called when the engine dies (`Stopped`/`Failed`), when a new session
 * replaces the old one (`attach`), and at ViewModel teardown (`onCleared`).
 *
 * **Load-bearing — do not remove as redundant.** A request can be queued
 * while no host is composed (app backgrounded, teardown mid-request).
 * `select`/`confirm`/`input` are also resolved by pi's own timer, but
 * `editor` has **no agent-side timer at all**
 * (`packages/coding-agent/src/modes/rpc/rpc-mode.ts:254-271`: an un-timed
 * promise with no timeout options in its signature), so an unanswered
 * `editor` hangs pi forever. Answering on teardown is the only thing between
 * "the UI went away" and "pi is wedged". The write may be undeliverable
 * (a dead pipe), which is fine — nobody is blocked then.
 */
internal fun PiSessionViewModel.cancelAllDialogs() {
    val pending = dialogs.drain()
    dialogTimeoutJob?.cancel()
    dialogTimeoutJob = null
    armedDialogId = null
    publishDialogState()
    pending.forEach { sendExtensionResponse(it.id, UiAnswer.Cancelled) }
}

/** Forget per-session chrome that a gone engine can no longer maintain. */
internal fun PiSessionViewModel.clearExtensionChrome() {
    toolRenderCache.reset()
    messageRenderCache.reset()
    entryRenderCache.reset()
    markdownTransformCache.reset()
    _entryRenderable.value = emptySet()
    _state.value = _state.value.copy(
        ext = ExtUiState(),
        windowTitle = null,
        composerFill = null,
    )
}

private fun PiSessionViewModel.sendExtensionResponse(id: String, answer: UiAnswer) {
    val engine = session ?: return
    // Best effort by design: the engine can die between a request and its
    // answer, and `send` writes to a pipe that is then closed. A broken pipe
    // must not crash the UI thread — the response only matters to a live pi.
    runCatching {
        when (answer) {
            is UiAnswer.Value -> engine.respondUi(id, value = answer.value)
            is UiAnswer.Confirmed -> engine.respondUi(id, confirmed = answer.confirmed)
            UiAnswer.Cancelled -> engine.respondUi(id, cancelled = true)
        }
    }
}
