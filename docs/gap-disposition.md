# Row-by-row disposition of every non-`IMPLEMENTED` capability in `docs/feature-gaps.md`

This document exists because `docs/feature-gaps.md` graded 148 capability rows and only 12 of
the ~55 actionable ones were ever recorded into `docs/known-gaps.md`. A list that is read
selectively is how "is anything still missing?" keeps finding real items. So this is the
**walk**, one row at a time: every non-`IMPLEMENTED`, non-`N/A` row gets an owner or an explicit
verdict, and every deficiency claimed below was re-opened in the current tree rather than
trusted from the earlier audit.

## 0. What was reviewed, and the state of the tree

| | Value |
|---|---|
| App tree | `/root/pi-android` @ **`dc00279`** (`docs: record 11 feature gaps that no document had captured`) |
| `git log --oneline -1` | `dc00279b5d5d6a1075eb1efba7699a1b7068e30b docs: record 11 feature gaps that no document had captured` |
| pi (the specification) | `/root/pi-src` @ `bbb61e34aaf231639fdaaad1adbd757947034eac`, `packages/coding-agent` 0.85.1 |
| Build | none (no Gradle, no emulator), as instructed |

`git status --short` was run once at the start, then **again at the end because the tree moved
during this pass** — other agents are working in `packages/**` and `ui/**` concurrently.

```
 M app/src/main/kotlin/app/pi/bridge/DeviceShell.kt
 M app/src/main/kotlin/app/pi/ui/blocks/BranchSummaryBlock.kt
 M app/src/main/kotlin/app/pi/ui/blocks/CompactionBlock.kt
 M app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt
?? app/src/main/kotlin/app/pi/packages/PiConfigFiles.kt
?? app/src/main/kotlin/app/pi/packages/PiCredentialService.kt
?? app/src/main/kotlin/app/pi/packages/PiModelScanner.kt
?? app/src/main/kotlin/app/pi/packages/PiProviderPresets.kt
?? app/src/main/kotlin/app/pi/ui/theme/PiThemeFiles.kt
?? docs/gap-disposition.md
```

(The start-of-pass snapshot had only `DeviceShell.kt` and `PiMarkdown.kt` dirty and did **not**
contain `PiCredentialService.kt` or `PiThemeFiles.kt`. The two block-file diffs are comment-only,
`git diff` confirms, so they change no verdict.) Three consequences that matter for reading §5:

- `PiConfigFiles.kt` (547 lines) + `PiCredentialService.kt` (225) are an in-flight credential/model
  importer. They contain a real `auth.json` writer. Rows #13/#47/#49 would have been "nothing
  exists" a day ago and are now "exists, unwired".
- `PiThemeFiles.kt` (612) is an in-flight pi-theme reader: discovery from `~/.pi/agent/themes` and
  `.pi/themes`, pi's colour grammar, fallbacks. Its types (`PiThemeLoader`, `PiThemeEntry`,
  `PiResolvedTheme`) have **zero consumers** (`grep` over `app/src/main/kotlin` → 0 hits outside
  the file). Rows #34–#37 are therefore addressed in code but still absent from the GUI.
- Files mid-write can also simply not compile, and none of this new code has a caller yet.

**Verification snapshot.** Every app citation below was read at the commit above plus the dirty
files listed here. Other agents were editing `ui/theme/PiTheme.kt`, `ui/blocks/*` and
`ui/render/*` while this was written (e.g. `PiTextStyles.scaled(deltaSp)` for
`app.appearance.fontScaleDelta` landed in the middle of the pass), so a row whose evidence is a
*negative* grep — #55's "no reader" sweep in particular — should be re-run immediately before
anyone acts on it. A row whose evidence is a positive citation (`X calls Y`) is stable while the
cited file is unmodified.

**The tree has barely moved since the audit.** `git diff --stat a7b7738..HEAD` touches only
`docs/**`, `app/src/main/kotlin/app/pi/packages/**`, `ui/render/PiLatex.kt` and
`ui/render/PiMarkdown.kt`. I did not rely on that, but it is why the audit's `path:line`
citations still resolve: I re-ran `md5sum` on the eleven files the audit published hashes for,
and **all eleven are byte-identical today** (`PiSessionViewModel.kt`, `ChatScreen.kt`,
`PiSlashCommands.kt`, `SessionTreeScreen.kt`, `PiPalette.kt`, `PiSettingEditorHost.kt`,
`PiSettingsRegistry.kt`, `BlockRenderer.kt`, `PiEngineHost.kt`, `SettingsGroupScreen.kt`,
`Commands.kt`). Every citation below was nevertheless re-read in the current file; several of
the audit's *conclusions* turned out to be stale even though its files had not moved (§5).

## 1. Disposition vocabulary

`feature-gaps.md`'s own rule is kept: a finding must be demonstrable from the current code.

| Disposition | Meaning |
|---|---|
| **ASSIGNED** | A `known-gaps.md` entry covers the row **and** the entry (or the repo's in-flight work) names an owner. Entry id given. |
| **RECORDED-NO-OWNER** | A `known-gaps.md` entry covers the row but names no owner (its section is given). |
| **UNRECORDED** | No entry in `known-gaps.md`, `extension-compatibility.md` or `fidelity-review.md` covers it. **These are the ones this exercise exists to surface.** |
| **LIMIT** | pi offers no path to an RPC client. Proved by quoting pi's source, not asserted. |
| **CLOSED** | The row needs no action: either the audit's premise is disproved at the current code, or the "reduction" is a deliberate adaptation. One line of justification each. |

`CLOSED` is a fifth bucket, added because the task requires an owner **or an explicit verdict**
for every row and four buckets cannot express "verified not-a-gap". Counts for all five are in
§3; the four required counts are called out there.

Scope note: `feature-gaps.md` treats `MISSING-TERMINAL-ONLY` (12) and `N/A` (7) as limits
rather than work. They are included in the table below (as `LIMIT`) so that the walk is
literally complete; they are marked so the 55 actionable rows stay separable.

## 2. The disposition table (69 rows)

The audit's `PARTIAL 20 / MISSING-GUI 26 / CLI-ONLY 9 = 55` is reproduced exactly, plus the 12
`MISSING-TERMINAL-ONLY` rows and 2 rows whose grade column is mixed (`--models/--tools`,
`httpProxy`) and which the audit's own subtotals therefore parked under `IMPLEMENTED`.

### 2.1 RPC commands (`feature-gaps.md` §1.1)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 1 | `prompt` `images` | PARTIAL | RECORDED-NO-OWNER (E) | **E3** | `ui/PiSessionViewModel.kt:1049` `fun send(text: String, images: List<PiImage> = emptyList())`; the sole caller `ui/screens/ChatScreen.kt:415` `session.send(route.text)` passes no images | `rpc-types.ts:22` `images?: ImageContent[]` | patch-ready P6 |
| 2 | `prompt` `streamingBehavior: followUp` | PARTIAL | RECORDED-NO-OWNER (I) | **I4** (same capability; the `follow_up` route is the fix) | `rpc/Commands.kt:21` `FollowUp("followUp"),` — no caller ever passes `streamingBehavior` | `rpc-types.ts:22` `streamingBehavior?: "steer" \| "followUp"` | patch-ready P4 |
| 3 | `follow_up` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I4** | `ui/PiSessionViewModel.kt:1086` `fun sendFollowUp(text: String) {` — repo-wide grep matches only this line | `rpc-types.ts:24`; `docs/rpc.md:102-104` | patch-ready P4 |
| 4 | `clear_queue` (returned text discarded) | PARTIAL | RECORDED-NO-OWNER (I) | **I6** | `ui/screens/ChatScreen.kt:433` `onStop = { session.stop() },` vs `ui/PiSessionViewModel.kt:1097` `fun stop(onRestored: (List<String>) -> Unit = {})` | `rpc-types.ts:26`; `docs/rpc.md:137-155` | patch-ready P2 |
| 5 | `new_session` `parentSession` | PARTIAL | **UNRECORDED** | — | `ui/PiSessionViewModel.kt:1281` `val result = api.newSession()` — no argument; the parameter exists at `engine/PiEngineApi.kt:295` | `rpc-types.ts:27` `parentSession?: string`; `rpc-mode.ts:438` | blocked |
| 6 | `get_messages` (builder has no caller) | PARTIAL | CLOSED | — | `engine/PiEngineApi.kt:60` `suspend fun getMessages(...)` — no caller. Verdict: the transcript is projected from `get_entries` + events, a superset (`feature-gaps.md` §4.5). No user-visible loss, no work. | `rpc-types.ts:71` | closed |
| 7 | `export_html` (always HTML) | PARTIAL | RECORDED-NO-OWNER (I) | **I3** | `ui/PiSessionViewModel.kt:1376` `val written = api.exportHtml(guestPath)`, while the palette advertises `.jsonl` at `ui/chat/PiSlashCommands.kt:122` | `rpc-mode.ts:600-602` `exportToHtml` unconditionally; `rpc-types.ts:60` | patch-ready P3 |
| 8 | `export_html` `outputPath` (re-rooted) | PARTIAL | CLOSED | — | `ui/PiSessionViewModel.kt:1374` `val guestPath = "${guestWorkspace()}/$name"`. Verdict: deliberate — the file must land where the app can read it back (`:1377` `File(defaultWorkspace(), name)`). Documented behaviour, not a defect. | `rpc-types.ts:60` | closed |

### 2.2 pi's CLI surface (`feature-gaps.md` §1.2)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 9 | `pi install` / `remove` / `list` / `update` | CLI-ONLY | **ASSIGNED** | **B5** + **E7**, owner *包管理代理* (§G) | `packages/PiPackagesScreen.kt:95` `fun PiPackagesScreen(` — no caller outside `packages/`; `packages/PiPackageService.kt:59` likewise | `package-manager-cli.ts:46` `export type PackageCommand = "install" \| "remove" \| "update" \| "list"`; not in the `RpcCommand` union | in-flight |
| 10 | `pi config` (per-resource enable/disable) | CLI-ONLY | **UNRECORDED** | — | `ui/settings/PiSettingsRegistry.kt:779` `key = "packages[].autoload",` — whole-package only; no per-resource switch anywhere | `package-manager-cli.ts:278-289` | blocked |
| 11 | `--offline` / `PI_OFFLINE` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | `engine/PiEngineHost.kt:247-260` `extra = mapOf(` … `"PI_ANDROID_BRIDGE_FILE" to …` — no `PI_OFFLINE` | `cli/args.ts:223`; `docs/environment-variables.md:84` | in-flight |
| 12 | `--system-prompt` / `--append-system-prompt` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | `engine/PiEngineHost.kt:232-233` `append(" --mode rpc")` / `append(" --session-dir ")` — argv has no such flag | `cli/args.ts:110,112` | in-flight |
| 13 | `--api-key` | CLI-ONLY | **ASSIGNED** | **E9** + in-flight `packages/PiConfigFiles.kt` / `PiCredentialService.kt` | `ui/chat/PiSlashCommands.kt:138` `/login` is `TerminalOnly`; the new writer `packages/PiConfigFiles.kt:308` `fun setApiKey(providerId: String, key: String): String?` and its orchestrator `packages/PiCredentialService.kt:39` `class PiCredentialService(` both have **no caller** | `cli/args.ts:108`; `docs/providers.md:62-107` | in-flight |
| 14 | `--continue`/`-c`, `--resume`, `--session`, `--fork` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** (`-c` only; resume/switch/fork exist as actions) | `ui/PiSessionViewModel.kt:363` `private fun attach(engine: PiEngineSession) {` — no `switch_session`; argv has no `--continue` (`PiEngineHost.kt:232`) | `cli/args.ts:100`; `docs/sessions.md:39` | patch-ready P12 |
| 15 | per-run `--no-*` suppression (`--no-session`, `--no-extensions`, …) | CLI-ONLY | CLOSED | — | Persistent equivalents exist as the `extensions`/`skills`/`prompts`/`themes` rows; the app owns one long-lived engine, so there is no "per run" to suppress. Nothing a user can want is missing. | `cli/args.ts:169,192` | closed |

### 2.3 Interactive TUI (`feature-gaps.md` §1.3)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 16 | queue editing: restore queued text on Esc | MISSING-GUI | RECORDED-NO-OWNER (I) | **I6** | `ui/screens/ChatScreen.kt:433` `onStop = { session.stop() },` | `docs/rpc.md:137-155`; `keybindings.md:166` `app.message.dequeue` | patch-ready P2 |
| 17 | queue a follow-up (`alt+enter`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I4** | `ui/PiSessionViewModel.kt:1086` — only occurrence | `keybindings.md:165` `app.message.followUp`; `docs/rpc.md:102` | patch-ready P4 |
| 18 | expand/collapse thinking (`ctrl+t`) | PARTIAL | RECORDED-NO-OWNER (I) | **I7** | `ui/screens/ChatScreen.kt:348` `thinkingDefaultExpanded = false,` — hard-coded | `keybindings.md` `app.thinking.toggle` | patch-ready P5 |
| 19 | `hideThinkingBlock` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I7** | `ui/screens/ChatScreen.kt:345-349` `BlockRenderer(` passes neither `hideThinking` nor a collapse-all; the renderer supports it at `ui/blocks/BlockRenderer.kt:39` `hideThinking: Boolean = false,` | `core/settings-manager.ts:119` `hideThinkingBlock?: boolean` | patch-ready P5 |
| 20 | transcript search (`ctrl+shift+f`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | No search UI in `app/src/main/kotlin/app/pi/ui`: `grep -r "rememberSearch\|SearchBar"` → 0 hits; only `settings/SettingsSearchScreen.kt` exists, which searches settings | `keybindings.md:114` `tui.altScreen.search` | blocked |
| 21 | jump to previous/next message (`ctrl+shift+up/down`) | MISSING-GUI | **UNRECORDED** | — | `ui/screens/ChatScreen.kt:167` `listState.animateScrollToItem(last)` is the only scroll control (auto-pin); no user-facing jump exists | `keybindings.md:112` `tui.altScreen.previousPrompt` | patch-ready P7 |
| 22 | tree filters (`treeFilterMode`, `ctrl+t/u/l/a/o`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | `ui/chat/SessionTreeScreen.kt:107` `BranchTab(state = state, onFork = onFork, …)` — no filter control; `treeFilterMode` occurs only at `PiSettingsRegistry.kt:1261` | `docs/sessions.md:100` | blocked |
| 23 | session picker: search / sort / named filter / rename / delete | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** (search/sort/named) + **I9** (delete) | `ui/screens/SessionsScreen.kt:105` `items(sessions, key = { it.file.absolutePath })` — a plain list | `docs/sessions.md:43-48`; `keybindings.md:141-145` | patch-ready P8 |
| 24 | session tree "delete session" + non-invasive variant | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | `session/PiSessionStore.kt:49` `suspend fun list(limit: Int = 300)` — the store exposes no delete; `SessionsScreen.kt:105` renders rows only | `keybindings.md:144-145` | patch-ready P9 |
| 25 | external editor (`ctrl+g`) | PARTIAL | **UNRECORDED** | — | `ui/settings/PiSettingsRegistry.kt:1288` `key = "externalEditor",` — the sole occurrence outside a doc comment; no launch action exists | `keybindings.md` `app.editor.external` | blocked |
| 26 | scoped models selector (`/scoped-models`) | PARTIAL | **UNRECORDED** | — | `ui/chat/PiSlashCommands.kt:118-119` `/scoped-models` is `PiCommandAction.TerminalOnly`; the setting is a raw `List` row at `PiSettingsRegistry.kt:355` | `keybindings.md` §Scoped Models Selector | patch-ready P10 |
| 27 | `/tree` navigation: switch the active leaf | MISSING-TERMINAL-ONLY | **LIMIT** | **I1** / **F2** | `ui/chat/SessionTreeScreen.kt:226` `TextButton(onClick = { onFork(id) }) { Text("分支") }` — the only tree action is a fork, which writes a new session file | `rpc-types.ts:20-74` — the only tree commands are `get_tree` (`:66`) and `fork` (`:62`); `interactive-mode.ts:5216-5322` | limit |
| 28 | tree labels (`setLabel`, `shift+l`) | MISSING-TERMINAL-ONLY | **LIMIT** | **F2** | `ui/chat/SessionTreeScreen.kt:197` `row.node.label?.let { label ->` — labels are rendered, never set | no `RpcCommand` for `setLabel` | limit |
| 29 | `/import` | MISSING-TERMINAL-ONLY | **LIMIT** | **F2** / §2.9 | `ui/chat/PiSlashCommands.kt:127` `PiCommandAction.TerminalOnly,` (the `/import` row) | `interactive-mode.ts:2998,6107`; no `RpcCommand` | limit |
| 30 | `/share` | MISSING-TERMINAL-ONLY | **LIMIT** | §2.9 | `ui/chat/PiSlashCommands.kt:129` `PiSlashCommand("share", …, PiCommandAction.TerminalOnly)` | `core/slash-commands.ts:27`; no `RpcCommand` | limit |
| 31 | `/login`, `/logout` | MISSING-TERMINAL-ONLY | **LIMIT** | **F3** / §2.9 | `ui/chat/PiSlashCommands.kt:138-139` both `PiCommandAction.TerminalOnly` | `core/slash-commands.ts:33-34`; no `RpcCommand` | limit |
| 32 | `/reload` | MISSING-TERMINAL-ONLY | **LIMIT** | **B6** | `ui/screens/ChatScreen.kt:240-242` `IconButton(onClick = { session.notifyTerminalOnly(reloadCommand()) })` | `rpc-types.ts:20-74`; built-ins are excluded from `get_commands` | limit |
| 33 | `/changelog`, `/hotkeys`, `/quit` | MISSING-TERMINAL-ONLY | **LIMIT** | — | `ui/chat/PiSlashCommands.kt:133-134,144` all `TerminalOnly` | `core/slash-commands.ts:31-32,42`; no `RpcCommand` | limit |

### 2.4 Themes (`feature-gaps.md` §1.4)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 34 | a user theme JSON changes the app's own colours | MISSING-GUI | **ASSIGNED** | **I5** + in-flight `ui/theme/PiThemeFiles.kt` | `ui/theme/PiPalette.kt:114` `val Dark = PiPalette(` and `:177` `val Light = PiPalette(` remain the only two palettes the UI can use; `MainActivity.kt:35-40` still maps only `light`/`dark`/`a/b` and `else -> dark`. The new `PiThemeLoader`/`PiResolvedTheme` have **0 consumers** | `docs/themes.md:14-18`; `resource-loader.ts:875` | in-flight |
| 35 | theme discovery `<agentDir>/themes/*.json` | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.AgentDir`) | `ui/settings/PiSettingEditorHost.kt:90-98` `localThemeNames` still derives names only from the `themes` settings row; nothing calls the new discovery | `resource-loader.ts:815` | in-flight |
| 36 | theme discovery `.pi/themes/*.json` | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.Project`) | same as #35 | `resource-loader.ts:821` | in-flight |
| 37 | themes from packages (`themes/`, `pi.themes`) | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.Configured`) | same as #35 | `docs/themes.md:17` | in-flight |
| 38 | `--theme` / `--no-themes` | CLI-ONLY | CLOSED | — | Persistent `themes` row exists; per-run flag has no per-run engine to attach to. | `cli/args.ts:177,192` | closed |

### 2.5 Sessions (`feature-gaps.md` §1.5)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 39 | switch branch **in place** | MISSING-TERMINAL-ONLY | **LIMIT** | **I1** | `ui/chat/SessionTreeScreen.kt:226` — fork only | `rpc-types.ts:20-74`; `docs/sessions.md:71` | limit |
| 40 | branch summarization when leaving a branch | MISSING-TERMINAL-ONLY | **LIMIT** | **I1** / §2.1 | requires #39; unreachable | `interactive-mode.ts:5238`; `docs/sessions.md:131` | limit |
| 41 | **Delete** a session | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | `session/PiSessionStore.kt:49` — `list` only | `docs/sessions.md:48`; `keybindings.md:144` | patch-ready P9 |
| 42 | search / sort / named-only filter in the picker | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | `ui/screens/SessionsScreen.kt:105` | `docs/sessions.md:43-46` | patch-ready P8 |
| 43 | resume most recent session on launch (`pi -c`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | `ui/PiSessionViewModel.kt:363` `attach()` issues no `switch_session`; `PiEngineHost.kt:232` argv | `cli/args.ts:100`; `docs/sessions.md:39` | patch-ready P12 |
| 44 | export to JSONL | MISSING-GUI | RECORDED-NO-OWNER (I) | **I3** | `ui/PiSessionViewModel.kt:1376` — always `export_html` | `interactive-mode.ts:6064-6065` vs `rpc-mode.ts:600-602` | patch-ready P3 |
| 45 | import from a JSONL file | MISSING-TERMINAL-ONLY | **LIMIT** | **F2** / §2.9 | `ui/chat/PiSlashCommands.kt:127` | `interactive-mode.ts:6107`; no `RpcCommand` | limit |
| 46 | per-cwd session **grouping** in the UI | PARTIAL | **UNRECORDED** | — | `ui/screens/SessionsScreen.kt:105` builds one flat `LazyColumn`; cwd appears only as a per-row string at `:172` `append(shortenPath(summary.cwd))` | `docs/sessions.md:7` | patch-ready P11 |

### 2.6 Models, providers, thinking (`feature-gaps.md` §1.6)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 47 | custom provider via `models.json` (no editor) | PARTIAL | **ASSIGNED** | **E9** + in-flight `packages/PiConfigFiles.kt` / `PiCredentialService.kt` / `PiModelScanner.kt` | `packages/PiConfigFiles.kt:499` `fun upsert(provider: Provider): String?` and `PiCredentialService.kt:39` have no caller; the Action row `PiSettingsRegistry.kt:387` `key = "app.localModels.manage",` is inert | `docs/custom-provider.md`; `core/model-runtime.ts:174-175` | in-flight |
| 48 | OAuth login (`/login`, `/logout`) and `auth.json` | MISSING-TERMINAL-ONLY | **LIMIT** | **F3** / §2.9 | `PiSettingsRegistry.kt:378` `key = "app.credentials.oauth",` (`Action` kind at `:381`) is inert; `/login` is `TerminalOnly` (`PiSlashCommands.kt:138-139`) | no `RpcCommand` for login; `docs/providers.md:17-26` | limit |
| 49 | API-key credentials | MISSING-GUI | **ASSIGNED** | **E9** + in-flight `PiConfigFiles.kt` | `PiRoot.kt:130-138` calls `PiSettingsStack(` without `onRunAction`; the writer `PiConfigFiles.kt:308` has no caller | `docs/providers.md:62-107`; `core/auth-storage.ts:52` | in-flight |
| 50 | `provider/auth` events visible to the client | PARTIAL | **LIMIT** (compensated) | `fidelity-review.md` §4 / `extension-compatibility.md` §5.3 | The app works around it by polling: `ui/PiSessionViewModel.kt:492` "`model_select` is emitted to extensions only" and `:895` "Polled rather than event-driven on purpose" | `core/agent-session.ts:1665-1670` — `_emitModelSelect` calls `this._extensionRunner.emit(...)` only | limit |

### 2.7 Skills, templates, packages (`feature-gaps.md` §1.7)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 51 | npm/git packages (`packages`) management UI | PARTIAL | **ASSIGNED** | **B5** + **E7**, owner *包管理代理* (§G) | `packages/PiPackagesScreen.kt:95` has no caller; `packages/PiPackageService.kt:59` has no caller | `docs/packages.md:22-39`; not in the `RpcCommand` union | in-flight |
| 52 | package resource enable/disable (`pi config`) | CLI-ONLY | **UNRECORDED** | — | `PiSettingsRegistry.kt:779` `key = "packages[].autoload",` — whole-package only | `package-manager-cli.ts:278-289` | blocked |
| 53 | extension load diagnostics | MISSING-TERMINAL-ONLY | **LIMIT** | **F1** | `grep -rn "runtime.diagnostics"` over `app/src` + `rpc/src` → **0 hits**; no event carries it | pi writes load errors only to `runtime.diagnostics`; no RPC channel (`known-gaps` F1) | limit |
| 54 | built-in vs user-installed package distinction | MISSING-GUI | **ASSIGNED** | **E7**, owner *包管理代理* (§G) | `packages/PiPackagesScreen.kt:95` unmounted; bundled assets are installed at `bridge/DeviceBridgeController.kt:299` | — (app-side) | in-flight |

### 2.8 Settings coverage (`feature-gaps.md` §1.8)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 55 | registered settings that nothing reads | MISSING-GUI | RECORDED-NO-OWNER (I) | **I7** | All 13 keys re-swept: each occurs once, inside `PiSettingsRegistry.kt` (`:662`, `:676`, `:690`, `:869`, `:879`, `:893`, `:908`, `:918`, `:1057`, `:1071`, `:1086`, `:1177`, `:1615`). Caveat: this is a negative-grep row and the UI agent was wiring `fontScaleDelta` (`PiTheme.kt`) during the pass — re-run the sweep before acting. | — (app-side) | fixed (partial) |
| 56 | all 20 `Action`-kind rows are inert | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | `PiRoot.kt:130-138` omits `onRunAction`; `SettingsGroupScreen.kt:155` then renders "这个入口由运行时接管，当前宿主还没有接入对应的实现"; 20 `PiRowKind.Action` rows counted in the registry | — (app-side) | blocked |
| 57 | device capability switches (two authorities) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I10** | `PiSettingsRegistry.kt:1474-1549` declares `app.device.*`; `grep -rn "app\.device\."` outside the registry → **0 hits**, while enforcement reads SharedPreferences at `bridge/DeviceCapabilityStore.kt:65-92` | — (app-side) | blocked |
| 58 | update checks | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | `PiSettingsRegistry.kt:1566` `key = "app.runtime.checkUpdate",` — an `Action` row inside the inert set above | — (app-side) | blocked |

### 2.9 Attachments, images, `@` mentions (`feature-gaps.md` §1.9)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 59 | images into `prompt` | PARTIAL | RECORDED-NO-OWNER (E) | **E3** | `PiSessionViewModel.kt:1049` / `ChatScreen.kt:415` (as #1) | `rpc-types.ts:22` | patch-ready P6 |
| 60 | picking an image from the device | MISSING-GUI | RECORDED-NO-OWNER (E) | **E3** | `ChatScreen.kt:402-410` `Composer(` has no attachment affordance; `grep "PickVisualMedia\|GetContent\|ACTION_OPEN_DOCUMENT"` in UI → 0 hits | — | patch-ready P6 |
| 61 | pasting an image from the clipboard | MISSING-GUI | RECORDED-NO-OWNER (E) | **E3** | `ChatScreen.kt:552-554` only *writes* the clipboard (`clipboard?.setPrimaryClip(…)`); nothing reads an image in | `keybindings.md` `app.clipboard.pasteImage` | patch-ready P6 |
| 62 | rendering attachments in the transcript | PARTIAL | **UNRECORDED** | — | `ui/blocks/ImageGridBlock.kt:30` "The cells are drawn as labelled placeholders: this app has no image-loading dependency yet"; `:117` `text = "图片 ${index + 1}"` | `session-format.md:53-57` | patch-ready P1 |
| 63 | `@` file mentions | MISSING-GUI | RECORDED-NO-OWNER (E) | **E4** | `ui/chat/SlashPalette.kt:206-210` `routeComposerText` recognises only `!`/`/`; no `@` branch anywhere | `utils/paths.ts:17,80` | blocked |

> Note on #62: `known-gaps` **A3** is about markdown-embedded `![](...)` images via
> `ImageTransformer`. It is a *different surface* — `feature-gaps.md:281` itself says "the
> placeholder nature of the attachment grid is **not** called out there". A3 remains open
> (`render/PiMarkdown.kt:68` still uses `NoOpImageTransformerImpl`), but it does not record #62.

### 2.10 Environment and ops (`feature-gaps.md` §1.10)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 64 | `PI_OFFLINE` / `--offline` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | `PiEngineHost.kt:247-260` — fixed `extra = mapOf(...)`, no offline key | `docs/environment-variables.md:84`; `cli/args.ts:223` | in-flight |
| 65 | `PI_CACHE_RETENTION` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I11** | same env map; `grep "PI_CACHE_RETENTION\|cacheRetention"` over `app/src` + `rpc/src` → **0 hits** | `docs/environment-variables.md:87` | in-flight |
| 66 | engine argv (closed to overrides) | PARTIAL | RECORDED-NO-OWNER (I) | **I11** | `PiEngineHost.kt:232-233` | `docs/rpc.md:7-15` | in-flight |
| 67 | runtime/ops diagnostics (`app.runtime.*`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | 8 `app.runtime.*` `Action` rows at `PiSettingsRegistry.kt:1566-1668`, all inside the inert set of #56 | — (app-side) | blocked |

### 2.11 Mixed-grade rows the audit parked under `IMPLEMENTED`

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 68 | `--models` / `--tools` etc.: reading or setting the **live** tool set | PARTIAL (live) | **LIMIT** | **F2** | Only startup-equivalents exist (`enabledModels`, `defaultTools` settings); no RPC command reads or writes the live tool table | `rpc-types.ts:20-74` — no tools command; `known-gaps` F2 | limit |
| 69 | `httpProxy` reaching **package installs** | PARTIAL | CLOSED (audit premise disproved) | — | `packages/GuestCommand.kt:198-204` `ENV` carries no proxy — but none is needed: pi applies the setting to its own process environment before dispatching package commands (`main.ts:583` then `:586`), and npm is spawned with the inherited env (`core/package-manager.ts:15-35` `getEnv` returns `process.env`) | `core/http-dispatcher.ts:45-50` `applyHttpProxySettings` | closed |

## 3. Count summary

Rows: **69** = the audit's 55 actionable rows (PARTIAL 20 / MISSING-GUI 26 / CLI-ONLY 9) + 12
`MISSING-TERMINAL-ONLY` (limits) + 2 mixed-grade rows parked under `IMPLEMENTED`.

| Disposition | Rows | Of which in the 55 actionable | Of which limits / parked |
|---|---|---|---|
| ASSIGNED | **10** | 10 | 0 |
| RECORDED-NO-OWNER | **32** | 32 | 0 |
| UNRECORDED | **8** | 8 | 0 |
| LIMIT | **14** | 2 (#48 OAuth, #50 provider events) | 12 (`MISSING-TERMINAL-ONLY`) |
| CLOSED | **5** | 3 (#6, #8, #15) | 2 (#38, #69) |
| **Total** | **69** | **55** | **14** |

The four required counts, over the 67 non-`IMPLEMENTED`/non-`N/A` rows:
**ASSIGNED 10 · RECORDED-NO-OWNER 32 · UNRECORDED 8 · LIMIT 14**, with 3 more rows explicitly
`CLOSED` (no action).

Over the 55 actionable rows: **ASSIGNED 10 · RECORDED-NO-OWNER 32 · UNRECORDED 8 · CLOSED 3 ·
LIMIT 2**.

The four rows that moved from RECORDED-NO-OWNER to ASSIGNED during this pass are #34–#37 (themes):
`ui/theme/PiThemeFiles.kt` appeared in the working tree while this document was being written and
implements exactly what I5 records, but nothing calls it yet. That is the correct shape of an
`ASSIGNED` row here: **recorded, owned, in flight, not yet reachable** — so the deficiency in the
"app file" column is still true today and the owner is now known.

## 4. The UNRECORDED rows

Eight rows, seven distinct gaps. This is the section the exercise exists for: no entry anywhere
in `known-gaps.md`, `extension-compatibility.md` or `fidelity-review.md` covers them.

| # | Capability | One-line user-visible consequence |
|---|---|---|
| 5 | `new_session` `parentSession` unused (`PiSessionViewModel.kt:1281`) | Every session started from the app is a root; the parent link pi accepts over RPC cannot be expressed, so app-created sessions never appear as children in pi's own lineage. Low impact. |
| 10 | `pi config` per-resource enable/disable has no app path (`PiSettingsRegistry.kt:779`) | A user cannot switch off one skill/prompt/theme/extensions entry inside an installed package from the GUI — only the whole package's `autoload`; per-resource filtering means hand-editing `settings.json`. |
| 21 | No jump to previous/next message (`ChatScreen.kt:167`) | On a long transcript the only navigation is scrolling; pi's `previousPrompt` equivalent does not exist. Low impact. |
| 25 | `externalEditor` is a setting with no consumer (`PiSettingsRegistry.kt:1288`) | A user can set an external editor command and nothing ever launches it from the GUI; `Ctrl+G` behaviour exists only inside the terminal tab. |
| 26 | No scoped-models selector (`PiSlashCommands.kt:118-119`) | `/scoped-models` answers "terminal only" and the app offers no picker; choosing which models participate in cycling means hand-editing the raw `enabledModels` list. |
| 46 | No per-cwd grouping in the session list (`SessionsScreen.kt:105`) | With several projects the session list is one flat column (capped at 300 rows) showing a path string per row; there is no grouping or filter by project. |
| 52 | `pi config` again, recorded as a skills/packages row (`PiSettingsRegistry.kt:779`) | Same consequence as #10: per-resource enable/disable is unreachable from the GUI. |
| 62 | Received/attached images render as placeholders (`ImageGridBlock.kt:30,117`) | An image that is in the session renders as a cell reading "图片 N" plus its mime type — the user cannot see the image. This is the only unrecorded gap with a plainly wrong-looking result. |

## 5. Where the earlier audit is now stale (do not send anyone to "fix" these)

Five of these would have cost real work if taken at face value.

1. **`httpProxy` and package installs (row #69) — the audit is wrong.** `feature-gaps.md:269`
   says "an `npm install` run for a package would not inherit the proxy". It does. pi calls
   `applyHttpProxySettings(bootstrapSettingsManager.getGlobalSettings().httpProxy)` at
   `main.ts:583`, *before* `handlePackageCommand(args, …)` at `:586`
   (`core/http-dispatcher.ts:45-50` sets `process.env.HTTP_PROXY ??= proxy`), and
   `core/package-manager.ts:15-35` (`getEnv`) hands `process.env` to the npm child. No app-side
   proxy plumbing is needed. Classified `CLOSED`.
2. **`--api-key` / `app.credentials.apiKey` — "no `auth.json` writer anywhere in the app (grep:
   0 hits)" is stale.** The working tree now contains an untracked `packages/PiConfigFiles.kt`
   (547 lines) implementing `auth.json` write with pi's `proper-lockfile` protocol
   (`:308` `setApiKey`), plus `PiModelsFile.upsert` (`:499`), and a second new file
   `packages/PiCredentialService.kt` (225 lines, `:39`) orchestrating the whole paste-a-key flow,
   with `PiModelScanner`/`PiProviderPresets` beside them. None of it has a caller, so the GUI row
   is still inert — but the disposition is ASSIGNED/in-flight, not empty. Anyone re-grepping for
   `auth.json` will now find it; the remaining work is the `onRunAction` wiring, not the file
   format.
3. **The theme rows (#34–#37) have the same shape.** `ui/theme/PiThemeFiles.kt` (612 lines,
   untracked) appeared mid-pass and reads pi's theme files the way `resource-loader.ts` and
   `theme.ts` do; `PiThemeLoader`/`PiThemeEntry`/`PiResolvedTheme` have zero consumers, so the
   GUI palette is still the two hard-coded instances at `PiPalette.kt:114,177`.
4. **`known-gaps.md` entries whose work is already done** (so a reader does not re-open them):
   **A1** (markdown now used by `CompactionBlock.kt:104`, `BranchSummaryBlock.kt:80`,
   `HookMessageBlock.kt:65`), **A2** (LaTeX wired at `render/PiMarkdown.kt:153,157` via
   `PiLatex`), **B1** (`/` palette), **B2** (tree/fork/clone/switch/rename/export), **B3**
   (model/thinking pickers), **B4** (bash mode) — all graded `IMPLEMENTED` by `feature-gaps.md`,
   **B8** (`ExtensionUiHost` is mounted once, globally, at `ui/PiRoot.kt:166`, with no duplicate
   in any screen), **B11** (`PiEvent.ExtensionError` now carries `extensionPath` —
   `rpc/Events.kt:285` and the parse at `:468`), **E2** (`PiNodeCodeHighlighter.attach(context)`
   is called from `render/PiMarkdown.kt:62`).
5. **`feature-gaps.md` §5 UNVERIFIED items — five of six are now settled:**
   `hideThinkingBlock` (0 hits outside registry+doc comment), `SessionTreeScreen` (only `onFork`),
   the `app.device.*` disconnect (0 hits outside the registry), the 13 no-reader settings
   (re-confirmed key by key), and `attach(context)` (now provably wired). Only `/share` and the
   device-tab questions remain (below).

## 6. Reverse direction: `known-gaps.md` entries **no** `feature-gaps.md` row justifies

Listed so the ledger cannot over-claim. "Justified" = at least one of the 148 rows depends on it.

| Entry | Status |
|---|---|
| A3 markdown-embedded images | **Valid and unjustified.** Still open (`render/PiMarkdown.kt:68` `NoOpImageTransformerImpl`), but no feature-gaps row grades it. The nearest row (#62) is the attachment grid, a different surface. |
| B7 project-trust prompt | **Unjustified and currently unreachable.** `packages/PiProjectTrustPrompt.kt:52` exists, but its only call site is `PiPackagesScreen.kt:119`, and `PiPackagesScreen` has no caller — so the trust UI is dead code until the packages screen is mounted. |
| B9 markdown lib version, B10 `libprootloader.so` PIE | Valid, no capability row (library/dependency constraints, by design outside the matrix). |
| B12 / E6 `ASSET_VERSION` fingerprint | Valid (`bridge/DeviceBridgeController.kt:69` is still the literal `"3"`), no capability row. |
| C1–C4 device/real-run verification | No capability row, by construction — these are "has it been run" items, not features. |
| D1–D4 design compromises | No row needed; they are explicit non-gaps. |
| E1 tool-name collisions | Justified only indirectly: row 1.7 is graded `IMPLEMENTED` for *command* collisions and its "Recorded where" cell says "tool-name collisions open". No row grades the tool half. |
| E5 lazy engine start, E8 terminal build-vs-buy | App-side improvements/decisions with no feature-gaps row. |
| G table (six `rpc/**` items, `turnIndex`, `contentIndex`, `CAMERA`, restart entry, trust store) | Fidelity/bridge items, not capability rows. `CAMERA` has since been implemented (`bridge/DeviceCapabilityStore.kt:252-274`); the restart entry is delivered by `packages/EngineRestartCoordinator.kt` (also unwired to a caller). |
| I1–I11 | All justified — each maps onto one or more rows above (I1→27/39/40, I2→56/58/67, I3→7/44, I4→2/3/17, I5→34-37, I6→4/16, I7→18/19/55, I8→20/22/23/42, I9→14/23/24/41/43, I10→57, I11→11/12/64/65/66). |

## 7. UNVERIFIED

| Item | Why | What would settle it |
|---|---|---|
| **Does the tree compile?** | **Checked: `bash tools/typecheck.sh`, last line `typecheck: FAILED in :app`** (run started 07:50 on the working tree; `:rpc` phase passed). **7 errors, in 2 files, none of them touched by this pass**: `ui/PiSessionViewModel.kt:548` (×2) — the new `PiLaunchOptions` wiring is being called with a trailing lambda where a `PiLaunchOptions` is expected; and the untracked `ui/theme/PiThemeFiles.kt` (×5) — `:258` `'internal' function exposes its 'private-in-class' return type 'ParsedTheme'`, `:383` `unresolved reference 'intOrNull'` plus three inference errors. Both are mid-write by other agents; route them there. **Zero errors point at any file changed by this pass** (`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalSurface.kt`, `TerminalKeyBar.kt`, `terminal/TerminalController.kt`, `terminal/TerminalKeys.kt`). | Fix the two files above, then re-run; a frozen-tree run is still the only verdict on the untracked `packages/**` code, which has no caller and is therefore not exercised. |
| Whether `/share` can work on this host at all | Needs a browser plus a reachable loopback callback (`PI_SHARE_VIEWER_URL` is unset); never run. | Run the gist flow from the 工作区 → `pi TUI（原版）` tab on a device. |
| Whether the 12 `TerminalOnly` rows are actually usable in this app's terminal tab | The tab exists (`PtyLauncher.Kind.PiTui`, reachable at `ChatScreen.kt:694`) but `known-gaps` C2 records the hand-written VT emulator as never run on a device. | The device pass already listed as C1/C2. |
| The user-visible effect of `new_session parentSession` on pi's side (row #5) | Read from `rpc-mode.ts:438`; the consequence ("sessions are always roots") is inferred from the wire, not observed. | Create a parented session with a raw `pi --mode rpc` client and diff the session header / `get_tree`. |
| Whether `PiPackagesScreen` / `PiCredentialService` / `PiThemeFiles` get wired before this document is acted on | All are in-flight; absence of callers is verified *now*. `PiCredentialService.kt` and `PiThemeFiles.kt` appeared **during** this pass, so the snapshot in §0 is a moving target. | Re-grep `PiPackagesScreen(`, `PiCredentialService(`, `PiThemeLoader`, `onRunAction` after the tree freezes. |

No row in §2 depends on an UNVERIFIED item: every deficiency above was read out of the current
Kotlin (or proved absent by a repo-wide grep) and every LIMIT was proved against pi's source.

## 8. Ready-to-apply patches (ordered by user-visible severity)

For rows whose files are owned by another agent, this is the deliverable: a patch small enough to
paste. All line numbers are from `dc00279` + the dirty files named in §0; **re-check the anchor
line before applying**, because the tree moves. The status column in §2 points at the patch ids
here.

### P1 — #62 images render as placeholders (highest severity: the bytes are already in memory)

`PiImage` carries the image inline as base64 (`rpc/Commands.kt:13`
`data class PiImage(val base64: String, val mimeType: String)`), so **no new dependency and no
byte channel are needed** — the file's own doc comment ("this app has no image-loading dependency
yet") is the only thing blocking it. pi has no client-side answer to copy here; this is pure app
rendering.

`app/src/main/kotlin/app/pi/ui/blocks/ImageGridBlock.kt`, in `ImageCell` (line 111):

before
```kotlin
        Box(contentAlignment = Alignment.Center) {
            Column(
```
after
```kotlin
        Box(contentAlignment = Alignment.Center) {
            // `PiImage` is base64 inline, so a decode is the whole channel.
            val bitmap = remember(image.base64) { decodeImage(image.base64) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 张图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            Column(
```
close the new `else` after the placeholder `Column`'s closing brace (line 126), then add:

```kotlin
/** Decoded from the wire's inline base64; null keeps the labelled placeholder. */
private fun decodeImage(base64: String): Bitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()
```
New imports: `android.graphics.Bitmap`, `android.graphics.BitmapFactory`, `android.util.Base64`,
`androidx.compose.foundation.Image`, `androidx.compose.foundation.layout.fillMaxSize`,
`androidx.compose.runtime.remember`, `androidx.compose.ui.graphics.asImageBitmap`,
`androidx.compose.ui.layout.ContentScale`. Caveat: decoding in composition is main-thread work; a
follow-up should move it to `produceState(Dispatchers.IO)`.

### P2 — #4 / #16 Stop destroys the queued text (one lambda)

`app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt:433`

before
```kotlin
            onStop = { session.stop() },
```
after
```kotlin
            onStop = {
                session.stop { restored ->
                    if (restored.isNotEmpty()) draft = restored.joinToString("\n")
                }
            },
```
`stop(onRestored)` already exists (`PiSessionViewModel.kt:1097`) and pi prescribes exactly this
(`docs/rpc.md:137-155`: restore the returned text in the client editor). No other change needed;
`draft` is in scope.

### P3 — #7 / #44 `/export x.jsonl` writes HTML (silent wrong bytes)

Two edits; the first is the honest minimum, the second stops the wrong write.

`app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt:122`

before
```kotlin
        "export", "导出会话（默认 HTML，或指定 .html/.jsonl 路径）", PiCommandSource.Builtin, null,
```
after
```kotlin
        "export", "导出会话为 HTML（RPC 只提供 export_html；JSONL 导出只在终端标签页可用）", PiCommandSource.Builtin, null,
```
`app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:1371-1374`

before
```kotlin
    fun exportHtml(fileName: String? = null) {
        val name = fileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "pi-session-${System.currentTimeMillis()}.html"
```
after
```kotlin
    fun exportHtml(fileName: String? = null) {
        val name = fileName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "pi-session-${System.currentTimeMillis()}.html"
        if (!name.endsWith(".html", ignoreCase = true)) {
            pushNotice("导出路径需要 .html：RPC 只有 export_html，pi 的 JSONL 导出仅 TUI 可用", Notice.Tone.Warning)
            return
        }
```
pi proof: `rpc-mode.ts:600-602` calls `session.exportToHtml(command.outputPath)` unconditionally,
while the TUI branches on `.jsonl` (`interactive-mode.ts:6064-6065` → `exportToJsonl`). A JSONL
export would have to be written app-side from `get_entries`; that is a feature, not a patch.

### P4 — #2 / #3 / #17 follow-up delivery is unreachable

`app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt`, in the overflow menu (next to the
"循环切换模型" item, around line 295):

```kotlin
                    OverflowItem("排队追问（本轮结束后发送）") {
                        if (draft.isNotBlank()) {
                            session.sendFollowUp(draft.trim())
                            draft = ""
                        }
                        overflow = false
                    }
```
`sendFollowUp` exists and is correct (`PiSessionViewModel.kt:1086-1093`); it simply has no caller
today. This also makes the already-wired `followUpMode` (`PiSettingsRegistry.kt:411`) mean
something. pi: `keybindings.md:165` `app.message.followUp` (`alt+enter`); `docs/rpc.md:102-104`.

### P5 — #18 / #19 `hideThinkingBlock` and thinking collapse-all never reach the renderer

`app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt:345-349`

before
```kotlin
                    BlockRenderer(
                        item = item,
                        toolsDefaultExpanded = toolsExpanded,
                        thinkingDefaultExpanded = false,
                    )
```
after
```kotlin
                    val prefs = session.settingsStore
                    BlockRenderer(
                        item = item,
                        toolsDefaultExpanded = toolsExpanded,
                        thinkingDefaultExpanded = thinkingExpanded
                            ?: (prefs.readBoolean("app.appearance.thinkingCollapsedByDefault") != true),
                        hideThinking = prefs.readBoolean("hideThinkingBlock") == true,
                    )
```
plus one state var beside `toolsExpanded` (line 146) and one overflow item:

```kotlin
    var thinkingExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
...
                    OverflowItem(if (thinkingExpanded == true) "收起全部思考" else "展开全部思考") {
                        thinkingExpanded = thinkingExpanded != true
                        overflow = false
                    }
```
Imports: `app.pi.settings.readBoolean`. `BlockRenderer` already implements both parameters
(`ui/blocks/BlockRenderer.kt:39-53`); pi's setting is `settings-manager.ts:119`
(`hideThinkingBlock?: boolean`), documented in `docs/settings.md` §UI & Display. Caveat: it reads
the store at composition, so the toggle applies on the next transcript recomposition; making it
reactive means putting it in `UiState` (that file is owned).

### P6 — #1 / #59 / #60 / #61 image input (picker + clipboard), end to end

The plumbing below the UI already exists: `PiImage` (`rpc/Commands.kt:13`), `putImages`
(`:265-278`), `PiEngineSession.prompt(images)` and `PiSessionViewModel.send(text, images)`
(`:1049-1066`). Only the affordance is missing. In `ChatScreen`, beside the `Composer`:

```kotlin
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val mime = context.contentResolver.getType(uri) ?: "image/*"
                session.send(
                    draft.ifBlank { "请看这张图片" },
                    listOf(PiImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)),
                )
                draft = ""
            }
        }
    }
```
and in the key-hint row (line 684-686, next to `KeyHint("/", …)`):

```kotlin
                KeyHint("图", { imagePicker.launch("image/*") })
```
New imports: `android.util.Base64`, `androidx.activity.compose.rememberLauncherForActivityResult`,
`androidx.activity.result.contract.ActivityResultContracts`, `app.pi.rpc.PiImage`. The clipboard
half (#61) is the same call with a URI from `clipboard.primaryClip?.getItemAt(0)?.uri`. pi side:
`rpc-types.ts:22` `images?: ImageContent[]`; `keybindings.md` `app.clipboard.pasteImage`. Caveat:
`readBytes()` runs on the main thread; wrap it in `withContext(Dispatchers.IO)` before shipping.

### P7 — #21 no jump to previous/next message

`ChatScreen`: a `rememberCoroutineScope()` plus two overflow items over the transcript index list.

```kotlin
    val scope = rememberCoroutineScope()
    val userRowIndices = state.transcript.mapIndexedNotNull { i, item ->
        if (item is UserMessage) i else null
    }
...
                    OverflowItem("跳到上一条提问") {
                        val current = listState.firstVisibleItemIndex
                        userRowIndices.lastOrNull { it < current }?.let { row ->
                            scope.launch { listState.animateScrollToItem(row) }
                        }
                        overflow = false
                    }
                    OverflowItem("跳到下一条提问") {
                        val current = listState.firstVisibleItemIndex
                        userRowIndices.firstOrNull { it > current }?.let { row ->
                            scope.launch { listState.animateScrollToItem(row) }
                        }
                        overflow = false
                    }
```
Imports: `androidx.compose.runtime.rememberCoroutineScope`, `kotlinx.coroutines.launch`,
`app.pi.rpc.UserMessage`. pi: `keybindings.md:112` `tui.altScreen.previousPrompt`. Low value per
the original audit, but ~15 lines.

### P8 — #23 / #42 session list has no search/sort

`app/src/main/kotlin/app/pi/ui/screens/SessionsScreen.kt`, above the `LazyColumn` (line ~99):

```kotlin
            var query by rememberSaveable { mutableStateOf("") }
            var byName by rememberSaveable { mutableStateOf(false) }
            val visible = remember(sessions, query, byName) {
                sessions
                    .filter { query.isBlank() || it.displayName.contains(query, true) || it.cwd.contains(query, true) }
                    .let { list -> if (byName) list.sortedBy { it.displayName.lowercase() } else list }
            }
```
then a search field in the `TopAppBar` `actions` (line 83) and a sort toggle
(`IconButton(onClick = { byName = !byName })`), and change `items(sessions, …)` (line 105) to
`items(visible, …)`. pi: `docs/sessions.md:43-46` (search by typing, Ctrl+S sort, Ctrl+N
named-only filter); the named-only part is one more filter clause (`summary.name != null`).

### P9 — #24 / #41 no way to delete a session

Needs one store method and one UI flow; the store lives in an unowned package, the screen does not.

`app/src/main/kotlin/app/pi/session/PiSessionStore.kt` (after `list`, line 70):
```kotlin
    /** Remove one session file. pi's TUI always confirms first (`docs/sessions.md:48`). */
    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { file.delete() }.getOrDefault(false)
    }
```
`PiSessionViewModel` (new method, beside `switchSession`):
```kotlin
    fun deleteSession(summary: PiSessionStore.Summary) {
        viewModelScope.launch {
            if (sessionStore.delete(summary.file)) {
                pushNotice("已删除：${summary.displayName}", Notice.Tone.Info)
                refreshSessions()
            } else {
                pushNotice("删除失败：${summary.file.name}", Notice.Tone.Warning)
            }
        }
    }
```
`SessionsScreen`: `combinedClickable(onLongClick = { confirming = summary })` on `SessionRow`, plus
an `AlertDialog` with 取消/删除 (pi requires the confirm step — `docs/sessions.md:48`).
Caveat: deleting the **active** session must be refused or must switch first, otherwise pi keeps
appending to a deleted inode; that check belongs with the owner.

### P10 — #26 `/scoped-models` is terminal-only

`app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt:116-120`: change the action from
`PiCommandAction.TerminalOnly` to a new `PiCommandAction.EditScopedModels`, add the enum value
(`:30-60`), and dispatch it in `ChatScreen.pick()` (around line 190) to open the editor for the
`enabledModels` row instead of refusing:

```kotlin
            PiCommandAction.EditScopedModels -> {
                session.requestNav(NavRequest.Settings)   // highlight "enabledModels"
                overflow = false
            }
```
The cheapest honest version is exactly this — route the command to the settings row that already
exists (`PiSettingsRegistry.kt:355`). A real multi-select picker is new UI and should not be
invented in a patch.

### P11 — #46 no per-cwd grouping in the session list

`SessionsScreen.kt:105`: replace the flat `items(sessions, …)` with grouped output.

```kotlin
            val groups = visible.groupBy { it.cwd }.toList()
                .sortedByDescending { (_, rows) -> rows.maxOf { it.lastActivityAt } }
...
            groups.forEach { (cwd, rows) ->
                item(key = "hdr:$cwd") { PiSectionHeader(shortenPath(cwd)) }
                items(rows, key = { it.file.absolutePath }) { summary -> SessionRow(...) }
            }
```
pi groups sessions by cwd on disk (`docs/sessions.md:7`; `session-manager.ts:479`), so this mirrors
the store rather than inventing a grouping.

### P12 — #14 / #43 no `pi -c` (resume the most recent session on launch)

A behaviour change, so behind a setting rather than a silent new default.

1. `PiSettingsRegistry.kt` (sessions group, near `:718`): add a `PiSetting` with
   `key = "app.sessions.resumeLast"`, `kind = PiRowKind.Switch`, `defaultValue = bool(false)`,
   `effective = EffectiveKind.RestartApp`, described as "启动时续接最近一次会话（对应 `pi -c`）".
2. `PiSessionViewModel.attach()` (`:363-410`): after `replayHistory(engine)` and `refreshState()`,
   add
   ```kotlin
   if (settingsStore.readBoolean("app.sessions.resumeLast") == true) {
       sessions.value.firstOrNull()?.let { switchSession(it) }
   }
   ```
   guarded on "the user has not already chosen a session on this launch".
pi proof: `cli/args.ts:100` `--continue`/`-c`; `docs/sessions.md:39`.

### Rows that are **not** directly patchable, and why

| Rows | Why not |
|---|---|
| #10, #52 (`pi config` per-resource) | Needs a settings model for `packages[]` object filters (extensions/skills/prompts/themes patterns) plus a UI; the current `autoload` boolean is a different shape. `package-manager-cli.ts:278-289` is a TUI, not a protocol. |
| #20 (transcript search) | New state (query, matches, current match, scroll anchoring) and a UI surface; nothing in `ChatScreen`'s current shape carries it. pi's panel is `tui.altScreen.search` (`keybindings.md:114`). |
| #22 (tree filters) | pi offers five modes (`docs/sessions.md:100`) that each consult different node metadata; picking a subset is a product decision, and implementing all five blind (no device) is how a filter silently hides branches. |
| #25 (external editor) | `externalEditor` is a **shell command pi itself runs** (`keybindings.md` `app.editor.external`), not an Android intent. Making it work on a phone means choosing a target app / `ACTION_EDIT` contract and reconciling it with the terminal tab, which already honours pi's setting. |
| #5 (`new_session parentSession`) | There is no GUI concept of a parent session to pass; the app's "new session" is deliberately rootless and its branch affordance is fork. Needs a product decision, not a patch. |
| #56, #58, #67 (the 20 inert Action rows) | `PiRoot.kt:130-138` needs `onRunAction` (one line), but the *actions* must exist: update check needs a version source, diagnostics a collector, rollback a target. Wiring the dispatcher without implementations trades one lie for twenty. |
| #57 (duplicate device switches) | Two acceptable fixes (delete the registry rows, or make them a view over `DeviceCapabilityStore`); choosing between them is a decision about whether `app.device.*` should exist at all. |
| #63 (`@` mentions) | Needs a workspace file picker and pi's `stripAtPrefix` semantics (`utils/paths.ts:17,80`); new UI, not a patch. |
| #9, #51, #54, #13, #47, #49, #34–#37 | Already **in flight** — another agent's uncommitted `packages/**` and `ui/theme/**` code implements them; the remaining work is mounting/wiring, which is that agent's file. |
| #11, #12, #64, #65, #66 | Also **in flight**: `rpc/PiLaunchOptions.kt` (new, untracked) and the `PiEngineHost.boot(launch = …)` change implement `PI_OFFLINE`, `PI_CACHE_RETENTION`, `--system-prompt`/`--append-system-prompt`; what is left is the settings rows and passing the options. |

## 9. Status board

Every row in §2 carries a status in the extra column of its table, using this vocabulary:

| Status | Meaning |
|---|---|
| `fixed` | Verified working at the current tree; evidence in the row. |
| `patch-ready` | A complete patch is in §8 (the P-id is in the row). |
| `blocked` | Needs a design decision, a cross-file refactor, or a device pass — reason in §8's last table. |
| `in-flight` | Another agent's uncommitted work implements it; only wiring remains. |
| `limit` | pi offers no RPC path; the terminal tab is the route. |
| `closed` | Audited and requires no action (premise disproved or deliberate adaptation). |

Summary: **fixed 1 · patch-ready 23 · blocked 11 · in-flight 15 · limit 14 · closed 5** (69 rows).
Only row #55 is `fixed` today, and only partially: the four `app.terminal.*` keys are wired
(`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalSurface.kt`, `TerminalKeyBar.kt`,
`terminal/TerminalController.kt`), which is 4 of the 13 settings that row lists. Everything else
below is a patch for the file's owner, not a change made by this pass.

## 10. Merged ledger: `docs/rendering-review.md` (34 rows)

`docs/rendering-review.md` (949 lines, as-of `98678da`) is a second audit — the rendering/transcript
pipeline rather than the capability surface — and the parent asked for its rows to be merged here so
there is one ledger. Its findings are `F1`–`F34` (DEFECT 8 · GAP 10 · PERF 8 · DEAD CODE 6 ·
INCONSISTENCY 2) and its own ready-to-apply diffs are `RR-P1`–`RR-P10`, **renamed here** to avoid
colliding with this document's `P1`–`P12`. The diffs themselves are not duplicated; apply them from
`docs/rendering-review.md` §"Ready-to-apply patches", which stays the authority for its own text.

**Ownership** (per the parent's allocation): `ui/**` except `ui/device|terminal` → **行为修复代理**;
`ui/render/**` → **markdown 代理**; `rpc/**` → **RPC 代理**; `engine/**`, `packages/**` →
**包管理代理**. No F-row falls in an unowned file, so all 34 are patches for an owner — including
the ones touching `ui/terminal/**`, because that directory is occupied by *this* pass's terminal
fix, not abandoned (rendering-review's note at its `:32-38` is correct about the mtimes).

| id | class | Finding (one line) | App file → owner | RR patch | Status | Relates to |
|---|---|---|---|---|---|---|
| F1 | DEFECT | a message sent mid-turn never reaches the screen | `ui/PiSessionViewModel.kt` → 行为 | RR-P1 | patch-ready | #2/#3/#17 (my P4) |
| F2 | DEFECT | aborted/errored turns leave tool cards spinning forever | `rpc/Transcript.kt` → RPC | RR-P5 | patch-ready | — |
| F3 | DEFECT | a `length`-truncated or aborted answer reports nothing | `rpc/Transcript.kt` → RPC | RR-P5 | patch-ready | — |
| F4 | DEFECT | follow-the-tail scroll re-animated on every token, stealing the scroll | `ui/screens/ChatScreen.kt` → 行为 | — | patch-ready (fix described in §F4) | — |
| F5 | DEFECT | every `entry_appended` is projected twice | `ui/PiSessionViewModel.kt` → 行为 | RR-P2 | patch-ready | — |
| F6 | GAP | extension `custom` entries render nothing | `rpc/Transcript.kt` → RPC | RR-P4 | patch-ready | — |
| F7 | PERF | whole screen + app root invalidated per token; computed change index discarded | `ui/PiSessionViewModel.kt`, `ui/screens/ChatScreen.kt` → 行为 | — | blocked (needs a finer invalidation design) | #55 (`showTimestamps` et al. share the root) |
| F8 | PERF | `tool_execution_update` applied unthrottled (spec asks 200 ms) | `rpc/Transcript.kt` → RPC | RR-P3 | patch-ready | — |
| F9 | PERF | session replay and entry projection run on the main thread | `ui/PiSessionViewModel.kt` → 行为 | — | patch-ready (move to `Dispatchers.IO`) | — |
| F10 | GAP | no live token/context indicator; all three `usage` payloads dropped | `ui/**` + `rpc/**` → 行为 + RPC | — | blocked (new UI) | — |
| F11 | DEFECT | block margins and rhythm are ~2× the spec | `ui/blocks/BlockChrome.kt`, `ui/screens/ChatScreen.kt` → 行为 | RR-P6 | patch-ready | — |
| F12 | DEFECT | dark-theme `dim` text is 2.1–2.9:1, under the spec's 3:1 floor | `ui/blocks/UserMessageBlock.kt`, `BranchSummaryBlock.kt` → 行为 | RR-P7 | patch-ready | — |
| F13 | DEFECT | content tokens below 4.5:1 (tool output, diff context, thinking headline) | `ui/theme/PiPalette.kt` → 行为 | — | blocked (palette decisions) | #34–#37 (theme files) |
| F14 | GAP | the light theme's `muted`/`dim` correction does not exist | `ui/theme/PiPalette.kt` → 行为 | — | blocked (palette decisions) | #34–#37 |
| F15 | GAP | user messages render raw Markdown source | `ui/blocks/UserMessageBlock.kt` → 行为 | — | patch-ready (swap `Text` for `PiMarkdownText`) | A1 (same renderer) |
| F16 | GAP | images returned by a tool are flattened to the literal text `[image]` | `rpc/Transcript.kt` + `ui/blocks/` → RPC + 行为 | — | patch-ready | #62, A3 |
| F17 | GAP | tool output hard-capped at 400 lines, no way to see the rest; >200 KB rule absent | `ui/blocks/` → 行为 | — | patch-ready | #55 (`outputMaxLines`) |
| F18 | GAP | compaction/branch-summary cost is parsed then dropped | `rpc/Transcript.kt` + `ui/blocks/` → RPC + 行为 | — | patch-ready | A1 |
| F19 | GAP | five tap affordances unreachable — callbacks never supplied | `ui/screens/ChatScreen.kt` → 行为 | RR-P10 | patch-ready | — |
| F20 | GAP | spec §4.8 per-block actions absent | `ui/blocks/` → 行为 | — | blocked (new UI) | — |
| F21 | GAP | sending mid-turn silently steers instead of asking | `ui/screens/ChatScreen.kt` + `ui/PiSessionViewModel.kt` → 行为 | — | patch-ready | #2/#3/#17 (my P4) |
| F22 | DEAD CODE | the `system-prompt` block kind cannot be produced | `rpc/Transcript.kt` → RPC | — | patch-ready (wire or delete) | — |
| F23 | DEAD CODE | `outputTruncated` is never assigned, but is read for a label | `rpc/Transcript.kt` + `ui/blocks/ToolCallBlock.kt` → RPC + 行为 | — | patch-ready | — |
| F24 | DEAD CODE | 8 `message_update` delta kinds parse then fall into `None` | `rpc/Events.kt`, `rpc/Transcript.kt` → RPC | — | patch-ready | — |
| F25 | DEAD CODE | the `else -> NoticeBlock("暂不支持的内容块")` branch is unreachable | `ui/blocks/BlockRenderer.kt` → 行为 | — | patch-ready (delete; move the notice to `PiEvent.Unknown`) | — |
| F26 | DEAD CODE | dead `skill`/`skill_invocation` entry helpers and aliases (pi has no such entry type) | `rpc/Transcript.kt` → RPC | — | patch-ready (delete) | — |
| F27 | DEAD CODE | `turn_start` parsed with no renderer | `rpc/Transcript.kt` → RPC | — | closed (review says no fix needed) | — |
| F28 | INCONSISTENCY | the same "expand" gesture differs per block type | `ui/blocks/*` → 行为 | — | blocked (standardise across ~8 occupied files) | — |
| F29 | INCONSISTENCY | off-scale dp literals bypass `PiSpacing`/`PiShapes` | `ui/blocks/*`, `ui/theme/` → 行为 | — | blocked (mechanical but touches many occupied files) | — |
| F30 | PERF | `formatClock` builds a `SimpleDateFormat` per call, per recomposition | `ui/blocks/BlockChrome.kt` → 行为 | RR-P8 | patch-ready | — |
| F31 | PERF | `ToolCallBlock` re-scans/splits the whole output every composition | `ui/blocks/ToolCallBlock.kt` → 行为 | — | patch-ready (`remember(item.output)`) | #55 (`outputMaxLines`) |
| F32 | PERF | five markdown config objects rebuilt per composition | `ui/render/PiMarkdown.kt` → markdown | RR-P9 | patch-ready | — |
| F33 | PERF | `items(...)` has no `contentType`, so slots cannot be reused | `ui/screens/ChatScreen.kt` → 行为 | — | patch-ready | — |
| F34 | PERF | nothing bounds the transcript or the entry list | `rpc/Transcript.kt` + `ui/screens/ChatScreen.kt` → RPC + 行为 | — | patch-ready (client-side slice; pi's `get_entries` has only a forward cursor, so backward paging must not be built) | — |

Merged status counts for the 34 rendering rows: **patch-ready 26 · blocked 7 · closed 1**, plus one
row (F34) whose *limit* is pi's forward-only cursor and whose fix is client-side. Combined with §9:

| Ledger | fixed | patch-ready | blocked | in-flight | limit | closed | rows |
|---|---|---|---|---|---|---|---|
| `feature-gaps.md` rows (§2) | 1 | 23 | 11 | 15 | 14 | 5 | 69 |
| `rendering-review.md` rows (§10) | 0 | 26 | 7 | 0 | 0 | 1 | 34 |
| **Total** | **1** | **49** | **18** | **15** | **14** | **6** | **103** |

Cross-ledger duplicates worth one owner, not two: F16 ↔ row #62 (both are "an image becomes
nothing") and F17/F31 ↔ row #55's `app.tools.outputMaxLines`. Everything else in the rendering
ledger is new work that the capability audit could not see, which is the point of merging it:
**8 DEFECTs and 8 PERFs are not capability gaps and would never have appeared in a feature table.**
One of them (F4) was found by the rendering review *after* my §2 snapshot; two of my rows (#55's
terminal four) were fixed after theirs. Where the two documents disagree about a line number, the
quoted snippet wins — that is rendering-review's own rule (`:28-31`) and it is the right one.
