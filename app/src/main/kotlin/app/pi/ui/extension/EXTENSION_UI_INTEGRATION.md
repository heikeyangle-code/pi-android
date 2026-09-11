# Extension UI — integration notes and hand-off

Owner of this directory: the extension-UI agent. Everything else below is a
hand-off to whoever owns `PiSessionViewModel.kt` now.

## What lives here

| File | Contents |
|------|----------|
| `ExtensionUi.kt` | Protocol model: `ExtensionDialogMethod`, `ExtensionDialog`, `ExtensionAnswer`, `ExtensionNotice`, `ExtensionStatus`, `ExtensionWidget`, `WidgetPlacement`, `ComposerFill`, `noticeToneOf`, and `ExtensionDialogQueue` (the FIFO policy). |
| `ExtensionDialogs.kt` | Material 3 renderings of `select` / `confirm` / `input` / `editor`, including the live countdown. |
| `ExtensionUiHost.kt` | The single overlay mount: snackbar queue for `notify` + `extension_error`, and the dialog host. Mounted once by `PiRoot` (line 177). Do not mount it anywhere else. |
| `ExtensionChrome.kt` | `ExtensionStatusRow` (`setStatus`), `ExtensionWidgetStack` (`setWidget`), `windowTitleOf` (`setTitle`). |

## API contract with the ViewModel (already present in the tree)

```
UiState.extensionDialog        : ExtensionDialog?        // queue head, the one modal
UiState.extensionDialogBacklog : Int                     // waiting behind the head
UiState.extensionStatuses      : List<ExtensionStatus>
UiState.extensionWidgets       : List<ExtensionWidget>
UiState.windowTitle            : String?
UiState.composerFill           : ComposerFill?
UiState.notices                : List<ExtensionNotice>

PiSessionViewModel.answerDialog(id, answer)     // id-addressed; drops stale ids
PiSessionViewModel.consumeNotice(seq)
PiSessionViewModel.consumeComposerFill(seq)
```

`answerDialog` takes the id on purpose: a timeout can resolve a dialog in the same
frame the user taps it, and by then the queue head is the *next* request. An
answer whose id is not the current head is dropped, so a stale tap can never
resolve another extension's promise.

## Required patch — the teardown drain comment (not this agent's file)

`PiSessionViewModel.kt`, lines 1075–1082. Reason: `editor` has **no agent-side
timer** (`src/modes/rpc/rpc-mode.ts` — `editor(title, prefill)` has no `opts`
parameter at all and builds an un-timed promise; `select`/`confirm`/`input` do arm
pi's own timer). So an unanswered `editor` hangs pi forever, and the drain is the
only thing that answers a request queued while no host was composed.

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
     * **This drain is load-bearing — do not remove it as redundant.** A request
     * can be queued while no host is composed (app backgrounded, a destination
     * without the overlay, teardown mid-request), and for `select`/`confirm`/
     * `input` pi resolves its own timer anyway; but `editor` has **no agent-side
     * timer at all** (`rpc-mode.ts` `editor()` is an un-timed promise with no
     * timeout options in its signature), so an unanswered `editor` hangs pi
     * forever. Answering on teardown is the only thing between "the UI went
     * away" and "pi is wedged". The write may be undeliverable (a dead pipe),
     * which is fine — nobody is blocked then.
     */
```

## Verification

`tools/typecheck.sh` was last fully executed over this code by this agent before
peers began editing `tools/typecheck.sh` and the shared `build/typecheck/` scratch
tree concurrently. That run reported exactly one error in this directory —
`ExtensionUiHost.kt` missing the `fillMaxSize` import for its default modifier —
which is fixed (import at line 4, use at line 48). Later re-runs could not finish:
the script was caught mid-write by a peer (syntax error at line 180), and
background runs died with `fork: Function not implemented` under the load of
several concurrent `K2JVMCompiler` processes. The `:rpc` stage of the last attempt
did complete and produced a fresh `build/typecheck/rpc.jar`.
