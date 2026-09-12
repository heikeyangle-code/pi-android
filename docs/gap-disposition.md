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
| 1 | `prompt` `images` | PARTIAL | RECORDED-NO-OWNER (E) | **E3** | `ui/PiSessionViewModel.kt:1049` `fun send(text: String, images: List<PiImage> = emptyList())`; the sole caller `ui/screens/ChatScreen.kt:415` `session.send(route.text)` passes no images | `rpc-types.ts:22` `images?: ImageContent[]` | implemented — `ChatScreen.imagePicker` + `PiSessionViewModel.send(text, images)` |
| 2 | `prompt` `streamingBehavior: followUp` | PARTIAL | RECORDED-NO-OWNER (I) | **I4** (same capability; the `follow_up` route is the fix) | **Protocol/engine side COMPLETE** (RPC agent): `rpc/Commands.kt:46-57` builds `streamingBehavior` and `PiEngineSession.prompt(message, images, streamingBehavior)` forwards it; what remains is only a GUI caller — `ui/PiSessionViewModel.send` (`.kt:1053-1063`) never passes one | `rpc-types.ts:22` `streamingBehavior?: "steer" \| "followUp"` | implemented — delivery via `PiSessionViewModel.sendFollowUp` (`follow_up`) |
| 3 | `follow_up` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I4** | `ui/PiSessionViewModel.kt:1086` `fun sendFollowUp(text: String) {` — repo-wide grep matches only this line (the RPC side exists: `rpc/Commands.kt:75`, `engine/PiEngineApi` covers `clearQueue`/`abort`; nothing in `rpc/**` blocks this) | `rpc-types.ts:24`; `docs/rpc.md:102-104` | implemented — `ChatScreen` overflow → `PiSessionViewModel.sendFollowUp` |
| 4 | `clear_queue` (returned text discarded) | PARTIAL | RECORDED-NO-OWNER (I) | **I6** | `ui/screens/ChatScreen.kt:433` `onStop = { session.stop() },` vs `ui/PiSessionViewModel.kt:1097` `fun stop(onRestored: (List<String>) -> Unit = {})` — the engine already returns the text: `PiEngineSession.stopAndDrainQueue()` + `PiEngineApi.clearQueue()` (`rpc/.../Responses.kt` `ClearQueueResult`) | `rpc-types.ts:26`; `docs/rpc.md:137-155` | implemented — `ChatScreen` `session.stop { restored -> mergeRestoredQueue(...) }` |
| 5 | `new_session` `parentSession` | PARTIAL | **UNRECORDED** | — | **Engine side COMPLETE** (RPC agent): `engine/PiEngineApi.kt` exposes `newSession(parentSession: String? = null)` → `PiCommands.newSession`; the gap is only the GUI caller — `ui/PiSessionViewModel.kt:1281` calls `api.newSession()` with no argument | `rpc-types.ts:27` `parentSession?: string`; `rpc-mode.ts:438` | blocked (UI caller) |
| 6 | `get_messages` (builder has no caller) | PARTIAL | CLOSED | — | `engine/PiEngineApi.kt:60` `suspend fun getMessages(...)` — no caller. Verdict: the transcript is projected from `get_entries` + events, a superset (`feature-gaps.md` §4.5). No user-visible loss, no work. | `rpc-types.ts:71` | closed |
| 7 | `export_html` (always HTML) | PARTIAL | RECORDED-NO-OWNER (I) | **I3** | `ui/PiSessionViewModel.kt:1376` `val written = api.exportHtml(guestPath)`, while the palette advertises `.jsonl` at `ui/chat/PiSlashCommands.kt:122` | `rpc-mode.ts:600-602` `exportToHtml` unconditionally; `rpc-types.ts:60` | implemented — `PiSessionViewModel.exportSession` branches to `exportJsonl` |
| 8 | `export_html` `outputPath` (re-rooted) | PARTIAL | CLOSED | — | `ui/PiSessionViewModel.kt:1374` `val guestPath = "${guestWorkspace()}/$name"`. Verdict: deliberate — the file must land where the app can read it back (`:1377` `File(defaultWorkspace(), name)`). Documented behaviour, not a defect. | `rpc-types.ts:60` | closed |

### 2.2 pi's CLI surface (`feature-gaps.md` §1.2)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 9 | `pi install` / `remove` / `list` / `update` | CLI-ONLY | **ASSIGNED** | **B5** + **E7**, owner *包管理代理* (§G) | `packages/PiPackagesScreen.kt:95` `fun PiPackagesScreen(` — no caller outside `packages/`; `packages/PiPackageService.kt:59` likewise | `package-manager-cli.ts:46` `export type PackageCommand = "install" \| "remove" \| "update" \| "list"`; not in the `RpcCommand` union | in-flight |
| 10 | `pi config` (per-resource enable/disable) | CLI-ONLY | **UNRECORDED** | — | `ui/settings/PiSettingsRegistry.kt:809` `key = "packages[].autoload",` — whole-package only; no per-resource switch anywhere | `package-manager-cli.ts:278-289` | blocked |
| 11 | `--offline` / `PI_OFFLINE` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `rpc/src/main/kotlin/app/pi/rpc/PiLaunchOptions.kt:31,47` maps `offline` → `PI_OFFLINE=1` — omitted, never `"0"`, because `core/model-runtime.ts:196` treats *any* defined value as offline; applied at `engine/PiEngineHost.kt:316` (`extra = mapOf(…) + launch.environment()`) and reachable via `boot(…, launch)` (`:192`). **Settings + UI are wired too (re-read in the tree):** `PiSettingsRegistry` registers `app.runtime.offline` (`PiRowKind.Switch`), and `PiSessionViewModel.launchOptions()` reads it into `PiLaunchOptions.offline`; `boot()` passes `launch = launchOptions()` and `restartEngine()` builds a fresh one. Nothing here is blocked any more. | `cli/args.ts:223`; `docs/environment-variables.md:84` | **implemented** |
| 12 | `--system-prompt` / `--append-system-prompt` | CLI-ONLY | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `PiLaunchOptions.kt:61` builds both flags, POSIX-quoted because the guest command is run by `bash -lc` (`ProotCommand.build`); applied at `PiEngineHost.kt:275`. **Settings + UI are wired too:** `app.runtime.systemPrompt` (Text row) → `PiSessionViewModel.launchOptions()` → `PiLaunchOptions.systemPrompt`; blank means "use pi's own prompt", so no empty flag is ever passed. Same `boot()` call site as #11. | `cli/args.ts:110,112` | **implemented** |
| 13 | `--api-key` | CLI-ONLY | **ASSIGNED** | **E9** + in-flight `packages/PiConfigFiles.kt` / `PiCredentialService.kt` | `ui/chat/PiSlashCommands.kt:165` `/login` is `TerminalOnly`; **stale citation fixed — the writers are now reached**: `packages/PiConfigFiles.kt:308` `fun setApiKey(providerId: String, key: String): String?` is called from `PiCredentialService.kt`, which `ui/settings/PiCredentialScreen.kt:125` constructs, and the row that opens that screen is `PiSettingsRegistry.kt:367` via `PiSettingsStack.kt:146`. Same residual as #49: uncommitted, no device pass | `cli/args.ts:108`; `docs/providers.md:62-107` | applied (uncommitted) |
| 14 | `--continue`/`-c`, `--resume`, `--session`, `--fork` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** (`-c` only; resume/switch/fork exist as actions) | `ui/PiSessionViewModel.kt:363` `private fun attach(engine: PiEngineSession) {` — no `switch_session`; argv has no `--continue` (`PiEngineHost.kt:232`) | `cli/args.ts:100`; `docs/sessions.md:39` | implemented — `PiSettingsRegistry` `app.sessions.resumeLast` + `PiSessionViewModel` resume gate |
| 15 | per-run `--no-*` suppression (`--no-session`, `--no-extensions`, …) | CLI-ONLY | CLOSED | — | Persistent equivalents exist as the `extensions`/`skills`/`prompts`/`themes` rows; the app owns one long-lived engine, so there is no "per run" to suppress. Nothing a user can want is missing. | `cli/args.ts:169,192` | closed |

### 2.3 Interactive TUI (`feature-gaps.md` §1.3)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 16 | queue editing: restore queued text on Esc | MISSING-GUI | RECORDED-NO-OWNER (I) | **I6** | `ui/screens/ChatScreen.kt:433` `onStop = { session.stop() },` | `docs/rpc.md:137-155`; `keybindings.md:166` `app.message.dequeue` | implemented — `ChatScreen.mergeRestoredQueue` (same site as #4) |
| 17 | queue a follow-up (`alt+enter`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I4** | `ui/PiSessionViewModel.kt:1086` — only occurrence | `keybindings.md:165` `app.message.followUp`; `docs/rpc.md:102` | implemented — same site as #3 |
| 18 | expand/collapse thinking (`ctrl+t`) | PARTIAL | RECORDED-NO-OWNER (I) | **I7** | `ui/screens/ChatScreen.kt:348` `thinkingDefaultExpanded = false,` — hard-coded | `keybindings.md` `app.thinking.toggle` | implemented — `ChatScreen` `thinkingDefaultExpanded = !prefs.thinkingCollapsedByDefault` |
| 19 | `hideThinkingBlock` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I7** | `ui/screens/ChatScreen.kt:345-349` `BlockRenderer(` passes neither `hideThinking` nor a collapse-all; the renderer supports it at `ui/blocks/BlockRenderer.kt:39` `hideThinking: Boolean = false,` | `core/settings-manager.ts:119` `hideThinkingBlock?: boolean` | implemented — `ChatScreen` `hideThinking = prefs.hideThinkingBlock` |
| 20 | transcript search (`ctrl+shift+f`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | No search UI in `app/src/main/kotlin/app/pi/ui`: `grep -r "rememberSearch\|SearchBar"` → 0 hits; only `settings/SettingsSearchScreen.kt` exists, which searches settings | `keybindings.md:114` `tui.altScreen.search` | implemented — `ChatScreen.searchQuery` + `SearchBar` + `searchTextOf` |
| 21 | jump to previous/next message (`ctrl+shift+up/down`) | MISSING-GUI | **UNRECORDED** | — | `ui/screens/ChatScreen.kt:167` `listState.animateScrollToItem(last)` is the only scroll control (auto-pin); no user-facing jump exists | `keybindings.md:112` `tui.altScreen.previousPrompt` | implemented — `ChatScreen` overflow 跳到上一条/下一条提问 |
| 22 | tree filters (`treeFilterMode`, `ctrl+t/u/l/a/o`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | `ui/chat/SessionTreeScreen.kt:107` `BranchTab(state = state, onFork = onFork, …)` — no filter control; `treeFilterMode` occurs only at `PiSettingsRegistry.kt:1261` | `docs/sessions.md:100` | implemented — `SessionTreeScreen.TreeFilter` (pi's five modes, cycled) |
| 23 | session picker: search / sort / named filter / rename / delete | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** (search/sort/named) + **I9** (delete) | `ui/screens/SessionsScreen.kt:105` `items(sessions, key = { it.file.absolutePath })` — a plain list | `docs/sessions.md:43-48`; `keybindings.md:141-145` | implemented — `SessionsScreen` `query` / `byName` / `namedOnly` |
| 24 | session tree "delete session" + non-invasive variant | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | **Store half done:** `session/PiSessionStore.kt:81` `suspend fun delete(file: File): Boolean`, contained to `.jsonl` files inside `sessionsRoot`. Remaining: `SessionsScreen.kt:105` still renders rows with no long-press / confirm dialog | `keybindings.md:144-145` | implemented — `SessionsScreen.combinedClickable(onLongClick=…)` + `PiSessionViewModel.deleteSession` |
| 25 | external editor (`ctrl+g`) | PARTIAL | **UNRECORDED** | — | `ui/settings/PiSettingsRegistry.kt:1288` `key = "externalEditor",` — the sole occurrence outside a doc comment; no launch action exists | `keybindings.md` `app.editor.external` | blocked |
| 26 | scoped models selector (`/scoped-models`) | **IMPLEMENTED** (applied, uncommitted — not on CI) | — | — | **Applied:** the command is now `PiCommandAction.OpenModelScope` (`ui/chat/PiSlashCommands.kt:59`, set at `:146`), which both entry points dispatch to `NavRequest.SettingsFocus("enabledModels")` (`ui/screens/ChatScreen.kt:346-349`; typed text routes there too via `ui/chat/SlashPalette.kt:232`), and the settings stack opens that group with the row highlighted (`ui/settings/PiSettingsStack.kt:79-84`, `ui/PiRoot.kt:203-204`). pi's target is `showModelsSelector()` (`interactive-mode.ts:2975-2978`, method `:5024`), which toggles `settings.enabledModels` (`settings-manager.ts:1316-1326`) — the app's row for that key is `PiSettingsRegistry.kt:355`. What is *not* a picker: pi's selector is a TUI overlay that also reorders/enables in one screen; the app maps it to the settings row (the same `enabledModels` value), not to a re-implementation of the overlay. | `keybindings.md` §Scoped Models Selector; `interactive-mode.ts:2975-2978` | implemented — `PiCommandAction.OpenModelScope` → `NavRequest.SettingsFocus(enabledModels)` |
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
| 34 | a user theme JSON changes the app's own colours | MISSING-GUI | **ASSIGNED** | **I5** + in-flight `ui/theme/PiThemeFiles.kt` | `ui/theme/PiPalette.kt:114` `val Dark = PiPalette(` and `:177` `val Light = PiPalette(` remain the only two palettes the UI can use; `MainActivity.kt:35-40` still maps only `light`/`dark`/`a/b` and `else -> dark`. `PiSessionViewModel.theme` is now a `StateFlow<PiResolvedTheme>` fed by `PiThemeLoader.load`/`discover` | `docs/themes.md:14-18`; `resource-loader.ts:875` | implemented — `PiThemeLoader.discover`/`load` + `PiSessionViewModel.refreshTheme` |
| 35 | theme discovery `<agentDir>/themes/*.json` | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.AgentDir`) | `PiSessionViewModel.refreshTheme` calls `PiThemeLoader.discover(host.paths().agentDir, defaultWorkspace(), configured)`, so the agent dir and project themes now merge with the `themes` row | `resource-loader.ts:815` | implemented — `PiThemeLoader.discover`/`load` + `PiSessionViewModel.refreshTheme` |
| 36 | theme discovery `.pi/themes/*.json` | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.Project`) | same as #35 — `PiThemeLoader.discover` covers the project `.pi/themes` root (`PiThemeScope.Project`) | `resource-loader.ts:821` | implemented — `PiThemeLoader.discover`/`load` + `PiSessionViewModel.refreshTheme` |
| 37 | themes from packages (`themes/`, `pi.themes`) | PARTIAL | **ASSIGNED** | **I5** + in-flight `PiThemeFiles.kt` (`PiThemeScope.Configured`) | same as #35 — `PiThemeLoader.discover` covers the project `.pi/themes` root (`PiThemeScope.Project`) | `docs/themes.md:17` | implemented — `PiThemeLoader.discover`/`load` + `PiSessionViewModel.refreshTheme` |
| 38 | `--theme` / `--no-themes` | CLI-ONLY | CLOSED | — | Persistent `themes` row exists; per-run flag has no per-run engine to attach to. | `cli/args.ts:177,192` | closed |

### 2.5 Sessions (`feature-gaps.md` §1.5)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 39 | switch branch **in place** | MISSING-TERMINAL-ONLY | **LIMIT** | **I1** | `ui/chat/SessionTreeScreen.kt:226` — fork only | `rpc-types.ts:20-74`; `docs/sessions.md:71` | limit |
| 40 | branch summarization when leaving a branch | MISSING-TERMINAL-ONLY | **LIMIT** | **I1** / §2.1 | requires #39; unreachable | `interactive-mode.ts:5238`; `docs/sessions.md:131` | limit |
| 41 | **Delete** a session | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | `session/PiSessionStore.kt:49` — `list` only | `docs/sessions.md:48`; `keybindings.md:144` | implemented — same site as #24 |
| 42 | search / sort / named-only filter in the picker | MISSING-GUI | RECORDED-NO-OWNER (I) | **I8** | `ui/screens/SessionsScreen.kt:105` | `docs/sessions.md:43-46` | implemented — same site as #23 |
| 43 | resume most recent session on launch (`pi -c`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I9** | `ui/PiSessionViewModel.kt:363` `attach()` issues no `switch_session`; `PiEngineHost.kt:232` argv | `cli/args.ts:100`; `docs/sessions.md:39` | implemented — same site as #14 |
| 44 | export to JSONL | MISSING-GUI | RECORDED-NO-OWNER (I) | **I3** | `ui/PiSessionViewModel.kt:1376` — always `export_html` | `interactive-mode.ts:6064-6065` vs `rpc-mode.ts:600-602` | implemented — same site as #7 |
| 45 | import from a JSONL file | MISSING-TERMINAL-ONLY | **LIMIT** | **F2** / §2.9 | `ui/chat/PiSlashCommands.kt:127` | `interactive-mode.ts:6107`; no `RpcCommand` | limit |
| 46 | per-cwd session **grouping** in the UI | PARTIAL | **UNRECORDED** | — | `ui/screens/SessionsScreen.kt:105` builds one flat `LazyColumn`; cwd appears only as a per-row string at `:172` `append(shortenPath(summary.cwd))` | `docs/sessions.md:7` | implemented — `SessionsScreen` `.groupBy { it.cwd }` + `PiSectionHeader` |

### 2.6 Models, providers, thinking (`feature-gaps.md` §1.6)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 47 | custom provider via `models.json` (no editor) | PARTIAL | **ASSIGNED** | **E9** + in-flight `packages/PiConfigFiles.kt` / `PiCredentialService.kt` / `PiModelScanner.kt` | **The row is no longer inert**: `PiSettingsRegistry.kt:396` `key = "app.localModels.manage",` opens `PiCredentialScreen` (`ui/settings/PiSettingsStack.kt:147`), which builds the service (`PiCredentialScreen.kt:125`) and writes `models.json` (`PiCredentialService.kt:200` calls `models().upsert(provider)`, the `PiConfigFiles.kt:499` writer). **The honest residual is now in the row's own text**: pi's `/llama` load/unload/GGUF-download half only runs in the TUI (`extensions/llama/index.ts:186-189`), so the row says so instead of promising it | `docs/custom-provider.md`; `core/model-runtime.ts:174-175` | applied (uncommitted) |
| 48 | OAuth login (`/login`, `/logout`) and `auth.json` | MISSING-TERMINAL-ONLY | **LIMIT** | **F3** / §2.9 | **The row is no longer inert, and it no longer pretends**: `PiSettingsRegistry.kt:379` `key = "app.credentials.oauth",` (`Action` kind at `:383`) now navigates to 工作区 → 终端 and says `/login` has to be run there (`ui/PiRoot.kt:62-79`, `:189-201`). The LIMIT stands — pi's OAuth cannot be run over RPC (no login/logout in `rpc-types.ts:20-74`), so this is a pointer, not an implementation | no `RpcCommand` for login; `docs/providers.md:17-26` | limit |
| 49 | API-key credentials | MISSING-GUI | **ASSIGNED** | **E9** + in-flight `PiConfigFiles.kt` | **Stale citation fixed**: `PiRoot.kt:188` now passes `onRunAction`, and the API-key row (`PiSettingsRegistry.kt:367`) is one of the two opened directly by `PiSettingsStack.kt:146` `hostActions` into `PiCredentialScreen` — the writer is reached (`PiCredentialService.kt:200`). What remains open is only what §E9-接线规格 lists: the screen is uncommitted/untested on device | `docs/providers.md:62-107`; `core/auth-storage.ts:52` | applied (uncommitted) |
| 50 | `provider/auth` events visible to the client | PARTIAL | **LIMIT** (compensated) | `fidelity-review.md` §4 / `extension-compatibility.md` §5.3 | The app works around it by polling: `ui/PiSessionViewModel.kt:492` "`model_select` is emitted to extensions only" and `:895` "Polled rather than event-driven on purpose" | `core/agent-session.ts:1665-1670` — `_emitModelSelect` calls `this._extensionRunner.emit(...)` only | limit |

### 2.7 Skills, templates, packages (`feature-gaps.md` §1.7)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 51 | npm/git packages (`packages`) management UI | PARTIAL | **ASSIGNED** | **B5** + **E7**, owner *包管理代理* (§G) | Was: no caller. **Now mounted and effective** — `packages/PiPackagesHost.kt:129` is the screen's caller (settings entry row) and, since §K5, the command it runs binds the *same* agent dir as the engine (`GuestCommand.kt:198-206`), so a successful `pi install` is actually read by the running engine. **Still not git-capable:** the runtime has no git (§K2), and `SPEC_HINT` no longer advertises it (`PackageStrings.kt:41`). **Second entry point added (this pass, uncommitted):** the settings `packages` row (`PiSettingsRegistry.kt:794`) is now `readOnly = true` and jumps here (`PiSettingsStack.kt:148-153`) instead of opening a list editor — hand-editing that array bypasses `pi install`'s resolve/download step and can leave a spec that looks installed but was never fetched | `docs/packages.md:22-39`; not in the `RpcCommand` union | applied (uncommitted) |
| 52 | package resource enable/disable (`pi config`) | CLI-ONLY | **UNRECORDED** | — | `PiSettingsRegistry.kt:809` `key = "packages[].autoload",` — whole-package only | `package-manager-cli.ts:278-289` | blocked |
| 53 | extension load diagnostics | MISSING-TERMINAL-ONLY | **LIMIT** | **F1** | `grep -rn "runtime.diagnostics"` over `app/src` + `rpc/src` → **0 hits**; no event carries it | pi writes load errors only to `runtime.diagnostics`; no RPC channel (`known-gaps` F1) | limit |
| 54 | built-in vs user-installed package distinction | MISSING-GUI | **ASSIGNED** | **E7**, owner *包管理代理* (§G) | Done: `packages/PiPackageModel.kt:63-119` (`PiBuiltinExtension`, transcribed from `assets/pi-extensions/`) + `packages/PiPackagesHost.kt:455-468` (three-state presence in both agent dirs) + `packages/PiPackagesScreen.kt:215-296` (built-in block, **no remove button**; the reasoning lives in comments — the screen carries one sentence, "「内置」是 App 的标注：pi 不分内置和用户安装。"). **pi itself has no such distinction**: auto-discovery labels everything `source:"auto"`/`scope:"user"` (`package-manager.ts:2352-2362`, `:2470-2475`) and `pi list` reads only settings' `packages` (`package-manager-cli.ts:970-1002`) | — (app-side) | applied (uncommitted) |

### 2.8 Settings coverage (`feature-gaps.md` §1.8)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 55 | registered settings that nothing reads | MISSING-GUI | RECORDED-NO-OWNER (I) | **I7** | Originally all 13 keys occurred once each, inside `PiSettingsRegistry.kt` (`:662`, `:676`, `:690`, `:869`, `:879`, `:893`, `:908`, `:918`, `:1057`, `:1071`, `:1086`, `:1177`, `:1615`). **Reconciled: 10 wired, 3 deleted with pi-source reasons.** Wired: the 4 terminal keys in `ui/terminal/TerminalSettings.kt`, and 6 in `PiSessionViewModel.readPrefs()` — `fontScaleDelta`, `messageDensity`, `showTimestamps`, `thinkingCollapsedByDefault`, `expandToolsByDefault`, `keepAlive` (plus `hideThinkingBlock`, which is also row #19). Deleted as unhonourable: `app.tools.bashTimeoutSeconds` (the `bash` RPC command carries no timeout field; pi's bash tool takes it per call), `app.tools.outputMaxLines` (pi's truncation limits are compiled constants), `app.appearance.dynamicColor` — each with the reason left in `PiSettingsRegistry.kt` at the deletion site. **Re-verified key by key on the current worktree (`applied (uncommitted)`)**: only **2** of the 4 terminal keys are actually consumed (`app.terminal.fontSize` and `app.terminal.keyBar`, both in `TerminalPreferences.read`) — `app.terminal.cursorStyle` and `app.terminal.scrollbackLines` had no reader at all and `ui/terminal/TerminalSettings.kt`'s KDoc handed the decision back to this catalog, so **both rows were deleted** with the pi-source reason at the deletion site (pi has no cursor-style or scrollback setting of its own; `showHardwareCursor` is a different key, and cursor shape comes from the guest's `DECSCUSR`). True split now: **8 wired, 2 deleted this round, 3 deleted earlier**. The `hideThinkingBlock` half is wired too (`PiSessionViewModel.readPrefs` + `ChatScreen`'s `hideThinking` and its search filter). Not one of the 13, but found in the same sweep and left alone: `app.runtime.piVersion` / `nodeVersion` / `rootfsUsage` / `wakeLock` have no writer anywhere, so they display only their defaults (and the `G_RUNTIME` group summary therefore reads 「pi 未安装」). | — (app-side) | **applied (uncommitted)** — 8 wired / 2 deleted this round / 3 deleted earlier; 4 further no-writer value rows recorded as a new finding |
| 56 | all `Action`-kind rows are inert (19, not 20 — `app.device.sessionOverride` was deleted with the other `app.device.*` rows in `9a16271`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | **applied (uncommitted), no CI yet.** `PiRoot.kt:188` passes `onRunAction` and every remaining row now has a decision, none left on the "还没有接入实现" fallback: **4 implemented** (`app.credentials.apiKey` + `app.localModels.manage` → `PiCredentialScreen` via `PiSettingsStack.kt:146-147`; `app.compaction.runNow` → RPC `compact` `PiRoot.kt:209`; `app.security.emergencyStop` → `stop()`+`abortBash()` `PiRoot.kt:217-220`), **3 navigate to the original TUI** for behaviours pi implements only there (`app.credentials.oauth` / `app.sessions.import` / `app.about.changelog`, `PiRoot.kt:49-70` + `:189-201`), **12 removed** with the reason left at the deletion site (`PiSettingsRegistry.kt:731`, `:1520`, `:1555`, `:1588`, `:1596`, `:1688`). The fallback branch survives only as a regression guard and is unreachable for every registered row (`PiRoot.kt:230-233`). Per-row evidence: `known-gaps.md` **§I2** | — (app-side) | applied (uncommitted) |
| 57 | device capability switches (two authorities) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I10** | `PiSettingsRegistry.kt:1474-1549` declares `app.device.*`; `grep -rn "app\.device\."` outside the registry → **0 hits**, while enforcement reads SharedPreferences at `bridge/DeviceCapabilityStore.kt:65-92`. **The second authority is gone** (§11 row "§2.10 `app.device.*` dead switches"), but removing it did not make the one remaining screen truthful: a static pass over `ui/device/DeviceCapabilityScreen.kt` found 6 UI-vs-behaviour mismatches — wrong Shell backend label, a camera-permission button that `DeviceCapabilityStore`'s own hint names while no such button existed, screenshot claimed as available below API 30, the default-on 基础 group silent about `POST_NOTIFICATIONS`, an approvals card that never re-composed, and a swallowed `startActivity`. **Re-verified item by item on this worktree: all six are present**, in the file that now has no diff against HEAD — they were committed as `2bd97cd fix(ui): six places the device screen disagreed with the device` (no CI run, no device pass). See `known-gaps.md` §C3.1. | — (app-side) | **applied (committed 2bd97cd; no CI, no device)** |
| 58 | update checks | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | **applied (uncommitted) — the row was removed, not wired.** pi's own check is switched off by this app on purpose (`PiEngineHost.kt:305-306` sets `PI_SKIP_VERSION_CHECK`; the gate is `utils/version-check.ts:98`), and its replacement is an npm self-update (`package-manager-cli.ts:1033-1068`) while this app's engine is an APK asset re-extracted per runtime revision (`RuntimeProvisioner.kt:505-528`, `:122`) — so an in-guest update is overwritten or diverges. Nothing is left to check; the read-only 「pi 版本」 row (`PiSettingsRegistry.kt:1510`) still reports the version. pi's check is not reachable over RPC either | `docs/environment-variables.md`; `cli/args.ts` | applied (uncommitted) |

### 2.9 Attachments, images, `@` mentions (`feature-gaps.md` §1.9)

| # | Capability | Grade | Disposition | Owner / entry | App file proving the deficiency still exists | pi citation | Status |
|---|---|---|---|---|---|---|---|
| 59 | images into `prompt` | PARTIAL | RECORDED-NO-OWNER (E) | **E3** | `PiSessionViewModel.kt:1049` / `ChatScreen.kt:415` (as #1) | `rpc-types.ts:22` | implemented — same site as #1 |
| 60 | picking an image from the device | MISSING-GUI | RECORDED-NO-OWNER (E) | **E3** | `ChatScreen.kt:402-410` `Composer(` has no attachment affordance; `grep "PickVisualMedia\|GetContent\|ACTION_OPEN_DOCUMENT"` in UI → 0 hits | — | implemented — same site as #1 (`ActivityResultContracts.GetContent`) |
| 61 | pasting an image from the clipboard | MISSING-GUI | RECORDED-NO-OWNER (E) | **E3** | `ChatScreen.kt:552-554` only *writes* the clipboard (`clipboard?.setPrimaryClip(…)`); nothing reads an image in | `keybindings.md` `app.clipboard.pasteImage` | implemented — same site as #1 (clipboard URI → same send path) |
| 62 | rendering attachments in the transcript | PARTIAL | **UNRECORDED** | — | `ui/blocks/ImageGridBlock.kt:30` "The cells are drawn as labelled placeholders: this app has no image-loading dependency yet"; `:117` `text = "图片 ${index + 1}"` | `session-format.md:53-57` | implemented — `ImageGridBlock.decodeImage` (`Base64.decode` + `BitmapFactory.decodeByteArray`) |
| 63 | `@` file mentions | MISSING-GUI | RECORDED-NO-OWNER (E) | **E4** | **applied (uncommitted)** — was: `ui/chat/SlashPalette.kt:206-210` `routeComposerText` recognises only `!`/`/`. Now: `ui/chat/PiFileMentions.kt` (trigger boundaries, quoted `@` prefix, fd argv + shell quoting, `scoreEntry` and the full ordering, insertion), `ui/chat/PiMentionSource.kt` (the guest's own `fd` through `GuestCommand`, same proot recipe as the engine), `ui/chat/MentionPalette.kt` + `ui/screens/ChatScreen.kt` (debounced list, tap inserts), `ui/PiSessionViewModel.kt` (`requestMentions`/`dismissMentions`, superseded answers dropped). `routeComposerText` is **unchanged on purpose**: pi does not expand a mention either (`interactive-mode.ts:2964-2967`), so `@path` stays ordinary message text that the model resolves through the tool layer | `packages/tui/src/autocomplete.ts:7,107-121,132-161,413-429,702-784`; `utils/paths.ts:17,80` | applied (uncommitted) |

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
| 65 | `PI_CACHE_RETENTION` | MISSING-GUI | RECORDED-NO-OWNER (I) | **I11** | **Engine side APPLIED** (RPC agent): `PiLaunchOptions.kt:47` maps `longCacheRetention` → `PI_CACHE_RETENTION=long`, applied at `engine/PiEngineHost.kt:316`. **Settings + UI are wired too:** `app.runtime.cacheRetention` (Value row, 默认/长保留) → `PiSessionViewModel.launchOptions()` maps `"long"` → `PiLaunchOptions.longCacheRetention`. pi has no `Settings` key for it (`core/settings-manager.ts`), so the `app.`-prefixed row is the route, as predicted here. | `docs/environment-variables.md:87` | **implemented** |
| 66 | engine argv (closed to overrides) | PARTIAL | RECORDED-NO-OWNER (I) | **I11** | **APPLIED at the engine boundary** (RPC agent): `engine/PiEngineHost.kt:192` `boot(…, launch: PiLaunchOptions = PiLaunchOptions())` → `:215` `bootLocked(…, launch)`; default reproduces the previous argv/env byte for byte; `restart()` replays the stored options (`:180`, `:399`) so a reload cannot silently change the engine. Residual is only "who passes non-defaults" (#11/#12/#65) | `docs/rpc.md:7-15` | engine applied |
| 67 | runtime/ops diagnostics (`app.runtime.*`) | MISSING-GUI | RECORDED-NO-OWNER (I) | **I2** | **applied (uncommitted) — all six `Action` rows removed, not wired.** `checkUpdate`, `rollback`, `cleanNpmCache`, `phantomKillerGuide`, `logViewer`, `exportDiagnostics` are gone (`PiSettingsRegistry.kt:1520`/`:1555`/`:1588`/`:1596`). pi has no counterpart for any of them (no rollback/cache-clear/diagnostics subcommand), and the app has no level-filtered log sink or update channel to back them. **The old "8 `app.runtime.*` Action rows" count was already wrong** — only these six were `Action`; `piVersion`/`nodeVersion`/`rootfsUsage` are read-only `Text`, `keepAlive`/`safeMode` are `Switch`, `wakeLock` is `Value`. **New in this pass (found while re-verifying §I7, not fixed):** those four read-only rows have **no writer anywhere in the tree**, so they render only their declared defaults — 「未安装」/「未安装」/「未知」/「未知」. The `G_RUNTIME` group summary reads `app.runtime.piVersion`, so the settings home therefore shows 「pi 未安装 · 保活：…」 on a device where pi is installed and running. The fix is a runtime producer (pi/Node version, rootfs size, wake-lock state), i.e. `runtime/**` + `service/**` work, not a registry edit; the row this replaces (row #58's claim that the pi-version row "still reports the version") is wrong until then | — (app-side) | applied (uncommitted); 4 no-writer rows recorded |

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

**Status is a second axis, and it was re-tallied against the tree in §9:** implemented 29 ·
applied (uncommitted/in-flight) 16 · resolved 1 · blocked 4 · limit 14 · closed 5. The `patch-ready`
count that used to sit on that axis is now **0** (§9 carries the row-by-row proof).

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
| 10 | `pi config` per-resource enable/disable has no app path (`PiSettingsRegistry.kt:809`) | A user cannot switch off one skill/prompt/theme/extensions entry inside an installed package from the GUI — only the whole package's `autoload`; per-resource filtering means hand-editing `settings.json`. |
| 21 | No jump to previous/next message (`ChatScreen.kt:167`) | On a long transcript the only navigation is scrolling; pi's `previousPrompt` equivalent does not exist. Low impact. |
| 25 | `externalEditor` is a setting with no consumer (`PiSettingsRegistry.kt:1288`) | A user can set an external editor command and nothing ever launches it from the GUI; `Ctrl+G` behaviour exists only inside the terminal tab. |
| 26 | No scoped-models selector (`PiSlashCommands.kt:118-119`) | **Mostly closed (applied, uncommitted).** `/scoped-models` no longer answers "terminal only": it navigates to 设置 → 模型 and highlights the `enabledModels` row. What remains hand-edited is only the *shape* of the value (glob patterns) — there is no dedicated picker widget that lists available models with checkboxes beside this row. |
| 46 | No per-cwd grouping in the session list (`SessionsScreen.kt:105`) | With several projects the session list is one flat column (capped at 300 rows) showing a path string per row; there is no grouping or filter by project. |
| 52 | `pi config` again, recorded as a skills/packages row (`PiSettingsRegistry.kt:809`) | Same consequence as #10: per-resource enable/disable is unreachable from the GUI. |
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
   with `PiModelScanner`/`PiProviderPresets` beside them. That audit was right about the tree at the
   time, but **the "no caller" half is now stale too** (§I2 pass): `PiCredentialService.kt:178`
   `auth().setApiKey(...)` and `:200` `models().upsert(...)` are the callers, the service is built by
   `ui/settings/PiCredentialScreen.kt:125`, and the row that opens that screen is
   `PiSettingsStack.kt:146` — so the GUI row is no longer inert. What is left is a device pass, not
   the `onRunAction` wiring and not the file format.
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
| B12 / E6 `ASSET_VERSION` fingerprint | **Closed (`ba297c7`).** `bridge/DeviceBridgeController.assetFingerprint` hashes the whole `assets/pi-extensions` tree (`sha256:<hex>`, sorted asset paths plus bytes) and the installer stamps `.pi-android-assets` with it; `ASSET_VERSION` survives only as the fallback marker for "the asset tree could not be read at all", which is strictly the old behaviour and never a false "already current". The manual bump is gone, so the silent-failure mode B12/E6 recorded cannot recur. Cost measured here: 9 files / 129,363 bytes, re-read and hashed on every `DeviceBridgeController.start()`. No capability row. |
| C1–C4 device/real-run verification | No capability row, by construction — these are "has it been run" items, not features. C3 gained a **static** sub-item (`known-gaps.md` §C3.1): the 设备能力 screen was audited claim-by-claim against `DeviceCapabilityStore` / `DeviceShellGuard` / `/app/health` and six UI-vs-behaviour mismatches were fixed (committed `2bd97cd`; all six re-verified on this worktree). Another agent also wrote the executable checklist for this whole section: `docs/device-verification.md` (51 items, every one still `未验`). Neither is a device run — C3 itself stays open. |
| D1–D4 design compromises | No row needed; they are explicit non-gaps. |
| E1 tool-name collisions | Justified only indirectly: row 1.7 is graded `IMPLEMENTED` for *command* collisions and its "Recorded where" cell says "tool-name collisions open". No row grades the tool half. |
| E5 lazy engine start, E8 terminal build-vs-buy | App-side improvements/decisions with no feature-gaps row. |
| G table (six `rpc/**` items, `turnIndex`, `contentIndex`, `CAMERA`, restart entry, trust store) | Fidelity/bridge items, not capability rows. **The nine `rpc/**` items are APPLIED** by the RPC agent: `ExtensionError.extensionPath`/`event` (`rpc/Events.kt:322` + parse arm), `MessageEnd.customType`/`display` (`:140-146`) routed live to `onHookMessage` (`rpc/Transcript.kt:663`), `model_select` dead branch removed (`Transcript.kt:1073`, `ENTRY_EVENT_TYPES` at `:1692`), `AssistantDelta.Unknown` now renders a deduplicated `Notice` (`Transcript.kt:900-921`), `system_prompt`/`error` branches removed (`Transcript.kt:1085-1093`), `turnIndex` settled from source (no index on the RPC wire — `rpc/Events.kt:76-119` carries the full chain), `contentIndex` verified against pi's real `anthropic-messages` adapter (text(0)→tool_use(1)→text(2) emits `text_start@0, toolcall_start@1, text_start@2`; streaming rows are now keyed per index — `Transcript.kt:560-575`). `CAMERA` has since been implemented (`bridge/DeviceCapabilityStore.kt:252-274`); the restart entry is delivered by `packages/EngineRestartCoordinator.kt` (also unwired to a caller). |
| I1–I11 | All justified — each maps onto one or more rows above (I1→27/39/40, I2→56/58/67, I3→7/44, I4→2/3/17, I5→34-37, I6→4/16, I7→18/19/55, I8→20/22/23/42, I9→14/23/24/41/43, I10→57, I11→11/12/64/65/66). |

## 7. UNVERIFIED

| Item | Why | What would settle it |
|---|---|---|
| **Does the tree compile?** | **P0 is cleared** (both blocker files fixed — see §8 P0), but there is **no green run yet**: the `:rpc` phase is currently red at `rpc/Transcript.kt` (the RPC agent is fixing its F3/`failTurn` work), so the script stops before `:app`. The last completed run ended `typecheck: FAILED in :app` with **7 errors, in 2 files, now both fixed, and none of them touched by this pass**; **zero diagnostics ever pointed at any file changed here** (`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalKeyBar.kt`, `TerminalBridge.kt`, `TerminalPalette.kt`, `session/PiSessionStore.kt`). | A green `bash tools/typecheck.sh` once `:rpc` is green. A frozen-tree run is still the only verdict on the untracked `packages/**` code, which has no caller and is therefore not exercised. |
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

### P10 — #26 `/scoped-models` is terminal-only — **APPLIED (uncommitted, not on CI)**

**Status: done, with the enum named `OpenModelScope` rather than `EditScopedModels`** (the command
navigates; it does not edit in place). What landed:

- `ui/chat/PiSlashCommands.kt:59` — new `PiCommandAction.OpenModelScope`, documented with pi's
  `showModelsSelector()` (`interactive-mode.ts:2975-2978`, method `:5024`) and the fact that it
  persists `settings.enabledModels` (`settings-manager.ts:1316-1326`);
- `ui/chat/PiSlashCommands.kt:146` — the `/scoped-models` row now carries it, so the palette no
  longer labels the command 「仅终端」 (`SlashPalette.kt:102` only marks `TerminalOnly` rows);
- `ui/chat/SlashPalette.kt:232` — typed `/scoped-models` routes to `ComposerRoute.Command` instead of
  `Unreachable`, which is what lets the *typed* path reach the same dispatch as a palette tap;
- `ui/screens/ChatScreen.kt:346-349` — `PiCommandAction.OpenModelScope` clears the draft (pi does
  `editor.setText("")` at `interactive-mode.ts:2976`) and requests
  `NavRequest.SettingsFocus("enabledModels")`;
- `ui/PiSessionViewModel.kt:122` (`NavRequest.SettingsFocus`), `ui/PiRoot.kt:104-108` + `:203-204`,
  `ui/settings/PiSettingsStack.kt:79-84` — the request opens 设置 on the `enabledModels` row and
  highlights it, then is consumed once.

Original patch sketch (kept for the record):

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
| #56, #58, #67 (the Action rows) | **No longer "why not" — done as far as it can be, `applied (uncommitted)`.** The old reason was right: wiring a dispatcher without implementations trades one lie for many. So this pass gave every row a pi-side verdict instead: 4 wired to real RPC commands, 3 turned into explicit "仅终端" jumps to the original TUI, **12 deleted** because pi has no counterpart (update check / rollback / cache clear / phantom-killer guide / log viewer / diagnostics export / desktop import / config export / raw settings / reset / licenses / bulk session export). Details and per-row evidence: `known-gaps.md` §I2. |
| #57 (duplicate device switches) | Two acceptable fixes (delete the registry rows, or make them a view over `DeviceCapabilityStore`); choosing between them is a decision about whether `app.device.*` should exist at all. |
| #63 (`@` mentions) | Was: "needs a workspace file picker and pi's `stripAtPrefix` semantics; new UI, not a patch." **No longer blocked — implemented, `applied (uncommitted)`.** The picker exists (`ui/chat/MentionPalette.kt`), and the candidate *source* was decided by measurement rather than taste: pi's candidates are `fd`'s candidates (its layered ignore handling is the `ignore` crate), and the runtime already ships that binary for pi's `find` tool, so the app runs **the same binary with the same argv** through the existing `GuestCommand` channel instead of reimplementing ignore semantics in Kotlin. It is a new UI, so it is not in §8 as a patch — it is `ui/chat/**` + `ChatScreen.kt` + `PiSessionViewModel.kt`, with the two deliberate deviations (no caret state in the composer; a longer debounce) recorded in `docs/known-gaps.md` §E4. |
| #9, #51, #54, #13, #47, #49, #34–#37 | Already **in flight** — another agent's uncommitted `packages/**` and `ui/theme/**` code implements them; the remaining work is mounting/wiring, which is that agent's file. **#13/#47/#49 are now mounted** (§I2 pass): the credential/local-model rows open `PiCredentialScreen` through `PiSettingsStack.kt:146-147` and reach `PiConfigFiles.setApiKey`/`upsert`; they are `applied (uncommitted)`, still without a device pass. #9/#51/#54 and the theme rows are unchanged. |
| #11, #12, #64, #65, #66 | Also **in flight**: `rpc/PiLaunchOptions.kt` (new, untracked) and the `PiEngineHost.boot(launch = …)` change implement `PI_OFFLINE`, `PI_CACHE_RETENTION`, `--system-prompt`/`--append-system-prompt`; what is left is the settings rows and passing the options. |

## 9. Status board

Every row in §2 carries a status in the extra column of its table, using this vocabulary:

| Status | Meaning |
|---|---|
| `fixed` | Verified working at the current tree; evidence in the row. |
| `patch-ready` | A complete patch is in §8 (the P-id is in the row). |
| `blocked` | Needs a design decision, a cross-file refactor, or a device pass — reason in §8's last table. |
| `in-flight` | Another agent's uncommitted work implements it; only wiring remains. |
| `applied (uncommitted)` | The work is **in the working tree but not on CI** and not necessarily wired end to end. Used by the package-management rows (#51, #54) and by #63 (`@` mentions, raised by the DSH mention agent from `blocked`) for exactly that reason: "done" is a claim only a green build supports. |
| `limit` | pi offers no RPC path; the terminal tab is the route. |
| `closed` | Audited and requires no action (premise disproved or deliberate adaptation). |

Summary, **re-tallied after the §2 reconciliation below**: **implemented 29 · applied (uncommitted/in-flight) 16 · resolved 1 · blocked 4 · limit 14 · closed 5** (69 rows).

### §2 reconciliation — the `patch-ready: 23` rows, walked one at a time

The previous summary read `patch-ready: 23` and flagged it unverified. Every one of those rows has now
been opened in the working tree: **`patch-ready` is zero**, because **29 rows are implemented** — the
23 that carried a patch, plus #20 and #22 which were carried as `blocked`, plus #34–#37 which were
carried as `in-flight` while `PiThemeLoader` still had no consumer. Per-status before/after:

| status | before | after | note |
|---|---|---|---|
| implemented (verified against the tree) | 0 (carried as `patch-ready`) | **29** | proof per row below |
| patch-ready | 23 | **0** | none survives |
| blocked | 10 | **4** | #5, #10, #25, #52 only |
| closed | 5 | 5 | unchanged |
| limit | 14 | 14 | unchanged (pi has no path) |
| applied (uncommitted / in-flight) | 20 | 16 | other agents' rows, not re-verified here |
| resolved (row #55) | 0 | 1 | 10 keys wired, 3 rows deleted with pi-source reasons |

Proof for each changed row, by **file + symbol** (line numbers are deliberately absent — the tree's own
convention, because they drifted collectively once already):

| # | was | now — proof |
|---|---|---|
| 1, 59, 60, 61 | patch-ready P6 | implemented — `ChatScreen.imagePicker` (`rememberLauncherForActivityResult` + `ActivityResultContracts.GetContent`) → `PiSessionViewModel.send(text, images)` |
| 2, 3, 17 | patch-ready P4 | implemented — `PiSessionViewModel.sendFollowUp` now has a caller (`ChatScreen` overflow) |
| 4, 16 | patch-ready P2 | implemented — `ChatScreen.mergeRestoredQueue` fed by `PiSessionViewModel.stop(onRestored)` |
| 7, 44 | patch-ready P3 | implemented — `PiSessionViewModel.exportSession` branches on `.jsonl` → `exportJsonl` |
| 14, 43 | patch-ready P12 | implemented — `PiSessionViewModel` resume gate + `PiSettingsRegistry` `app.sessions.resumeLast` |
| 18 | patch-ready P5 | implemented — `ChatScreen` `thinkingDefaultExpanded = !prefs.thinkingCollapsedByDefault` |
| 19 | patch-ready P5 | implemented — `ChatScreen` `hideThinking = prefs.hideThinkingBlock` |
| 20 | blocked | implemented — `ChatScreen.searchQuery` / `SearchBar` / `searchTextOf` |
| 21 | patch-ready P7 | implemented — `ChatScreen` overflow 跳到上一条/下一条提问 |
| 22 | blocked | implemented — `SessionTreeScreen.TreeFilter` (pi's five modes, cycled) |
| 23, 42 | patch-ready P8 | implemented — `SessionsScreen` `query` / `byName` / `namedOnly` |
| 24, 41 | patch-ready P9 | implemented — `SessionsScreen` `combinedClickable(onLongClick=…)` + `PiSessionViewModel.deleteSession` (store half `PiSessionStore.delete`) |
| 26 | patch-ready P10 | implemented — `PiCommandAction.OpenModelScope` → `NavRequest.SettingsFocus(enabledModels)` |
| 46 | patch-ready P11 | implemented — `SessionsScreen` `.groupBy { it.cwd }` + `PiSectionHeader` |
| 62 | patch-ready P1 | implemented — `ImageGridBlock.decodeImage` (`Base64.decode` + `BitmapFactory.decodeByteArray`) |
| 55 | fixed (4/13), 3 open | resolved — 10 keys wired (`TerminalSettings`, `PiSessionViewModel.readPrefs`), 3 rows deleted with pi-source reasons (`PiSettingsRegistry` deletion comments) |

Why this happens, recorded so the next pass checks rather than assumes: **the ledger is written ahead
of the tree.** The behaviour agent's §11 pass landed most of these while §2 still described them as
patches, and nothing re-read the rows; the rendering table had exactly the same defect (F1, F4, F5
carried as `patch-ready` for two passes after the work landed). An unverified "not done" costs what an
unverified "done" costs — somebody rebuilds something that exists. Read a status here as a claim with
a proof, and check the symbol before building.
The three `applied (uncommitted)` rows are #51 and #54 (`packages/**`, owner *包管理代理*) and #63
(`@` mentions, `ui/chat/**` + `ui/screens/ChatScreen.kt` + `ui/PiSessionViewModel.kt`). For #51/#54 the
screen is mounted, the built-in/user distinction is drawn and labelled as the app's own claim, and the
install command now binds the same agent dir as the engine (`docs/known-gaps.md` §K5). For #63 the
composer opens a mention list from the guest's own `fd`, a pick inserts pi's literal `@path`, and the
message goes out through the existing `prompt` path — with two deviations stated in §E4 (the composer
has no caret state, so only the trailing token completes; and a longer debounce, because each lookup is
a guest process). None of them is `fixed` until CI has built them, and the git-source half of #51 stays
blocked on §K2.
**Delta from the §I2 pass (counts above not re-tallied, because other agents moved rows at the same
time):** `#56`, `#58`, `#67` left `blocked` and `#13`, `#47`, `#49` left `in-flight`; all six are now
`applied (uncommitted)` — #58/#67 by **deleting** the rows rather than implementing them, which is the
disposition the criterion "pi 里有没有" forces. #48 stays `limit` but is no longer inert (it points at
the TUI). The three `applied (uncommitted)` rows named above are therefore no longer the only ones.
Row #55 is the only `fixed` row, and it is a mix: the four `app.terminal.*` keys are wired by this
pass (`ui/terminal/TerminalSettings.kt`, `TerminalPane.kt`, `TerminalKeyBar.kt`,
`TerminalBridge.kt`, `TerminalPalette.kt`), and **seven more** were wired concurrently by the behaviour agent
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
| **A1** `SystemPromptBlock` | **deleted** — the whole `system_prompt` item went: it had no producer (pi has no transcript component and no RPC command returns the prompt), so the item, `ui/blocks/SystemPromptBlock.kt` and the `onSystemPrompt` hook were removed together. Nothing on screen, nothing left to keep in sync | pi has no such component (only `interactive-mode.ts:1716-1719`, `:2068` read it) | **no pi counterpart — removed** |
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
| F1 | DEFECT | a message sent mid-turn never reaches the screen | `ui/PiSessionViewModel.kt` → 行为 | RR-P1 | implemented — RR-P1 applied — (`echoUserPrompt` in the steer path) | #2/#3/#17 (my P4) |
| F2 | DEFECT | aborted/errored turns leave tool cards spinning forever | `rpc/Transcript.kt` → RPC | RR-P5 | implemented — RR-P5 applied — (`TranscriptReducer.failTurn` + `TURN_FAILURE_REASONS`) | — |
| F3 | DEFECT | a `length`-truncated or aborted answer reports nothing | `rpc/Transcript.kt` → RPC | RR-P5 | implemented — RR-P5 applied — (`TURN_FAILURE_REASONS`, `detailsTruncated`) | — |
| F4 | DEFECT | follow-the-tail scroll re-animated on every token, stealing the scroll | `ui/screens/ChatScreen.kt` → 行为 | — | **implemented (uncommitted)** — already in the tree from the behaviour agent's pass (§11), verified by reading it this pass: a `following` flag (`ChatScreen.kt:257`, `rememberSaveable`) plus `atBottom` (`derivedStateOf` over `layoutInfo`, `:258-264`) which is the only thing that unlocks the follow (`:266`); the tail effect (`:267-275`) returns while unlocked and uses the non-suspending `scrollToItem` while `state.streaming`, reserving `animateScrollToItem` for the non-streaming case; search jumps set `following = false` (`:279`) and the §4.5 「回到最新」FAB re-arms it (`:565-573`). P-divergence: the review's "keep the previous item count" is replaced by the stronger `following` + at-bottom gate, so a grow that happens while the user is away from the bottom cannot yank the view. No code change was needed | — |
| F5 | DEFECT | every `entry_appended` is projected twice | `ui/PiSessionViewModel.kt` → 行为 | RR-P2 | implemented — RR-P2 applied — (`EntryAppended -> Unit`; reducer is the single projector) | — |
| F6 | GAP | extension `custom` entries render nothing | `rpc/Transcript.kt` → RPC | RR-P4 | implemented — RR-P4 applied — (`onCustomEntry` for `"custom"` entries) | — |
| F7 | PERF | whole screen + app root invalidated per token; computed change index discarded | `ui/PiSessionViewModel.kt`, `ui/screens/ChatScreen.kt` → 行为 | — | **not worth doing in the documented form — re-ruled this pass (2026-09-12); its mechanisms are already in the tree.** F7 asked for the engine to stop discarding the reducer's change index, and that half **is** in the tree: `PiEngineSession` publishes `TranscriptPublication{revision, change, rows, changedIndices, replaced, streaming}` (`engine/PiEngineSession.kt:118-186`) and diffs row identity itself (`publish`/`diffIndices`, `:327-386`), while `PiSessionViewModel.syncTranscript` updates only `changedIndices` through `applyChanged` (`:869-939`) and adopts wholesale only in the four documented cases. The "whole screen invalidated = whole screen redrawn" half is **disproved by reading**: `PiRoot`'s `Scaffold`/`bottomBar` lambdas read no `uiState` field (`ui/PiRoot.kt:143-155`; the root collects it only for `navRequest`, `:109`), `itemsIndexed` registers an interval rather than n items, and `LazyColumn` composes only the viewport — so a token costs O(visible rows) + the row that moved, **an order of magnitude below the review's estimate**. A read-only desktop-JVM benchmark (2000 entries / 3335 lines / 1.1 MB payload; **desktop, not a phone**) measured the remaining per-token O(n) at **≈0.16 ms** (`applyChanged` copy 0.029 + whole-list `equals` 0.059 + identity diff 0.076) against a 16–50 ms token interval. The only piece that could still matter is spec §4.2's per-row `MutableState<String>` for the streaming text, and it **needs a device measurement before anyone touches four files' data flow**. Residual suspicion, written down so it does not become a phantom entry: the per-token **full markdown re-parse** (`PiMarkdown.kt`'s `remember(markdown) { piMarkdownSource(markdown) }`, invalidated because the text changes each token) is real — but pi rebuilds the whole assistant message per delta too (`interactive-mode.ts:3245-3247`, `components/assistant-message.ts:91-106`), so changing it would make us **better than pi**: a product decision plus a measurement, **not a fidelity debt**. First step if it is ever done: move the streaming row's text out of `items[index]` into its own `MutableState`/flow (`rpc/Transcript.kt`), have `publish` stop producing a new row identity for text deltas (`engine/**`, outside this agent), and let the block read that state directly — **do not** start with `PiRoot`/`ChatScreen`'s subscriptions. | #55 (`showTimestamps` et al. share the root) |
| F8 | PERF | `tool_execution_update` applied unthrottled (spec asks 200 ms) | `rpc/Transcript.kt` → RPC | RR-P3 | implemented — RR-P3 applied — (`TOOL_UPDATE_THROTTLE_MS = 200L`) | — |
| F9 | PERF | session replay and entry projection run on the main thread | `ui/PiSessionViewModel.kt` → 行为 | — | **the real symptom, but the review's fix direction is reversed — doing it as written is a net zero, so it is deliberately not done here; the real fix is `engine/**` and is now owned by `89270cd0`** — re-verified against the tree this pass (2026-09-12). `PiResponses.entries` (`rpc/Responses.kt:477-480`) is `as? JsonArray` + `mapNotNull`, i.e. a **cast, not a parse** (desktop-JVM benchmark: **0.28 ms**); the actual JSON parse is `PiEvents.parse` inside `readLoop`'s `withContext(Dispatchers.IO)` (`engine/PiEngineSession.kt:239-255`, `:257-258`) and is **already off the frame thread** (**16.52 ms** for 2000 entries / 1.1 MB). What pins the main thread is the **projection**: `replayHistory` (`ui/PiSessionViewModel.kt:1408-1426`, reached from `viewModelScope.launch` = `Main.immediate`) → `engine.seedHistory` → `TranscriptReducer.seedFromHistory` (**41.84 ms**) → `publish`'s O(n) diff + `toList()` (`engine/PiEngineSession.kt:345-350`) → `syncTranscript`'s adopt `map`. The review's concurrency reason is stale twice over: the collector reads the snapshot `pub.streaming`, not the reducer, and the reducer is **already** written from the IO reader thread (`handle()` → `transcript.onEvent`, `:257-277`), so the invariant is "one writer, never concurrent with the reader" — establishable only inside `engine/**`. **Delegated fix (`89270cd0`):** an ownership point (mutex or single-thread confinement) around reducer writes, `seedHistory` becoming `suspend` and running the projection on `Dispatchers.Default`, with one main-thread publication afterwards. An in-boundary micro-win was identified and **not** taken — the adopt branch can reuse `pub.rows` when `!interrupted` (`:879-887`), saving one n-element `map` — because it is a fraction of a symptom whose dominant term is `seedFromHistory`. All three numbers are desktop-JVM; **no phone frame-time measurement exists for any of the three items**. | — |
| F10 | GAP | no live token/context indicator; all three `usage` payloads dropped | `ui/**` + `rpc/**` → 行为 + RPC | — | **rpc half implemented, UI half blocked (new UI)** — all three payloads now reach the reducer: `Transcript.kt:717` (`lastUsage`) written from `message_update` (`:975`) and `message_end` (`:793`), plus `compaction_end.result.usage` / the persisted entry's `usage` onto `CompactionMarker` (`:144`, `:1434`, `:1750`); parsed at `Events.kt:432`, `:443` and `Responses.kt:370`. Test: `FidelityFixesTest:719`. But a grep over `app/src/main` finds **no reader** of `TokenUsage`/`lastUsage` (only the comment at `PiEngineSession.kt:265`), so nothing is painted: spec §4.1's 32 dp status row and §4.9's 70 %/90 % states are still a `ui/**` change. | — |
| F11 | DEFECT | block margins and rhythm are ~2× the spec | `ui/blocks/BlockChrome.kt`, `ui/screens/ChatScreen.kt` → 行为 | RR-P6 | **implemented (uncommitted)** — `ui/blocks/BlockChrome.kt:44-65`: `BlockColumn` no longer pads itself (the `.padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit / 2)` is gone, KDoc now says the list owns the margin), and `ui/screens/ChatScreen.kt:520-539`: the default `blockSpacing` is the spec's 16 dp (`PiSpacing.screen`, was `PiSpacing.unit` = 18) with `compact`/`cozy` still scaling around it, `contentPadding` vertical likewise 16. Net: side margin 16 dp instead of 32 (spec §7.4 `assistant-text` 左右内边距 0) and the inter-block gap the density setting's single value instead of 18 + 9 + 9 = 36. Net: side margin 16 dp instead of 32 (spec §7.4 `assistant-text` 左右内边距 0) and the inter-block gap the density setting's single value instead of 18 + 9 + 9 = 36. **Also fixed this pass, on the parent's ruling:** `DateSeparatorBlock.kt:27-40` and `ModelChangeBlock.kt:35-47` do not use `BlockColumn` but each carried its own `horizontal = PiSpacing.screen`, which stacked on the list's 16 dp and left those two kinds at 32 dp while everything else went to 16 — both now pad vertically only, so the page margin really has one source. (The review's note that they "already pad themselves, so nothing loses its margin" was true of the block's own box but not of the total.) | — |
| F12 | DEFECT | dark-theme `dim` text is 2.1–2.9:1, under the spec's 3:1 floor | `ui/blocks/UserMessageBlock.kt`, `BranchSummaryBlock.kt` → 行为 | RR-P7 | implemented — RR-P7 applied — (`palette.userMessageText.copy(alpha = 0.62f)`) | — |
| F13 | DEFECT | content tokens below 4.5:1 (tool output, diff context, thinking headline) | `ui/theme/PiPalette.kt` → 行为 | — | blocked (palette decisions) | #34–#37 (theme files) |
| F14 | GAP | the light theme's `muted`/`dim` correction does not exist | `ui/theme/PiPalette.kt` → 行为 | — | blocked (palette decisions) | #34–#37 |
| F15 | GAP | user messages render raw Markdown source | `ui/blocks/UserMessageBlock.kt` → 行为 | — | implemented (`UserMessageBlock` renders `PiMarkdownText`) | A1 (same renderer) |
| F16 | GAP | images returned by a tool are flattened to the literal text `[image]` | `rpc/Transcript.kt` + `ui/blocks/` → RPC + 行为 | — | **rpc half implemented, UI half not wired** — the bytes survive the parser: `Events.kt:471` + `imageBlocks` (`:661`) feed `ToolCall.images` (`Transcript.kt:82`), from the live `tool_execution_end` (`:1201`) and from replay's toolResult entry (`:1612`); `contentText` still writes `[image]` but only on the plain-text path. Test: `FidelityFixesTest:688` (image kept alongside `outputTruncated`). **But `ToolCall.images` has no consumer in `app/src/main`** (`ToolCallBlock.kt` never reads `images`; `ImageGridBlock` is used only by `UserMessageBlock.kt:48`), so a tool's picture still is not drawn. | #62, A3 |
| F17 | GAP | tool output hard-capped at 400 lines, no way to see the rest; >200 KB rule absent | `ui/blocks/` → 行为 | — | **implemented (uncommitted)** — `ui/blocks/ToolCallBlock.kt`: `MAX_OUTPUT_LINES = 400` is gone, replaced by `COLLAPSED_OUTPUT_LINES = 200` + `MAX_OUTPUT_BYTES = 200 * 1024` (`:166-174`); the card's toggle shows the spec's 200 lines and a new 「展开全部（共 N 行）」 row (`:124-134`) switches a second, per-row state to the **full** text, so the remainder is reachable; past 200 KB the body is replaced by the spec's 「输出超过 200 KB，请前往工作区查看完整日志」 label (`:110-119`) instead of painting it. `tail` of the design, recorded: the spec's 「展开后启用虚拟滚动」 is not implemented — the expanded body is one `Text`, which is what the review's minimal fix asks for and what a single Compose text node does; the 200-line default is also what bounds the common case. The block's KDoc/comments cite spec §4.2 | #55 (`outputMaxLines`) |
| F18 | GAP | compaction/branch-summary cost is parsed then dropped | `rpc/Transcript.kt` + `ui/blocks/` → RPC + 行为 | — | **rpc half done (this pass); the visible row is the app's by design** — both kinds now carry the figure: `CompactionMarker.usage` (`Transcript.kt:144`, live `compaction_end.result.usage` `:1434`, persisted entry `:1750`) and `BranchSummary.usage` (`:165`, read by `onBranchEntry` `:1779-1793`) — tests `FidelityFixesTest:740` and the two new branch-summary ones. pi bills branch summaries identically: `interactive-mode.ts:3791-3792` builds the `compaction_cost` item for `compaction` *and* `branch_summary`, `:3708-3711` dispatches it, `:3430-3436` is the live path. **Why the reducer must not draw the row:** pi gates it on `settingsManager.getShowCacheMissNotices()` (`:3804`, default **false** at `core/settings-manager.ts:120`), and the reducer is constructed with a clock and nothing else — appending it here would show what pi hides by default. The app owns the gate and already has the switch (`ui/settings/PiSettingsRegistry.kt:331`, currently **no reader** — an I5-class dead setting). Wording/format for whoever draws it: `interactive-mode.ts:3803-3814` (`${label}: ${formatTokens(tokens)} tokens billed (~$0.03)`, label `Compaction` / `Branch summary`). | A1 |
| F19 | GAP | five tap affordances unreachable — callbacks never supplied | `ui/screens/ChatScreen.kt` → 行为 | RR-P10 | implemented — RR-P10 applied — (`onModelClick` supplied at the BlockRenderer call) | — |
| F20 | GAP | spec §4.8 per-block actions absent | `ui/blocks/` → 行为 | — | blocked (new UI) | — |
| F21 | GAP | sending mid-turn silently steers instead of asking | `ui/screens/ChatScreen.kt` + `ui/PiSessionViewModel.kt` → 行为 | — | **blocked (ruled this pass, not done) — the premise no longer holds and the spec's sheet is new UI needing a ViewModel action.** Read from the tree: the streaming composer icon is Stop, not Send (`ChatScreen.kt:1032` `if (streaming) onStop() else onSend()`), and the 后续 chip calls `onFollowUp` → `sendFollowUp` (`:1060-1085`, `:663`), so nothing steers silently any more — mid-turn **steer** is in fact now unreachable from the UI, which is the mirror image of the finding. Spec §4.4's three-way sheet still needs: (1) a new sheet + per-row state in `ChatScreen.kt` (this agent's file, ~40 lines), and (2) **打断，重说** = `clear_queue` + `abort` + resend, which has no ViewModel surface — `PiCommands.clearQueue`/`abort` exist (`rpc/Commands.kt:86-88`) but `ui/PiSessionViewModel.kt` (outside this agent's writable set) has only `stop(onRestored)`, and sequencing a resend inside its callback races the reducer's `streaming` flag, so it is not a safe UI-only composition. Also found while reading: `PiCommands.followUp` **does** carry images (`rpc/Commands.kt:75-84`) but `PiSessionViewModel.sendFollowUp(text)` did not, so the existing 后续 chip **silently dropped an attachment the user had added — the message queued and the picture was gone.** **Fixed this pass (point authorisation for exactly these lines):** `PiSessionViewModel.sendFollowUp(text, images = emptyList())` now echoes and sends them (`:1629-1656`; `engine.echoUserPrompt(trimmed, images)` + `PiCommands.followUp(..., images = images)`), the chip passes them and clears the attachment row (`ChatScreen.kt:730-745`), and pi does the same — `interactive-mode.ts:4146` → `_queueFollowUp(text, currentImages)` (`agent-session.ts:1225-1226`). Not changed: a caption-less follow-up is still not offered (the chip's gate is `draft.isNotBlank()`), so nothing is lost there either. The review itself marks the sheet "needs new UI → report only" | #2/#3/#17 (my P4) |
| F22 | DEAD CODE | the `system-prompt` block kind cannot be produced | `rpc/Transcript.kt` → RPC | — | **rpc-side fix is impossible alone; labelled app-only instead** — pi has no `system_prompt` entry (`session-manager.ts:145-155`) and **no RPC command returns the prompt** (`rpc-types.ts:20-71`; `get_state`'s `RpcSessionState`, `:96-109`, has no prompt field), while pi never renders the prompt text at all (its `/context` listing prints only the source path, `interactive-mode.ts:1715-1722`, and the only reader of the text is the extension hook at `:2068`). `onSystemPrompt` has no caller in `app/**` (tests only). **Resolved by deletion — the coordinated change this row asked for has since happened (verified in the tree):** `ui/blocks/SystemPromptBlock.kt` no longer exists, `BlockRenderer`'s `when` has no `system_prompt` branch, and `rpc/Transcript.kt`'s class note records that the item, the block and the hook went together. Nothing is blocked here. | **closed (removed)** |
| F23 | DEAD CODE | `outputTruncated` is never assigned, but is read for a label | `rpc/Transcript.kt` + `ui/blocks/ToolCallBlock.kt` → RPC + 行为 | — | implemented (`detailsTruncated(details)` assigns `outputTruncated`) | — |
| F24 | DEAD CODE | 8 `message_update` delta kinds parse then fall into `None` | `rpc/Events.kt`, `rpc/Transcript.kt` → RPC | — | implemented (`AssistantDelta.TextEnd` / `ToolCallEnd` handled) | — |
| F25 | DEAD CODE | the `else -> NoticeBlock("暂不支持的内容块")` branch is unreachable | `ui/blocks/BlockRenderer.kt` → 行为 | — | implemented (unreachable `NoticeBlock` branch deleted) | — |
| F26 | DEAD CODE | dead `skill`/`skill_invocation` entry helpers and aliases (pi has no such entry type) | `rpc/Transcript.kt` → RPC | — | implemented (no `skill`/`skill_invocation` case, with the reason recorded) | — |
| F27 | DEAD CODE | `turn_start` parsed with no renderer | `rpc/Transcript.kt` → RPC | — | closed (review says no fix needed) | — |
| F28 | INCONSISTENCY | the same "expand" gesture differs per block type | `ui/blocks/*` → 行为 | — | blocked (standardise across ~8 occupied files) | — |
| F29 | INCONSISTENCY | off-scale dp literals bypass `PiSpacing`/`PiShapes` | `ui/blocks/*`, `ui/theme/` → 行为 | — | blocked (mechanical but touches many occupied files) | — |
| F30 | PERF | `formatClock` builds a `SimpleDateFormat` per call, per recomposition | `ui/blocks/BlockChrome.kt` → 行为 | RR-P8 | implemented — RR-P8 applied — (hoisted `DateTimeFormatter` `CLOCK_FORMAT`) | — |
| F31 | PERF | `ToolCallBlock` re-scans/splits the whole output every composition | `ui/blocks/ToolCallBlock.kt` → 行为 | — | implemented (`remember(item.output) { lineCount(...) }`) | #55 (`outputMaxLines`) |
| F32 | PERF | five markdown config objects rebuilt per composition | `ui/render/PiMarkdown.kt` → markdown | RR-P9 | implemented — RR-P9 applied — (`remember { piMarkdownComponents() }` et al.) | — |
| F33 | PERF | `items(...)` has no `contentType`, so slots cannot be reused | `ui/screens/ChatScreen.kt` → 行为 | — | implemented (`contentType = { _, item -> item::class }`) | — |
| F34 | PERF | nothing bounds the transcript or the entry list | `rpc/Transcript.kt` + `ui/screens/ChatScreen.kt` → RPC + 行为 | — | **implemented this pass as a rendering window — deliberately not as a data bound (2026-09-12).** `ui/screens/ChatScreen.kt`: `renderWindow` (50, `rememberSaveable(sessionKey)`, reset per session) → `renderedItems = visibleItems.takeLast(renderWindow)`, a 「加载更早的 N 条」 header plus scroll-to-top auto-load (`earlierArmed`), `userRowIndices` moved from a per-composition O(n) scan to an on-tap derivation, and jumps use the two-phase `reveal(row)` → `pendingJump` so a target outside the window is revealed before it is scrolled to. **The reducer keeps every row**: "load earlier" is local, which is the only thing pi allows — `get_entries` has a forward-only `since` cursor (`packages/coding-agent/src/modes/rpc/rpc-types.ts:65`, `modes/rpc/rpc-mode.ts:638-648`, pi's own `docs/rpc.md:717-745`) and `get_messages` has no cursor either (`rpc-types.ts:71`). **The earlier "ChatScreen renders all of it" reading does not hold**: `LazyColumn` composes only the viewport, so the window's measurable value is one O(n)/token pass removed (the `userRowIndices` scan) plus ≤50 items handed to the list instead of n; the engine/ViewModel O(n)/token passes are F7's territory and remain. **No bound/eviction architecture is built, on purpose**: pi has none either (its TUI flatMaps every entry into components and draws only what its ScrollView shows; a whole-tree grep finds no trim), and the rule is "pi 没有的不做" — this window is spec §4.5's visible behaviour, **not** a performance claim. Untouched by it: what the reducer stores, and the cost of opening a session (see F9). | — |

Merged status counts for the 34 rendering rows, after the rendering pass walked every one of them
against the code: **implemented/applied 21 · blocked 8 (of which F7 and F9 are re-ruled *not worth doing as
written* — see their rows) · partial 3 (rpc half done, UI half not) · closed 2** (F27 needs no fix; F22 was closed by deleting the item). The IDs, so the numbers can
be checked rather than believed:

```
implemented/applied (21)  F1 F2 F3 F4 F5 F6 F8 F11 F12 F15 F17 F19 F23 F24 F25 F26 F30 F31 F32 F33 F34
blocked            ( 8)  F7 F9 F13 F14 F20 F21 F28 F29
                         (F7 and F9 are not waiting on anyone: F7's mechanisms are in the tree and its
                          remaining cost was disproved as O(session); F9's documented fix is a net zero and
                          its real fix is `engine/**`, delegated to `89270cd0`)
partial            ( 3)  F10 F16 F18
closed             ( 2)  F22 F27
```

**`patch-ready` is now zero for the rendering rows**, which is the point: every one of the fourteen
rows this document once carried as "a patch exists but nobody has applied it" now has a verdict of
its own.

Three of them turned out to be **already implemented** before the pass started (F1, F4, F5 - the
behaviour agent's §11 work landed them and nothing updated §10), which is why the count moved by
more than the work did. **§2 had the same defect and it is now walked too** — see
"§2 reconciliation" in §9: `patch-ready` there is **0**, 29 rows turned out to be implemented, and
only 4 remain genuinely blocked. Do not treat a status in either table as final: check the symbol it
names. An unverified "not done" is not harmless — it sends someone to build what already exists,
which is the same cost as an unverified "done".

| Ledger | implemented/applied | partial | patch-ready | blocked | limit | closed | rows |
|---|---|---|---|---|---|---|---|
| `feature-gaps.md` rows (§2) | 1 fixed + 3 applied | 0 | 23 *(unverified - see above)* | 10 | 14 | 5 | 69 |
| `rendering-review.md` rows (§10) | 21 | 3 | 0 | 9 | 0 | 1 | 34 |

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
| RR F1 (mid-turn echo) | **Done** | `PiSessionViewModel.kt:1545` (`send`) and `:1595` (`sendFollowUp`) now call `engine.echoUserPrompt(...)` instead of `transcript.onUserPrompt(...)`: the reducer path creates the row without publishing it, so no consumer would ever see it (`PiEngineSession.echoUserPrompt` exists for exactly this). The reducer's `MessageStart` still ignores `role == "user"` (`rpc/Transcript.kt:587-593`, not this agent's file), so this is the only live echo. **If that reducer branch is fixed, this echo must be deleted or the message renders twice.** |
| RR F2 / F3 (pending tool cards; truncated/aborted answer reports nothing) | **Reducer root fix applied (uncommitted, rpc agent), conditions ruled 1:1 with pi; app-side mitigation now duplicates it** | The behaviour is in the row model, not only live: `rpc/.../Transcript.kt:1182-1229` `failTurn()` closes a still-`Pending` tool card **only** for `aborted`/`error` (pi: `interactive-mode.ts:3294` live / `:3735` replay; `length` takes the else branch at `:3305-3315`), and appends the row only when `length`, or when `aborted`/`error` carried **no** tool call (`Transcript.kt:1191`; pi's gate is `hasToolCalls` at `assistant-message.ts:180,187`, parsed onto the event at `Events.kt:167,430`). Driven by the live `message_end` (`:689`) **and** by the persisted assistant entry's `stopReason` in `onHistoryAssistant` (`:1525`), so `seedFromHistory` replay reproduces the same rows — pi does this on replay too (`interactive-mode.ts:3735-3746`). `errorMessage === "Request was aborted"` is handled at `Transcript.kt:1196` like `assistant-message.ts:190`. Tests: `rpc/src/test/.../FidelityFixesTest.kt` — live: `a length stop closes no tool card`, `an aborted turn with a tool call closes its card and prints no second line`, `an aborted turn with no tool call prints the abort line`, `pi's fixed "Request was aborted" literal is not echoed`, `an error stop with a tool call closes the card and prints no row`; replay: `history replay reports a length truncation exactly like the live stream`, `… closes the tool cards an aborted turn left pending`, `… leaves a length-truncated tool card pending`, `a re-seed of a failed turn keeps the key stable`; parse: `EventsTest` `message_end reports whether the message carried a tool call`. **Consequence for the app layer:** `stopReasonRow()` + `liveTurnIssue` used to add a second, differently-worded row for the same live failure; **applied (uncommitted)** — both are now deleted (`PiSessionViewModel.kt`: the field, the `MessageEnd` projection, the `MessageStart` reset, the three `= null` sites and the `stopReasonRow` helper are gone), so the reducer's row is the only one. `syncTranscript`'s `interrupted` projection (now `projectRow`/`applyChanged`) stays, and its scope is written down there: only a row still at `ToolStatus.Pending` is touched, `output` is filled only when empty, so it cannot overwrite a result the reducer wrote. |
| RR F4 (scroll steals the tail) | **Done** | `ChatScreen.kt` follow-state + `derivedStateOf` at bottom, non-animated `scrollToItem` while streaming, and the §4.5 "回到最新" button (`:553`); search jumps and prev/next jumps unlock the follow. |
| RR F5 (double `entry_appended` projection) | **Done** | `PiSessionViewModel.kt` `PiEvent.EntryAppended -> Unit`: the engine's own `onEvent` already projects and bumps revision. Engine half belongs to `5d01ecd2`. |
| RR F7 (whole screen invalidated per token) | **Done for the app half — applied; both halves are in `f37545f`** | The engine now publishes `TranscriptPublication` (`revision` / `rows` / `changedIndices` / `replaced` / `streaming`) and `ui/PiSessionViewModel.kt` consumes it: `engine.publication.collect { pub -> syncTranscript(engine, pub) }` (`:702`), and `syncTranscript(engine, pub)` (`:809`) updates only `pub.changedIndices` when the consumer is exactly one publication behind, adopting `pub.rows` in the four cases that need it (`replaced` / a revision gap / a streaming flip — the F2 projection rewrites rows globally / a shorter local list). The O(n) identity scan per delta is gone (`applyChanged`, `:867`), and the "is there work" test is `pub.changedIndices.isNotEmpty()` — **never `pub.change`**, because F8's throttled `tool_execution_update` mutates its row and answers `None` (`rpc/Transcript.kt:1112-1115`). A publication with no row work still rewrites `UiState` (revision always advances) so usage/thinking wakes are not lost. |
| RR F32/P9 (markdown config rebuilt per frame) | **Implemented — the wrapper is gone, the builders are pure** | `remember { piMarkdownColors() }` was illegal because the builders are `@Composable` and `remember`'s calculation lambda is `@DisallowComposableCalls`. The fix is the one that entry asked for: `PiMarkdownText` reads the four inputs and calls pure constructors (`PiMarkdown.kt:87-101`), `PiMarkdownTheme.kt` builds `DefaultMarkdownColors` / `DefaultMarkdownTypography` / `alert` colours and holds `MarkdownPadding`/`MarkdownDimens` implementations as constants (`:136`, `:178`, `:212`, `:250-290`, `:292-324`), and `piMarkdownComponents()` is simply not `@Composable` any more (`PiMarkdownComponents.kt:98-103`) so it is remembered directly. No composable call sits inside any `remember`. Not verifiable here — CI is the only build. |
| #62 / RR F16 (images are placeholders) | **Done** | `ImageGridBlock.kt` decodes the inline base64 off the main thread (`produceState` + `Dispatchers.IO`) and falls back to the labelled placeholder only for bytes the platform codec refuses. |
| #1 / #59–#61 / #60 (image input) | **Done** | `ChatScreen.kt:194` (picker → base64 `PiImage`), attachment chips, `send(text, images)` on the existing pipe; an image with no caption is still a message. |
| #18 / #19 (thinking toggle never reaches the renderer) | **Done** | `UiPrefs.hideThinkingBlock` + `thinkingCollapsedByDefault`, passed at `ChatScreen.kt:544-546`; `hideThinkingBlock` is pi's own key (`settings-manager.ts:119`). |
| #21 (no previous/next prompt) | **Done** | `ChatScreen.kt:427` jumps over the same `visibleItems` list the list renders, so a hidden date separator cannot shift the target. |
| #23 / #42 / #46 (session search/sort/named/grouping) | **Done** | `SessionsScreen.kt:104` filters by name/cwd/file name, `:144` named-only, sort toggle, `:165` grouped by cwd — pi's own picker controls (`docs/sessions.md:43-46`). |
| #24 / #41 (delete a session) | **Done** | `PiSessionViewModel.kt:1636` unlinks the file off the main thread; the screen's confirm dialog refuses the **active** session so the engine is never left writing a removed inode. `PiSessionStore` stays read-only. |
| #14 / #43 (`pi -c`) | **Done behind a switch** | `app.sessions.resumeLast` (`PiSettingsRegistry.kt`) + `PiSessionViewModel.kt:642`, one attempt per process, `switch_session` after attach. |
| #26 / P10 (`/scoped-models`) | **Done** | Reachable: `PiCommandAction.OpenModelScope` (`ui/chat/PiSlashCommands.kt`) on the `scoped-models` command, carried as a settings-highlight nav request through `PiRoot` (`NavRequest.SettingsFocus(enabledModels)`) into `PiSettingsStack`. The settings-highlight surface this row called "more surface than a patch" was built. |
| I8 transcript search | **Done** | `ChatScreen.kt:839` + `:816` (per-kind text extractor); row-level highlight with pi's `searchMatchBg`/`searchMatchText`, "n/m", prev/next/close, and the UI states its own limit (block-level, not per-character). |
| I1 分支 button | **Done** | `SessionTreeScreen.kt:352` now says 分叉新会话 and the header states the truth: pi's `/tree` moves the leaf without a file (`docs/sessions.md:71`), RPC has no such command (`rpc-types.ts:20-74`), so this button forks. |
| I8 tree filters | **Done** | `SessionTreeScreen.kt:256` reproduces `TreeSelectorComponent`'s five modes (`components/tree-selector.ts:340-395`), including the no-text-assistant rule now that `PiMessage.Assistant.stopReason` is on the model. |
| I5 the 13 no-consumer settings | **9 resolved here, 4 by the concurrent terminal agent** | Wired: `app.appearance.fontScaleDelta`, `messageDensity`, `showTimestamps`, `thinkingCollapsedByDefault`, `app.tools.expandByDefault`, `app.runtime.keepAlive` (gates the foreground service in `boot()`), plus `hideThinkingBlock`. Removed: `app.appearance.dynamicColor` (cannot coexist with "every colour comes from `PiTheme.palette`"), `app.tools.bashTimeoutSeconds` (no field on the `bash` command, `rpc-types.ts:55`; timeout is a per-call model argument, `core/tools/bash.ts:234`), `app.tools.outputMaxLines` (pi constant, `core/tools/truncate.ts:11`; same change as RR F17). The four `app.terminal.*` rows are read by `ui/terminal/TerminalSettings.kt`, which another agent owns. `app.runtime.wakeLock` is **not** one of the 13, and it is no longer a dead row either: `RuntimeFacts.runtimeOverrides()` supplies all four `app.runtime.*` read-only rows (`piVersion` / `nodeVersion` / `rootfsUsage` / `wakeLock`) and `PiSettingsStack` reads them on `Dispatchers.IO`. |
| I4 theme files → palette | **Done** | `PiThemeFiles.kt:96` discovery (agent dir + project + `themes` setting, `resource-loader.ts:872-902`), `:190` resolution incl. the `a/b` pair (`theme.ts:597-608`), `:264` colour grammar (`theme.ts:228-264`) with the optional-token fallbacks and the exporter's derived surfaces (`export-html/index.ts:81-105`). `MainActivity.kt` paints `PiTheme(palette = theme.palette)`; the picker lists discovered names with scope and states every caveat it cannot honour (`""` terminal-default tokens, 256-colour indices, missing tokens) — `PiSettingsEditors.kt` theme sheet. |
| §2.10 `app.device.*` dead switches | **Done** | The seven rows and the `G_DEVICE` group entry are gone from `PiSettingsRegistry.kt`; `SettingsHome.kt:78-83` now records that `DeviceCapabilityStore` is the one authority. |
| `app.trust.extensions` | **Done** | Removed from `PiSettingsRegistry.kt`: pi has no extension allow-list; trust is per project (`settings-manager.ts:509-525`). |
| 设备能力界面 vs 真实行为（本次，`ui/device/**`） | **applied (uncommitted)** — 只改 `ui/device/DeviceCapabilityScreen.kt` | Claim-by-claim audit of the one authorization screen against `DeviceCapabilityStore` (sole authority) / `DeviceShellGuard` / `/app/health`; 6 mismatches fixed: Shell card showed `ShizukuShellBackend.label` ("未安装/未运行/未授权") as the **current** backend while `DeviceShellGuard.active()` would run as the app uid; the a11y card's `runCatching { startActivity }` swallowed its failure; screenshot listed as available below API 30 (`DeviceUiAutomation.kt:609-616`); the camera/location grants were never requested anywhere while `cameraPrecondition()`'s hint names a 「授予相机权限」 button on that card (`DeviceCapabilityStore.kt:275-276`); `POST_NOTIFICATIONS` silent in the default-on 基础 group (`DeviceSystemActions.kt:103-112`); `ApprovalsCard` read `DeviceApprovalLedger` without any polled state, so a `LazyColumn` item composed once and never refreshed. Verified-consistent and untouched: loopback-only bind (`DeviceBridgeHttp.kt:131`), shell policy card read-through (`DeviceShell.kt:590-614`), storage legacy-permission notice, the "no silent failure" note (`DeviceCapabilityStore.kt:111-148`). Evidence table: `known-gaps.md` §C3.1. |
| #63 / E4 (`@` file mentions), raised from `blocked` by the DSH mention agent | **applied (uncommitted, not on CI)** | Decision and evidence are in `known-gaps.md` §E4; the short version: pi's candidate *set* is fd's set (its layered ignore handling is the `ignore` crate), so the app runs **the guest's own `fd`** (`RuntimeProvisioner.kt:106`, `:464-469` → `/usr/local/bin/fd`) through the existing `GuestCommand` channel (`GuestCommand.kt:99`, the engine's own proot argv and binds) with pi's flags verbatim (`autocomplete.ts:132-161`), and reproduces in Kotlin only what pi does *after* fd prints (`:202-217`, `:702-784`, `:785`). `ui/chat/PiFileMentions.kt` is pure logic (Android-free) with a bare-JVM harness at `app/src/test/kotlin/app/pi/ui/chat/PiFileMentionsCheck.kt`; its 50 assertions were checked against a line-by-line Python transliteration of the same algorithm, and **the harness is registered** (`tools/run-app-pure-checks.sh`'s `run_harness mentions`), so CI compiles it; `guest-paths` and `agent-tool-paths` were registered in the same batch. `PiFileMentions.kt` was never compiled locally. Two deliberate deviations: the composer has no caret state (`OutlinedTextField(value = String)` in `ui/screens/ChatScreen.kt`), so only the mention token ending the draft completes; and the debounce is 150 ms rather than pi's 20 ms, because each lookup here is a guest process (no device measurement is claimed). One risk written down rather than assumed away: whether `fd` is still executable after the engine's agent-dir bind shadows `/root/.pi/agent`, which is the symlink's target. |

Still open from this agent's queue, in priority order: P10/`/scoped-models`; the remaining UI halves
of F10, F16, F18 and F20–F29 — all of which live in `rpc/**` or in files this agent does not own.
(RR F7's engine half and F6/F8 are off this list: the engine half shipped in `f37545f`, and both
are marked implemented in §10.)

RR F2/F3's reducer half is no longer on that list: the RPC agent's fix is in the working tree
(uncommitted) at `rpc/.../Transcript.kt:1182-1229` + `:1525` (with the `hasToolCalls` parse at
`rpc/.../Events.kt:167,430`), and both of its conditions were re-ruled 1:1 against
`interactive-mode.ts:3294`/`:3735` and `assistant-message.ts:180-199`; what remains is the app-side
duplicate it supersedes (see the RR F2/F3 row above).
