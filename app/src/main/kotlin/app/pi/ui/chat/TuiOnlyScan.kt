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
    TUI_ONLY_MARKERS.filter { (_, needle) -> source.contains(needle) }.map { it.first }
