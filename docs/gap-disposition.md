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

**Two engine-side facts landed after §2's snapshot, and they relax several rows.** (1)
`engine/PiEngineHost.kt:285-291` now binds `paths.agentDir` into the guest (`extraBinds`, with an
explicit migration) — so app-side writes (settings.json, trust.json, `auth.json`, `models.json`,
session files) finally land in the directory pi actually reads. That removes the "two truths" half of
`known-gaps` B7 and turns the in-flight credential/model work (#13/#47/#49) from *written* into
*effective*; the theme-discovery rows (#35–#37) now also resolve pi's real agent dir. (2)
`rpc/PiLaunchOptions.kt` plus `PiEngineHost.boot(launch = …)` implement #11/#12/#64/#65/#66.

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
| 2 | `prompt` `streamingBehavior: followUp` | PARTIAL | RECORDED-NO-OWNER (I) | **I4** (same capability; the `follow_up` route is the fix) | **Protocol/engine side COMPLETE** (RPC agent): `rpc/Commands.kt:46-57` builds `streamingBehavior` and `PiEngineSession.prompt(message, images, streamingBehavior)` forwards it; what remains is only a GUI caller — `ui/PiSessionViewModel.send` (`.kt:1053-1063`) never passes one | `rpc-types.ts:22` `streamingBehavior?: "steer" \| "followUp"` | patch-ready P4 (UI) |
| 3 | `follow_up` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I4** | `ui/PiSessionViewModel.kt:1086` `fun sendFollowUp(text: String) {` — repo-wide grep matches only this line (the RPC side exists: `rpc/Commands.kt:75`, `engine/PiEngineApi` covers `clearQueue`/`abort`; nothing in `rpc/**` blocks this) | `rpc-types.ts:24`; `docs/rpc.md:102-104` | patch-ready P4 |
| 4 | `clear_queue` (returned text discarded) | PARTIAL | RECORDED-NO-OWNER (I) | **I6** | `ui/screens/ChatScreen.kt:433` `onStop = { session.stop() },` vs `ui/PiSessionViewModel.kt:1097` `fun stop(onRestored: (List<String>) -> Unit = {})` — the engine already returns the text: `PiEngineSession.stopAndDrainQueue()` + `PiEngineApi.clearQueue()` (`rpc/.../Responses.kt` `ClearQueueResult`) | `rpc-types.ts:26`; `docs/rpc.md:137-155` | patch-ready P2 |
| 5 | `new_session` `parentSession` | PARTIAL | **UNRECORDED** | — | **Engine side COMPLETE** (RPC agent): `engine/PiEngineApi.kt` exposes `newSession(parentSession: String? = null)` → `PiCommands.newSession`; the gap is only the GUI caller — `ui/PiSessionViewModel.kt:1281` calls `api.newSession()` with no argument | `rpc-types.ts:27` `parentSession?: string`; `rpc-mode.ts:438` | blocked (UI caller) |
| 6 | `get_messages` (builder has no caller) | PARTIAL | CLOSED | — | `engine/PiEngineApi.kt:60` `suspend fun getMessages(...)` — no caller. Verdict: the transcript is projected from `get_entries` + events, a superset (`feature-gaps.md` §4.5). No user-visible loss, no work. | `rpc-types.ts:71` | closed |
| 7 | `export_html` (always HTML) | PARTIAL | RECORDED-NO-OWNER (I) | **I3** | `ui/PiSessionViewModel.kt:1376` `val written = api.exportHtml(guestPath)`, while the palette advertises `.jsonl` at `ui/chat/PiSlashCommands.kt:122` | `rpc-mode.ts:600-602` `exportToHtml` unconditionally; `rpc-types.ts:60` | patch-ready P3 |
| 8 | `export_html` `outputPath` (re-rooted) | PARTIAL | CLOSED | — | `ui/PiSessionViewModel.kt:1374` `val guestPath = "${guestWorkspace()}/$name"`. Verdict: deliberate — the file must land where the app can read it back (`:1377` `File(defaultWorkspace(), name)`). Documented behaviour, not a defect. | `rpc-types.ts:60` | closed |

### 2.2 pi's CLI surface (`feature-gaps.md` §1.2)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 9 | `pi install` / `remove` / `list` / `update` | CLI-ONLY | **ASSIGNED** | **B5** + **E7**, owner *包管理代理* (§G) | `packages/PiPackagesScreen.kt:95` `fun PiPackagesScreen(` — no caller outside `packages/`; `packages/PiPackageService.kt:59` likewise | `package-manager-cli.ts:46` `export type PackageCommand = "install" \| "remove" \| "update" \| "list"`; not in the `RpcCommand` union | in-flight |
| 10 | `pi config` (per-resource enable/disable) | CLI-ONLY | **UNRECORDED** | — | `ui/settings/PiSettingsRegistry.kt:779` `key = "packages[].autoload",` — whole-package only; no per-resource switch anywhere | `package-manager-cli.ts:278-289` | blocked |
| 11 | `--offline` / `PI_OFFLINE` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:31,47` maps `offline` → `PI_OFFLINE=1` — omitted, never `"0"`, because `core/model-runtime.ts:196` treats *any* defined value as offline; applied at `engine/PiEngineHost.kt:316` (`extra = mapOf(…) + launch.environment()`) and reachable via `boot(…, launch)` (`:192`). **Still blocked:** no settings row sets `offline = true`, and the only `boot()` caller is `ui/PiSessionViewModel.kt:535` (both outside `rpc/**` + `engine/**`) | `cli/args.ts:223`; `docs/environment-variables.md:84` | engine applied; settings+UI pending |
| 12 | `--system-prompt` / `--append-system-prompt` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `PiLaunchOptions.kt:61` builds both flags, POSIX-quoted because the guest command is run by `bash -lc` (`ProotCommand.build`); applied at `PiEngineHost.kt:275`. **Still blocked:** same missing settings row + `boot()` call site as #11 | `cli/args.ts:110,112` | engine applied; settings+UI pending |
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
| 24 | session tree "delete session" + non-invasive variant | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | **Store half done:** `session/PiSessionStore.kt:81` `suspend fun delete(file: File): Boolean`, contained to `.jsonl` files inside `sessionsRoot`. Remaining: `SessionsScreen.kt:105` still renders rows with no long-press / confirm dialog | `keybindings.md:144-145` | patch-ready P9 (store half landed) |
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
| 55 | registered settings that nothing reads | MISSING-GUI | RECORDED-NO-OWNER (I) | **I7** | Originally all 13 keys occurred once each, inside `PiSettingsRegistry.kt` (`:662`, `:676`, `:690`, `:869`, `:879`, `:893`, `:908`, `:918`, `:1057`, `:1071`, `:1086`, `:1177`, `:1615`). **Now 11 of 13 have a consumer**: 4 terminal keys in `ui/terminal/TerminalSettings.kt` (this pass), and 7 in `ui/PiSessionViewModel.kt:426-446` `readPrefs()` — `fontScaleDelta`, `messageDensity`, `showTimestamps`, `thinkingCollapsedByDefault`, `expandToolsByDefault`, `hideThinkingBlock`, `keepAlive` (behaviour agent, dirty at this snapshot). Still unconsumed: `app.appearance.dynamicColor`, `app.tools.bashTimeoutSeconds`, `app.tools.outputMaxLines`. | — (app-side) | fixed (4/13) + in-flight (7/13); 3 open |
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
> (**superseded later in this pass**: `render/PiMarkdown.kt` no longer installs
> `NoOpImageTransformerImpl` — it installs `rememberPiGuestImageTransformer()`, and A3 is closed;
> see section 10.1), but it does not record #62.

### 2.10 Environment and ops (`feature-gaps.md` §1.10)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 64 | `PI_OFFLINE` / `--offline` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** — duplicate of #11: `rpc/.../PiLaunchOptions.kt:47`, applied at `engine/PiEngineHost.kt:316` | `docs/environment-variables.md:84`; `cli/args.ts:223` | engine applied; settings+UI pending |
| 65 | `PI_CACHE_RETENTION` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `PiLaunchOptions.kt:47` maps `longCacheRetention` → `PI_CACHE_RETENTION=long`, applied at `engine/PiEngineHost.kt:316`. **Still blocked:** the settings row + `boot()` call site. pi has no `Settings` key for it (`core/settings-manager.ts`), so this is env-only by design — an app-level boolean is the only route | `docs/environment-variables.md:87` | engine applied; settings+UI pending |
| 66 | engine argv (closed to overrides) | PARTIAL | RECORDED-NO-OWNER (I) | **I11** | **APPLIED at the engine boundary** (RPC agent): `engine/PiEngineHost.kt:192` `boot(…, launch: PiLaunchOptions = PiLaunchOptions())` → `:215` `bootLocked(…, launch)`; default reproduces the previous argv/env byte for byte; `restart()` replays the stored options (`:180`, `:399`) so a reload cannot silently change the engine. Residual is only "who passes non-defaults" (#11/#12/#65) | `docs/rpc.md:7-15` | engine applied |
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
| A3 markdown-embedded images | **Valid and unjustified as of this audit; closed later in the same pass.** The audit's evidence (`render/PiMarkdown.kt` installing `NoOpImageTransformerImpl`) no longer holds: the renderer now installs `rememberPiGuestImageTransformer()` and A3 is marked done in section 10.1. No feature-gaps row graded it, and the nearest row (#62) is the attachment grid, a different surface. |
| B7 project-trust prompt | **Unjustified by any row, still unreachable, but its root cause is fixed.** `packages/PiProjectTrustPrompt.kt:52` exists and its only call site is `PiPackagesScreen.kt:119`, which nothing mounts — so the trust UI is still dead code. What *did* change is the path misalignment B7 documented: `PiEngineHost.kt:285-291` now binds `paths.agentDir` into the guest, so the app's trust.json/auth.json/session writes finally land where pi reads them. The UI half remains the packages agent's. |
| B9 markdown lib version, B10 `libprootloader.so` PIE | Valid, no capability row (library/dependency constraints, by design outside the matrix). |
| B12 / E6 `ASSET_VERSION` fingerprint | Valid (`bridge/DeviceBridgeController.kt:69` is still the literal `"3"`), no capability row. |
| C1–C4 device/real-run verification | No capability row, by construction — these are "has it been run" items, not features. |
| D1–D4 design compromises | No row needed; they are explicit non-gaps. |
| E1 tool-name collisions | Justified only indirectly: row 1.7 is graded `IMPLEMENTED` for *command* collisions and its "Recorded where" cell says "tool-name collisions open". No row grades the tool half. |
| E5 lazy engine start, E8 terminal build-vs-buy | App-side improvements/decisions with no feature-gaps row. |
| G table (six `rpc/**` items, `turnIndex`, `contentIndex`, `CAMERA`, restart entry, trust store) | Fidelity/bridge items, not capability rows. **The nine `rpc/**` items are APPLIED** by the RPC agent: `ExtensionError.extensionPath`/`event` (`rpc/Events.kt:322` + parse arm), `MessageEnd.customType`/`display` (`:140-146`) routed live to `onHookMessage` (`rpc/Transcript.kt:663`), `model_select` dead branch removed (`Transcript.kt:1073`, `ENTRY_EVENT_TYPES` at `:1692`), `AssistantDelta.Unknown` now renders a deduplicated `Notice` (`Transcript.kt:900-921`), `system_prompt`/`error` branches removed (`Transcript.kt:1085-1093`), `turnIndex` settled from source (no index on the RPC wire — `rpc/Events.kt:76-119` carries the full chain), `contentIndex` verified against pi's real `anthropic-messages` adapter (text(0)→tool_use(1)→text(2) emits `text_start@0, toolcall_start@1, text_start@2`; streaming rows are now keyed per index — `Transcript.kt:560-575`). `CAMERA` has since been implemented (`bridge/DeviceCapabilityStore.kt:252-274`); the restart entry is delivered by `packages/EngineRestartCoordinator.kt` (also unwired to a caller). |
| I1–I11 | All justified — each maps onto one or more rows above (I1→27/39/40, I2→56/58/67, I3→7/44, I4→2/3/17, I5→34-37, I6→4/16, I7→18/19/55, I8→20/22/23/42, I9→14/23/24/41/43, I10→57, I11→11/12/64/65/66). |

## 7. UNVERIFIED

| Item | Why | What would settle it |
|---|---|---|
| **Does the tree compile?** | **P0 is cleared** (both blocker files fixed — see §8 P0), but there is **no green run yet**: the `:rpc` phase is currently red at `rpc/Transcript.kt` (the RPC agent is fixing its F3/`failTurn` work), so the script stops before `:app`. The last completed run ended `typecheck: FAILED in :app` with **7 errors, in 2 files, now both fixed, and none of them touched by this pass**; **zero diagnostics ever pointed at any file changed here** (`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalSurface.kt`, `TerminalKeyBar.kt`, `terminal/TerminalController.kt`, `terminal/TerminalKeys.kt`, `session/PiSessionStore.kt`). | A green `bash tools/typecheck.sh` once `:rpc` is green. A frozen-tree run is still the only verdict on the untracked `packages/**` code, which has no caller and is therefore not exercised. |
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

### P0 — RESOLVED (kept for the record): the tree did not compile

`bash tools/typecheck.sh` ended `typecheck: FAILED in :app` with 7 errors in two files, both owned and
both mid-write. **Both are now fixed** at `af2d675`: the engine moved `launch` before `onStep` (with
a comment naming the trailing-lambda trap — `engine/PiEngineHost.kt:190-199`), and the theme agent
made `parseTheme`/`ParsedTheme` consistently private plus `primitive.content.toIntOrNull()`
(`ui/theme/PiThemeFiles.kt:252-264`, `:381-385`). Kept here because the shape recurs: **adding a
parameter after a trailing-lambda parameter silently rebinds every `f { … }` call site**, and the
compiler's message names the lambda, not the parameter order.


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

**Store half already landed by this pass** — `session/PiSessionStore.kt:81`
`suspend fun delete(file: File): Boolean`, contained to `.jsonl` files inside `sessionsRoot`. What
remains is the UI half below, which lives in owned files.

From here down is the remaining UI flow.

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

### P13 — #55 residual: the three settings still read by nothing

The other ten of that row's thirteen now have consumers (four wired by this pass, six appearance/tool
keys plus a seventh wired by the behaviour agent's `readPrefs()`, `PiSessionViewModel.kt:426-446`).
These three do not:

1. `app.tools.bashTimeoutSeconds` (`PiSettingsRegistry.kt:662`) — must bound the `bash` run. The
   consumer belongs beside `PiSessionViewModel.runBash`/`BashPanel`; without it the row's description
   ("命令超时") is a lie and a hung command has no timeout the user chose.
2. `app.tools.outputMaxLines` (`:676`) — the tool card's head-line cap. This is the *same* hard-coded
   cap rendering-review records as **F17** ("hard-capped at 400 lines, no way to see the rest"), so
   fixing F17 and consuming this key are one change in `ui/blocks/BlockChrome.kt`/`ToolCallBlock.kt`.
3. `app.appearance.dynamicColor` (`:869`) — needs `MainActivity`/`PiTheme`: when true, take the
   substrate colours from `dynamicDarkColorScheme`/`dynamicLightColorScheme` while keeping pi's
   semantic tokens (success/error/warning, the thinking ramp, diff, syntax) faithful to
   `PiPalette`, exactly as `PiPalette.kt:24-30` states the rule. Not a mechanical patch: it is a
   mapping decision, so it stays `blocked` until someone makes it.

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
Row #55 is the only `fixed` row, and it is a mix: the four `app.terminal.*` keys are wired by this
pass (`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalSurface.kt`, `TerminalKeyBar.kt`,
`terminal/TerminalController.kt`), and **seven more** were wired concurrently by the behaviour agent
in `PiSessionViewModel.readPrefs():426-446` (`fontScaleDelta`, `messageDensity`, `showTimestamps`,
`thinkingCollapsedByDefault`, `expandToolsByDefault`, `hideThinkingBlock`, `keepAlive`). The last
three (`dynamicColor`, `bashTimeoutSeconds`, `outputMaxLines`) are patch **P13**; `dynamicColor`
needs a mapping decision, so it is `blocked` rather than mechanical. That is 11 of 13 live, which is
the honest number — the row must not be marked `fixed` again until none of the 13 is unread.
Everything else in the table is a patch for the file's owner, not a change made by this pass.

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

### 10.1 `ui/render/**` status as of this pass (markdown agent)

Everything below is in `ui/render/**` plus the three `ui/blocks/**` files the markdown work had to
touch, and every claim has a file:line. `known-gaps.md` **A1/A2/A3** are the three items this pass
was scoped to.

| item | what shipped | evidence (`path:line`) | state |
|---|---|---|---|
| **A1** compaction summary | collapsed = plain `Text` + `maxLines = 2`; expanded = `PiMarkdownText` | `ui/blocks/CompactionBlock.kt:91-116`, `:128` | **done** |
| **A1** branch summary | collapsed = plain `Text` + `maxLines = 2`; expanded = `PiMarkdownText` | `ui/blocks/BranchSummaryBlock.kt:72-92`, `:102` | **done** |
| **A1** hook/custom message | collapsed = plain `Text` + `maxLines = 4`; expanded (or too short to collapse) = `PiMarkdownText` | `ui/blocks/HookMessageBlock.kt:59-80`, `:86` | **done** |
| **A1** `ErrorBlock` | **left as plain text on purpose** — pi renders the extension-error line with `new Text(...)`, not markdown | pi `modes/interactive/interactive-mode.ts:2829-2830`; app `ui/blocks/ErrorBlock.kt:47-50` | **correct as-is** |
| **A1** `SystemPromptBlock` | **left as-is** — pi has no transcript component for the system prompt at all, so there is no pi rendering to match; the block already offers both a mono and a markdown reading | pi has no such component (only `interactive-mode.ts:1716-1719`, `:2068` read it) | **no pi counterpart** |
| **A1** `SkillInvocationBlock` | expanded = `PiMarkdownText` over the skill body **in `customMessageText`** (a `textColor` parameter was added to `PiMarkdownText` for it); the card surface is pi's `customMessageBg`; collapsed stays header-only, which is pi's collapsed state too (one `[skill] name (… to expand)` line, no body) | pi `components/skill-invocation-message.ts:36-45` (expanded `Markdown`; `:43` is the `customMessageText` option, `:40` the `**name**` header), `:46-53` (collapsed line), `:17` (surface), `:38`/`:49` (`customMessageLabel`); app `ui/blocks/SkillInvocationBlock.kt:19-33`, `:47-58`, `:79-107`; `ui/render/PiMarkdown.kt:68-71`, `:91-96` | **done** — one recorded deviation: pi's `**name**` markdown header (`:40`) is not repeated, because this card's header carries `/skill:name` in both states. The surface was *corrected* rather than kept: `customMessageBg` is not `cardBg` (`theme/dark.json:20,88` → `#2d2838` vs `#1e1e24`; `light.json:19,87` → `#ede7f6` vs `#ffffff`) |
| **A2** LaTeX | `PiLatex.kt` ports pi's symbol/command tables and the text-level half of `LatexParser`; `piMarkdownSource` rewrites `$…$` / `$$…$$` before the parser sees them (code fences and code spans skipped) | `ui/render/PiLatex.kt:604` (`toUnicode`), `:624` (`formulaText`), `:649` (`toDisplayUnicode`), `ui/render/PiMarkdown.kt:132-167` | **done** for inline math without an environment; **limit** for pi's vertical layout (`renderLayout`, `packages/tui/src/latex.ts:723-809`), not ported — the class note now states exactly what that costs. pi gates only two of the three layout branches on `display`: `\frac` stacking (`latex.ts:1018`) and operator limits (`:1145`), so those differ **only inside** `$$…$$` (`components/markdown.ts:509` passes `display: true`; the inline call at `:649` does not), where the app prints `a/b` / `∑ᵢ₌₁ⁿ` instead of a stack. The environment branch is **not** gated (`parseEnvironment`, `:1090-1091`; matrix nodes at `:1350-1354`; `renderLayout` runs whenever a node exists, `:1382-1385`), so `\begin{pmatrix}` and friends differ in **both** contexts: the app reports the formula unsupported and prints the source. The note also records **why** a `renderLayout` port alone would not show: the app substitutes the rendered formula into the markdown source, and the renderer turns an in-paragraph EOL into a space (`MarkdownAnnotatorConfig.eolAsNewLine` default `false`; `annotator/AnnotatedStringKtx.kt:357`), so a grid would be re-flattened — a line-preserving channel is a prerequisite |
| **A2** the `custom` trap | `custom` claims **only** `INLINE_MATH`/`BLOCK_MATH`, with no `else` branch, so the dispatcher's own "unrecognised ⇒ recurse into children" verdict is left intact. Documented at the definition | `ui/render/PiMarkdownComponents.kt:107-150` (the trap's KDoc), `:145-150` (the `custom` body) | **done** |
| **A3** markdown images | **done.** The byte transport shipped in `07c27861` (`bridge/GuestImageBytes.kt` + `bridge/PiGuestImageTransformer.kt`) and the renderer now wires it at **three** points, all needed: `rememberPiGuestImageTransformer()` fills `LocalPiImageTransformer` (the local `PiImagePlaceholder` reads) **and** is passed as `Markdown(imageTransformer = …)` for the library's own `MarkdownImage`/`MarkdownInlineImage`, which read only the library local (`.../compose/Markdown.kt:258`, `:347`); `PiImagePlaceholder` then decides on `transform`'s **result** — a non-null `ImageData` delegates to `MarkdownImage`, a `null` keeps the alt + source fallback (the library drops the node entirely on `null`, `.../elements/MarkdownImage.kt:17-29`). `http(s)` is refused by design, so such a link takes the fallback branch. **The inline surface was closed too:** a markdown image written *inside a paragraph* is rendered by the library through the `inlineImage` slot (not `image`), and the library's default draws nothing on `null` (`.../elements/MarkdownInlineImage.kt:13`), where pi prints the alt text (`packages/tui/src/components/markdown.ts:619-627`). `PiInlineImage` now claims that slot and renders the same fallback as the block component through the shared `PiImageFallback` (`ui/render/PiMarkdownComponents.kt:314-333`), so the wording lives in one place. **One remaining honest limit:** the alt text is structurally unreachable on the inline path — the component model carries the resolved link as `content` and the *enclosing* text node as `node` (`.../elements/MarkdownText.kt:384-386`), and the annotator only appends `appendInlineContent(tag, imageUrl)` (`.../annotator/AnnotatedStringKtx.kt:280-282`) — so an unresolved inline image shows the generic 图片 label + its source, not pi's alt line | `ui/render/PiMarkdown.kt:125`, `:127-129`, `:130-140`; `ui/render/PiMarkdownComponents.kt:61-76`, `:120`, `:205-333` | **done** (transport + wiring on both image slots; the inline alt-text gap is recorded above as a model limitation, not an unfinished edit) |
| **F32** / **RR-P9** | **done, by making the builders pure instead of wrapping them.** The library's five builders are `@Composable` (their defaults read `MaterialTheme`) and `remember`'s lambda is `@DisallowComposableCalls`, so the previous `remember { piMarkdownColors() }` wrapper could not compile. `PiMarkdownText` now reads the inputs (`PiTheme.palette`, `isSystemInDarkTheme()`, `MaterialTheme.typography.bodyLarge`, `PiTheme.text.mono`) once per composition and passes them to pure constructors: `DefaultMarkdownColors`/`DefaultMarkdownTypography` are public data classes, `markdownAlertColors` is not composable, and `MarkdownPadding`/`MarkdownDimens` are public interfaces whose builders' implementations are private, so the app implements them as constant top-level values. `piMarkdownComponents()` merely loses its `@Composable` — `markdownComponents(...)` was never composable — and is remembered directly. | `ui/render/PiMarkdown.kt:87-101` (the four reads + three `remember`s), `ui/render/PiMarkdownTheme.kt:136`, `:178`, `:212`, `:250-290`, `:292-324`, `ui/render/PiMarkdownComponents.kt:98-103` | **implemented** — not verifiable here (no local build; CI is the only compiler) |
| theme: `darkTheme` | now a parameter of `piMarkdownColors`/`piAlertColors` instead of a read inside them, so the value is a `remember` key rather than something each builder re-reads | `ui/render/PiMarkdown.kt:88`, `ui/render/PiMarkdownTheme.kt:136`, `:178` | **done** |
| theme: `alertTitle` | kept (0.45.0 GFM alert titles) | `ui/render/PiMarkdownTheme.kt:234` | **done** |
| theme: GFM alert accents | mapped to pi semantic tokens (`mdQuote`/`success`/`mdHeading`/`warning`/`error`) instead of the library's Material-3 defaults, so no alert colour can drift with dynamic colour | `ui/render/PiMarkdownTheme.kt:153-186` | **done** |

**A3 is finished — the transport arrived and the renderer wires it. What is left is one honest hole.**
The renderer's image path is `ImageTransformer.transform(link) -> ImageData?`
(`multiplatform-markdown-renderer` `model/ImageTransformer.kt:16-29`). The bytes now come from
`bridge/GuestImageBytes.kt` (shipped in `07c27861`: `data:` URIs decoded in place, `file:`/bare paths
mapped from the guest spelling to the host file, `http(s)` refused so nothing leaves the device)
through `bridge/PiGuestImageTransformer.kt`, and `ui/render/PiMarkdown.kt:125-136` installs that one
instance in both seams the two consumer paths read — our `LocalPiImageTransformer` and the library's
`LocalImageTransformer` (via `Markdown(imageTransformer = …)`, `.../compose/Markdown.kt:258`, `:347`).
`PiImagePlaceholder` (`ui/render/PiMarkdownComponents.kt:175-243`) asks for the result before
delegating, so an unresolvable link keeps the alt + source text instead of becoming a node the
library silently drops (`.../elements/MarkdownImage.kt:17-29`).
**The inline surface is covered as well.** The library routes an image written inside a paragraph
through the `inlineImage` component slot, not through `image` (`.../elements/MarkdownText.kt:384`),
and its default draws nothing when `transform` returns `null`
(`.../elements/MarkdownInlineImage.kt:13`) — so before this fix such an image was blank where pi's
terminal prints its alt text (`packages/tui/src/components/markdown.ts:619-627`).
`PiInlineImage` (`ui/render/PiMarkdownComponents.kt:290-299`) now claims that slot and, on a failed
lookup, renders the same text fallback the block component uses — literally the same composable,
`PiImageFallback` (`:314-333`), so the two surfaces cannot drift apart in wording.
**The one limit that remains is the model, not the work:** on the inline path the component receives
the resolved link as `content` and the *enclosing* text node as `node`
(`.../elements/MarkdownText.kt:384-386`), and the annotator puts only the URL into the inline content
(`.../annotator/AnnotatedStringKtx.kt:280-282`); the alt text never reaches this slot, so an
unresolved inline image shows the generic 图片 label plus its source instead of pi's alt line. That is
recorded rather than papered over — recovering the alt would mean taking over the annotator and
encoding alt+link into one inline tag, which is a different design decision.

**Not in scope here (other owners), reported not changed:** `F12`/`RR-P7`'s `dim` contrast is a
`PiPalette` decision (`ui/theme/**`) even though two of its three sites are block files; `F11`/`RR-P6`
rhythm lives in `ui/blocks/BlockChrome.kt`; `F15`'s user-message markdown swap is
`ui/blocks/UserMessageBlock.kt`; `F19`/`RR-P10` tap callbacks are `ui/screens/ChatScreen.kt`.

| id | class | Finding (one line) | App file → owner | RR patch | Status | Relates to |
|---|---|---|---|---|---|---|
| F1 | DEFECT | a message sent mid-turn never reaches the screen | `ui/PiSessionViewModel.kt` → 行为 | RR-P1 | patch-ready | #2/#3/#17 (my P4) |
| F2 | DEFECT | aborted/errored turns leave tool cards spinning forever | `rpc/Transcript.kt` → RPC | RR-P5 | **applied (uncommitted, rpc agent)** — `failTurn` `Transcript.kt:1182-1229` closes every still-`Pending` `ToolCall`, but **only** for `aborted`/`error`, matching `interactive-mode.ts:3294` (live) / `:3735` (replay); `length` deliberately closes nothing (`:3305-3315`). Reached from the live `message_end` (`:689`) and from the persisted assistant entry (`:1525`) | — |
| F3 | DEFECT | a `length`-truncated or aborted answer reports nothing | `rpc/Transcript.kt` → RPC | RR-P5 | **applied (uncommitted, rpc agent)** — the row is appended only when `length`, or when `aborted`/`error` came with no tool call (`Transcript.kt:1191`, pi's `hasToolCalls` gate at `assistant-message.ts:180,187`); `hasToolCalls` parsed at `Events.kt:167,430`; replay reaches the same code at `:1525` | — |
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
| F32 | PERF | five markdown config objects rebuilt per composition | `ui/render/PiMarkdown.kt` → markdown | RR-P9 | **implemented this pass** — pure constructors plus `remember(inputs)`; the `remember { builder() }` form stays illegal (the builders are `@Composable`), which is why this needed a refactor, not a wrapper. See §10.1; CI is the only build that can confirm it | — |
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

---

## 11. In-flight agent status: the `ui/**` behaviour rows (written by the DSH behaviour agent)

Snapshot taken while the work was still landing; every `path:line` was read from the working tree at
that moment. "Done" means the code is in the tree, not that a device pass saw it.

| Row / finding | State | What landed, with the source it follows |
|---|---|---|
| #7 / #44 (`/export x.jsonl` writes HTML) | **Done**, by implementing rather than refusing | `PiSessionViewModel.kt:1737` branches on the extension the way pi's TUI does (`interactive-mode.ts:6062-6066`); `PiSessionViewModel.kt:1774` writes real JSONL from `get_entries` with pi's own shape — header (`session-export.ts:22-28`), the branch walked from `leafId` (`session-manager.ts:1274-1285`), `parentId` re-chained, trailing newline. Palette text at `PiSlashCommands.kt:132-135` and the action rename `ExportSession` match. RPC still has only `export_html`; that gap is bridged app-side, not faked. |
| #4 / #16 (Stop destroys queued text) | **Done** | `ChatScreen.kt:790` merges the drained queue with the draft exactly as pi's `restoreQueuedMessagesToEditor` does (`interactive-mode.ts:4387-4406`): queued first, blank halves dropped, `\n\n` join. |
| #2 / #3 / #17 (follow-up unreachable) | **Done**, gated on streaming | `PiSessionViewModel.kt:1412` still sends `follow_up`, now echoed locally (`:1419`); the composer offers 后续 only while a turn runs (`ChatScreen.kt:1035-1055`), because pi's `alt+enter` is a *normal submit* when idle (`interactive-mode.ts:4139-4152`) and an ungated queue row would sit in pi's queue until a later turn — the same silent no-op this pass removes. |
| RR F1 (mid-turn echo) | **Done** | `PiSessionViewModel.kt:1370` calls `transcript.onUserPrompt` before `steer`; the reducer's `MessageStart` still ignores `role == "user"` (`rpc/Transcript.kt:587-593`, not this agent's file), so this is the only live echo. **If that reducer branch is fixed, this echo must be deleted or the message renders twice.** |
| RR F2 / F3 (pending tool cards; truncated/aborted answer reports nothing) | **Reducer root fix applied (uncommitted, rpc agent), conditions ruled 1:1 with pi; app-side mitigation now duplicates it** | The behaviour is in the row model, not only live: `rpc/.../Transcript.kt:1182-1229` `failTurn()` closes a still-`Pending` tool card **only** for `aborted`/`error` (pi: `interactive-mode.ts:3294` live / `:3735` replay; `length` takes the else branch at `:3305-3315`), and appends the row only when `length`, or when `aborted`/`error` carried **no** tool call (`Transcript.kt:1191`; pi's gate is `hasToolCalls` at `assistant-message.ts:180,187`, parsed onto the event at `Events.kt:167,430`). Driven by the live `message_end` (`:689`) **and** by the persisted assistant entry's `stopReason` in `onHistoryAssistant` (`:1525`), so `seedFromHistory` replay reproduces the same rows — pi does this on replay too (`interactive-mode.ts:3735-3746`). `errorMessage === "Request was aborted"` is handled at `Transcript.kt:1196` like `assistant-message.ts:190`. Tests: `rpc/src/test/.../FidelityFixesTest.kt` — live: `a length stop closes no tool card`, `an aborted turn with a tool call closes its card and prints no second line`, `an aborted turn with no tool call prints the abort line`, `pi's fixed "Request was aborted" literal is not echoed`, `an error stop with a tool call closes the card and prints no row`; replay: `history replay reports a length truncation exactly like the live stream`, `… closes the tool cards an aborted turn left pending`, `… leaves a length-truncated tool card pending`, `a re-seed of a failed turn keeps the key stable`; parse: `EventsTest` `message_end reports whether the message carried a tool call`. **Consequence for the app layer:** `PiSessionViewModel.kt:1965` `stopReasonRow()` + `liveTurnIssue` now add a second, differently-worded row for the same live failure (the reducer already appends one whenever pi would), and `syncTranscript`'s `interrupted` projection (`:681-700`) is now only reachable for paths pi does not model (e.g. a killed engine) — both are the parent's patch list, not applied here. |
| RR F4 (scroll steals the tail) | **Done** | `ChatScreen.kt` follow-state + `derivedStateOf` at bottom, non-animated `scrollToItem` while streaming, and the §4.5 "回到最新" button (`:553`); search jumps and prev/next jumps unlock the follow. |
| RR F5 (double `entry_appended` projection) | **Done** | `PiSessionViewModel.kt` `PiEvent.EntryAppended -> Unit`: the engine's own `onEvent` already projects and bumps revision. Engine half belongs to `5d01ecd2`. |
| RR F7 (whole screen invalidated per token) | **Partial** | `PiSessionViewModel.syncTranscript` skips the publish when no element changed identity, so `None`-change events no longer allocate a copy or a `UiState`. The full fix needs `PiEngineSession` to publish the reducer's `TranscriptChange` index (`engine/**`, not this agent's file). |
| RR F32/P9 (markdown config rebuilt per frame) | **Implemented — the wrapper is gone, the builders are pure** | `remember { piMarkdownColors() }` was illegal because the builders are `@Composable` and `remember`'s calculation lambda is `@DisallowComposableCalls`. The fix is the one that entry asked for: `PiMarkdownText` reads the four inputs and calls pure constructors (`PiMarkdown.kt:87-101`), `PiMarkdownTheme.kt` builds `DefaultMarkdownColors` / `DefaultMarkdownTypography` / `alert` colours and holds `MarkdownPadding`/`MarkdownDimens` implementations as constants (`:136`, `:178`, `:212`, `:250-290`, `:292-324`), and `piMarkdownComponents()` is simply not `@Composable` any more (`PiMarkdownComponents.kt:98-103`) so it is remembered directly. No composable call sits inside any `remember`. Not verifiable here — CI is the only build. |
| #62 / RR F16 (images are placeholders) | **Done** | `ImageGridBlock.kt` decodes the inline base64 off the main thread (`produceState` + `Dispatchers.IO`) and falls back to the labelled placeholder only for bytes the platform codec refuses. |
| #1 / #59–#61 / #60 (image input) | **Done** | `ChatScreen.kt:194` (picker → base64 `PiImage`), attachment chips, `send(text, images)` on the existing pipe; an image with no caption is still a message. |
| #18 / #19 (thinking toggle never reaches the renderer) | **Done** | `UiPrefs.hideThinkingBlock` + `thinkingCollapsedByDefault`, passed at `ChatScreen.kt:544-546`; `hideThinkingBlock` is pi's own key (`settings-manager.ts:119`). |
| #21 (no previous/next prompt) | **Done** | `ChatScreen.kt:427` jumps over the same `visibleItems` list the list renders, so a hidden date separator cannot shift the target. |
| #23 / #42 / #46 (session search/sort/named/grouping) | **Done** | `SessionsScreen.kt:104` filters by name/cwd/file name, `:144` named-only, sort toggle, `:165` grouped by cwd — pi's own picker controls (`docs/sessions.md:43-46`). |
| #24 / #41 (delete a session) | **Done** | `PiSessionViewModel.kt:1636` unlinks the file off the main thread; the screen's confirm dialog refuses the **active** session so the engine is never left writing a removed inode. `PiSessionStore` stays read-only. |
| #14 / #43 (`pi -c`) | **Done behind a switch** | `app.sessions.resumeLast` (`PiSettingsRegistry.kt`) + `PiSessionViewModel.kt:642`, one attempt per process, `switch_session` after attach. |
| #26 / P10 (`/scoped-models`) | **Not done** | Still `TerminalOnly` and honest about it; making it reachable needs a settings-highlight nav request through `PiRoot`/`PiSettingsStack`, which is more surface than a patch. |
| I8 transcript search | **Done** | `ChatScreen.kt:839` + `:816` (per-kind text extractor); row-level highlight with pi's `searchMatchBg`/`searchMatchText`, "n/m", prev/next/close, and the UI states its own limit (block-level, not per-character). |
| I1 分支 button | **Done** | `SessionTreeScreen.kt:352` now says 分叉新会话 and the header states the truth: pi's `/tree` moves the leaf without a file (`docs/sessions.md:71`), RPC has no such command (`rpc-types.ts:20-74`), so this button forks. |
| I8 tree filters | **Done** | `SessionTreeScreen.kt:256` reproduces `TreeSelectorComponent`'s five modes (`components/tree-selector.ts:340-395`), including the no-text-assistant rule now that `PiMessage.Assistant.stopReason` is on the model. |
| I5 the 13 no-consumer settings | **9 resolved here, 4 by the concurrent terminal agent** | Wired: `app.appearance.fontScaleDelta`, `messageDensity`, `showTimestamps`, `thinkingCollapsedByDefault`, `app.tools.expandByDefault`, `app.runtime.keepAlive` (gates the foreground service in `boot()`), plus `hideThinkingBlock`. Removed: `app.appearance.dynamicColor` (cannot coexist with "every colour comes from `PiTheme.palette`"), `app.tools.bashTimeoutSeconds` (no field on the `bash` command, `rpc-types.ts:55`; timeout is a per-call model argument, `core/tools/bash.ts:234`), `app.tools.outputMaxLines` (pi constant, `core/tools/truncate.ts:11`; same change as RR F17). The four `app.terminal.*` rows are read by `ui/terminal/TerminalSettings.kt`, which another agent owns. `app.runtime.wakeLock` is still a dead Value row and is **not** one of the 13. |
| I4 theme files → palette | **Done** | `PiThemeFiles.kt:96` discovery (agent dir + project + `themes` setting, `resource-loader.ts:872-902`), `:190` resolution incl. the `a/b` pair (`theme.ts:597-608`), `:264` colour grammar (`theme.ts:228-264`) with the optional-token fallbacks and the exporter's derived surfaces (`export-html/index.ts:81-105`). `MainActivity.kt` paints `PiTheme(palette = theme.palette)`; the picker lists discovered names with scope and states every caveat it cannot honour (`""` terminal-default tokens, 256-colour indices, missing tokens) — `PiSettingsEditors.kt` theme sheet. |
| §2.10 `app.device.*` dead switches | **Done** | The seven rows and the `G_DEVICE` group entry are gone from `PiSettingsRegistry.kt`; `SettingsHome.kt:78-83` now records that `DeviceCapabilityStore` is the one authority. |
| `app.trust.extensions` | **Done** | Removed from `PiSettingsRegistry.kt`: pi has no extension allow-list; trust is per project (`settings-manager.ts:509-525`). |

Still open from this agent's queue, in priority order: P10/`/scoped-models`; RR F7's engine half; RR
F6 (custom entries), F8 (200 ms tool throttle), F10–F34 — all of which live in `rpc/**` or in files
this agent does not own.

RR F2/F3's reducer half is no longer on that list: the RPC agent's fix is in the working tree
(uncommitted) at `rpc/.../Transcript.kt:1182-1229` + `:1525` (with the `hasToolCalls` parse at
`rpc/.../Events.kt:167,430`), and both of its conditions were re-ruled 1:1 against
`interactive-mode.ts:3294`/`:3735` and `assistant-message.ts:180-199`; what remains is the app-side
duplicate it supersedes (see the RR F2/F3 row above).
