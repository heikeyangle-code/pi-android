package app.pi.ui.chat

/**
 * Markers of an `ExtensionUIContext` surface that pi's `--mode rpc` context does
 * **not** implement.
 *
 * pi reports nothing when an extension takes one of these paths — `custom()`
 * resolves `undefined` immediately, the setters are literal no-ops
 * (`rpc-mode.ts:179-231`, `:273-311`) — so the only detection available to a
 * client is a look at the extension's own source. The audit reaches the same
 * conclusion (§6.11: "detection has to be heuristic because pi reports
 * nothing"), and the consequence of *not* detecting is the trap in §5.1: an
 * extension guards on `ctx.hasUI` (which is `true` in RPC mode), calls
 * `custom()`, gets `undefined`, and silently takes its cancelled branch.
 *
 * The list is exactly the RPC-mode no-ops plus `onTerminalInput`, which returns
 * a no-op unsubscribe (`rpc-mode.ts:163-166`).
 */
val TUI_ONLY_MARKERS: List<Pair<String, String>> = listOf(
    "custom()" to ".custom(",
    "setFooter()" to ".setFooter(",
    "setHeader()" to ".setHeader(",
    "setWorkingMessage()" to ".setWorkingMessage(",
    "setWorkingIndicator()" to ".setWorkingIndicator(",
    "setEditorComponent()" to ".setEditorComponent(",
    "addAutocompleteProvider()" to ".addAutocompleteProvider(",
    "onTerminalInput()" to ".onTerminalInput(",
    "mode === \"tui\"" to "mode === \"tui\"",
    "mode === 'tui'" to "mode === 'tui'",
)

/**
 * One installed extension source that uses at least one TUI-only surface.
 *
 * [scope] is the discovery location in pi's own terms ("global"/"project"), and
 * [markers] are the surfaces found, so the list can say *why* the extension is
 * being reported instead of just naming it.
 */
data class TuiOnlyExtension(
    val name: String,
    val scope: String,
    val path: String,
    val markers: List<String>,
)

/**
 * Which TUI-only surfaces [source] uses.
 *
 * A plain substring scan, deliberately: parsing TypeScript to answer a
 * cosmetic question would need a parser inside the app, and a false positive
 * costs one line in a list while a false negative leaves the §5.1 trap
 * undiagnosed. `custom(` is matched with a leading dot so a local function named
 * `custom` does not trip it.
 */
fun tuiOnlyMarkers(source: String): List<String> =
    TUI_ONLY_MARKERS.filter { (_, needle) -> source.contains(needle) }.map { it.first } +
        if (WIDGET_FACTORY.containsMatchIn(source)) listOf("widget factory") else emptyList()

/**
 * `setWidget(key, <function>)` — a component factory passed as the widget's content.
 *
 * pi drops it before the wire (`rpc-mode.ts:195-208` only forwards an array or `undefined`), so
 * the extension gets **no panel and no error** — the same silence as `custom()`, from a call
 * that does not look like one. `pi-neuralwatt-provider` and `pi-subagents`' FleetView do it.
 *
 * A regex and not a substring, and the difference matters: `"setWidget("` alone matches the
 * *common* case — `setWidget("plan-todos", lines)`, 6 of the 7 text-widget senders in the
 * ecosystem — and listing nearly every widget extension as unsupported is worse than the
 * silence it was meant to explain. What is left is the second argument's shape.
 *
 * Known limit: a bare identifier bound to a factory elsewhere (`record.fallbackFactory`, which
 * `pi-extension-utils` does) is not matched. Catching that needs the argument's binding, not
 * its text, and a false positive costs a wrong sentence while this miss costs a missing one.
 */
private val WIDGET_FACTORY = Regex(
    """setWidget\(\s*[^,)]+,\s*(\([^)]*\)\s*=>|async\s*\(|function\b|[A-Za-z_$][\w$]*Factory\b|\(\s*_?\w*\s*:)""",
)
