package app.pi.ui.extension

import app.pi.host.HostRequest
import app.pi.i18n.tr
import app.pi.protocol.Ansi
import app.pi.protocol.Notice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The UI half of pi's extension UI sub-protocol over SDK Host
 * (`docs/sdk-host/protocol.md`, `sdk-host/ui/context.mjs`), modelled for the app.
 *
 * The SDK Host splits `ExtensionUIContext` methods into two channels:
 *
 *  - **Blocking dialog requests** (`ui.select`, `ui.confirm`, `ui.input`,
 *    `ui.editor`) arrive on `hostRequests` (`{"t":"req",...}`) and block the
 *    extension until the app answers with `{"t":"reply","id":...}`. Without an
 *    answer, `ctx.ui.confirm()` never returns and the active turn stays suspended.
 *  - **Fire-and-forget chrome events** (`ui.notify`, `ui.title`, `ui.reset`,
 *    `ui.editorText`, `ui.paste`, plus surface/status/widget frames handled by
 *    `ExtUiReducer`) arrive on `hostEvents` and expect no reply.
 */

/** A blocking dialog method on SDK Host `hostRequests`. */
enum class ExtensionDialogMethod(val hostMethod: String) {
    Select("ui.select"),
    Confirm("ui.confirm"),
    Input("ui.input"),
    Editor("ui.editor");

    /**
     * pi's TUI always sends a title, but a hand-rolled or newer extension might
     * not; a dialog without one would be an unlabelled modal.
     */
    val fallbackTitle: String
        get() = when (this) {
            Select -> tr("请选择", "Select")
            Confirm -> tr("请确认", "Confirm")
            Input -> tr("请输入", "Input")
            Editor -> tr("请编辑", "Edit")
        }

    /**
     * What pi resolves to when the timeout elapses (`docs/extensions.md`
     * §"Timed Dialogs with Countdown"): `confirm()` returns `false`, the other
     * three return `undefined`. The verb is shown to the user so a silent
     * auto-dismiss is never mistaken for a crash.
     */
    val timeoutVerb: String
        get() = if (this == Confirm) tr("自动拒绝", "auto-decline") else tr("自动取消", "auto-cancel")

    companion object {
        fun fromHostMethod(method: String): ExtensionDialogMethod? =
            entries.firstOrNull { it.hostMethod == method }
    }
}

/**
 * Map a blocking SDK Host UI request (`ui.select` / `ui.confirm` / `ui.input` / `ui.editor`)
 * directly into an [ExtensionDialog]. Non-dialog host requests (such as `auth.prompt`)
 * return `null`.
 */
fun HostRequest.toExtensionDialog(): ExtensionDialog? {
    val dialogMethod = ExtensionDialogMethod.fromHostMethod(method) ?: return null
    val p = params
    val timeoutMs = p.longOrNull("timeout") ?: p.longOrNull("timeoutMs")
    return ExtensionDialog(
        id = id,
        method = dialogMethod,
        // Labels keep their ANSI SGR bytes: extensions colour them with `ctx.ui.theme.fg(...)`
        // and the dialog host renders them with `chromeSpans` + `PiPalette.tokenColorFor`.
        title = p.strOrNull("title").orEmpty(),
        message = p.strOrNull("message"),
        options = p.strList("options"),
        placeholder = p.strOrNull("placeholder"),
        // `ui.editor` carries its initial content in `prefill` (plain editable draft).
        prefill = p.strOrNull("prefill") ?: p.strOrNull("text") ?: p.strOrNull("value"),
        timeoutMs = timeoutMs,
        // Seed the countdown so the first frame shows the full duration before the first tick.
        remainingMs = timeoutMs,
    )
}

private fun JsonObject.strOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.strList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

/**
 * One outstanding blocking request.
 *
 * [remainingMs] is a live countdown, not the original timeout: the ViewModel
 * ticks it so the dialog can show "还有 N 秒". Null when the host sent no
 * `timeout` field, in which case `ui/context.mjs` waits until answered or
 * aborted (`ui.cancel`) — `ui.editor` carries no timeout.
 */
data class ExtensionDialog(
    val id: String,
    val method: ExtensionDialogMethod,
    val title: String,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    /** `ui.editor`'s initial content (`prefill`). */
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
 * The three reply shapes for SDK Host UI requests (`ui.select` / `ui.confirm` /
 * `ui.input` / `ui.editor`), plus cancellation.
 *
 * Modelled as one sealed type so the dialog host has a single callback and the
 * ViewModel has one place to reject a mismatched pairing: sending `value` for a
 * `ui.confirm` request is rejected before hitting the wire.
 */
sealed interface ExtensionAnswer {
    /** `ui.select` / `ui.input` / `ui.editor`. */
    data class Value(val value: String) : ExtensionAnswer

    /** `ui.confirm` — yes/no. */
    data class Confirmed(val confirmed: Boolean) : ExtensionAnswer

    /** Dismiss any blocking dialog (`cancelled: true`). */
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
 *  5. **The teardown drain is not optional — do not "simplify" it away.** [drain]
 *     is called on engine death (`Stopped`/`Failed`), on a new session (`attach`),
 *     and on ViewModel teardown (`onCleared`), and every drained request is
 *     answered `cancelled`. That is what makes the period while no host is
 *     composed survivable: a request can be queued but not yet on screen (no
 *     composition, app in the background, a destination that does not render),
 *     and for `select`/`confirm`/`input` pi resolves its own timer anyway, but
 *     **`editor` has no agent-side timer at all** — `rpc-mode.ts`'s `editor()` is
 *     an un-timed promise and its signature has no timeout options
 *     (`types.ts`), so an unanswered `editor` hangs pi *forever*. Answering on
 *     teardown is the only thing standing between "the UI went away" and "pi is
 *     wedged", which makes the drain load-bearing rather than defensive.
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

/**
 * A one-shot `set_editor_text` waiting for the composer to pick it up.
 *
 * It carries a [seq] because the composer owns its draft as local Compose state:
 * the effect that applies the fill is keyed on the sequence and the ViewModel
 * clears the slot once consumed. Without that, leaving and re-entering the Chat
 * destination would re-run the effect and overwrite whatever the user typed since.
 */
data class ComposerFill(
    val seq: Long,
    val text: String,
    val append: Boolean = false,
    /** pi 的 `/tree`：`result.editorText && !editor.getText().trim()` —— 输入框里已经有字就不覆盖。 */
    val onlyIfEmpty: Boolean = false,
)

/**
 * `notifyType` -> tone. `docs/rpc.md`: the field is `info` / `warning` / `error`
 * and "Defaults to `info` if omitted", so anything unrecognised is info.
 */
fun noticeToneOf(notifyType: String?): Notice.Tone = when (notifyType?.lowercase()) {
    "warning" -> Notice.Tone.Warning
    "error" -> Notice.Tone.Error
    else -> Notice.Tone.Info
}

/**
 * The **plain** form of an extension's string: every ANSI escape sequence
 * removed, nothing else changed.
 *
 * Needed wherever the escapes are meaningless rather than merely unpaintable:
 *
 *  - a value the app hands *back* to the extension or the model — `editor`'s
 *    `prefill`, `set_editor_text`, and the `select` answer. Stripping those would
 *    silently edit a payload, which is a different act from declining to paint a
 *    label (see `buildAnswer`, which strips only the label it is answering with);
 *  - hosts that cannot lay out styled runs: the Chat AppBar's title, whose
 *    `Text` lives in `ChatScreen.kt` and takes one string.
 *
 * Everywhere a `Text` can carry several colours, prefer [chromeSpans] plus the
 * token mapper in `ChromeColor.kt`, which *paints* what pi asked for instead of
 * discarding it.
 *
 * Why the escapes exist at all: an extension colours its chrome with
 * `ctx.ui.theme.fg("accent", …)`, and pi returns that text wrapped in SGR bytes
 * (`modes/interactive/theme/theme.ts:323-327`). `examples/extensions/plan-mode/index.ts:63`
 * does exactly that for its `setStatus` text. A terminal paints those bytes; the
 * RPC wire carries them as an ordinary `string` (`rpc-types.ts:246-281` has no
 * colour field), so the app must either interpret them or remove them — leaving
 * them in would put `ESC` inside a Compose `Text`.
 */
fun chromeText(raw: String): String = Ansi.strip(raw)

/**
 * The **display** form of an extension's string: pi's styled runs, ready for the
 * token mapper.
 *
 * A string with no escapes parses to a single default-styled span
 * ([Ansi.parse] guarantees that), so a caller needs no branch for the ordinary
 * case.
 */
fun chromeSpans(raw: String): List<Ansi.Span> = Ansi.parse(raw)

