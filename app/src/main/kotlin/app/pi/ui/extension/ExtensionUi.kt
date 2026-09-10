package app.pi.ui.extension

import app.pi.rpc.Notice

/**
 * The UI half of pi's extension UI sub-protocol (`docs/rpc.md` §"Extension UI
 * Protocol"), modelled for the app.
 *
 * pi splits its `ExtensionUIContext` methods in two:
 *
 *  - **Dialog methods** (`select`, `confirm`, `input`, `editor`) emit an
 *    `extension_ui_request` and *block the extension* until the client answers
 *    with `extension_ui_response` carrying the same `id`. These are the reason
 *    this package exists: without an answer, `ctx.ui.confirm()` never returns and
 *    the whole pi turn is wedged (the shipped `pi-android-permission-gate.ts`
 *    calls exactly that, without a timeout, on every dangerous device action).
 *  - **Fire-and-forget methods** (`notify`, `setStatus`, `setWidget`, `setTitle`,
 *    `set_editor_text`) emit the same event but expect no reply; `docs/rpc.md`
 *    says the client "can display the information or ignore it".
 *
 * Every field name below is pi's own (`src/modes/rpc/rpc-types.ts`
 * `RpcExtensionUIRequest`), which is why this is a wide type rather than four
 * narrow ones: one request never fails to route because a field another method
 * owns is absent.
 */

/** A blocking dialog method. Anything else is fire-and-forget. */
enum class ExtensionDialogMethod(val wire: String) {
    Select("select"),
    Confirm("confirm"),
    Input("input"),
    Editor("editor");

    /**
     * pi's TUI always sends a title, but a hand-rolled or newer extension might
     * not; a dialog without one would be an unlabelled modal.
     */
    val fallbackTitle: String
        get() = when (this) {
            Select -> "请选择"
            Confirm -> "请确认"
            Input -> "请输入"
            Editor -> "请编辑"
        }

    /**
     * What pi resolves to when the timeout elapses (`docs/extensions.md`
     * §"Timed Dialogs with Countdown"): `confirm()` returns `false`, the other
     * three return `undefined`. The verb is shown to the user so a silent
     * auto-dismiss is never mistaken for a crash.
     */
    val timeoutVerb: String
        get() = if (this == Confirm) "自动拒绝" else "自动取消"

    companion object {
        fun fromWire(method: String): ExtensionDialogMethod? =
            entries.firstOrNull { it.wire == method }
    }
}

/**
 * One outstanding blocking request.
 *
 * [remainingMs] is a live countdown, not the original timeout: the ViewModel
 * ticks it so the dialog can show "还有 N 秒". Null when pi sent no `timeout`
 * field, in which case pi will wait forever and the dialog must not disappear on
 * its own — `docs/rpc.md` only promises auto-resolution when `timeout` is present,
 * and `rpc-mode.ts`'s `editor()` implementation ignores timeout options entirely.
 */
data class ExtensionDialog(
    val id: String,
    val method: ExtensionDialogMethod,
    val title: String,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    /** `editor`'s initial content (`docs/rpc.md` calls the field `prefill`). */
    val prefill: String? = null,
    val timeoutMs: Long? = null,
    val remainingMs: Long? = null,
) {
    val timed: Boolean get() = (timeoutMs ?: 0L) > 0L

    /** Seconds pi would display for this countdown, rounded up like pi's "(5s)". */
    fun remainingSeconds(): Long? =
        remainingMs?.let { (it + 999L) / 1000L }
}

/**
 * The three wire shapes of `extension_ui_response` (`docs/rpc.md` §"Extension UI
 * Responses"), plus cancellation.
 *
 * Modelled as one sealed type so the dialog host has a single callback and the
 * ViewModel has one place to reject a mismatched pairing: sending `value` for a
 * `confirm` request would be a protocol error pi cannot recover from.
 */
sealed interface ExtensionAnswer {
    /** `select` / `input` / `editor`. */
    data class Value(val value: String) : ExtensionAnswer

    /** `confirm` — pi's yes/no. */
    data class Confirmed(val confirmed: Boolean) : ExtensionAnswer

    /** Any dialog: "Dismiss any dialog method" (`docs/rpc.md`). */
    data object Cancelled : ExtensionAnswer
}

/**
 * FIFO, never-drop queue for blocking requests.
 *
 * Policy, and why:
 *
 *  1. **Strict arrival order.** pi's own TUI is single-threaded: a second
 *     `ctx.ui.confirm()` cannot even be *asked* before the first resolves. Over
 *     RPC that serialisation is gone — two extensions (or two tools in one turn)
 *     can have requests in flight at once — so the app has to restore the order
 *     itself or two modals would race and answer each other's ids.
 *  2. **One on screen, the rest queued.** Material dialogs stack into a visual
 *     mess and the user cannot tell which answer belongs to which request; the
 *     queue keeps exactly one modal, with the backlog count shown in the title.
 *  3. **Nothing that expects a reply is ever dropped**, not when the transcript
 *     is streaming, not when the queue is long, not on cancellation. The only way
 *     a dialog leaves this queue is [remove] (answered), [drain] (engine death /
 *     session reset — every entry is answered `cancelled` by the caller), or a
 *     duplicate id ([enqueue] returns false).
 *  4. **Duplicate ids are refused, not replaced.** pi ids are uuids per request
 *     (`rpc-mode.ts` `crypto.randomUUID()`); a repeat means a bug or a hostile
 *     stream, and answering the wrong request is worse than showing nothing.
 */
class ExtensionDialogQueue {

    private val waiting = ArrayDeque<ExtensionDialog>()

    /** The dialog the UI must render now, or null when nothing is pending. */
    val active: ExtensionDialog? get() = waiting.firstOrNull()

    /** Dialogs waiting behind [active]; shown as "还有 N 个在排队". */
    val backlog: Int get() = (waiting.size - 1).coerceAtLeast(0)

    val size: Int get() = waiting.size

    /** True when the id was accepted; false for a duplicate already in flight. */
    fun enqueue(dialog: ExtensionDialog): Boolean {
        if (waiting.any { it.id == dialog.id }) return false
        waiting.addLast(dialog)
        return true
    }

    /** Update the countdown of a queued dialog. False if it is no longer queued. */
    fun tick(id: String, remainingMs: Long): Boolean {
        val index = waiting.indexOfFirst { it.id == id }
        if (index < 0) return false
        waiting[index] = waiting[index].copy(remainingMs = remainingMs)
        return true
    }

    /** Take one dialog out of the queue. Null when it was already answered. */
    fun remove(id: String): ExtensionDialog? {
        val index = waiting.indexOfFirst { it.id == id }
        if (index < 0) return null
        return waiting.removeAt(index)
    }

    /** Oldest-first snapshot of everything pending, leaving the queue empty. */
    fun drain(): List<ExtensionDialog> {
        val all = waiting.toList()
        waiting.clear()
        return all
    }
}

/**
 * A transient, non-blocking message for the snackbar: `notify` and, separately,
 * `extension_error`.
 *
 * [tone] reuses pi's own [Notice.Tone] because the transcript already carries
 * that vocabulary for single-line notices; the snackbar is the same three states.
 */
data class ExtensionNotice(
    val seq: Long,
    val message: String,
    val tone: Notice.Tone = Notice.Tone.Info,
)

/** One `setStatus` entry. Ordered, keyed, cleared by an absent `statusText`. */
data class ExtensionStatus(val key: String, val text: String)

/**
 * `setWidget`'s placement. `docs/rpc.md` documents `aboveEditor` as the default
 * and `belowEditor` as the alternative; an unknown value is treated as the
 * documented default rather than dropped, so a newer pi still shows the widget.
 */
enum class WidgetPlacement(val wire: String) {
    AboveEditor("aboveEditor"),
    BelowEditor("belowEditor");

    companion object {
        fun fromWire(value: String?): WidgetPlacement =
            entries.firstOrNull { it.wire == value } ?: AboveEditor
    }
}

/** One `setWidget` panel: non-empty [lines]; an empty list clears the key. */
data class ExtensionWidget(
    val key: String,
    val lines: List<String>,
    val placement: WidgetPlacement = WidgetPlacement.AboveEditor,
)

/**
 * A one-shot `set_editor_text` waiting for the composer to pick it up.
 *
 * It carries a [seq] because the composer owns its draft as local Compose state:
 * the effect that applies the fill is keyed on the sequence and the ViewModel
 * clears the slot once consumed. Without that, leaving and re-entering the Chat
 * destination would re-run the effect and overwrite whatever the user typed since.
 */
data class ComposerFill(val seq: Long, val text: String)

/**
 * `notifyType` -> tone. `docs/rpc.md`: the field is `info` / `warning` / `error`
 * and "Defaults to `info` if omitted", so anything unrecognised is info.
 */
fun noticeToneOf(notifyType: String?): Notice.Tone = when (notifyType?.lowercase()) {
    "warning" -> Notice.Tone.Warning
    "error" -> Notice.Tone.Error
    else -> Notice.Tone.Info
}
