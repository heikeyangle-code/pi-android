# Extension UI — implementation status, hand-off, and remaining patches

Scope: `app/src/main/kotlin/app/pi/ui/extension/**` (this directory).
The RPC engine **deadlocks** without this code: pi's `extension_ui_request` for
`select` / `confirm` / `input` / `editor` blocks the extension until the client
answers on stdin with `extension_ui_response` carrying the same `id`
(`docs/rpc.md` §"Extension UI Protocol"). The shipped
`assets/pi-extensions/pi-android-permission-gate.ts` calls `ctx.ui.confirm()`
with no timeout on every dangerous device action, so before this code the
session hung forever with no error.

## 1. What is implemented (and where)

| File | Contents |
|------|----------|
| `ExtensionUi.kt` | Protocol model — `ExtensionDialogMethod`, `ExtensionDialog`, `ExtensionAnswer`, `ExtensionNotice`, `ExtensionStatus`, `ExtensionWidget`, `WidgetPlacement`, `ComposerFill`, `noticeToneOf` — plus `chromeText` / `chromeSpans` (the plain and styled forms of an extension string; §5), `boundedWidgetLines` with pi's `MAX_WIDGET_LINES` and truncation row (§5), and `ExtensionDialogQueue`, the FIFO policy (see §3). |
| `ChromeColor.kt` | `PiPalette.tokenColorFor(span)`: the ANSI-foreground → pi-theme-token mapping, with the measured threshold and the reasons there is a threshold at all (§5). |
| `ExtensionDialogs.kt` | Material 3 `select` / `confirm` / `input` / `editor` dialogs, live countdown bar, `prefill` handling, cancel-on-dismiss. Title, message, option labels and placeholder are painted; the field's *value* is not (§5). |
| `ExtensionUiHost.kt` | The single overlay: snackbar queue (`notify`, `extension_error`) + `ExtensionDialogHost`, driven by the ViewModel's extension state. |
| `ExtensionChrome.kt` | `ExtensionSpans` (the span renderer), `ExtensionWidgetStack` (`setWidget`, above/below editor, row-capped per §5), `windowTitleOf` (`setTitle`). The status row is **deleted** — see §5. |

Deadlock disposition, method by method:

| pi method | Disposition |
|-----------|-------------|
| `select` | Dialog; option tap → `{value}`; 取消 / back → `{cancelled:true}`. The label is painted with the extension's colour, the answer is sent **plain** (§5). |
| `confirm` | Dialog; 允许 → `{confirmed:true}`, 拒绝 / back → `{confirmed:false}` / `{cancelled:true}` (pi maps both to `false`). |
| `input` | Dialog with placeholder; 确定 → `{value}` (empty string is a legal value); 取消 → `{cancelled:true}`. |
| `editor` | Multi-line dialog pre-filled from `prefill`; 确定 → `{value}`; 取消 → `{cancelled:true}`. **No countdown — see §2.** `prefill` is byte-exact and unpainted because it is answered back (§5). |
| `notify` | Snackbar, tone from `notifyType` (info/warning/error, defaults to info); the message is painted with the extension's colour (§5). |
| `setStatus` | **Not rendered, by adjudication.** `statusKey` upserts and an absent/empty `statusText` clears, exactly as before, and `UiState.extensionStatuses` still collects every entry — but `ExtensionStatusRow` was deleted (D-3) and nothing draws the list today. See §5. |
| `setWidget` | Panel above/below the composer by `widgetPlacement` (default `aboveEditor`); empty `widgetLines` clears the key. Each line is painted, and the rows are capped at pi's 10 with pi's own `... (widget truncated)` row (§5). |
| `setTitle` | Primary line of the Chat AppBar; blank clears back to 会话/新会话. The one chrome surface that is **stripped, not painted** — its host is a `ChatScreen.kt` `Text` that takes one string (§5). Stripped before the blank test, so a title of nothing but escapes falls back instead of occupying the bar invisibly. |
| `set_editor_text` | Fills the composer once (`composerFill` seq, consumed on apply so re-entering the destination cannot overwrite newer typing); byte-exact, neither stripped nor painted (§5). |
| `extension_error` | Non-fatal error snackbar ("扩展出错：…"); the transcript already carries an `ErrorText` row from the rpc reducer. Both name the extension from `extensionPath` — see §4. |

## 2. `editor` has no agent-side timer — explicit disposition

`src/modes/rpc/rpc-mode.ts` implements `editor(title, prefill)` as an un-timed
hand-rolled promise (no `opts` parameter, so no `timeout` and no `AbortSignal`),
unlike `select`/`confirm`/`input`, which arm pi's own timer and auto-resolve with
`undefined`/`false`. Consequences, all deliberate:

1. **The app shows no countdown for `editor`** (`timeoutMs` is null because pi
   never sends a `timeout` field for it), so the dialog waits for a human
   decision for as long as it takes.
2. **If a `timeout` field ever did arrive on an `editor` request, expiry answers
   `{cancelled:true}`** — a response is always sent, never nothing. The expiry
   branch maps `Editor` to `Cancelled` explicitly.
3. **Teardown always answers.** `cancelAllDialogs()` drains the queue and answers
   every pending request `cancelled` (oldest first) on engine `Stopped`/`Failed`,
   on `attach()` of a new session, and in `onCleared()`. This is what makes the
   window in which no host is composed (app backgrounded, teardown mid-request,
   any period before the overlay exists) survivable. **It must not be removed as
   redundant**: for `editor` it is the only thing between "the UI went away" and
   "pi is wedged forever".
4. `set_editor_text` with a missing `text` field is ignored rather than treated
   as "clear", so a malformed event cannot wipe the composer.

## 3. Queue policy

`ExtensionDialogQueue` — FIFO, one modal on screen, backlog count shown in the
dialog title:

1. **Strict arrival order** — over RPC two extensions can have requests in
   flight at once, unlike pi's single-threaded TUI; the queue restores the order
   so two modals cannot answer each other's ids.
2. **Only the head is rendered**; the rest wait with a "还有 N 个在排队" line.
3. **Nothing that expects a reply is ever dropped.** The only exits are `remove`
   (answered), `drain` (teardown → answered `cancelled`), or a duplicate id
   (`enqueue` returns false).
4. **Duplicate ids are refused, not replaced** (pi ids are per-request uuids).
5. **Every answer is addressed by id** and `answerDialog(id, answer)` drops any
   answer whose id is not the current head. A timeout can resolve a dialog in
   the same frame the user taps it, at which point the head is already the next
   request — without this guard the tap would resolve a different extension's
   promise with the wrong value.

Timeout defaults on expiry follow pi exactly (`docs/extensions.md` §"Timed
Dialogs with Countdown"): `confirm` → `confirmed:false`; `select`/`input` →
`cancelled:true`, which is the wire form of `undefined` (`rpc-mode.ts`
`createDialogPromise`). pi also runs its own timer; a racing reply is safe in
both directions because pi deletes the pending entry before resolving and
silently drops a response whose id is no longer pending.

## 4. Cross-file notes

No patch is outstanding. Two entries in an earlier revision of this file were
wrong and are recorded here so they are not re-opened:

- The `cancelAllDialogs` KDoc patch ("only one patch is outstanding",
  `PiSessionViewModel.kt` lines 1075–1082) **is applied**. The function and its
  "Load-bearing — do not remove as redundant" KDoc now live at
  `PiSessionViewModel.kt:1855-1871`; line 1075 is a closing brace in the
  engine-restart path (`attach` itself is at `:1088`), so that range pointed at
  unrelated code even before the file moved.
- "`PiEvent.ExtensionError` models only `message` … so `extensionPath` is
  unreachable from this layer" was **never true of this tree**:
  `rpc/.../Events.kt:375-379` declares `extensionPath` and `event`, the parser
  fills them at `Events.kt:570-573`, and the app already uses them —
  `PiSessionViewModel.kt:1475-1482` and `rpc/.../Transcript.kt:1073-1081` both
  route the path through `extensionErrorHeadline`
  (`rpc/.../ExtensionErrorText.kt:28`). Attribution works; nothing is pending.

Everything else is already wired in the tree (verified at HEAD `61efd99`):

- `PiSessionViewModel.kt` — `UiState.extensionDialog/.extensionDialogBacklog/.extensionStatuses/.extensionWidgets/.windowTitle/.composerFill/.notices`; `answerDialog(id, answer)`, `consumeNotice(seq)`, `consumeComposerFill(seq)`; `ExtensionUiRequest` routing; drain on `attach` / `Stopped` / `Failed` / `onCleared`.
- `ChatScreen.kt` — `ExtensionWidgetStack` (above/below the composer), `windowTitleOf` for the AppBar, `composerFill` applied and consumed; `ExtensionStatusRow` used to be mounted here and its call site plus import are removed with the component.
- `PiRoot.kt` — exactly **one** `ExtensionUiHost` mount (line 858). Do not add a second: two mounts would render two dialogs for one request and two snackbar hosts racing for one notice queue.

## 5. Extension strings: the colour, the plain values, and the widget row budget

Two host-side adjustments happen to extension strings on the way in, because the
RPC wire carries far less than the TUI does. The reason for each is a gap in
`RpcExtensionUIRequest` (`rpc-types.ts:246-281`: every payload field is `string`,
`string[]` or a fixed enum — there is no colour channel and no layout budget).

1. **The colour is painted, not discarded.** An extension colours its chrome with
   `ctx.ui.theme.fg("accent", …)` — `examples/extensions/plan-mode/index.ts:63`
   does it to its status text — and that returns the text wrapped in SGR bytes
   (`theme.ts:323-327`). A terminal paints them; the wire carries them as an
   ordinary string, so the app has to interpret them itself:
   `chromeSpans` (`= Ansi.parse`) splits the string into runs and
   `PiPalette.tokenColorFor` maps each run's RGB back onto a pi theme token
   (`ChromeColor.kt`). The drawing sites are `ExtensionSpans` on every widget line,
   the snackbar message in `ExtensionUiHost`, and the dialog's title, message,
   option labels and placeholder in `ExtensionDialogs`. The status row used to be
   the fourth and is gone — see the note at the end of this section.

   **The mapping is "nearest token, within a measured threshold"**, and the
   threshold matters: the app's engine runs with `TERM=xterm-256color` and no
   `COLORTERM` (`runtime/PiRuntime.kt:233`), so pi quantises every token through
   `rgbTo256` before it reaches the wire (`theme.ts:164-193,529`) and the app gets
   a *cell*, not the token's exact value. Measured over both built-in palettes
   with pi's own distance metric: every **text** token survives the round trip
   within 507.4, while the first **surface** token sits at 1263 — so the threshold
   is 1000, and a colour further away than that keeps the app's own colour rather
   than being repainted as a token that merely happens to be closest. 110 of the
   118 shipped token colours are honoured; the 8 that fall back are exactly the
   dark theme's surface tokens. No colour is ever *derived* — the result is
   always a palette value, so nothing can drift from the user's theme.
   `background`, `dim`, bold, italic and underline on a span are ignored: the
   app's surfaces and type scale are its own, and foreground colour is the one
   thing an extension cannot express any other way.

2. **Values that leave the app again are never painted and never edited.**
   `chromeText` (`= Ansi.strip`) is the plain form, and it is used only where the
   styled form is impossible or wrong:

   - `setTitle` — its host is the Chat AppBar's `Text` in `ChatScreen.kt`, which
     takes one string, so the title is stripped rather than painted (and stripped
     *before* the blank test, so a title of nothing but escapes falls back to the
     screen's own name instead of occupying the bar invisibly);
   - `editor`'s `prefill` and `set_editor_text` — bytes are preserved exactly,
     neither stripped nor painted. They are drafts the user may send to the model,
     and they are drawn in a `BasicTextField`, which takes one string (see
     `ExtField`'s KDoc);
   - a `select` **answer** — the label is painted with the extension's colour, but
     `buildAnswer` strips it before it goes on the wire, so escape bytes can never
     come back to pi as the user's choice. (`input`/`editor` answers are *not*
     stripped: those are the user's own text, not a label.)

3. **`boundedWidgetLines` caps a widget at pi's `MAX_WIDGET_LINES` (10)** and
   appends pi's own row, verbatim: `... (widget truncated)`
   (`interactive-mode.ts:2276` for the constant, `:2216-2218` for the row). pi caps
   widgets so one panel cannot push the conversation off screen; without the cap a
   runaway widget grows the transcript without limit. The row is drawn in
   `palette.muted`, which is what pi's `theme.fg("muted", …)` resolves to.
   Verified field by field against pi: the constant is 10, the slice is
   `take(MAX_WIDGET_LINES)`, the over-limit test is `size > MAX_WIDGET_LINES` (so
   exactly ten rows adds no marker), and the row's text is untranslated. Applied in
   `ExtensionChrome.kt` at the draw site, so `ExtensionWidget.lines` stays exactly
   what pi sent.

4. **The status row is deleted, the status data is not.** pi draws `setStatus` in
   its terminal footer (`interactive-mode.ts:2090`); the app drew a horizontally
   scrolling row of counters under its AppBar. The user's adjudication
   (`design/ui-refactor/11-designer-adjudication.md` D-3: v2's dialogue shell has
   no such line) deleted the composable `ExtensionStatusRow`, so there is no
   `setStatus` drawing site any more — including in the colour work above, which
   now covers the widget, the notification and the dialog text only.

   What was **kept on purpose**:

   - `UiState.extensionStatuses` — the collected entries;
   - `setExtensionStatus(key, text)` — the upsert/clear semantics, unchanged;
   - the `"setStatus"` route in `onExtensionChrome`.

   The reason is that the data is the only copy of what an extension asked to
   show, and the adjudication names the 「会话与队列」 sheet as a place it could
   reappear. Re-rendering it is then a new composable plus one call site — no
   protocol or ViewModel change. Both the route and `setExtensionStatus` carry a
   comment saying exactly this, so the next reader does not "clean up" an
   apparently unused field.

## 6. Verification

`tools/typecheck.sh` over the whole tree, plus
`python3 tools/check-nested-comments.py`. The pass/fail verdict is whether any
`error:` diagnostic was emitted, not the compiler's exit status — the JVM exits
non-zero even on a clean frontend because the Compose compiler plugin is not on
the classpath (header of `tools/typecheck.sh`).

Known standing false positives, unchanged by this directory: three `BuildConfig`
unresolved references in `ui/settings/DiagnosticsReport.kt`, which exist only in a
Gradle-generated class that AAPT2 would have produced.

`ui/extension/**` has no unit-test harness. The palette mapping in `ChromeColor.kt`
is the part worth pinning — its threshold is a measured number, and the measurement
is reproduced by running both built-in palettes through pi's `rgbTo256`
(`theme.ts:164-193`) and then through the matcher. That needs a bare-JVM harness
following the pattern in `tools/run-app-pure-checks.sh`, plus a line in that
script; both files are outside this directory's ownership, and the mapper does
import Compose's `Color`, so a harness would have to take the palette as ARGB ints.


