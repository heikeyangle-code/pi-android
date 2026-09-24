package app.pi.ui.extension

import app.pi.rpc.Ansi
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

/**
 * pi's own cap on the rows a string widget may occupy
 * (`interactive-mode.ts:2276`: `MAX_WIDGET_LINES = 10`, "Maximum total widget
 * lines to prevent viewport overflow").
 *
 * Borrowed as pi's value rather than chosen here: a widget is an extension's
 * layout, and the cap exists because the *host* cannot let one panel push the
 * conversation off screen. A different number would make the same widget render
 * differently in the two hosts for no reason.
 */
const val MAX_WIDGET_LINES = 10

/**
 * pi's truncation row, **verbatim** (`interactive-mode.ts:2217`:
 * `theme.fg("muted", "... (widget truncated)")`).
 *
 * Left in English on purpose. It is a line of the interface pi itself paints,
 * not this app's prose, and translating it would make one widget read
 * differently depending on which host drew it. pi colours it `muted`; the app
 * already draws every widget line in `palette.muted`, so the bare string is the
 * faithful equivalent.
 */
const val WIDGET_TRUNCATED_LINE = "... (widget truncated)"

/**
 * pi's truncation rule for a string widget, applied where the widget is drawn.
 *
 * The slice happens at render time rather than when the state is built, so
 * [ExtensionWidget.lines] stays exactly what pi sent — the same separation pi
 * keeps between its widget map and the `Container` it builds from it
 * (`interactive-mode.ts:2213-2218`).
 */
fun boundedWidgetLines(lines: List<String>): List<String> =
    if (lines.size <= MAX_WIDGET_LINES) {
        lines
    } else {
        lines.take(MAX_WIDGET_LINES) + WIDGET_TRUNCATED_LINE
    }

/**
 * pi's row cap, applied **after** a text line's own newlines have been split into
 * rows ([widgetRows]).
 *
 * Same constant and same truncation row as [boundedWidgetLines] — one line of a widget
 * array can legitimately be several rows (an extension that pushes file content), and a
 * cap that counted array elements would let the split walk straight past it.
 */
internal fun boundedWidgetRows(rows: List<WidgetRow>): List<WidgetRow> =
    if (rows.size <= MAX_WIDGET_LINES) {
        rows
    } else {
        rows.take(MAX_WIDGET_LINES) + WidgetRow.Text(WIDGET_TRUNCATED_LINE)
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

