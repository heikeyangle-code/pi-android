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
| `ExtensionUi.kt` | Protocol model — `ExtensionDialogMethod`, `ExtensionDialog`, `ExtensionAnswer`, `ExtensionNotice`, `ExtensionStatus`, `ExtensionWidget`, `WidgetPlacement`, `ComposerFill`, `noticeToneOf` — and `ExtensionDialogQueue`, the FIFO policy (see §3). |
| `ExtensionDialogs.kt` | Material 3 `select` / `confirm` / `input` / `editor` dialogs, live countdown bar, `prefill` handling, cancel-on-dismiss. |
| `ExtensionUiHost.kt` | The single overlay: snackbar queue (`notify`, `extension_error`) + `ExtensionDialogHost`, driven by the ViewModel's extension state. |
| `ExtensionChrome.kt` | `ExtensionStatusRow` (`setStatus`), `ExtensionWidgetStack` (`setWidget`, above/below editor), `windowTitleOf` (`setTitle`). |

Deadlock disposition, method by method:

| pi method | Disposition |
|-----------|-------------|
| `select` | Dialog; option tap → `{value}`; 取消 / back → `{cancelled:true}`. |
| `confirm` | Dialog; 允许 → `{confirmed:true}`, 拒绝 / back → `{confirmed:false}` / `{cancelled:true}` (pi maps both to `false`). |
| `input` | Dialog with placeholder; 确定 → `{value}` (empty string is a legal value); 取消 → `{cancelled:true}`. |
| `editor` | Multi-line dialog pre-filled from `prefill`; 确定 → `{value}`; 取消 → `{cancelled:true}`. **No countdown — see §2.** |
| `notify` | Snackbar, tone from `notifyType` (info/warning/error, defaults to info). |
| `setStatus` | Row under the AppBar; upsert by `statusKey`; absent/empty `statusText` clears. |
| `setWidget` | Panel above/below the composer by `widgetPlacement` (default `aboveEditor`); empty `widgetLines` clears the key. |
| `setTitle` | Primary line of the Chat AppBar; blank clears back to 会话/新会话. |
| `set_editor_text` | Fills the composer once (`composerFill` seq, consumed on apply so re-entering the destination cannot overwrite newer typing). |
| `extension_error` | Non-fatal error snackbar ("扩展出错：…"); the transcript already carries an `ErrorText` row from the rpc reducer. |

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

## 4. Patch list for files owned by others

Only one patch is outstanding. `PiSessionViewModel.kt` lines **1075–1082** — the
drain's KDoc says *what* it does but not *why it cannot be removed*:

Before:

```kotlin
    /**
     * Dismiss everything pending and answer each one `cancelled`, oldest first.
     *
     * Called when the engine dies or a new session replaces the old one. The
     * write may be undeliverable (a dead pipe), which is fine — nobody is
     * blocked then — but on a *session reset* pi is still alive and would
     * otherwise sit forever on a dialog whose UI no longer exists.
     */
```

After:

```kotlin
    /**
     * Dismiss everything pending and answer each one `cancelled`, oldest first.
     *
     * Called when the engine dies (`Stopped`/`Failed`), when a new session
     * replaces the old one (`attach`), and at ViewModel teardown (`onCleared`).
     *
     * **Load-bearing — do not remove as redundant.** A request can be queued
     * while no host is composed (app backgrounded, teardown mid-request).
     * `select`/`confirm`/`input` are also resolved by pi's own timer, but
     * `editor` has **no agent-side timer at all** (`src/modes/rpc/rpc-mode.ts`:
     * an un-timed promise with no timeout options in its signature), so an
     * unanswered `editor` hangs pi forever. Answering on teardown is the only
     * thing between "the UI went away" and "pi is wedged". The write may be
     * undeliverable (a dead pipe), which is fine — nobody is blocked then.
     */
```

Everything else is already wired in the tree (verified at HEAD `2e8d93b`):

- `PiSessionViewModel.kt` — `UiState.extensionDialog/.extensionDialogBacklog/.extensionStatuses/.extensionWidgets/.windowTitle/.composerFill/.notices`; `answerDialog(id, answer)`, `consumeNotice(seq)`, `consumeComposerFill(seq)`; `ExtensionUiRequest` routing; drain on `attach` / `Stopped` / `Failed` / `onCleared`.
- `ChatScreen.kt` — `ExtensionStatusRow`, `ExtensionWidgetStack` (above/below the composer), `windowTitleOf` for the AppBar, `composerFill` applied and consumed.
- `PiRoot.kt` — exactly **one** `ExtensionUiHost` mount (line 177). Do not add a second: two mounts would render two dialogs for one request and two snackbar hosts racing for one notice queue.

Out of scope (other owners), recorded so it is not lost: `PiEvent.ExtensionError`
models only `message` and keeps no raw object, so `extensionPath` (present on the
wire per `docs/rpc.md` §`extension_error`) is unreachable from this layer. Adding
it is a one-line change in `rpc/src/main/kotlin/app/pi/rpc/Events.kt`.

## 5. Verification

Narrow frontend type-check of this package, run after the last edit, against the
shared classpath + `build/typecheck/rpc.jar` and a throwaway stub of the
peer-owned ViewModel API (script: `/tmp/piext/narrow2.sh`, log:
`/tmp/piext/narrow-raw.log`):

```
grep -cE "\.kt:[0-9]+:[0-9]+: error:"  ->  0
```

i.e. **no `error:` diagnostic in `ui/extension/**`**; the JVM still exits 2 with a
codegen exception on default-value stubs because the Compose compiler plugin is
not on the classpath — exactly the limitation documented in the header of
`tools/typecheck.sh` ("The pass/fail verdict is based on whether any `error:`
diagnostic was emitted, not on the compiler's exit status").

Full-module runs of `tools/typecheck.sh`: the last one that completed over this
code reported exactly one error here — `ExtensionUiHost.kt` missing the
`fillMaxSize` import for its default modifier — which is fixed (import line 4,
use line 48). Later attempts could not complete for reasons outside this
directory: a peer was mid-write on `tools/typecheck.sh` (transient shell syntax
error at line 180), and several concurrent `K2JVMCompiler` processes caused
`fork: Function not implemented` / silent death of background runs. The `:rpc`
stage did complete and refresh `build/typecheck/rpc.jar`. The parent runs the
final integration typecheck on the frozen tree.
