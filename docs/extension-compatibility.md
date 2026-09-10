# Extension compatibility audit: `pi` 0.85.1 hosted by the Android app over `--mode rpc`

Audit of how much of `pi`'s extension system an Android app can host while driving
pi over its `--mode rpc` JSONL protocol (stdio, as the app does today).

Authority is the source, not the docs. Every non-obvious claim carries a
`file:line` citation. Anything I could not verify is labelled **UNVERIFIED** with
the reason.

## 0. Snapshot, method, vocabulary

| | Value |
|---|---|
| pi (authority) | `/root/pi-src` @ `bbb61e34aaf231639fdaaad1adbd757947034eac` (`packages/coding-agent` version 0.85.1) |
| App audited | `/root/pi-android` @ `278d3d113e42dd92f44a9822497c3240761e76c7` **plus uncommitted working-tree changes** |
| Audit time | 2026-09-10 ~15:12 UTC |

The app tree was being modified while this audit ran: `git status` at audit time
showed `M app/.../PiSessionViewModel.kt` and an untracked
`app/src/main/kotlin/app/pi/ui/extension/` package that implements the
`extension_ui_request` sub-protocol. **The "app" column below grades the working
tree, not committed `HEAD`**, because the working tree is what will ship next.
Where `HEAD` differs materially, the row says so. Content hashes for the graded
snapshot:

```
1a795d50…  app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt          (688 lines)
8300c61f…  app/src/main/kotlin/app/pi/ui/PiRoot.kt
ef23fc60…  app/src/main/kotlin/app/pi/ui/extension/ExtensionUi.kt        (228 lines, untracked)
0da04b13…  app/src/main/kotlin/app/pi/ui/extension/ExtensionDialogs.kt   (untracked)
ca807d71…  app/src/main/kotlin/app/pi/ui/extension/ExtensionUiHost.kt    (untracked)
1d29ea1b…  app/src/main/kotlin/app/pi/ui/extension/ExtensionChrome.kt    (untracked)
d1058955…  rpc/src/main/kotlin/app/pi/rpc/Events.kt                      (505 lines)
3b056ffb…  rpc/src/main/kotlin/app/pi/rpc/Commands.kt                    (279 lines)
4873b31b…  rpc/src/main/kotlin/app/pi/rpc/Transcript.kt                  (1380 lines)
844a1893…  rpc/src/main/kotlin/app/pi/rpc/Responses.kt                   (210 lines)
```

### 0.1 Delta after the audited snapshot (read this before trusting a "no call site")

The same parallel work continued after the snapshot above. At **15:28 UTC** `git
status` showed these additional untracked/modified files, which I inspected only
superficially — **I did not re-run the audit against them**:

```
?? app/src/main/kotlin/app/pi/engine/PiEngineApi.kt      typed API over ~30 RPC commands
?? app/src/main/kotlin/app/pi/rpc/SessionEntries.kt      session-entry parsing
?? app/src/main/kotlin/app/pi/rpc/Messages.kt            SourceInfo + message kinds
?? app/src/main/assets/pi-extensions/pi-highlight/       another bundled extension
?? docs/known-gaps.md                                    (another author's document)
 M rpc/src/main/kotlin/app/pi/rpc/Responses.kt           now reads sourceInfo (74ce58ef…)
 M app/src/main/kotlin/app/pi/engine/PiEngineSession.kt  (57524470…)
 M app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt   (c9075370…)
 M app/src/main/kotlin/app/pi/ui/screens/WorkbenchScreen.kt
```

What this changes in the tables below:

- The §1.0 observation "only 8 of 33 commands have a call site" is now **stale at
  the wire layer**: `PiEngineApi.kt` exposes `get_entries`, `get_tree`,
  `get_commands`, `switch_session`, `export_html`, `get_available_models`,
  `get_session_stats` and more (`PiEngineApi.kt:56-350`). Rows below graded
  **MISSING** for "the command is never sent" should be read as "the typed call
  exists; no *UI* consumes it yet" where the API layer covers that command — the
  user-visible gap is unchanged, only the reason is narrower.
- §6.3 (`get_commands` palette) and §3.7 item 1 (the `sourceInfo` vs flat
  `location`/`path` split) are addressed at the parsing layer: `Responses.kt:245-266`
  now keeps `sourceInfo` and tolerates both shapes, with the conflict documented in
  `rpc/Messages.kt:13-20`. The palette UI itself is still absent.
- §6.7 (`get_entries` on attach) is partially addressed by `rpc/SessionEntries.kt`;
  whether `PiEngineSession` now replays history into the transcript was not
  re-checked.
- The BLOCKING finding and the §3 dialog checklist are unaffected: no file in the
  delta touches pi, and `editor` having no timeout is a pi property, not an app one.

Treat every "what the app does" cell as **true at the audited snapshot** and
re-verify against the current tree before acting on a specific row.

Grades, used strictly:

- **BLOCKING** — pi waits forever, or the turn cannot complete, unless the client
  answers. A hang, not a degradation.
- **DEGRADED** — the surface works but the user gets a different (usually poorer)
  experience.
- **MISSING** — the surface produces nothing a user can see or reach; silently
  lost. A MISSING surface is almost always invisible to the app's own tests.
- **N/A** — not reachable or not meaningful for this host.

Effort is the app-side cost to close the gap: **XS** (hours), **S** (≤1 day),
**M** (2–4 days), **L** (1–2 weeks), **XL** (architectural change).

Grade totals across the whole inventory, counted from the tables in §1 (129 graded
rows; every row carries exactly one grade, and merged rows cover all 36 events and
all `ExtensionAPI`/`ExtensionUIContext` members):

| Grade | Count | Meaning here | Where the weight is |
|---|---|---|---|
| BLOCKING | 1 | `ctx.ui.editor()` with no client answer | §1.2 |
| DEGRADED | 18 | works, differently | tools, events, wire shapes |
| MISSING | 68 | invisible to the user | §1.2 (16), §1.5 (17), §1.1 (15) |
| N/A | 42 | not this host's problem | extension-side and TUI-input surfaces |

---

## 1. Surface inventory

Columns: **surface** | **what pi does** | **behaviour in `--mode rpc`** | **what the app does (snapshot)** | **gap** | **effort**.

The RPC column is the same for every row unless stated otherwise: RPC is a
*transport* — pi runs the full extension system in-process in every mode, and the
only thing that changes per mode is which `ExtensionUIContext` is installed.
`runRpcMode` installs a context implemented over stdout/stdin
(`packages/coding-agent/src/modes/rpc/rpc-mode.ts:136-311`), binds it with
`mode: "rpc"` (`:319-321`), and streams `AgentSessionEvent`s via
`toJsonEvent` (`:355-360`). Everything pi does *not* put on that stream is
invisible to the app, no matter how the app is written.

### 1.0 Summary of the app's actual RPC usage

The app's command builders cover all 33 RPC commands
(`rpc/src/main/kotlin/app/pi/rpc/Commands.kt:42-279`), but only 8 have a call
site: `prompt`, `steer`, `follow_up`, `set_thinking_level`, `get_state`,
`clear_queue`, `abort` (`app/src/main/kotlin/app/pi/engine/PiEngineSession.kt:178-193`,
`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:613-650`) and
`extension_ui_response` (`PiSessionViewModel.kt:442-452`). `get_commands`,
`get_entries`, `get_tree`, `get_session_stats`, `get_available_models`,
`export_html`, `switch_session`, `fork`, `clone`, `compact`, `bash`,
`set_session_name` and the rest are dead code awaiting UI. Several "MISSING"
grades below are consequences of that, not of RPC.

### 1.1 `ExtensionAPI` methods

`ExtensionAPI` is defined at `packages/coding-agent/src/core/extensions/types.ts:1252-1506`.

| Surface | What pi does | Behaviour in `--mode rpc` | What the app does | Gap | Effort |
|---|---|---|---|---|---|
| `on(event, handler)` | Registers a handler for any of the 36 events in §1.4 (`types.ts:1257-1301`) | All events fire; handler errors are caught per-handler and reported through the error listener, which RPC serialises as `extension_error` (`runner.ts:851-882`; `rpc-mode.ts:348-350`) | Snackbar + transcript row via the `ExtensionError` event; **`extensionPath` and `event` are dropped** (`rpc/Events.kt:398`) | **DEGRADED** — "an extension errored" with no way to say which one or in which event | XS |
| `registerTool(tool)` | Registers an LLM-callable tool; callable at load and after startup without `/reload` (`types.ts:1308-1310`; `docs/extensions.md:1371-1375`) | Fully works: the tool reaches the model and its calls arrive as `tool_execution_start/update/end` with `toolName`, `args`, `result.content`, `result.details` (`agent-session.ts:798-846`; `agent-loop.ts:444-474`) | Renders a generic tool block from `resultText`/`details`; partial updates rendered (`rpc/Transcript.kt:571-573`; `rpc/Events.kt:313-331`) | **DEGRADED** — no `label`, no `promptSnippet`, no custom rendering; `details` from an unknown extension is passed to the app's diff/ANSI heuristics and mostly ignored | S |
| `registerCommand(name, opts)` | Slash command with `handler(args, ctx)`, dispatchable via `prompt("/name args")` even while streaming (`types.ts:1317`; `agent-session.ts:1181-1190`, `1331-1356`) | Works end-to-end: `prompt` with a `/`-prefixed string dispatches on the pi side (`agent-session.ts:1183-1189`) and the command is listed by `get_commands` (`rpc-mode.ts:685-692`) | **No discovery**: `get_commands` is never called, and there is no slash palette. Textual `/name args` typed in the composer does dispatch. `PiResponses.slashCommands` exists and is unused (`rpc/Responses.kt:96-112`) | **MISSING** — command exists but the user cannot find it | S |
| `registerShortcut(key, opts)` | Binds an app keybinding; conflicts with built-ins are diagnosed (`types.ts:1320-1326`; `runner.ts:544-597`) | Not representable: no keyboard in RPC, and `getShortcuts` is only consulted by interactive mode (`interactive-mode.ts:6034`) | Nothing | **N/A** — a phone has no Ctrl-key surface; the app must expose equivalent commands instead | — |
| `registerFlag(name, opts)` / `getFlag(name)` | CLI flags parsed from argv into `runtime.flagValues` (`types.ts:1329-1345`) | Works: values come from the process argv, and the app controls that argv (`PiEngineHost.kt:103-107`) | The app passes no extension flags | **N/A** — but see §6.4: flags are the only way to configure an extension from the app without a settings file | S |
| `registerMessageRenderer(customType, renderer)` | TUI renders a `CustomMessage` with the extension's own Component (`types.ts:1352`; `interactive-mode.ts:3597`) | **Renderer is never called.** The message still reaches the wire as a normal message event (`agent-session.ts:1537-1547`) | The app parses only `role`/`text`/`stopReason`/`usage` from `message_end` and calls `finishStreaming()`; `customType`, `display` and `details` are discarded (`rpc/Events.kt:303-311`; `rpc/Transcript.kt:563`) | **MISSING** — extension-rendered messages do not appear at all | M |
| `registerMarkdownTransformer(fn)` | Pure `string → string` transform applied to user/assistant markdown (`types.ts:1355`) | **Never called**: the only consumer is interactive mode (`interactive-mode.ts:2024-2025`, `3235`, `3645`) | Nothing | **MISSING** — but trivially recoverable: the hook is pure text and could be re-implemented app-side only if the app knew about it (it cannot; the transform lives in the extension) | M |
| `registerEntryRenderer(customType, renderer)` | TUI renders a `CustomEntry` (`types.ts:1358`; `interactive-mode.ts:3558`) | **Never called.** The entry data does reach the wire via `entry_appended` (`agent-session.ts:2616-2621`) | `entry_appended` is parsed to `entryId`+`entryType` only, the payload is dropped, and the event is a no-op in the reducer (`rpc/Events.kt:400-406`; `rpc/Transcript.kt:627`) | **MISSING** — extension state is invisible | M |
| `sendMessage(msg, opts)` | Appends a custom message (in context, optionally displayed) with `triggerTurn`/`deliverAs` (`types.ts:1365-1368`) | Works: `_appendCustomMessage` emits `message_start`+`message_end` with `role:"custom"`, `customType`, `content`, `display`, `details` (`agent-session.ts:1537-1547`) | Neither event is rendered: `message_start` has no case, `message_end` only stops the spinner (`rpc/Transcript.kt:563`) | **MISSING** — a `display: true` custom message is silently swallowed | M |
| `sendUserMessage(content, opts)` | Sends a user message and always triggers a turn; `source:"extension"` (`types.ts:1375-1378`; `agent-session.ts:1569-1605`) | Works: arrives as a `user` message event | The app draws user bubbles only from its own local `onUserPrompt` (`rpc/Transcript.kt:548-558`); an incoming `message_start`/`message_end` with `role:"user"` is not rendered | **MISSING** — extension-driven turns look like the model talking to itself | M |
| `appendEntry(customType, data)` | Persists a `CustomEntry` outside LLM context; the documented way to keep extension state across restarts (`types.ts:1381`; `docs/extensions.md:1477-1493`) | Works: `entry_appended` carries the **full entry** (`agent-session.ts:2616-2621`) and `get_entries` returns it | Payload dropped; `get_entries` never called; `TranscriptReducer.onEntry`/`seedFromHistory` exist but have no caller (`rpc/Transcript.kt:905`, `958`) | **MISSING** — restart-surviving extension state is unreachable | M |
| `setSessionName(name)` / `getSessionName()` | Session display name (`types.ts:1388-1391`) | `set_session_name` command, `session_info_changed` event, and `get_state.sessionName` (`rpc-mode.ts:661-668`, `agent-session.ts:159`) | `session_info_changed` is parsed (`rpc/Events.kt:408`) but unhandled in the ViewModel; `set_session_name` never sent | **MISSING** | S |
| `setLabel(entryId, label)` | Bookmarks an entry (`types.ts:1394`) | No RPC command and no event; only the session tree carries labels and the app has no tree UI (`docs/rpc.md:749-772`) | Nothing | **MISSING** | M |
| `exec(command, args, opts)` | Runs a process with the pi process's permissions (`types.ts:1397`) | Runs extension-side; nothing crosses the wire | Nothing to do | **N/A** | — |
| `getActiveTools()` | Active tool names (`types.ts:1400`) | Extension-side only | Nothing | **MISSING** (no tools UI) | M |
| `getAllTools()` | Tool names, schemas, prompt guidelines, provenance (`types.ts:1403`) | Extension-side only; `get_commands` does not include tools | Nothing | **MISSING** (no tools UI) | M |
| `setActiveTools(names)` | Enables/disables tools mid-run, including deferred/`search_tools` loading (`types.ts:1406`; `docs/extensions.md:2371-2404`) | Works on the pi side; the app never observes the tool set changing, so a "current tools" display would be stale | Nothing | **MISSING** | M |
| `getCommands()` | `SlashCommandInfo[]` in the session (`types.ts:1409`) | Extension-side; the same data is available as the `get_commands` command (`rpc-mode.ts:682-713`) | Not called | **MISSING** | XS |
| `setModel(model)` | Switches the session model, returns false when auth is missing (`types.ts:1419`) | Works, but **the change is not on the wire**: `model_select` is emitted only to extensions, never to session listeners (`agent-session.ts:1659-1670`) | Nothing (no model UI yet) | **DEGRADED** — a model changed by an extension cannot be displayed; the app must re-poll `get_state` | S |
| `getThinkingLevel()` / `setThinkingLevel(level)` | Thinking level, clamped to model capability (`types.ts:1422-1428`) | Works, and the change *is* on the wire: `_emit({type:"thinking_level_changed"})` (`agent-session.ts:1830`) alongside the extension-only `thinking_level_select` (`:1831-1832`) | `thinking_level_changed` handled (`rpc/Transcript.kt:575-578`); `PiSessionViewModel.setThinking` calls `set_thinking_level` (`PiSessionViewModel.kt:650`) | **N/A** — complete | — |
| `registerProvider(name, config)` | Adds/overrides a provider, models, OAuth login, custom `streamSimple` (`types.ts:1486-1487`) | Works immediately after initial load (`runner.ts:373-425`); registered providers become "configured" so their models join the availability snapshot (`model-runtime.ts:766-771`), hence `get_available_models` | `get_available_models` never called | **MISSING** — extension-provided models are unreachable because the app has no model picker | M |
| `unregisterProvider(name)` | Removes the provider, restores overridden built-ins (`types.ts:1502`) | Works (`runner.ts:420-425`) | Nothing | **MISSING** | S |
| `events` (`EventBus`) | Extension-to-extension pub/sub (`types.ts:1505`) | Works in-process; nothing crosses the wire | Nothing needed | **N/A** | — |

### 1.2 `ExtensionUIContext` methods

Interface at `types.ts:133-284`. The RPC implementation is
`rpc-mode.ts:136-311`; the no-op fallback used by print/json (and by the SDK
until an embedder binds a context) is `runner.ts:236-254`, and "does this host
have UI?" is literally `this.uiContext !== noOpUIContext` (`runner.ts:492-494`).

| Surface | What pi does | Behaviour in `--mode rpc` | What the app does | Gap | Effort |
|---|---|---|---|---|---|
| `select(title, options, opts)` | Blocking selector (`types.ts:135`) | `extension_ui_request{method:"select",title,options,timeout}` → `{value}`/`{cancelled:true}` (`rpc-mode.ts:137-140`) | Queued, rendered as a dialog, answered by id (`PiSessionViewModel.kt:308-343`, `431-465`; `ui/extension/ExtensionDialogs.kt:78-120`) | **N/A** — complete. **Fixed in the working tree; `HEAD` sends no response at all** | — |
| `confirm(title, message, opts)` | Blocking yes/no (`types.ts:138`) | `method:"confirm"` → `{confirmed:boolean}` (`rpc-mode.ts:142-145`) | Rendered and answered (`PiSessionViewModel.kt:437-453`) | **N/A** — complete (working tree) | — |
| `input(title, placeholder, opts)` | Blocking text input (`types.ts:141`) | `method:"input"` → `{value}` (`rpc-mode.ts:147-150`) | Rendered and answered | **N/A** — complete (working tree) | — |
| `editor(title, prefill)` | Blocking multi-line editor (`types.ts:224`) | `method:"editor"`, **no `timeout` field, and `opts` is not even in the signature**; the implementation ignores signal and timeout entirely (`rpc-mode.ts:254-271`) | Rendered and answered; the app knows the timer must be client-side (`ui/extension/ExtensionDialogs.kt:208-216`) | **BLOCKING** — an extension that never cancels an `editor()` wedges the pi process forever; the user can always answer, but if the app is killed the pi process is stuck | S (app) / unfixable from the app if the answer is never sent |
| `notify(message, type)` | TUI toast (`types.ts:144`) | `method:"notify"`, fire-and-forget (`rpc-mode.ts:152-161`) | Snackbar queue, preserved across arrivals (`PiSessionViewModel.kt:348-352`, `595-602`) | **N/A** — complete (working tree) | — |
| `onTerminalInput(handler)` | Raw keystrokes, interactive only (`types.ts:147`) | Returns a no-op unsubscribe (`rpc-mode.ts:163-166`) | Nothing | **MISSING** — extensions that add key handling are inert | L (needs a terminal surface) |
| `setStatus(key, text)` | Footer status row; `undefined` clears (`types.ts:150`) | `method:"setStatus"` fire-and-forget (`rpc-mode.ts:168-177`) | Upsert-by-key row under the AppBar (`PiSessionViewModel.kt:386-397`; `ui/extension/ExtensionChrome.kt:47`) | **N/A** — complete (working tree) | — |
| `setWorkingMessage(msg)` | Replaces the streaming status line (`types.ts:153`) | **No-op** (`rpc-mode.ts:179-181`) | Nothing (the app has its own spinner) | **MISSING** | S |
| `setWorkingVisible(bool)` | Shows/hides the loader row (`types.ts:156`) | **No-op** (`rpc-mode.ts:183-185`) | Nothing | **MISSING** | S |
| `setWorkingIndicator(opts)` | Custom spinner frames (`types.ts:166`) | **No-op** (`rpc-mode.ts:187-189`) | Nothing | **MISSING** | S |
| `setHiddenThinkingLabel(label)` | Label for collapsed thinking (`types.ts:169`) | **No-op** (`rpc-mode.ts:191-193`) | Nothing; the app has its own thinking label | **MISSING** | XS |
| `setWidget(key, lines\|factory, opts)` | Panel above/below the editor (`types.ts:172-177`) | **String arrays only**; component factories are silently dropped (`rpc-mode.ts:195-208`) | Rendered above/below the composer, cleared by empty lines (`PiSessionViewModel.kt:406-421`; `ui/extension/ExtensionChrome.kt:90`) | **DEGRADED** — the text half works (working tree); any component-factory widget is invisible | S |
| `setFooter(factory)` | Replaces the whole footer (`types.ts:185-189`) | **No-op** (`rpc-mode.ts:210-212`) | Nothing | **MISSING** | L (arbitrary components) |
| `setHeader(factory)` | Replaces the startup header (`types.ts:192`) | **No-op** (`rpc-mode.ts:214-216`) | Nothing | **MISSING** | L |
| `setTitle(title)` | Terminal tab title (`types.ts:195`) | `method:"setTitle"` fire-and-forget (`rpc-mode.ts:218-226`) | Mapped to the AppBar title (`PiSessionViewModel.kt:362-364`; `screens/ChatScreen.kt:149-150`) | **DEGRADED** — the wire field is a *terminal window title*; reusing it as the app's title is an interpretation, not a contract | XS |
| `custom(factory, opts)` | Hands the whole screen to a Component until `done(value)` (`types.ts:198-212`) | **Returns `undefined` immediately** (`rpc-mode.ts:228-231`) | Nothing — no dialog, no notice | **MISSING** and the most damaging one: an extension using `custom()` for required input proceeds with `undefined` and can make wrong decisions silently (`docs/rpc.md:1195-1196`) | XL (see §2/§6) |
| `pasteToEditor(text)` | Paste with collapse handling (`types.ts:215`) | Delegates to `setEditorText` (`rpc-mode.ts:233-236`) | Handled as `set_editor_text` (`PiSessionViewModel.kt:366-374`) | **DEGRADED** — no paste-collapse semantics for large text | S |
| `setEditorText(text)` | Sets the composer text (`types.ts:218`) | `method:"set_editor_text"` (`rpc-mode.ts:238-246`) | One-shot fill keyed by sequence, applied to the composer (`PiSessionViewModel.kt:366-374`, `468-472`) | **N/A** — complete (working tree) | — |
| `getEditorText()` | Reads the composer (`types.ts:221`) | **Always returns `""`** — "synchronous method can't wait for RPC response" (`rpc-mode.ts:248-252`) | Nothing | **MISSING** — and *silently wrong*, which is worse than absent: an extension that reads the draft to decide something always sees an empty editor | M (needs editor state mirrored into the guest) |
| `addAutocompleteProvider(factory)` | Composes completions (`types.ts:227`) | **No-op** (`rpc-mode.ts:273-275`) | Nothing | **MISSING** | L |
| `setEditorComponent(factory)` / `getEditorComponent()` | Replaces the editor component (`types.ts:262-265`) | **No-op / `undefined`** (`rpc-mode.ts:277-284`) | Nothing | **MISSING** — vim-style editor extensions are inert | XL |
| `theme` (getter) | The active `Theme` object (`types.ts:268`) | Returns pi's built-in `theme` singleton (`rpc-mode.ts:286-288`) | Nothing | **DEGRADED** — an extension reading colours gets the default dark theme, not the user's; cosmetic only | XS |
| `getAllThemes()` | Theme names + paths (`types.ts:271`) | **Returns `[]`** (`rpc-mode.ts:290-292`) | Nothing | **MISSING** — extension theming UIs see no themes | S |
| `getTheme(name)` | Loads a theme without switching (`types.ts:274`) | **Returns `undefined`** (`rpc-mode.ts:294-296`) | Nothing | **MISSING** | S |
| `setTheme(theme)` | Switches the active theme (`types.ts:277`) | **Returns `{success:false, error:"Theme switching not supported in RPC mode"}`** (`rpc-mode.ts:298-301`) | Nothing | **MISSING** — an extension-driven theme switch fails with an error the app never shows | S |
| `getToolsExpanded()` | Tool-output expansion state (`types.ts:280`) | **Always `false`** (`rpc-mode.ts:303-306`) | Nothing | **MISSING** | S |
| `setToolsExpanded(bool)` | Sets expansion (`types.ts:283`) | **No-op** (`rpc-mode.ts:308-310`) | Nothing | **MISSING** | S |

### 1.3 `ExtensionContext` / `ExtensionCommandContext` / `ReplacedSessionContext`

| Surface | What pi does | Behaviour in `--mode rpc` | What the app does | Gap | Effort |
|---|---|---|---|---|---|
| `ctx.mode` | `"tui" \| "rpc" \| "json" \| "print"` (`types.ts:307`, `:313`) | `"rpc"` (`rpc-mode.ts:321`) | — (extension-side) | **N/A** — and this is the *only* value that lets an extension adapt. Extensions written against `mode === "tui"` will take their non-TUI path, which is the honest reason "100% of pi's capability" is unreachable over RPC | — |
| `ctx.hasUI` | Dialog capability (`types.ts:315`) | **`true`** (`runner.ts:492-494` + `rpc-mode.ts:320`) — `custom()` returning `undefined` while `hasUI` is `true` is exactly the trap described in §5.1 | — | **DEGRADED** — extensions trust `hasUI` and then call `custom()`/`setFooter()` | — |
| `ctx.cwd` | Session cwd (`types.ts:317`) | Correct (the guest workspace, `PiEngineHost.kt:114`) | — | **N/A** | — |
| `ctx.sessionManager` | Read-only entries/branch/leaf (`types.ts:319`) | Fully works in-process | Nothing | **N/A** | — |
| `ctx.modelRegistry` | API-key resolution (`types.ts:321`) | Works in-process | Nothing | **N/A** | — |
| `ctx.model` | Current model (`types.ts:323`) | Works | The app has no model indicator | **MISSING** (display only) | S |
| `ctx.scopedModels` | Models scoped by `--models`/settings (`types.ts:328`) | Works; the app spawns without `--models` so it is empty | Nothing | **N/A** | — |
| `ctx.thinkingLevel` | Current level (`types.ts:330`) | Works | Nothing (extension-side) | **N/A** | — |
| `ctx.isIdle()` | Not streaming (`types.ts:332`) | Works | Nothing | **N/A** | — |
| `ctx.isProjectTrusted()` | Trust incl. temporary/CLI overrides (`types.ts:334`) | Works, and is the app's only honest signal that project resources were skipped (§4) | Nothing | **MISSING** (no trust UI) | M |
| `ctx.signal` | AbortSignal while streaming (`types.ts:336`) | Works | Nothing | **N/A** | — |
| `ctx.abort()` | Aborts the operation (`types.ts:338`) | Works (`runner.ts:776-779`) | Nothing | **N/A** | — |
| `ctx.hasPendingMessages()` | Queue non-empty (`types.ts:340`) | Works | Nothing | **N/A** | — |
| `ctx.shutdown()` | Graceful exit (`types.ts:342`) | Sets `shutdownRequested`; pi exits after the next `agent_settled` (`rpc-mode.ts:345-347`, `747-750`) | The app sees the process exit and reports "引擎已退出" — no attribution to an extension | **DEGRADED** | S |
| `ctx.getContextUsage()` | Token usage (`types.ts:344`) | Extension-side; `get_session_stats` exposes `contextUsage` (`rpc-mode.ts:595-598`; `docs/rpc.md:595`) | `get_session_stats` never called | **MISSING** | S |
| `ctx.compact(opts)` | Triggers compaction, with `onComplete`/`onError` (`types.ts:346`) | Works; `compaction_start`/`compaction_end` are wire events (`agent-session.ts:157`, `161-168`) | Events are handled (`rpc/Transcript.kt:580-581`) | **N/A** | — |
| `ctx.getSystemPrompt()` | Effective system prompt (`types.ts:348`) | Works | Nothing | **MISSING** (no system-prompt viewer) | S |
| `ctx.getSystemPromptOptions()` | Structured prompt inputs (`types.ts:357`) | Works | Nothing | **N/A** | — |
| `ctx.waitForIdle()` | Await idle (`types.ts:360`) | Works | Nothing | **N/A** | — |
| `ctx.newSession(opts)` | New session with setup callback (`types.ts:363-367`) | Works via `runtimeHost.newSession` (`rpc-mode.ts:324`) | `new_session` never sent | **MISSING** | M |
| `ctx.fork(entryId, opts)` | Fork from an entry (`types.ts:370-373`) | Works (`rpc-mode.ts:325-328`); cancellable via `session_before_fork` | `fork` never sent | **MISSING** | M |
| `ctx.navigateTree(targetId, opts)` | Branch navigation with optional summary (`types.ts:376-379`) | Works (`rpc-mode.ts:329-337`) | No tree UI at all | **MISSING** | L |
| `ctx.switchSession(path, opts)` | Switch session file (`types.ts:382-385`) | Works (`rpc-mode.ts:338-340`); `switch_session` also exists as a command | Session switching is done by reading files on disk, not by the RPC command (`PiSessionViewModel.kt:141-152`) | **DEGRADED** — file-driven switching cannot carry a `withSession` callback contract | S |
| `ctx.reload()` | Reload extensions/skills/prompts/themes/context (`types.ts:388`; `agent-session.ts:2841-2866`) | Works **only from inside the guest**: there is no RPC command for it (§5.5) | Nothing | **MISSING** | S |
| `ReplacedSessionContext.sendMessage/sendUserMessage` | Bind messages to the replacement session (`types.ts:396-406`) | Works in-process | Nothing | **N/A** | — |

### 1.4 Lifecycle and interception events

36 events (`types.ts:1086-1113`). The decisive question for every row is whether
the event is *also* an `AgentSessionEvent` (`agent-session.ts:144-185`): the RPC
writer forwards exactly `session.subscribe` events (`rpc-mode.ts:355-360`), so an
extension-only event never reaches the app. Note the systematic origin:
`_emitExtensionEvent`
(`agent-session.ts:765-846`) forwards *some* agent events to extensions; a handler
can then modify state that a later wire event will reflect — that is how
`tool_result` and `message_end` rewrites become visible without their own event.

| Event | What pi does | Behaviour in `--mode rpc` | What the app does | Gap | Effort |
|---|---|---|---|---|---|
| `project_trust` | Decides trust before project resources load; first yes/no wins (`types.ts:521-543`; `runner.ts:204-234`) | Fires, but the context handed to it has `hasUI: false`, so `ctx.ui.select` returns `undefined` (`main.ts:753`; `cli/project-trust.ts:13-30`) | Nothing — no trust handling exists | **MISSING** — see §4; project extensions never load and nothing says why | M |
| `resources_discover` | Contributes skills/prompts/themes after `session_start` (`types.ts:546-557`) | Works in-process; contributed skills then appear in `get_commands` as `skill:` (`agent-session.ts:2493-2516`) | `get_commands` not called | **MISSING** | S |
| `session_start` | `startup\|reload\|new\|resume\|fork` (`types.ts:564-570`) | Extension-side; on the wire only as a side effect (messages, name) | Nothing | **MISSING** | XS |
| `session_info_changed` | Session renamed (`types.ts:573-577`) | Both an extension event and a wire event (`agent-session.ts:159`) | Parsed, unhandled (`rpc/Events.kt:408`) | **MISSING** | XS |
| `session_before_switch` | Cancellable (`types.ts:580-584`) | Cancellation surfaces as `{cancelled:true}` in the `new_session`/`switch_session` response | Those commands are unused | **MISSING** | S |
| `session_before_fork` | Cancellable, can skip conversation restore (`types.ts:587-591`) | `fork` response `{cancelled}` | `fork` unused | **MISSING** | S |
| `session_before_compact` | Cancellable or fully replaces the compaction result (`types.ts:594-604`) | Cancellation shows as `compaction_end.aborted` (`agent-session.ts:161-168`) | Handled generically | **N/A** | — |
| `session_compact` | Compaction succeeded (`types.ts:607-615`) | `compaction_end` (with `result`) | Handled | **N/A** | — |
| `session_compact_failed` | Failure/abort, incl. `fromExtension` (`types.ts:618-630`) | `compaction_end{aborted,errorMessage}` | Handled | **N/A** | — |
| `session_shutdown` | Teardown on quit/reload/replacement (`types.ts:633-638`) | Fires in-process; only visible as process exit | Reported as engine exit | **DEGRADED** | S |
| `session_before_tree` | Cancellable tree navigation (`types.ts:656-660`) | In-process | No tree UI | **MISSING** | L |
| `session_tree` | Post-navigation (`types.ts:663-669`) | In-process | Nothing | **MISSING** | L |
| `context` | Before each LLM call; can replace the message array (`types.ts:688-691`; `runner.ts:1034-1063`) | In-process only. **The rewritten messages are not on the wire**; the app's transcript is a projection of what the model saw *before* the rewrite | Nothing | **DEGRADED** — the app can show content that the model never saw | M |
| `before_provider_request` | Replaces the request payload (`types.ts:694-697`; `runner.ts:1066-1097`) | In-process | Nothing | **N/A** | — |
| `before_provider_headers` | Mutates headers in place; `null` deletes (`types.ts:704-707`) | In-process | Nothing | **N/A** | — |
| `after_provider_response` | Status + headers pre-stream (`types.ts:710-714`) | In-process | Nothing | **N/A** | — |
| `before_agent_start` | Can inject a message and/or chain-replace the system prompt per turn (`types.ts:717-727`; `runner.ts:1131-1194`) | In-process. Injected messages appear on the wire as message events (custom role, §1.1) | Not rendered | **MISSING** | M |
| `agent_start` | Loop started (`types.ts:730-732`) | Wire event | Handled (`rpc/Transcript.kt:564-567`) | **N/A** | — |
| `agent_end` | `messages` + `willRetry` (`types.ts:735-738`) | Wire event with `willRetry` (`agent-session.ts:666`) | Handled | **N/A** | — |
| `agent_settled` | No retry/compaction/continuation pending (`types.ts:741-743`) | Wire event (`agent-session.ts:629`) | Handled; also the app's idle signal | **N/A** | — |
| `ui_prompt_start` / `ui_prompt_end` | "pi is now blocked on a dialog", with `kind` (`types.ts:748-761`; `runner.ts:453-486`) | Extension-only; **not on the wire** | Nothing — but the app's own dialog queue is the equivalent signal | **N/A** (extension-facing) | — |
| `turn_start` / `turn_end` | Turn boundaries with `turnIndex`, `toolResults` (`types.ts:764-776`) | Wire events | Handled; the app's comment claims pi sends no `turnIndex` (`rpc/Events.kt:76-83`) — **wrong**: `AgentEvent`'s `turn_start` carries `turnIndex` (`types.ts:764-768`) | **DEGRADED** — the app discards a field it could use | XS |
| `message_start` | Message (incl. `custom`) begins (`types.ts:779-782`) | Wire event | Ignored except as a hint; the app draws user bubbles locally | **MISSING** for `custom`/extension-sent `user` roles | M |
| `message_update` | Token deltas (`types.ts:785-789`) | Wire event; cumulative `message`/`partial` **stripped** (`json-event.ts:40-61`) | Reassembles text/thinking/tool-call deltas | **N/A** | — |
| `message_end` | Can replace the message (same role required) (`types.ts:792-795`; `runner.ts:885-924`) | The replacement becomes the persisted message and is what `message_end` on the wire carries (`agent-session.ts:805-817`) | Renders only role/text/usage; a *rewritten* message is rendered as-is (correct) but a `custom` one is not | **DEGRADED** | M |
| `tool_execution_start/update/end` | Tool lifecycle (`types.ts:798-821`) | All three on the wire | Handled, incl. streaming partials | **N/A** | — |
| `model_select` | Model changed, with `source` (`types.ts:830-835`) | **Not on the wire at all** — `_emitModelSelect` calls only `_extensionRunner.emit` (`agent-session.ts:1659-1670`) | Nothing | **MISSING** — the app cannot know the model changed | S |
| `thinking_level_select` | Level changed (`types.ts:838-842`) | Extension-only; the wire carries the parallel `thinking_level_changed` (`agent-session.ts:1830-1832`) | Handled via the wire twin | **N/A** | — |
| `user_bash` | Intercepts `!`/`!!`; can supply `operations` or a full result (`types.ts:849-857`; `runner.ts:1005-1031`) | Fires for the `bash` RPC command, and `eventResult.result` short-circuits execution (`rpc-mode.ts:563-576`) | `bash` command never sent by the app | **MISSING** | S |
| `input` | Raw input before expansion; `source: interactive\|rpc\|extension`; `continue`/`transform`/`handled` (`types.ts:867-883`; `runner.ts:1246-1285`) | Fires with `source:"rpc"` for `prompt` (`rpc-mode.ts:402`) and for `steer`/`follow_up` (`rpc-mode.ts:419`, `424`; `agent-session.ts:1425-1438`) | Nothing needed | **N/A** — works, and `{action:"handled"}` returns a `success:true` prompt response with no turn, which the app will render as "nothing happened" | S |
| `tool_call` | Block/allow, mutate `input` in place (`types.ts:894-954`; `runner.ts:982-1002`) | A blocked call becomes an **error tool result** with the reason (`agent-loop.ts:626-655`) and reaches the app as `tool_execution_end{isError:true}` (`agent-loop.ts:470-472`) | Rendered as a failed tool block | **DEGRADED** — the block reason is shown, but not *which extension* blocked it | S |
| `tool_result` | Can replace `content`/`details`/`isError`/`usage`, chained in load order (`types.ts:1144-1149`; `runner.ts:927-980`) | The rewrite happens **before** `tool_execution_end`, so the app sees the rewritten content (`agent-session.ts:504-535`; `agent-loop.ts:731-758`) | Renders the rewritten content | **DEGRADED** — the app cannot show "the extension changed this" | S |

### 1.5 Commands, resources, packaging, rendering, persistence

| Surface | What pi does | Behaviour in `--mode rpc` | What the app does | Gap | Effort |
|---|---|---|---|---|---|
| `get_commands` | Returns extension commands + prompt templates + skills, tagged `source` (`rpc-mode.ts:682-713`) | Works; payload is `{name, description, source, sourceInfo}` — **`sourceInfo`, not the flattened `location`/`path` the docs show** (`rpc-mode.ts:690`; `source-info.ts:3-12`; docs claim at `docs/rpc.md:838-851`) | Never called; the reader drops `sourceInfo` too (`rpc/Responses.kt:96-112`), so provenance is lost even when it is wired | **MISSING** | S |
| Slash commands | Extensions, templates and skills are all invokable via `prompt("/name")` (`agent-session.ts:1211-1216`) | Works textually | No palette; no `/` handling in the composer, so `/reload` is sent to the model as literal text (`PiSessionViewModel.kt:606-624`) | **DEGRADED** | S |
| Built-in slash commands | `/reload`, `/trust`, `/model`, … (`slash-commands.ts:19-43`) | **Excluded from `get_commands`** and not handled by `prompt` (`docs/rpc.md:853`; `agent-session.ts:1331-1343` only dispatches *extension* commands) | Nothing | **MISSING** — the app cannot invoke a single built-in command, including `/reload` and `/trust` | S |
| Skills | `SKILL.md` discovery; `/skill:name` expands to a `<skill>` block (`agent-session.ts:1362-1389`; `docs/skills.md:24-42`) | Works; skills are listed by `get_commands` and expanded on `prompt`/`steer`/`follow_up` (`docs/rpc.md:69`) | Nothing | **MISSING** (discovery) | S |
| Prompt templates | `.md` templates expanded on `prompt` (`agent-session.ts:1215`; `docs/prompt-templates.md:9-17`) | Works; listed as `source:"prompt"` | Nothing | **MISSING** (discovery) | S |
| Extension-contributed skills/templates/themes | `resources_discover` → merged with `scope:"temporary"`, `source:"extension:<name>"` (`agent-session.ts:2493-2544`) | Works and shows up in `get_commands` | Nothing | **MISSING** | S |
| Themes | Theme JSON, `ctx.ui.setTheme`, hot-reload on edit (`docs/themes.md:17-28`, `141`) | `getAllThemes()`→`[]`, `getTheme()`→`undefined`, `setTheme()`→error (`rpc-mode.ts:290-301`) | Nothing | **MISSING** | M |
| Packages (`pi install`) | npm/git/local packages, dedup, autoload, per-type filters (`docs/packages.md:22-39`, `64-133`) | Works entirely behind the CLI; nothing on the wire. npm install uses `install <spec> --prefix <root> --legacy-peer-deps` (`package-manager.ts:1785-1806`); **a git package's own dependencies are installed with `install --omit=dev`** (`package-manager.ts:1772-1778`) | No install UI; no `pi install` invocation | **MISSING** | M |
| Package install trust gate | Project packages install only for a trusted project (`package-manager.ts:1740-1745`, `2398-2402`) | — | — | **MISSING** (no trust UI; see §4) | M |
| Tool rendering (`renderCall`/`renderResult`) | TUI Components per tool row (`types.ts:491-500`; `interactive-mode/components/tool-execution.ts:116-121`) | **Never called on the wire.** But **`export_html` does call them**: `createToolHtmlRenderer` invokes the tool definition's renderers and converts ANSI→HTML (`export-html/tool-renderer.ts:99-156`, consumed at `:197`, `:211`) | `export_html` never called | **MISSING** live; **recoverable via `export_html`** — the only non-TUI path by which extension rendering reaches a client | M |
| Message/entry renderers | Components for custom messages/entries (`interactive-mode.ts:3558`, `3597`) | Never called | Nothing | **MISSING** | M |
| Markdown transformer | `string → string` (`types.ts:1207`) | Never called (`interactive-mode.ts:2024-2025`) | Nothing | **MISSING** | M |
| Session persistence (`appendEntry`) | Custom entries survive restarts; documented recovery is to scan entries on `session_start` (`docs/extensions.md:1486-1492`) | Works; `get_entries` returns them | Nothing reads them | **MISSING** | M |
| Labels | Persisted bookmarks (`docs/extensions.md:1514-1529`) | Only inside the session tree | No tree UI | **MISSING** | M |
| Extension load errors | Collected into `LoadExtensionsResult.errors` (`loader.ts:588-591`, `634-637`) | **Never printed and never emitted**: the RPC path has no consumer; they land in `runtime.diagnostics`, which the app never sees (`main.ts:775-782`) | Nothing — a broken extension is indistinguishable from an absent one | **MISSING** | S |
| Extension handler errors | Caught and reported via `onError` (`runner.ts:851-882`) | `extension_error{extensionPath, event, error}` (`rpc-mode.ts:348-350`) | Snackbar + transcript row; path/event dropped | **DEGRADED** | XS |
| `tool_call` handler errors | **Not caught** — they propagate and block the tool (`runner.ts:982-1002`; `agent-session.ts:496-501`) | Tool fails with the thrown message as an error result | Rendered as a failed tool | **N/A** (docs `docs/extensions.md:2931`) | — |
| Trust | Project `.pi` resources gated on `~/.pi/agent/trust.json` (§4) | **RPC never prompts; with `defaultProjectTrust:"ask"` it silently skips project resources** (`main.ts:753`; `project-trust.ts:86-88`) | No trust handling at all | **MISSING** — and silently lossy | M |
| Hot reload | `/reload` / `ctx.reload()` rebinds the whole runtime (`agent-session.ts:2841-2866`) | No RPC command; only reachable through an extension command | Nothing | **MISSING** | S |
| Extension-registered models | Appear in `get_available_models` (`model-runtime.ts:766-771`) | Works | Not called | **MISSING** | M |
| `-e` / inline extensions | `pi -e ./x.ts`, `--no-builtin-tools` (`docs/extensions.md:7`, `2095-2099`) | Works: the app controls argv | The app passes only `--mode rpc --session-dir` (`PiEngineHost.kt:103-107`) | **N/A** (available if wanted) | — |
| Bundled extension install | — | — | The app copies `assets/pi-extensions` into `<agentDir>/extensions` and `<rootfs>/root/.pi/agent/extensions` (`bridge/DeviceBridgeController.kt:225-241`) — i.e. the **global** user extension dir, which is trust-exempt (`trust-manager.ts:30-38`) | **N/A** — this is the correct way to ship an app-owned extension | — |

---

## 2. The three-mode comparison that decides architecture

Three ways to drive pi, and what each can express. The RPC column is the app's
current path; the SDK column is the question the app is evaluating.

### 2.1 What each mode actually installs as the extension UI

| Mode | `ctx.mode` | `ctx.hasUI` | UI context | Where |
|---|---|---|---|---|
| `--mode rpc` | `"rpc"` | `true` | `createExtensionUIContext()` over stdout/stdin | `rpc-mode.ts:136-311`, bound `:319-321` |
| `--mode json` | `"json"` | `false` | **none** → `noOpUIContext` | `print-mode.ts:76-77` binds `mode:"json"` with **no** `uiContext`; `agent-session.ts:2469-2474` leaves `_extensionUIContext` undefined; `runner.ts:436-439` installs `noOpUIContext`; `hasUI()` is `uiContext !== noOpUIContext` (`runner.ts:492-494`) |
| In-process SDK | `"print"` until the embedder changes it | `false` until the embedder binds one | none by default; **the embedder supplies any object implementing `ExtensionUIContext`** | `agent-session.ts:360`, `runner.ts:273`; embedder path `session.bindExtensions({uiContext, mode, …})` (`agent-session.ts:232-239`, `2468-2491`) |

Two consequences that decide the architecture:

1. **`--mode json` is strictly worse than RPC for extensions.** It runs one prompt
   from argv and exits; it has no stdin command channel (`docs/json.md:3-7`) and no
   UI context. Any surface that RPC degrades, JSON deletes. It is not a candidate.
2. **The SDK is not "RPC minus a process" — it is a different UI contract.** In the
   SDK path the app does not implement a wire protocol at all: it hands pi a
   JavaScript object whose methods are the app's own code. Every method RPC turns
   into a no-op becomes implementable, in-process, with no protocol design.

### 2.2 Per-surface: what the SDK gives instead of RPC's degraded behaviour

| Surface | RPC reality (quoted) | SDK equivalent | Reachable by the app? |
|---|---|---|---|
| `ui.custom(factory)` | `async custom() { /* Custom UI not supported in RPC mode */ return undefined as never; }` (`rpc-mode.ts:228-231`) | The embedder's `custom` receives `(tui, theme, keybindings, done)` and returns a `Component`; it is called by nothing inside pi — **the host** drives it (`types.ts:198-212`). The app could render the returned `Component` to lines (`component.render(width)`, as `export-html` does at `tool-renderer.ts:113`) and paint those lines, or hand them to its existing terminal surface | Yes, with an ANSI/component bridge — **this is the concrete meaning of "embedded-terminals escape hatch"** |
| `ui.setFooter` / `setHeader` | `setFooter(_factory) { /* … requires TUI access */ }` (`rpc-mode.ts:210-216`) | Same shape: the host owns the `Component` and its lifecycle (`types.ts:185-192`) | Yes, same bridge |
| `ui.setEditorComponent` / `getEditorComponent` | `setEditorComponent() { /* Custom editor components not supported */ }` (`rpc-mode.ts:277-284`) | Host owns the editor slot; the factory returns an `EditorComponent` built on pi-tui's `CustomEditor` (`types.ts:229-265`) | Yes, but only via a real terminal (the app's PTY path) or a component bridge |
| `ui.getEditorText()` | `getEditorText(): string { /* Synchronous method can't wait for RPC response */ return ""; }` (`rpc-mode.ts:248-252`) | Direct synchronous read of host state — no wire, no lie | Yes (trivially) |
| `ui.addAutocompleteProvider` | `addAutocompleteProvider(): void { /* not supported */ }` (`rpc-mode.ts:273-275`) | Host composes providers (`types.ts:227`) | Yes |
| `ui.onTerminalInput` | `onTerminalInput() { return () => {}; }` (`rpc-mode.ts:163-166`) | Host feeds raw key data to registered handlers (`types.ts:115`, `:147`) | Yes — the app has a terminal surface (`ui/terminal/TerminalPane.kt`) |
| `ui.setWorkingMessage/Visible/Indicator`, `setHiddenThinkingLabel` | four no-ops (`rpc-mode.ts:179-193`) | Host-owned streaming chrome (`types.ts:153-169`) | Yes |
| `ui.theme` / `getAllThemes` / `getTheme` / `setTheme` | returns the built-in `theme`; `[]`; `undefined`; `{success:false}` (`rpc-mode.ts:286-301`) | Host owns the theme registry (`types.ts:268-277`) | Yes |
| `ui.getToolsExpanded` / `setToolsExpanded` | `false`; no-op (`rpc-mode.ts:303-310`) | Host state (`types.ts:280-283`) | Yes |
| `registerMessageRenderer` | no-op (only `interactive-mode.ts:3597` reads it) | The SDK exposes it identically, but the *renderer is still TUI-only*: nothing outside interactive mode calls `session.extensionRunner.getMessageRenderer` (`runner.ts:629-636`). **The SDK alone does not fix this**; the app must call the accessor itself | Partially — the accessor is public (`runner.ts:629`; `session.extensionRunner` is a public getter, `agent-session.ts:3549`) |
| `registerEntryRenderer` | same, `runner.ts:643-650` | same — public accessor, TUI-only consumer | Partially |
| `registerMarkdownTransformer` | same, `runner.ts:639-641` | same — public accessor `getMarkdownTransformers()` | Yes (pure text transform; the app can apply it itself) |
| `renderCall` / `renderResult` | never called on the wire; called by `export_html` (`export-html/tool-renderer.ts:99-156`) | Public: `session.extensionRunner.getToolDefinition(name)` (`runner.ts:514-519`) returns a definition whose renderers the host can invoke and render | Yes |
| `model_select` on the wire | not emitted to session listeners (`agent-session.ts:1659-1670`) | In-process: `session.subscribe` still does not receive it, but the host can call `session.extensionRunner.emit`/register its own handlers, or simply read `session.model` | Yes (by polling, or by wrapping the runner) |
| Trust | RPC auto-denies with no prompt (`project-trust.ts:86-88`; `main.ts:753`) | The SDK is **worse, not better**: `createAgentSession` never resolves trust, and `SettingsManager` defaults `projectTrusted` to `true` (`settings-manager.ts:374`), so project settings/resources load as trusted with no gate | Not a reason to switch — see §4 |
| `ctx.reload()` | no RPC command (only an extension command can call it) | Direct: `session.reload()` (`agent-session.ts:2841`) | Yes |
| `registerShortcut` | meaningless (no keyboard) | meaningless | — |

### 2.3 Verdict

An in-process SDK path is **not** the fix for the TUI-only surfaces, because the
TUI-only surfaces are TUI-only *inside pi*, not because of RPC: the consumers are
`interactive-mode.ts` itself (`:3558`, `:3597`, `:2024-2025`) and
`interactive-mode/components/tool-execution.ts:116-121`. Binding a richer
`ExtensionUIContext` in-process gives the app every *callback* (custom, footer,
header, editor, autocomplete, terminal input, editor text, themes), but not the
renderers.

What the SDK genuinely buys over RPC, in order of value:

1. **Real blocking-dialog semantics with an app-owned implementation.** No
   `extension_ui_request`/`response` protocol, no timeouts to reimplement, no
   process boundary that can lose a request. `ui.editor()` stops being a
   forever-hang risk (§3) because the app's implementation is a coroutine.
2. **`custom()`, `setFooter`, `setHeader`, `setEditorComponent`,
   `addAutocompleteProvider`, `onTerminalInput`, `getEditorText`, themes** — all
   implementable, none expressible over the shipped RPC protocol.
3. **`ctx.reload()`, `session.reload()`, `newSession`/`fork`/`navigateTree` with
   `withSession` callbacks**, called directly.

What it costs, and why the app should not switch wholesale:

- The SDK is Node-only (`node:path`, `node:fs`, `jiti`; `sdk.ts:1-37`,
  `loader.ts:14-29`). It must run inside the same proot'd Ubuntu, so **the
  transport does not disappear — it moves**. The app would have to define its own
  Node↔Kotlin bridge expressing every `ExtensionUIContext` method, which is
  strictly more work than the JSONL protocol it replaces, and it would lose the
  process isolation and the CLI run-mode machinery (`main.ts:966-970`).
- Therefore the pragmatic shape is: **keep `--mode rpc` as the engine path, and
  use a pi extension the app owns as the adapter** for anything the app must
  inject into the guest (the device bridge already proves this pattern,
  `bridge/DeviceBridgeController.kt:225-241`). The in-process SDK is only worth it
  if the app also commits to a component bridge (ANSI rendering of pi-tui
  Components), because that is what unlocks the TUI-only surfaces.
- The only way to reach *third-party* TUI-only extensions at full fidelity is
  pi's own interactive mode inside a real PTY. The app already has that:
  `PtyLauncher.Kind.PiTui` (`runtime/PtyLauncher.kt:303`; exposed as the
  Workbench tab at `ui/terminal/TerminalPane.kt:143`). That is the escape hatch,
  and the product claim "100% of pi's capability" is honest only when it points at
  that path rather than at the chat UI.

---

## 3. Dialog protocol correctness checklist (implement against this)

Everything here is verified against `rpc-mode.ts` and `rpc-types.ts`, not the docs,
because the docs and the code disagree in two places (§3.7).

### 3.1 Requests (pi → client, stdout)

Transport: one JSON object per line, `\n`-terminated, LF only (`docs/rpc.md:30-37`).
All requests: `{"type":"extension_ui_request","id":<uuid>,...}`; `id` is a fresh
`crypto.randomUUID()` per request (`rpc-mode.ts:99`, `:122`).

| `method` | Exact fields | Blocking? | Legacy `HEAD` app behaviour |
|---|---|---|---|
| `select` | `id`, `method:"select"`, `title:string`, `options:string[]`, `timeout?:number` (ms) | yes | no answer sent |
| `confirm` | `id`, `method:"confirm"`, `title:string`, `message:string`, `timeout?:number` | yes | no answer sent |
| `input` | `id`, `method:"input"`, `title:string`, `placeholder?:string`, `timeout?:number` | yes | no answer sent |
| `editor` | `id`, `method:"editor"`, `title:string`, `prefill?:string` — **never `timeout`** | yes, **unconditionally forever** | no answer sent |
| `notify` | `id`, `method:"notify"`, `message:string`, `notifyType?:"info"\|"warning"\|"error"` | no | ignored |
| `setStatus` | `id`, `method:"setStatus"`, `statusKey:string`, `statusText:string\|undefined` | no | ignored |
| `setWidget` | `id`, `method:"setWidget"`, `widgetKey:string`, `widgetLines:string[]\|undefined`, `widgetPlacement?:"aboveEditor"\|"belowEditor"` | no | ignored |
| `setTitle` | `id`, `method:"setTitle"`, `title:string` | no | ignored |
| `set_editor_text` | `id`, `method:"set_editor_text"`, `text:string` | no | ignored |

Source: `rpc-mode.ts:137-150` (select/confirm/input), `:254-271` (editor),
`:152-161` (notify), `:168-177` (setStatus), `:195-208` (setWidget),
`:218-226` (setTitle), `:238-246` (set_editor_text). Types:
`rpc-types.ts:246-281`.

`timeout` is placed on the wire **only** for `select`/`confirm`/`input`, and only
as a value the *extension* passed (`{..., timeout: opts?.timeout}`, `rpc-mode.ts:138`,
`:143`, `:148`); when absent it is dropped by JSON serialisation.

### 3.2 Responses (client → pi, stdin)

`{"type":"extension_ui_response","id":<same id>, ...}` with exactly one payload
(`rpc-types.ts:288-291`):

| Method | Correct response | Also accepted (and what pi does) |
|---|---|---|
| `select` | `{"value":"<one of options>"}` | `{"cancelled":true}` → `undefined`. Any other string is returned to the extension unsanctioned — **pi does not validate `value` against `options`** (`rpc-mode.ts:139`). `{"confirmed":…}` → `undefined` (falls through both `in` tests, `:139`) |
| `confirm` | `{"confirmed":true\|false}` | `{"cancelled":true}` → `false` (`rpc-mode.ts:144`). `{"value":…}` → `false` — a shape error is silently converted to "no", not rejected |
| `input` | `{"value":"<text>"}` | `{"cancelled":true}` → `undefined` |
| `editor` | `{"value":"<text>"}` | `{"cancelled":true}` → `undefined` (`rpc-mode.ts:259-265`) |
| fire-and-forget methods | none | Any response for such an `id` is silently discarded (no pending entry, `rpc-mode.ts:776-781`) |

`cancelled` is the wire encoding of `undefined` for select/input/editor and of
`false` for confirm (`docs/rpc.md:1368-1374`).

### 3.3 Timeout semantics — exact, and where they come from

- pi arms the timer **agent-side**, in `createDialogPromise`:
  `if (opts?.timeout) timeoutId = setTimeout(() => { cleanup(); resolve(defaultValue); }, opts.timeout)`
  (`rpc-mode.ts:115-120`). Defaults per method, passed as `defaultValue` at the
  call site: `select` → `undefined` (`:137`), `confirm` → `false` (`:142`),
  `input` → `undefined` (`:147`).
- The **client does not need to track timeouts** (`docs/rpc.md:1193`); the app's
  countdown is presentation plus belt-and-braces (`PiSessionViewModel.kt:495-551`).
  It is correct to answer at zero, and it is also correct not to.
- `opts.signal` is honoured: on abort the pending entry is removed and the
  default resolves (`rpc-mode.ts:97`, `:109-113`).
- **`editor` has no timeout and no signal path.** Its signature is
  `editor(title, prefill): Promise<string|undefined>` (`types.ts:224`), the RPC
  implementation hand-rolls its own promise and never sets a timer
  (`rpc-mode.ts:254-271`), and the docs' timeout section lists return values for
  `select`/`confirm`/`input` only (`docs/extensions.md:2560-2563`). An extension
  that opens an editor and is then abandoned leaves pi waiting **permanently**.
  This is the one genuinely BLOCKING surface in the inventory.
- If `opts.timeout` is `0`, no timer is armed (`if (opts?.timeout)` is falsy) —
  i.e. `timeout: 0` means "wait forever", not "return immediately".
- `custom()` never consults `timeout` either, but in RPC mode it returns
  immediately anyway (`rpc-mode.ts:228-231`).

### 3.4 `cancelled`, late replies, unknown ids

- A late reply after a timeout is **silently dropped**: `cleanup()` removed the
  pending entry (`rpc-mode.ts:106`), and the stdin handler only resolves when
  `pendingExtensionRequests.get(response.id)` exists (`rpc-mode.ts:776-780`).
  There is no error, no log, no response.
- A reply with an unknown or mismatched `id` is dropped identically.
- A second reply for the same `id` is also dropped — the first resolution deleted
  the entry. So the client may be sloppy about duplicates without breaking pi; the
  app still guards deliberately (`PiSessionViewModel.kt:455-465`).
- `editor` has **no** mechanism for a late reply, because it has no timeout: the
  entry stays pending until pi exits.

### 3.5 Can several dialogs be outstanding at once?

**Yes.** Outstanding requests live in a `Map<id, …>` (`rpc-mode.ts:80-83`) keyed by
a fresh uuid per request (`:99`), and each promise resolves from its own entry.
Nothing in the runner serialises them either: `withUIPrompt` only increments a
depth counter to coalesce `ui_prompt_start`/`ui_prompt_end` events — it does not
queue (`runner.ts:453-486`). Two extensions (or two concurrent tool calls) can
therefore have dialogs in flight simultaneously, and answers may arrive in any
order.

Consequence for the app: a FIFO display queue is **safe** (pi matches by id), and
it is the right choice because the extension's view of ordering is unknowable.
The app already does this (`ui/extension/ExtensionUi.kt:108-170`).

### 3.6 What pi does if the client never replies

For `select`/`confirm`/`input` with a `timeout`: the extension gets the documented
default and the turn continues. Without a `timeout`: the promise never settles —
the extension handler awaits forever, the tool call or event handler never
returns, and the turn never completes. For `editor`: always the second case.

A pty-level detail matters here: pi's stdout is not the only thing that stalls. If
the blocked call is inside a tool's `execute`, no `tool_execution_end` follows and
the app's spinner runs forever with no error. The app has no watchdog for this —
worth adding (§6.1).

### 3.7 Where the docs and the code disagree (implement the code)

1. **`get_commands` payload shape.** `docs/rpc.md:838-851` documents flat
   `location`/`path` fields; the code returns a nested `sourceInfo` object
   (`rpc-mode.ts:690`, `699`, `708`; `source-info.ts:3-12`). The app's reader
   already reads `source` only (`rpc/Responses.kt:104-109`), so it hits neither.
2. **Which dialogs carry `timeout`.** `docs/rpc.md:1249-1273` shows `input` and
   `editor` examples without `timeout` and says "dialog methods with a `timeout`
   field" — true, but the *only* methods that can have one are
   select/confirm/input, because `editor`'s signature has no options parameter
   (`types.ts:224`).

---

## 4. Trust and security model — concrete requirements

### 4.1 How trust is recorded

- File: `<agentDir>/trust.json` (`trust-manager.ts:212-214`), i.e. in the app
  `<files>/pi/.pi/agent/trust.json` (`runtime/PiRuntime.kt:19`), bind-mounted to
  the guest's `/root/.pi/agent/trust.json` (`PiEngineHost.kt:51`, `123`).
- Format: a JSON object mapping **canonical** absolute directory path →
  `true | false | null`, keys sorted, 2-space indent, trailing newline; any other
  value makes the whole store invalid and pi throws (`trust-manager.ts:98-135`).
  `null` deletes an entry (`:236-237`).
- Key normalisation: `canonicalizePath(resolvePath(cwd))` (`trust-manager.ts:40-42`),
  so symlinks must be resolved before writing.
- Lookup is **nearest ancestor wins** (`trust-manager.ts:44-58`) — trusting
  `/workspace` trusts everything under it.
- The store is protected by an advisory lock on `<trust.json>.lock`
  (`trust-manager.ts:137-176`), so concurrent writers (the app and pi) coordinate.

### 4.2 What prompts, and what happens with no UI

Resolution order, exact (`project-trust.ts:46-95`):

1. `--approve` / `--no-approve` override wins outright.
2. If the cwd has no trust-requiring resource, **trusted** — no prompt. The
   trigger list is `.pi/settings.json`, `.pi/{extensions,skills,prompts,themes}`,
   `.pi/SYSTEM.md`, `.pi/APPEND_SYSTEM.md`, or a non-user `.agents/skills` in cwd
   or an ancestor; a bare `.pi` does not count (`trust-manager.ts:30-38`, `185-207`;
   `docs/security.md:9-16`).
3. Registered `project_trust` handlers (user/global and `-e` extensions only) —
   first `yes`/`no` wins, `remember:true` persists (`runner.ts:204-234`;
   `project-trust.ts:54-70`).
4. Saved `trust.json` decision for the nearest ancestor.
5. `defaultProjectTrust` global setting: `"always"` → true, `"never"` → false,
   `"ask"` → continue (`settings-manager.ts:1014-1017`).
6. **`if (!hasUI) return false`** (`project-trust.ts:86-88`).
7. Otherwise a `select`; dismissal → false.

For `--mode rpc`, step 6 always fires: `hasUI` is
`isInitialRuntime && trustPromptMode === "interactive"` (`main.ts:753`), and RPC
is not interactive. So with the default `"ask"`, **project-local `.pi/extensions`
never load over RPC, silently**, unless a saved decision exists.

### 4.3 Requirements for the app

R1. **Persist trust explicitly.** Either write `<files>/pi/.pi/agent/trust.json`
with the canonical guest path as key (`/workspace/...`) — note the key is the
**guest** cwd, because pi resolves `cwd` inside proot — or pass `--approve`
(`cli/args.ts:219-222`). Do not rely on `defaultProjectTrust: "always"` as the
default: it silences the gate permanently.
R2. **Expose the five real choices**, matching pi's own option set (`Trust`,
`Trust parent folder`, `Trust (this session only)`, `Do not trust`,
`Do not trust (this session only)`; `trust-manager.ts:66-96`). The app's settings
registry already declares "项目信任名单" and "扩展信任名单" rows
(`PiSettingsRegistry.kt:1400-1422`) but **nothing reads or writes them** — no
`app.trust.*` consumer exists in `app/src`. Those rows are currently placeholders
and should either be implemented against `trust.json` or removed.
R3. **Surface the denial.** Today a skipped project extension produces no wire
event, no stderr line and no error (`loader.ts:634-637`; `main.ts:775-782`).
Either read `<agentDir>/trust.json` at boot and show a banner, or ship an app-owned
**global** extension (like the device bridge) that implements `project_trust` and
reports its decision to the app over the loopback bridge.
R4. **Never auto-trust on first sight of a new workspace.** The appearance of a
   `.pi/extensions` directory immediately makes the workspace trust-requiring
   (`trust-manager.ts:185-207`), i.e. it arms the gate; an attacker-supplied
   workspace dropped into the app's storage is exactly the scenario the gate
   exists for (`docs/security.md:37`). Resolving it must stay a human decision.
R5. **Project package installs are trust-gated too** (`package-manager.ts:1740-1745`,
   `2398-2402`), so an install UI must consult the same decision.

### 4.4 What a malicious extension can do

Extensions are TypeScript modules loaded by jiti into the **pi process**
(`loader.ts:1-16`), running with the pi process's full permissions
(`docs/security.md:3`, `:33`). There is **no sandbox** and none is planned
(`docs/security.md:33-35`). Concretely, an extension can:

1. **Run arbitrary code.** `pi.exec()` (`types.ts:1397`) plus unrestricted
   `node:fs` — read `auth.json`, session JSONL files, and anything else the
   proot guest can see. Inside the app, that guest is the whole Ubuntu rootfs and
   the bind-mounted workspace.
2. **Shadow a built-in tool.** Extension tools are written into the tool registry
   *after* built-ins, keyed by name (`agent-session.ts:2710-2727`), so
   `registerTool({name:"bash", …})` replaces the built-in. `getAllRegisteredTools`
   takes the **first** registration per name across extensions, so between two
   extensions the earlier-loaded one wins (`runner.ts:501-513`). Docs:
   `docs/extensions.md:2086-2099`. **This is the app's single largest integrity
   risk**: the device-bridge tools and the warning path assume the built-in and
   the app's tools are the ones running. A malicious extension registered before
   the bridge can impersonate any device tool by name, and nothing in the app
   would notice — the tool name on the wire is all the app sees
   (`rpc/Events.kt:313-331`).
3. **Intercept or rewrite everything.** `tool_call` lets it mutate tool arguments
   in place with no re-validation (`types.ts:939-944`), and `tool_result` lets it
   replace the content the app displays (`runner.ts:927-980`). It can therefore
   make a denied device action look allowed, or vice versa.
4. **Steal credentials.** `before_provider_headers` mutates outbound headers
   (`runner.ts:1100-1128`) and `before_provider_request` replaces the payload
   (`:1066-1097`); `registerProvider` can point an existing provider at an
   attacker base URL (`types.ts:1486-1487`).
5. **Deny service.** A `ui.editor()` with no answer blocks a turn forever (§3.6);
   a throwing `tool_call` handler blocks the tool (`runner.ts:982-1002`).
6. **Terminate the process.** `ctx.shutdown()` (`types.ts:342`;
   `rpc-mode.ts:345-347`).

Consequences for the app's two "gate" extensions
(`assets/pi-extensions/pi-android-permission-gate.ts`, `pi-android-bridge`): a
permission gate implemented **as an extension is not a security boundary**. It is
a policy convenience, and it is bypassable by any other loaded extension. If the
app must enforce "no device action without approval", the enforcement point has
to be the app (the loopback bridge's own authorization), not a `tool_call`
handler.

### 4.5 What `/reload` does, and which locations hot-reload

`ctx.reload()` and interactive `/reload` run the same flow
(`docs/extensions.md:1309-1331`; `agent-session.ts:2841-2866`): emit
`session_shutdown{reason:"reload"}` → invalidate the old runner (captured
`ctx`/`pi` objects become throwing stubs, `runner.ts:593-608`) → reload settings →
clear the jiti extension cache (`loader.ts:158-162`) → `resourceLoader.reload()`
(extensions, skills, prompts, themes, AGENTS.md, SYSTEM.md/APPEND_SYSTEM.md;
`resource-loader.ts:388-547`) → rebuild the runtime → emit
`session_start{reason:"reload"}` → re-run `resources_discover`.

Hot-reloadable locations: the auto-discovered dirs, i.e. `~/.pi/agent/extensions/`
and project `.pi/extensions/` (`docs/extensions.md:7`, `113-120`), plus everything
re-resolved from settings/packages on each reload (`resource-loader.ts:404-513`).
`pi -e ./file.ts` is explicitly *not* a hot-reload location — it is a one-shot
inline path (`docs/extensions.md:7`), and it is also **not trust-gated**
(`docs/security.md:27`), which is why the app's `--approve`-free bootstrap plus a
global extension is the safe combination.

**App-facing gap:** `/reload` is a *built-in* command (`slash-commands.ts:41`),
built-ins are excluded from `get_commands` (`docs/rpc.md:853`) and are not
dispatched by `prompt` (`agent-session.ts:1331-1343`), and there is no
`reload` RPC command (`rpc-types.ts:20-74`). So after the app installs an
extension package it **cannot make pi pick it up** in the running process. The
only supported path is an extension command that calls `ctx.reload()` — exactly
what `docs/extensions.md:1333-1363` recommends. Requirement: ship a global
`pi-android-reload` command extension (or extend the bridge extension) that
exposes `reload` and `install` commands; then the app invokes them with
`prompt("/reload")`.

---

## 5. Things nobody notices until they break

Findings beyond the inventory. Each is a behaviour a UI author would reasonably
assume works and which does not.

### 5.1 `hasUI === true` in RPC mode, and `custom()` returns `undefined`

`ctx.hasUI` is `true` in RPC (`docs/rpc.md:1205`, `runner.ts:492-494`), so the
documented guard `if (ctx.hasUI) ctx.ui.custom(...)` passes — and then `custom()`
resolves `undefined` immediately (`rpc-mode.ts:228-231`). An extension that gates
destructive work behind a custom confirmation proceeds as if the user confirmed
nothing (i.e. proceeds down its "cancelled" branch) or, worse, treats `undefined`
as a value. The correct guard was `ctx.mode === "tui"`, and the app cannot make an
extension write it. There is no wire signal for `custom()` at all, so the app
cannot even detect that an extension took that branch; the only real mitigations
are user-facing documentation and routing such extensions to the terminal path
(§6.11).

### 5.2 `getEditorText()` returns `""`, not an error

`rpc-mode.ts:248-252` returns the empty string because the method is synchronous.
An extension that inspects the composer (draft-preserving commands, "expand this
selection") silently acts on an empty editor. This is a **wrong value**, not a
missing feature, which is the failure mode that never shows up in a bug report.

### 5.3 A model changed by an extension is invisible on the wire

`model_select` is emitted only to extensions (`agent-session.ts:1659-1670`), while
its thinking-level twin is explicitly dual-emitted (`agent-session.ts:1830-1832`).
So an extension that calls `pi.setModel()` changes the session and the app's model
indicator never updates. Any model UI must therefore poll `get_state` after
`agent_settled` (or after `set_model` responses), not wait for an event.

### 5.4 `turn_start` *does* carry `turnIndex`

The app asserts the opposite in a comment and discards the field
(`rpc/Events.kt:76-83`), but `TurnStartEvent` has both `turnIndex` and `timestamp`
(`types.ts:764-768`), and `AgentSessionEvent` forwards the core `AgentEvent`
(`agent-session.ts:144-145`; emitted with `turnIndex: this._turnIndex`,
`agent-session.ts:771-781`). Nothing breaks, but the app's transcript loses a
stable ordering key it could have had.

### 5.5 There is no way for the client to trigger reload or any built-in command

See §4.5. A user who edits `.pi/extensions/foo.ts` inside the app's workspace and
then types `/reload` sends the literal string `/reload` to the model — a real
prompt, a real API charge, and no reload. The app must intercept `/`-prefixed
input client-side; today `PiSessionViewModel.send` has no `/` special-case at all
(`PiSessionViewModel.kt:606-624`).

### 5.6 Extension load failures are completely silent

`loadExtension` returns `{error}` (`loader.ts:588-591`), `loadExtensionsCached`
collects them (`:634-637`), and the RPC path never reads them: they end up in
`runtime.diagnostics` (`main.ts:775-782`), consumed only by interactive mode.
A syntax error in an app-shipped extension therefore looks identical to the
extension not being installed. The app captures guest stderr into a `StringBuilder`
(`PiEngineSession.kt:92-96`) but loader errors never reach stderr, so the
diagnostics card cannot show them either. Requirement: after boot, compare the
expected extension set against `get_commands`' `source:"extension"` entries and
warn on a mismatch.

### 5.7 `extension_error` loses its two most useful fields

pi sends `{extensionPath, event, error}` (`rpc-mode.ts:348-350`). The app parses
only `message`/`error` (`rpc/Events.kt:398`). With several extensions loaded,
"扩展出错" is unattributable. Cheap fix, high diagnostic value.

### 5.8 `entry_appended` carries the whole entry and the app throws it away

`agent-session.ts:2616-2621` emits the full `CustomEntry`; the app keeps only
`{entryId, entryType}` (`rpc/Events.kt:400-406`) and the reducer ignores the event
(`rpc/Transcript.kt:627`). Combined with the never-called `get_entries`, this means
extension state is not merely unrendered — the app has *no* path to it. The
reducer is already able to render `custom_message`/`hook_message` entries
(`rpc/Transcript.kt:1172-1194`, `1279-1296`), so the missing piece is one wire
handler and one command call.

### 5.9 Precedence when two extensions collide (three different rules)

| Collision | Actual rule | Where |
|---|---|---|
| Two extensions, same **tool** name | **First** registration wins (load order); later ones are silently dropped | `runner.ts:501-513` |
| Two extensions, same **command** name | **All** kept; later ones get `name:1`, `name:2` suffixes by load order | `runner.ts:653-691`; `docs/extensions.md:1535` |
| Extension vs **built-in tool** | Extension wins, entirely replacing it | `agent-session.ts:2710-2727` |
| Extension vs **built-in keybinding** | Reserved bindings win; the shortcut is skipped with a diagnostic | `runner.ts:72-96`, `544-597` |
| Same **flag** name | First wins | `runner.ts:524-534` |

An app that lists tools or commands must therefore not assume one row per name.
Command suffixing in particular means `/review:2` is a real invocation name the
palette must show verbatim (`rpc-mode.ts:687` uses `command.invocationName`).

### 5.10 What a throwing handler does, per event (not uniform)

| Handler | Behaviour on throw | Where |
|---|---|---|
| any `emit(...)` event (turn, session, execution, provider…) | caught, reported via `extension_error`, other handlers still run | `runner.ts:851-882` |
| `message_end` | caught, reported; the message is left unmodified | `runner.ts:885-924` |
| `tool_result` | caught, reported | `runner.ts:927-980` |
| `context`, `before_provider_request`, `before_provider_headers`, `before_agent_start`, `resources_discover`, `input`, `user_bash` | caught, reported | `runner.ts:1034-1285` |
| **`tool_call`** | **not caught** — propagates, the tool is blocked with the thrown message as an error result | `runner.ts:982-1002`; `agent-session.ts:496-501` |
| `project_trust` | caught; error collected, handler's vote lost | `runner.ts:217-233` |
| `command` handler (extension command invoked via `prompt`) | caught, reported as `command:<name>` | `agent-session.ts:1345-1353` |

The `tool_call` asymmetry is documented (`docs/extensions.md:2931`) and means a
buggy policy extension fails *closed* for tools and *open* (silently ignored) for
everything else.

### 5.11 Extension-customised tool rendering reaches exactly one non-TUI surface

`renderCall`/`renderResult` are never called for the JSONL stream, but they **are**
called during `export_html`, converted from ANSI to HTML
(`export-html/tool-renderer.ts:99-156`, called at `export-html/index.ts:197`, `:211`). So a
client that wants extension-rendered tool output has exactly one wire path:
`{"type":"export_html"}` → render the file. That is a workable fallback and the
only supported one; RPC deserves no other assumption. The `export-html` renderer
runs at a fixed width of 100 columns (`tool-renderer.ts:59`).

### 5.12 Extension-contributed skills and templates do appear in `get_commands` — with a `source` that is not enough

`source` distinguishes `extension` / `prompt` / `skill` (`rpc-mode.ts:689`, `698`,
`707`), and skill commands are prefixed `skill:` (`agent-session.ts:2587`). But the
useful provenance (user vs project vs package vs temporary, and the absolute path)
lives in `sourceInfo` (`source-info.ts:3-12`), which the app's reader drops
(`rpc/Responses.kt:96-112`). A palette that wants to show "from your project" vs
"from an npm package" must keep `sourceInfo.scope` and `sourceInfo.origin`.
Also: **built-in commands are never in this list** (`docs/rpc.md:853`), so a
palette built only from `get_commands` will be missing `/reload`, `/model`,
`/compact`, `/trust`, `/export` — the commands users type most.

### 5.13 Package install: the exact dependency rule

For an npm package, pi installs with
`install <spec> --prefix <installRoot> --legacy-peer-deps`
(`package-manager.ts:1785-1806`), deliberately disabling peer resolution so the
host-provided `@earendil-works/pi-*` peers are not installed (`:1787-1790`).
For a **git** package, its own `package.json` dependencies are installed with
`install --omit=dev` (`package-manager.ts:1772-1778`), which is why
`docs/extensions.md:150` warns that `devDependencies` are unavailable at runtime;
when the `npmCommand` setting is present, plain `install` is used instead
(`docs/settings.md:256`). No `--ignore-scripts` is used anywhere in this path
(**UNVERIFIED** as a design statement; grep found none). Practical consequence for
the app: an extension package that declares a runtime dependency in
`devDependencies` will load in the app exactly as it would on desktop — by
failing — and the failure is silent (§5.6).

### 5.14 State that must survive restarts, and where it lives

Three different mechanisms, three different app obligations:

1. `pi.appendEntry(customType, data)` → `CustomEntry` in the session JSONL, not in
   LLM context. Survives restarts; recovered by scanning entries on
   `session_start` (`docs/extensions.md:1486-1492`). **The app must keep
   `get_entries` available on reattach or the extension's state is gone from the
   user's perspective** (the extension itself recovers it in-process; the app just
   must not hide the resulting output).
2. `pi.sendMessage(..., {display:true})` → `CustomMessageEntry`, in context, with
   `customType`/`display`/`details` (`session-manager.ts:104-140`).
3. Labels via `pi.setLabel` (`types.ts:1394`).

None of the three is readable by the app today (§1.5).

### 5.15 `--mode rpc` starts no session header, and the app must not assume one

JSON mode prints a `{"type":"session",…}` header line (`docs/json.md:69-73`).
The RPC docs never document one, and `runRpcMode` emits only responses and
`toJsonEvent`-wrapped session events (`rpc-mode.ts:355-360`). The app's parser
tolerates an unknown `session` line (`rpc/Events.kt:412`), which is the right
defensive posture — but no code may depend on it.

---

## 6. Prioritised work list for the app

### P0 — blocking hangs (do first)

**6.1 Dialog watchdog and "answer everything" invariant.**
The blocking half is now implemented (`PiSessionViewModel.kt:431-465`), but three
holes remain:
(a) a dialog whose engine died between request and answer is answered on engine
death and on `onCleared` (`:230`, `:245`, `:561-568`, `:672-679`) — good;
(b) there is **no timeout for a dialog pi sent without one**, which is correct
(pi waits forever either way), but the app has no way to tell the user *why* the
turn stalled. Add: when a dialog with `timeoutMs == null` is on screen and the
engine is streaming but idle for N seconds with no events, render "扩展正在等待你的回答".
Concrete API: `PiSessionViewModel.UiState.extensionDialog` already carries
`timeoutMs == null`; add a derived `stalledSince: Long?` in `UiState`.
(c) `ExtensionDialogMethod.Editor` must be shown with an explicit "此对话框没有超时，
pi 会一直等待" note — the app already documents it in code
(`ui/extension/ExtensionDialogs.kt:208-216`); make sure the UI says it.

**6.2 Never drop a `tool_call`-blocked turn silently.** Not an app bug, but the
app must render `tool_execution_end{isError:true}` with content "Tool execution
was blocked" in a way that reads as *policy*, not failure
(`agent-loop.ts:644-655`). API: `rpc/Events.kt:325-331` already carries
`isError` + `resultText`; add a `ToolBlocked` transcript item when
`isError && toolName in knownToolNames && resultText` matches the blocked prefix.

### P1 — cheap, high-value (≤ 1 day each)

**6.3 Wire `get_commands` into a slash palette.** `PiCommands.getCommands` and
`PiResponses.slashCommands` already exist and are unused. Keep `sourceInfo` and
add the built-in list app-side (from `slash-commands.ts:19-43`) so `/reload`,
`/model`, `/compact`, `/trust` are discoverable. API:
`PiEngineSession.suspend fun listCommands(): List<PiResponses.SlashCommand>`
(send `get_commands`, refresh after `agent_settled` and after any reload).

**6.4 Intercept `/`-prefixed composer input.** Today `/reload` goes to the model
(§5.5). API: `PiSessionViewModel.send` should route a leading `/` to a palette
selection, and only send `prompt("/cmd args")` for commands that are actually
invokable (`source` present in `get_commands`), showing a clear error otherwise.

**6.5 Keep `extensionPath` and `event` on `extension_error`.** One-line change in
`rpc/Events.kt:398` plus the snackbar text; turns "扩展出错" into "扩展
pi-android-bridge 在 tool_call 中出错: …".

**6.6 Render `role:"custom"` messages from `message_end`.** The payload is already
on the wire (`agent-session.ts:1537-1547`); `rpc/Events.kt:303-311` must stop
dropping `customType`/`display`/`details`, and `rpc/Transcript.kt:563` must append
a `HookMessage` when `display !== false` (the block type exists,
`rpc/Transcript.kt:1283`).

**6.7 Surface `entry_appended` and call `get_entries` on attach.** Feed
`PiEvent.EntryAppended`'s full entry into `TranscriptReducer.onEntry`
(`rpc/Transcript.kt:905`) instead of `TranscriptChange.None`, and after every
boot/reconnect call `get_entries` and `seedFromHistory` (`:958`). This is what
makes extension state and restart-surviving messages visible.

**6.8 Poll `get_state` for the model after `agent_settled`** and use
`get_available_models` for a picker, because `model_select` is not on the wire
(§5.3) and extension-registered providers only appear via that command.

**6.9 Turn session switching into `switch_session`.** The app lists sessions from
disk (`PiSessionViewModel.kt:141-152`) but never tells pi. API:
`PiEngineSession.suspend fun switchSession(path: String)` sending
`switch_session`, then re-read `get_entries` (which also starts honouring
`session_before_switch` cancellation).

### P2 — needs the terminal/component escape hatch (days–weeks)

**6.10 Ship an app-owned "adapter" extension in the global extensions dir.**
Precedent exists: the device bridge is installed to `<agentDir>/extensions`
(`bridge/DeviceBridgeController.kt:225-241`), which is trust-exempt
(`trust-manager.ts:30-38`). Extend that pattern with commands the RPC surface
lacks:
- `reload` → `ctx.reload()` (§4.5);
- `install`/`remove`/`list` packages (`docs/packages.md:22-39`) driven from the
  app's UI, since `pi install` has no wire equivalent;
- a `project_trust` handler that reports the decision to the app over the loopback
  bridge (§4.3 R3).
API: extension commands are invoked with `prompt("/<name>")` (`agent-session.ts:1181-1190`),
so no protocol work is needed.

**6.11 Route TUI-only extensions to the real terminal.** `custom()`,
`setFooter`, `setHeader`, `setEditorComponent`, `addAutocompleteProvider`,
`onTerminalInput` and the renderer hooks cannot be expressed over this protocol
(§1.2, §2.2). The app already launches pi's TUI in a PTY
(`PtyLauncher.Kind.PiTui`, `runtime/PtyLauncher.kt:303`; UI at
`ui/terminal/TerminalPane.kt:143`). Requirement: offer it, do not pretend the chat
UI can render it. Detection has to be heuristic because pi reports nothing — a
static scan of the installed extension sources for `mode === "tui"` / `ui.custom(` /
`setEditorComponent(` is enough to warn; the user must also be able to open the
terminal for any session at will. Show "此扩展需要终端模式" with a one-tap
"在终端中打开". API: a `PiSessionViewModel.openTerminalInWorkbench()` intent
consumed by `PiRoot`'s destination state (`ui/PiRoot.kt:37`).

**6.12 Implement trust end-to-end.** Write/read `<files>/pi/.pi/agent/trust.json`
(canonical guest paths, `true|false|null`), implement the five options, and
replace the placeholder settings rows. API:
`app/src/main/kotlin/app/pi/runtime/PiTrustStore.kt` with
`fun get(cwd: String): Boolean?`, `fun set(cwd: String, trusted: Boolean)`,
`fun trustParent(cwd: String)`, using `PiPaths.agentDir` and the format in
`trust-manager.ts:125-135`. Do not add a second format.

### P3 — impossible without switching to the in-process SDK, and even then only with a component bridge

**6.13 Live extension rendering (`renderCall`/`renderResult`, message/entry
renderers, markdown transformers).** Over RPC these never run (§1.1, §1.5); the
only exposure is the `export_html` fallback (§5.11). Under the SDK the app can
obtain the same definitions (`session.extensionRunner.getToolDefinition`, public
per `runner.ts:514-519`) and render them itself — but it must first build an
ANSI→Compose bridge, exactly as `export-html` does to HTML
(`export-html/tool-renderer.ts:44-56`, `113-114`). Effort: **XL**. Recommendation:
use `export_html` (P1) and defer the bridge.

**6.14 Third-party TUI-only extensions at full fidelity.** Only pi's interactive
mode in a real PTY delivers `custom()` overlays, custom editors, footers/headers
and terminal input. The app has the PTY; the chat UI never will. This is the
architectural conclusion: **the promise "100% of pi's capability, including
extensions" is a promise about the terminal path, not about the RPC path**, and
the UI should say which one the user is in.

**6.15 `getEditorText()` and the composer.** RPC returns `""` forever
(`rpc-mode.ts:248-252`). The only fixes are (a) an app-owned extension that mirrors
the composer over the bridge, or (b) the SDK. Effort: **L**. Low priority — few
extensions call it, but the ones that do misbehave silently.

### Explicitly not worth doing

- **A second engine in `--mode json`.** It has no UI context and no stdin channel
  (§2.1); it can only reduce coverage.
- **Reimplementing `--mode rpc` semantics on top of the SDK without a component
  bridge.** It is strictly more work than the protocol it replaces and unlocks
  nothing (§2.3).
- **Relying on an extension for permission enforcement.** It is bypassable by any
  other extension (§4.4).

---

## Appendix A — UNVERIFIED items

| Item | Why unverified |
|---|---|
| Whether `model_select` can be observed by an SDK embedder through a public API | `session.subscribe` does not receive it (`agent-session.ts:1659-1670`); the only public escape is `session.extensionRunner` (`:3549`) plus `emit`, which is a push API, not a subscription to model changes. Not tested at runtime. |
| Whether the app's `get_available_models` call would include extension-registered models | Derived from `model-runtime.ts:766-771` adding the provider to `configuredProviders` and `:278-281` filtering `available` by that set. Code-consistent but not executed. |
| Whether any trust-related environment variable exists | Grep for `PI_TRUST`/`PI_APPROVE` found none; `trust-manager.ts` reads no env. |
| Whether `--ignore-scripts` is ever used for package installs | Grep over `package-manager.ts` and `docs/packages.md` found none; only the repo root `AGENTS.md` (a dev-hydration rule, not shipped behaviour) mentions it. |
| The exact behaviour of `cd`-less relative paths in `resources_discover` contributed paths under proot | The merge is `resolvePath` + `baseDir` (`resource-loader.ts:658-670`); not exercised in a guest. |
| Whether pi's TUI in the app's PTY preserves extension `custom()` fidelity | `PtyLauncher` allocates a real pty via `script(1)` (`runtime/PtyLauncher.kt:1-60`), which is the precondition; the extension path itself was not run. |
| `ExtensionMode` as a package-root export | Exported from `core/extensions/index.ts:74`, but no `ExtensionMode` export found in `src/index.ts`; an SDK embedder may have to widen a literal instead. |
