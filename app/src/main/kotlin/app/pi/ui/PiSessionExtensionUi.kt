package app.pi.ui

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import app.pi.host.ExtUiState
import app.pi.host.HostRequest
import app.pi.host.HostSignal
import app.pi.i18n.tr
import app.pi.protocol.Notice
import app.pi.service.PiEngineLifecyclePolicy
import app.pi.ui.PiSessionViewModel.UiState
import app.pi.ui.extension.ComposerFill
import app.pi.ui.extension.ExtensionAnswer
import app.pi.ui.extension.ExtensionDialog
import app.pi.ui.extension.ExtensionDialogMethod
import app.pi.ui.extension.ExtensionDialogQueue
import app.pi.ui.extension.chromeText
import app.pi.ui.extension.noticeToneOf
import app.pi.ui.extension.toExtensionDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Route one blocking SDK Host dialog request (`ui.select` / `ui.confirm` / `ui.input` / `ui.editor`).
 *
 * Non-dialog requests (such as `auth.prompt`, handled by `AuthPromptHost`) are ignored here.
 */
internal fun PiSessionViewModel.onExtensionHostRequest(request: HostRequest) {
    val dialog = request.toExtensionDialog() ?: return

    // Every reply is addressed by the request's id, so a request without one
    // can never be answered and showing a dialog would only mask the hang.
    if (dialog.id.isEmpty()) {
        pushNotice(
            message = tr("扩展发来一个没有编号的「${request.method}」对话框，无法回复；这个扩展的弹窗在当前引擎上无法使用。", "Extension sent a \"${request.method}\" dialog without a request ID and cannot be answered."),
            tone = Notice.Tone.Warning,
        )
        return
    }

    // A duplicate id is a protocol violation: answering it would resolve the
    // wrong request (see ExtensionDialogQueue).
    if (dialogs.enqueue(dialog)) publishDialogs()
}

/** Route fire-and-forget SDK Host UI events (`ui.notify`, `ui.reset`, `ui.title`, `ui.editorText`, `ui.paste`). */
internal fun PiSessionViewModel.onExtensionHostEvent(event: HostSignal.Event) {
    when (event.name) {
        "ui.notify" -> {
            val d = event.data as? JsonObject ?: return
            val message = d.hostStr("message").orEmpty()
            if (message.isBlank()) return
            val tone = noticeToneOf(d.hostStr("tone"))
            // 100% 对齐官方 Pi 1.7.0（interactive-mode.js showExtensionNotify）：
            // 直接写入当前会话内存聊天流底部（不落盘 .jsonl，连续 info 原地覆盖、长内容折叠、支持复制），
            // 仅在引擎会话尚未建立时退回顶部轻提示。
            val activeSession = session
            if (activeSession != null) {
                activeSession.appendExtensionNotice(message, tone)
            } else {
                pushNotice(message = message, tone = tone)
            }
        }

        // 官方 resetExtensionUI 末尾恢复默认标题（空标题 = 回到屏幕自己的名字）。
        "ui.reset" -> _state.update { s -> s.copy(windowTitle = null) }

        "ui.title" -> {
            val title = (event.data as? JsonObject)?.hostStr("title")
            _state.update { s -> s.copy(
                // `windowTitle` becomes the Chat AppBar's plain `Text`; strip ANSI escapes
                // before the blank check so an escape-only title falls back cleanly.
                windowTitle = title?.let { chromeText(it) }?.takeIf { it.isNotBlank() },
            ) }
        }

        "ui.editorText" -> (event.data as? JsonObject)?.hostStr("text")?.let { text ->
            // Last write wins, mirrored into the composer as a plain draft without stripping.
            composerFillSeq += 1
            _state.update { s -> s.copy(composerFill = ComposerFill(composerFillSeq, text)) }
        }

        // 宿主的 `pasteToEditor`：接在现有草稿后面。
        "ui.paste" -> (event.data as? JsonObject)?.hostStr("text")?.let { text ->
            composerFillSeq += 1
            _state.update { s -> s.copy(composerFill = ComposerFill(composerFillSeq, text, append = true)) }
        }

        else -> Unit
    }
}

private fun JsonObject.hostStr(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

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
 * interchangeable on the wire (`docs/sdk-host/protocol.md`, `ui/context.mjs`): a
 * `value` for `ui.confirm`, or `confirmed` for `ui.input`, is rejected before
 * hitting the wire.
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
 * All value answers (`select`, `input`, `editor`) are returned verbatim so
 * extensions matching `choice === styledOption` (exactly like Pi's official TUI)
 * succeed without losing ANSI escape sequences.
 */
private fun PiSessionViewModel.buildAnswer(dialog: ExtensionDialog, answer: ExtensionAnswer): UiAnswer? = when (answer) {
    is ExtensionAnswer.Value ->
        if (dialog.method == ExtensionDialogMethod.Confirm) {
            null
        } else {
            UiAnswer.Value(answer.value)
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
    // longer produce a second response for the same id. One reply per id
    // is the SDK Host contract (`docs/sdk-host/protocol.md`).
    if (dialogs.remove(dialog.id) == null) return
    // Promoting the next request also re-arms the countdown for it.
    publishDialogs()
    sendExtensionResponse(dialog.id, answer)
}

/** Apply one `set_editor_text` to the composer, then forget it. */
internal fun PiSessionViewModel.consumeComposerFillImpl(seq: Long) {
    if (_state.value.composerFill?.seq == seq) {
        _state.update { s -> s.copy(composerFill = null) }
    }
}

/** Drop the snackbar that was just shown, promoting the next notice. */
internal fun PiSessionViewModel.consumeNoticeImpl(seq: Long) {
    _state.update { s -> s.copy(notices = s.notices.filterNot { it.seq == seq }) }
}

// ------------------------------------------------------- extension timers

/** Project the queue into [UiState] and (re)arm the countdown if the head changed. */
internal fun PiSessionViewModel.publishDialogs() {
    publishDialogState()
    val active = dialogs.active
    if (active?.id != armedDialogId) armDialogTimer(active)
}

private fun PiSessionViewModel.publishDialogState() {
    _state.update { s -> s.copy(
        extensionDialog = dialogs.active,
        extensionDialogBacklog = dialogs.backlog,
    ) }
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
 * The SDK Host runs its own timer for the same deadline (`sdk-host/hub.mjs`
 * `ask()` → `setTimeout` → resolve with default + emit `ui.cancel`), so this
 * countdown is presentation; the answer at zero is belt-and-braces. A late
 * reply is ignored by the host, and `ui.editor` carries no `timeout` so it
 * never enters this loop.
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
 * `undefined` has no JSON form — `cancelled: true` is how the SDK Host
 * reply carries it (`sdk-host/hub.mjs` resolves cancelled asks with the method's
 * default value: `false` for confirm, `undefined` for select/input/editor).
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
 * `ui.select`/`ui.confirm`/`ui.input` may carry a timeout, but `ui.editor`
 * has **no host-side timer** (`sdk-host/ui/context.mjs`), so an unanswered
 * `editor` keeps the extension suspended forever. Answering on teardown is the only thing between
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
    _state.update { s -> s.copy(
        ext = ExtUiState(),
        overriddenNativeTools = emptySet(),
        windowTitle = null,
        composerFill = null,
    ) }
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
