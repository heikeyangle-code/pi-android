# Feature-level gap review: the Android app against `pi` 0.85.1

> ⚠️ **The grades in §1 are as-of `a7b7738`, and the tree moved past most of them.** A reconciliation
> pass at `182823e` sampled **30 of the 148 rows**, preferring everything marked `PARTIAL`/`MISSING`,
> and re-read each against the working tree: **most were already implemented.** §7 carries the sample
> with its proof. The behaviour agent's own record of the fixes is §6. **Do not act on a §1
> `PARTIAL`/`MISSING` grade without checking its §7 row (or the symbol it names) first** — one
> unverified "not done" makes somebody rebuild working code.

This is an **audit of the app's user-facing capability surface**, one capability at a time:
"can the app do this?". It is deliberately not a repeat of the two existing reviews:

| Existing document | Its scope | This document |
|---|---|---|
| `docs/extension-compatibility.md` | the extension surface: `ExtensionAPI`, `ExtensionUIContext`, lifecycle events, packaging, dialog protocol | the **features a user reaches**, not the extension API |
| `docs/known-gaps.md` | a hand-kept deferred/undone list, now sections A–H | nothing; it is the *record ledger* this review diffs against |
| `docs/fidelity-review.md` | wire-protocol fidelity in `rpc/**` (projection bugs, comment lies, dead branches) | the surfaces above `rpc/**`: composer, settings, sessions, models, themes, ops |

Method, and the two rules that produced the useful parts of this document:

1. **What pi does** is quoted from pi's source (`packages/…:<line>`) or its docs, never from memory.
2. **What the app does** is read out of the app's Kotlin. **A comment that claims a feature is a
   claim, not evidence.** Several findings below exist precisely because a file's own doc comment
   asserts behaviour its code does not implement (e.g. `PiPalette.kt:16-22`, `SettingsHome.kt:80-82`).

## 0. Snapshot reviewed, and the moving tree

| | Value |
|---|---|
| pi (the specification) | `/root/pi-src` @ `bbb61e34aaf231639fdaaad1adbd757947034eac`, `packages/coding-agent` version 0.85.1 |
| App (graded) | `/root/pi-android` @ **`a7b7738`**, working tree dirty: `M app/src/main/kotlin/app/pi/ui/render/PiLatex.kt`, `M docs/known-gaps.md` |
| Review time | 2026-09-10 18:25 UTC |
| Build | none (no Gradle, no APK, no emulator, per the task) |

**The tree moved while this review ran, and that is recorded here because it changes how to read
every row.** When this pass started the app was at `e8bffc0` with 13 modified paths and
`app/src/main/kotlin/app/pi/ui/chat/` + `app/src/main/kotlin/app/pi/packages/` untracked; by the end
it was at `a7b7738` with almost everything committed. Two consequences:

- **Findings were re-verified at `a7b7738`.** One early finding (no global "expand all tool output"
  toggle) was **fixed by another agent mid-pass** and has been removed rather than reported. Treat a
  row here as true at `a7b7738`; re-check before acting.
- **Files mid-write can look broken.** `bridge/DeviceShizuku.kt` and `ui/device/DeviceCapabilityScreen.kt`
  were half-finished during part of this pass, and `PiSessionViewModel.kt` changed under the reviewer
  twice. Rows that depend on a concurrently-edited file say so and are marked **UNVERIFIED** where the
  churn makes the answer unstable.

Content hashes of the files this document cites (so a later reader can tell whether a citation still
points at the code it was written against):

```
5153ce3b9a538144ec9e3f7e5d9aaaf4  app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt      (1524 lines)
bfd7abaf554db00e65ecb6e20d31f810  app/src/main/kotlin/app/pi/ui/screens/ChatScreen.kt     (736 lines)
dd49e3e44e6ff3ebfc2880c9ec33ecf5  app/src/main/kotlin/app/pi/ui/chat/PiSlashCommands.kt
ea850f74e6e6722779334d3b0c4068cb  app/src/main/kotlin/app/pi/ui/chat/SessionTreeScreen.kt  (326 lines)
0d25783c446f2c4191c2a5f1802be8b1  app/src/main/kotlin/app/pi/ui/theme/PiPalette.kt
ee845e9b3d46568c95153bfc1e51b7a4  app/src/main/kotlin/app/pi/ui/settings/PiSettingEditorHost.kt
cd0b075f9e90e9307b485d9f1c5703b6  app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt
21155f528b1150d3b1fe16a149b1b121  app/src/main/kotlin/app/pi/ui/blocks/BlockRenderer.kt
a1d44844c026a94c62fa31eb3884e7f3  app/src/main/kotlin/app/pi/engine/PiEngineHost.kt
2e10fac32464fd3266e76809dcf6fd77  app/src/main/kotlin/app/pi/ui/settings/SettingsGroupScreen.kt
af8799828bc480c734c03e998724c4e4  rpc/src/main/kotlin/app/pi/rpc/Commands.kt               (279 lines)
```

Vocabulary used in the tables, strictly:

| Classification | Meaning |
|---|---|
| **IMPLEMENTED** | The GUI reaches the capability and the code that performs it was read. |
| **PARTIAL** | Reachable, but with a demonstrable reduction (a field dropped, an option not offered, a value ignored). |
| **MISSING-GUI** | pi exposes it to a protocol client (or the app itself declares it); the app has no working path. Achievable. |
| **MISSING-TERMINAL-ONLY** | pi itself does not expose it to RPC clients — not in the `RpcCommand` union and not dispatchable through `prompt`. Only pi's own TUI has it. |
| **CLI-ONLY** | A terminal/CLI operation with no protocol form that the app must reproduce another way (settings file, engine argv, or the terminal tab). |
| **N/A** | Not meaningful for this host, or not a capability gap. |
| **UNVERIFIED** | The app's status could not be demonstrated from its code. The evidence that would settle it is named. |

"Can the GUI do it?" is answered **Yes / Partly / No / Terminal tab / N/A**.

---

## 1. The gap table

### 1.1 Every RPC command (`docs/rpc.md`, `src/modes/rpc/rpc-types.ts:20-74`)

The app has a builder for all 33 commands (`rpc/src/main/kotlin/app/pi/rpc/Commands.kt`, cited by
line below). `fidelity-review.md` already verified *field-level* fidelity for all 33
(`docs/fidelity-review.md:199-206`), so this table asks the different question: **is it reachable
from the GUI?** The typed façade is `app/src/main/kotlin/app/pi/engine/PiEngineApi.kt`; the user-facing
actions are in `PiSessionViewModel.kt`; the only screen that invokes them is `ChatScreen.kt`.

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| `prompt` (text) | `docs/rpc.md` §prompt; `rpc-types.ts:22` | builder `Commands.kt:46`; `PiEngineSession.prompt` (`PiEngineSession.kt:182-190`); `PiSessionViewModel.send` (`.kt:1049`); `ChatScreen.kt:415` | IMPLEMENTED | Yes | — |
| `prompt` **`images`** | `rpc-types.ts:22`; `docs/rpc.md` "With images" | builder + plumbing exist (`Commands.kt:46-57,265-278`, `PiImage` at `Commands.kt:13`, `PiEngineSession.kt:182-190`, `PiSessionViewModel.send(text, images)`); **no GUI ever passes a non-empty list** | PARTIAL | No | `known-gaps.md` **E3** |
| `prompt` `streamingBehavior: steer` | `rpc-types.ts:22` | `PiSessionViewModel.send` while streaming (`.kt:1053-1063`) | IMPLEMENTED | Yes | — |
| `prompt` `streamingBehavior: followUp` | `rpc-types.ts:22` | the `StreamingBehavior.FollowUp` enum value exists (`Commands.kt:21`) but is used **only** through `follow_up`; it is never sent on `prompt` | PARTIAL | No | — |
| `steer` | `docs/rpc.md` §steer; `rpc-types.ts:23` | builder `Commands.kt:59`; used by `send` while streaming | IMPLEMENTED | Yes | — |
| `follow_up` | `docs/rpc.md` §follow_up; `rpc-types.ts:24` | builder `Commands.kt:75`; `PiSessionViewModel.sendFollowUp` at `.kt:1086` — **zero call sites in the whole tree** (verified: only the declaration matches) | **MISSING-GUI** | No | — |
| `abort` | `rpc-types.ts:25` | builder `Commands.kt:86`; sent inside `PiEngineSession.stopAndDrainQueue` (`:202`), reached by `PiSessionViewModel.stop` (`.kt:1097`) ← `ChatScreen.kt:433` | IMPLEMENTED | Yes | — |
| `clear_queue` | `docs/rpc.md` §clear_queue; `rpc-types.ts:26` | builder `Commands.kt:88`; `stopAndDrainQueue` (`PiEngineSession.kt:197-204`) returns the queued text | PARTIAL — the returned text is **discarded** | Partly | — (see §2.3) |
| `new_session` | `rpc-types.ts:27` | builder `Commands.kt:91`; `PiSessionViewModel.newSession` (`.kt:1283`); `ChatScreen.kt:196,259` | IMPLEMENTED | Yes | — |
| `new_session` `parentSession` | `rpc-types.ts:27` | parameter exists (`Commands.kt:91`, `PiEngineApi.kt:295`); no GUI caller passes it (`api.newSession()` at `.kt:1290`) | 已实现（本轮接上） | No | `PiSessionViewModel.newSession(parentSession)` + `newChildSession` ← `SessionsScreen` 长按「新建子会话」；guest 路径经 `guestSessionPath` |
| `get_state` | `rpc-types.ts:30` | builder `Commands.kt:99`; `refreshState` (`.kt:875`), polled after model change and on attach | IMPLEMENTED | Yes | — |
| `get_messages` | `rpc-types.ts:71` | builder `Commands.kt:101`; `PiEngineApi.getMessages` (`PiEngineApi.kt:60`) — **no caller**; the transcript is projected from `get_entries` + events instead | PARTIAL | No | — (no user-visible loss; see §4) |
| `set_model` | `rpc-types.ts:33` | builder `Commands.kt:105`; `PiSessionViewModel.setModel` (`.kt:1143`) ← `ModelPickerSheet` (`ChatScreen.kt:445`) and the AppBar chip (`:253`) | IMPLEMENTED | Yes | — |
| `cycle_model` | `rpc-types.ts:34` | builder `Commands.kt:112`; `cycleModel` (`.kt:1160`) ← overflow row (`ChatScreen.kt:284`) | IMPLEMENTED | Yes | — |
| `get_available_models` | `rpc-types.ts:35` | builder `Commands.kt:114`; `refreshModels` (`.kt:913`) feeding the picker | IMPLEMENTED | Yes | — |
| `set_thinking_level` | `rpc-types.ts:38` | builder `Commands.kt:118`; `setThinkingLevel` (`.kt:1052`) ← `ThinkingPickerSheet` (`ChatScreen.kt:452`) | IMPLEMENTED | Yes | — |
| `cycle_thinking_level` | `rpc-types.ts:39` | builder `Commands.kt:124`; `cycleThinkingLevel` (`.kt:1057`) ← composer chip tap (`ChatScreen.kt:396`) | IMPLEMENTED | Yes | — |
| `get_available_thinking_levels` | `rpc-types.ts:40` | builder `Commands.kt:126`; `refreshThinkingLevels` (`.kt:903`) | IMPLEMENTED | Yes | — |
| `set_steering_mode` | `rpc-types.ts:43` | builder `Commands.kt:131`; `setSteeringMode` (`.kt:1116`) ← `SessionToolsSheet` | IMPLEMENTED | Yes | — |
| `set_follow_up_mode` | `rpc-types.ts:44` | builder `Commands.kt:137`; `setFollowUpMode` (`.kt:1123`) ← `SessionToolsSheet` | IMPLEMENTED | Yes | — |
| `compact` | `rpc-types.ts:47` | builder `Commands.kt:145`; `compact` (`.kt:1135`) ← `/compact` and the tools sheet; `customInstructions` passed when args are given (`ChatScreen.kt:214`) | IMPLEMENTED | Yes | — |
| `set_auto_compaction` | `rpc-types.ts:48` | builder `Commands.kt:151`; `setAutoCompaction` (`.kt:1145`) ← tools sheet | IMPLEMENTED | Yes | — |
| `set_auto_retry` | `rpc-types.ts:51` | builder `Commands.kt:159`; `setAutoRetry` (`.kt:1152`), seeded from settings at attach (`.kt:973-977`) | IMPLEMENTED | Yes | — |
| `abort_retry` | `rpc-types.ts:52` | builder `Commands.kt:165`; `abortRetry` (`.kt:1160`) ← tools sheet | IMPLEMENTED | Yes | — |
| `bash` + `excludeFromContext` | `rpc-types.ts:55`; `docs/rpc.md` §bash | builder `Commands.kt:169`; `runBash` (`.kt:1175`) ← `!` / `!!` composer routing (`SlashPalette.kt:212-216`, `ChatScreen.kt:418`) ← `BashPanel` | IMPLEMENTED | Yes | — |
| `abort_bash` | `rpc-types.ts:56` | builder `Commands.kt:177`; `abortBash` (`.kt:1204`) ← `BashPanel` stop (`ChatScreen.kt:363`) | IMPLEMENTED | Yes | — |
| `get_session_stats` | `rpc-types.ts:59` | builder `Commands.kt:181`; `refreshStats` (`.kt:936`) ← `SessionStatsSheet` | IMPLEMENTED | Yes | — |
| `export_html` | `rpc-types.ts:60`; `docs/rpc.md` §export_html | builder `Commands.kt:184`; `exportHtml` (`.kt:1371`) ← overflow + `/export` | **PARTIAL** — always HTML, and a `.jsonl` argument silently produces HTML | Partly | — (see §2.4) |
| `export_html` `outputPath` | `rpc-types.ts:60` | the argument is accepted but **re-interpreted as a bare file name** and force-prefixed with the guest workspace (`PiSessionViewModel.kt:1372-1374`); an absolute path or `..` cannot be expressed | PARTIAL | Partly | — |
| `switch_session` + `cancelled` | `rpc-types.ts:61` | builder `Commands.kt:190`; `switchSession` (`.kt:1301`) ← `SessionsScreen.kt:110`; the `cancelled` answer is surfaced as a notice (`.kt:1309-1312`) | IMPLEMENTED | Yes | — |
| `fork` + `cancelled` | `rpc-types.ts:62` | builder `Commands.kt:196`; `forkFrom` (`.kt:1319`) ← fork picker and the tree screen | IMPLEMENTED | Yes | — |
| `clone` | `rpc-types.ts:63` | builder `Commands.kt:202`; `cloneSession` (`.kt:1332`) ← overflow + tools sheet | IMPLEMENTED | Yes | — |
| `get_fork_messages` | `rpc-types.ts:64` | builder `Commands.kt:204`; `refreshForkMessages` (`.kt:959`) | IMPLEMENTED | Yes | — |
| `get_entries` + `since` | `rpc-types.ts:65` | builder `Commands.kt:211`; `PiEngineApi.getEntries` (`:71`); used on attach/replay (`.kt:853-874`) | IMPLEMENTED | Yes | — |
| `get_tree` | `rpc-types.ts:66` | builder `Commands.kt:217`; `refreshTree` (`.kt:950`) ← `SessionTreeScreen` | IMPLEMENTED | Yes | — |
| `get_last_assistant_text` | `rpc-types.ts:67` | builder `Commands.kt:219`; `copyLastAssistantText` (`.kt:1396`) ← `/copy` and overflow (`ChatScreen.kt:552`) | IMPLEMENTED | Yes | — |
| `set_session_name` | `rpc-types.ts:68` | builder `Commands.kt:221`; `renameSession` (`.kt:1350`) ← rename dialog | IMPLEMENTED | Yes | — |
| `get_commands` | `rpc-types.ts:74` | builder `Commands.kt:229`; `refreshCommands` (`.kt:950-957`) → `piCommandPalette` (`PiSlashCommands.kt:162`) → `SlashPalette` | IMPLEMENTED | Yes | — |
| Extension UI requests (`select`/`confirm`/`input`/`editor`/`notify`/`setStatus`/`setWidget`/`setTitle`/`set_editor_text`) | `docs/rpc.md` §Extension UI Protocol | `PiSessionViewModel.onExtensionUi/onExtensionChrome` (`.kt:497-625`); rendered by `ui/extension/**` | IMPLEMENTED | Yes | `extension-compatibility.md` §1.2, §6.1 |

**Subtotal (RPC, 39 rows):** IMPLEMENTED 31 · PARTIAL 7 · MISSING-GUI 1 · MISSING-TERMINAL-ONLY 0 · CLI-ONLY 0 · N/A 0.
All 33 commands carry a builder and 32 of the 39 rows are fully reachable; the reductions are the
images/`followUp`/`parentSession`/`outputPath`/`get_messages` cells above.

### 1.2 pi's CLI surface (`src/cli/args.ts`, `src/package-manager-cli.ts`, `docs/usage.md`)

Flags were enumerated mechanically from `src/cli/args.ts` (50 flags). The question per flag is
whether the command is *protocol-reachable* (then the app can drive it) or *spawn-time / interactive*
(the app must reproduce it another way).

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| `--mode rpc`, `--session-dir`, `--name`, `--thinking`, `--provider`, `--model` | `src/cli/args.ts`; `docs/rpc.md:9-15` | argv is fixed to `--mode rpc --session-dir <agentDir>/sessions` (`PiEngineHost.kt:231-233`); provider/model/name/thinking are set **after** start over RPC (`set_model`/`set_session_name`/`set_thinking_level`) and persisted via `defaultProvider`/`defaultModel`/`defaultThinkingLevel` | IMPLEMENTED (another way) | Yes | — |
| `pi install` / `remove` / `list` / `update` | `package-manager-cli.ts:255-276`; `docs/packages.md:22-39` | no RPC command exists (verified against `rpc-types.ts:20-74`); an app-side implementation is under construction — `app/src/main/kotlin/app/pi/packages/` (`PiPackageService.kt`, `PiPackagesScreen.kt`, `PiListOutput.kt`) | CLI-ONLY (being reproduced) | Partly | `known-gaps.md` **B5**, **E7** |
| `pi config` (resource enable/disable TUI) | `package-manager-cli.ts:278-289` | **reproduced (applied, uncommitted)**: `packages/PiPackageFilters.kt` parses/serialises the object form (empty array → key dropped, all four empty → back to the bare source string), `packages/PiPackageFilterStore.kt` reads and writes `packages` through the app's existing `PiSettingsFileStore`, `PiPackageEntry.filters` carries them into the UI, and `PiPackagesScreen`'s `FilterEditor` adds/removes one glob per resource type. pi's own `packages[].autoload` row is unchanged | IMPLEMENTED (uncommitted) | Yes | pi 落盘 = `settings.json` 的 `packages[]` 对象形式的 `extensions/skills/prompts/themes` glob 数组，全空时塌回字符串（`modes/interactive/components/config-selector.ts:585-629`）。**一处有意偏离**：`autoload` 存在时不塌回裸字符串——pi 的 `:623-628` 只看四个数组，会顺手丢掉 `autoload` |
| `--offline` / `PI_OFFLINE` | `args.ts`; `docs/environment-variables.md` | **reproduced**: the `app.runtime.offline` Switch row → `PiSessionViewModel.launchOptions()` → `PiLaunchOptions.offline` → `PI_OFFLINE=1` in the engine environment (`PiLaunchOptions.environment()`, applied by `PiEngineHost` via `boot(…, launch)`) | IMPLEMENTED | Yes | — (see §2.11) |
| `--system-prompt` / `--append-system-prompt` | `args.ts` | **reproduced**: the `app.runtime.systemPrompt` Text row → `PiSessionViewModel.launchOptions()` → `PiLaunchOptions.systemPrompt` → `--system-prompt`; blank means "use pi's own prompt", so no empty flag is ever passed | IMPLEMENTED | Yes | — (see §2.11) |
| `--api-key` | `args.ts`; `docs/providers.md:62-107` | no writer for `auth.json` anywhere in the app (grep: 0 hits outside a description string); `/login` is marked `TerminalOnly` (`PiSlashCommands.kt:138`) and the Settings rows `app.credentials.apiKey` / `app.credentials.oauth` are inert Action rows | CLI-ONLY | Terminal tab | — (see §2.9, §2.10) |
| `--continue` / `-c`, `--resume`, `--session`, `--session-id`, `--fork` | `args.ts`; `docs/sessions.md:39` | `boot()` starts a fresh engine and `attach()` never sends `switch_session` (`PiSessionViewModel.kt:341-361`); resume/switch/fork exist as explicit user actions. **`-c` (resume the most recent session for this cwd) has no counterpart** — every launch begins a new session | MISSING-GUI | No | — (see §2.7) |
| `--list-models`, `--export` | `args.ts` | reproduced by `get_available_models` (picker) and `export_html` | IMPLEMENTED (another way) | Yes | — |
| `--models`, `--tools`, `--no-tools`, `--no-builtin-tools`, `--exclude-tools` | `args.ts` | reproduced by the `enabledModels` and `defaultTools` settings; *reading/setting the live tool set* is not protocol-reachable | IMPLEMENTED (startup) / PARTIAL (live) | Partly | `known-gaps.md` **F2** |
| `--extension`, `--skill`, `--prompt-template`, `--theme`, `--no-extensions`, `--no-skills`, `--no-prompt-templates`, `--no-themes`, `--no-context-files`, `--no-session` | `args.ts` | the persistent equivalents are `extensions` / `skills` / `prompts` / `themes` settings (all present, see §1.8) + `app.contextFiles`; the per-run `--no-*` suppression has no app equivalent, and `--no-session` is meaningless (the app always persists) | CLI-ONLY / N/A | No | — |
| `--use-theme` (per-run theme), `--tui-mode`, `--print`, `--verbose`, `--help`, `--version`, `--approve`/`--no-approve` | `args.ts` | interactive/CLI-only or covered by trust; `--print`/`--mode json` are alternative headless modes the app deliberately does not use | N/A | N/A | `extension-compatibility.md` §6 "Explicitly not worth doing" |

**Subtotal (CLI, 11 rows):** IMPLEMENTED 3 · CLI-ONLY 6 · MISSING-GUI 1 · N/A 1.

### 1.3 Interactive TUI features (`docs/keybindings.md`, `docs/tui.md`, slash commands)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Built-in slash commands | `core/slash-commands.ts:19-43` | all **23** built-ins transcribed verbatim into `PI_BUILTIN_SLASH_COMMANDS` (`PiSlashCommands.kt:106-145`); 13 are implemented natively, 10 are `TerminalOnly` | IMPLEMENTED | Yes | — |
| `/` palette incl. extension/template/skill commands, `name:1` suffixes, source tags | `docs/rpc.md` §get_commands; `runner.ts:653-691` | `get_commands` → `piCommandPalette` (`PiSlashCommands.kt:162-183`); suffix + `sourceInfo` tag preserved (`:199-211`) | IMPLEMENTED | Yes | `known-gaps.md` **B1** (now done), `extension-compatibility.md` §5.9/§5.12 |
| `/` interception (a `/cmd` must not become a prompt) | `interactive-mode.ts:2980+` | `routeComposerText` (`SlashPalette.kt:206-232`) classifies Message / Bash / Command / Unreachable / Unknown before sending | IMPLEMENTED | Yes | `extension-compatibility.md` §6.4 (now done) |
| bash mode `!` / `!!` | `keybindings.md` `app.tools.expand` neighbours; `interactive-mode.ts` bash mode | `SlashPalette.kt:212-216`, composer border token (`ChatScreen.kt:670-676`), `BashPanel` | IMPLEMENTED | Yes | `known-gaps.md` **B4** (now done) |
| queue editing: restore queued text on Esc | `docs/rpc.md` §clear_queue; `keybindings.md` `app.message.dequeue` | `stopAndDrainQueue` returns the text (`PiEngineSession.kt:197-204`) but `ChatScreen.kt:433` calls `session.stop()` **without** `onRestored`, dropping it | **MISSING-GUI** | No | — (see §2.3) |
| queue a follow-up message (`alt+enter`) | `keybindings.md` `app.message.followUp` | `sendFollowUp` has no caller (§1.1) | MISSING-GUI | No | — (see §2.2) |
| expand/collapse all tool output (`ctrl+o`) | `keybindings.md` `app.tools.expand` | overflow row "展开全部工具输出 / 收起全部工具输出" toggles `toolsExpanded` (`ChatScreen.kt:146,291-292,347`) | IMPLEMENTED | Yes | — (fixed mid-pass) |
| expand/collapse thinking (`ctrl+t`) | `keybindings.md` `app.thinking.toggle` | no global toggle (`thinkingDefaultExpanded = false` hard-coded at `ChatScreen.kt:348`); per-block chevrons only | PARTIAL | Partly | — (see §2.5) |
| `hideThinkingBlock` (hide thinking entirely) | `settings-manager.ts:119`; `docs/settings.md` §UI & Display | `BlockRenderer` accepts `hideThinking` (`BlockRenderer.kt:39,53-55`) but `ChatScreen` never passes it (grep `hideThinking` in `ChatScreen.kt`: **0**) | MISSING-GUI | No | — (see §2.5) |
| transcript search (`ctrl+shift+f`) | `keybindings.md` `tui.altScreen.search`; search panel in fullscreen mode | no search UI anywhere in `ui/` (grep `SearchBar`/`rememberSearch`: 0 hits) | **MISSING-GUI** | No | — (see §2.6) |
| jump to previous/next message (`ctrl+shift+up/down`) | `keybindings.md` `tui.altScreen.previousPrompt` | no such control | MISSING-GUI | No | — (low value; grouped with §2.6) |
| model picker / cycle (`ctrl+l`, `ctrl+p`) | `keybindings.md` §Models and Thinking | chip + picker + overflow cycle (`ChatScreen.kt:230-233,284`) | IMPLEMENTED | Yes | — |
| thinking picker / cycle (`shift+tab`) | `keybindings.md` | composer chip tap = cycle, long-press = picker (`ChatScreen.kt:390-400`) | IMPLEMENTED | Yes | — |
| `/tree` navigation: **switch the active leaf** | `docs/sessions.md:71`; `interactive-mode.ts:5216-5322` (incl. optional branch summarization) | **not in the `RpcCommand` union** (`rpc-types.ts:20-74` — the only session-tree commands are `get_tree`, `fork`). The app renders the tree and offers only **分支 (fork)**, which writes a *new session file* (`SessionTreeScreen.kt:74,165,226`) | **MISSING-TERMINAL-ONLY** | No (fork is not a substitute) | — (see §2.1) |
| tree filters (`treeFilterMode`, `ctrl+t/u/l/a/o`) | `docs/sessions.md:100`; `keybindings.md` §Tree Navigation | `SessionTreeScreen` has no filter control; `treeFilterMode` is only a settings value pi reads | MISSING-GUI | No | — (see §2.6) |
| tree labels (`setLabel`, edit label `shift+l`) | `keybindings.md` `app.tree.editLabel` | labels are **rendered** (`SessionTreeScreen.kt:197-203`) but cannot be set — no RPC command | MISSING-TERMINAL-ONLY | No | `known-gaps.md` **F2** |
| session picker: search / sort / named filter / rename / delete | `docs/sessions.md:43-48`; `keybindings.md` `app.session.toggle*`, `.rename`, `.delete` | `SessionsScreen` lists, refreshes and opens only (`SessionsScreen.kt:78-132`); `PiSessionStore` exposes only `list` (`PiSessionStore.kt:49`). Rename exists on the Chat overflow; **search, sort, named filter and delete do not exist** | **MISSING-GUI** | No | — (see §2.7) |
| session tree "delete session" + non-invasive variant | `keybindings.md` `app.session.delete*` | absent (see above) | MISSING-GUI | No | — |
| external editor (`ctrl+g`, `externalEditor`) | `keybindings.md` `app.editor.external` | `externalEditor` is a settings row only (`PiSettingsRegistry.kt:1288-1290`); no launch action; works inside the terminal tab because pi reads the setting | 已实现（ACTION_EDIT 映射） | Terminal tab | pi: 临时 `prompt.md` + spawn；**退出码≠0 丢弃**（`modes/interactive/external-editor.ts:14-52`），命令解析 `settings-manager.ts:970-981`，触发 `app.editor.external`（`interactive-mode.ts:4246-4261`）。App: `ChatScreen.openInExternalEditor` → `ACTION_EDIT`(text/plain)；RESULT_OK 回写、取消保留草稿、无应用处理时 notifyUser 说明（对应 pi 的 spawn 失败） |
| scoped models selector (`/scoped-models`) | `core/slash-commands.ts:24`; `keybindings.md` §Scoped Models Selector | marked `TerminalOnly` (`PiSlashCommands.kt:117-120`); the underlying `enabledModels` setting **is** editable as a raw list, but there is no selector | PARTIAL | Partly | — (see §2.6) |
| `/import` (import a session JSONL) | `interactive-mode.ts:2997`; `docs/sessions.md` | `TerminalOnly` in the palette (`PiSlashCommands.kt:125-128`); the `app.sessions.import` Action row is inert (`PiSettingsRegistry.kt:715-722`); no RPC command exists | MISSING-TERMINAL-ONLY | Terminal tab | — (see §2.9) |
| `/share` (secret GitHub gist) | `core/slash-commands.ts:27`; `docs/environment-variables.md` (`PI_SHARE_VIEWER_URL`) | `TerminalOnly`; no RPC command | MISSING-TERMINAL-ONLY | Terminal tab | — (see §2.9) |
| `/login`, `/logout` | `docs/providers.md:17,26` | `TerminalOnly` (`PiSlashCommands.kt:138-139`); Settings rows inert | MISSING-TERMINAL-ONLY | Terminal tab | — (see §2.9) |
| `/reload` | `docs/extensions.md` §Reloading; no RPC command | AppBar button deliberately reports "terminal only" (`ChatScreen.kt:236-239,542-549`) | MISSING-TERMINAL-ONLY | Terminal tab | `known-gaps.md` **B6**, `extension-compatibility.md` §4.5 |
| `/changelog`, `/hotkeys`, `/quit` | `core/slash-commands.ts:31-32,42` | `TerminalOnly`; `hotkeys` has no meaning on a phone and `quit` is meaningless (the app owns the engine) | N/A / MISSING-TERMINAL-ONLY | Terminal tab | — |
| custom keybindings (`keybindings.json`) | `docs/keybindings.md` §Custom Configuration | a phone has no keybindings to rebind; `app.interaction.keybindings` is a settings row with **no reader** | N/A | N/A | — |

**Subtotal (TUI, 26 rows):** IMPLEMENTED 7 · PARTIAL 3 · MISSING-GUI 8 · MISSING-TERMINAL-ONLY 7 · N/A 1.

### 1.4 Themes (`docs/themes.md`)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Built-in `dark` / `light` | `docs/themes.md:14`; `src/modes/interactive/theme/dark.json`, `light.json` | transcribed token-for-token into `PiPalette.Dark` / `PiPalette.Light` (`PiPalette.kt:112-249`); `PiTheme(dark=…)` selects them | IMPLEMENTED | Yes | — |
| Automatic `lightTheme/darkTheme` pair | `docs/themes.md:35-40`; `theme.ts:582-597` | the setting round-trips the literal string unsplit (`PiSettingsStore.kt:19-23`, `PiSettingsEditors.kt:56,596`) and `MainActivity.kt:35-40` follows the system appearance for the `a/b` form | IMPLEMENTED | Yes | `fidelity-review.md` §"Settings registry" |
| Select a theme by name | `docs/themes.md:24-31` | `theme` is a Value row with a dedicated editor sheet (`PiSettingEditorHost.kt:26-35`) and the write is echoed to the UI (`SettingsGroupScreen.kt:130`) | IMPLEMENTED | Yes | — |
| **A user theme JSON changes the app's own colours** | `docs/themes.md:56-90` (theme format); `resource-loader.ts:815,821,875` (locations) | **`PiPalette` has exactly two hard-coded instances and nothing parses a theme file** (grep for theme-JSON reading in `app/src/main`: 0 hits). `MainActivity.kt:35-40` ignores any name that is not `light`/`dark`/`a/b`. `PiPalette.kt:16-22` *claims* "the app's palette should BE the user's pi theme" — it is not | **MISSING-GUI** | No | — (see §2.4) |
| Theme **discovery** from `<agentDir>/themes/*.json` | `resource-loader.ts:815`; `docs/themes.md:14-18` | **reproduced**: `PiThemeFiles.discover` scans `~/.pi/agent/themes`, `<workspace>/.pi/themes` **and** the `themes` settings array, so a theme dropped in pi's default global directory does appear in the picker | **IMPLEMENTED** | Yes | — (see §2.4) |
| Theme discovery from `.pi/themes/*.json` (project) | `resource-loader.ts:821,875` | same as above | PARTIAL | Partly | — |
| Themes from packages (`themes/`, `pi.themes`) | `docs/themes.md:17` | same as above | PARTIAL | Partly | — |
| `--theme` / `--no-themes` | `args.ts` | CLI-only; persistent forms exist as settings | CLI-ONLY | No | — |
| Theme used by `export_html` | `agent-session.ts:3463-3466` | engine-side; the app passes no `themeName` (not in the RPC shape) | N/A | N/A | `extension-compatibility.md` §5.11 |

**Subtotal (themes, 9 rows):** IMPLEMENTED 3 · PARTIAL 3 · MISSING-GUI 1 · CLI-ONLY 1 · N/A 1.

### 1.5 Sessions (`docs/sessions.md`, `docs/session-format.md`)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Sessions as JSONL, grouped by cwd | `docs/sessions.md:7`; `session-manager.ts:479` | `PiSessionStore.list` reads pi's group directories; `encodedCwdFromGroupName` (`PiSessionStore.kt:152-153`) matches pi's `--<cwd with / \ : → ->--` scheme exactly | IMPLEMENTED | Yes | — |
| Byte-compatibility with pi's session format | `docs/session-format.md`; `session-manager.ts:42-142` | pi **owns all writes** — the app's store is read-only (`PiSessionStore.kt:22-25,49`) and parses headers/messages via `rpc/SessionEntries.kt` (verified field-by-field in `fidelity-review.md` §"Session entries") | N/A (no app-side writer) | N/A | `fidelity-review.md` |
| Tree view (`/tree`) | `docs/sessions.md:69-105` | `get_tree` rendered in two tabs, with labels resolved (`SessionTreeScreen.kt:55-57,96-110`) | IMPLEMENTED | Yes | `known-gaps.md` **B2** (now done) |
| Switch branch **in place** | `docs/sessions.md:71`; `interactive-mode.ts:5216-5322` | no RPC command; the app offers fork only | MISSING-TERMINAL-ONLY | No | — (see §2.1) |
| Branch summarization when leaving a branch | `docs/sessions.md:131` | requires the in-place switch above; unreachable | MISSING-TERMINAL-ONLY | No | — (see §2.1) |
| Fork from a user message | `docs/sessions.md:31`; `agent-session-runtime.ts:274-287` | `fork` guarded by pi's own user-message condition in the UI (`SessionTreeScreen.kt:57-61`) | IMPLEMENTED | Yes | — |
| Clone current branch | `docs/sessions.md:32` | `cloneSession` | IMPLEMENTED | Yes | — |
| Switch / resume a session | `docs/sessions.md:26,39` | `SessionsScreen` → `switch_session`, with `cancelled` surfaced | IMPLEMENTED | Yes | — |
| Rename | `docs/sessions.md:47` | `set_session_name` via the rename dialog | IMPLEMENTED | Yes | — |
| **Delete** a session | `docs/sessions.md:48`; `keybindings.md` `app.session.delete` | absent everywhere (grep: no delete in `session/` or `screens/`) | MISSING-GUI | No | — (see §2.7) |
| **Search / sort / named-only filter** in the picker | `docs/sessions.md:43`; `keybindings.md` `app.session.toggle*` | absent (`SessionsScreen.kt:78-132`) | MISSING-GUI | No | — (see §2.7) |
| Resume most recent session on launch (`pi -c`) | `args.ts` `--continue`; `docs/sessions.md:39` | `boot()` → `attach()` performs no switch (`PiSessionViewModel.kt:341-361`) | MISSING-GUI | No | — (see §2.7) |
| Export to HTML | `docs/sessions.md:34` | `export_html` + `exportHtml` | IMPLEMENTED | Yes | — |
| Export to JSONL | `agent-session.ts:3482-3488` (`exportToJsonl`); TUI `.jsonl` branch at `interactive-mode.ts:6064-6065` | **RPC exposes only `export_html`** (`rpc-types.ts:60`; `rpc-mode.ts:600-602` calls `exportToHtml` unconditionally). The app always calls `export_html` (`PiSessionViewModel.kt:1371-1376`), so a `.jsonl` path yields **HTML bytes under a `.jsonl` name** | **MISSING-GUI** (capability) + defect | No | — (see §2.4) |
| Import from a JSONL file | `interactive-mode.ts:2997` | no RPC command; palette row `TerminalOnly`; Action row inert | MISSING-TERMINAL-ONLY | Terminal tab | — (see §2.9) |
| Entry log (`get_entries`) incl. extension state | `docs/rpc.md` §get_entries | rendered as the 条目 tab, which is the only place `pi.appendEntry` state is visible (`SessionTreeScreen.kt:57-63`) | IMPLEMENTED | Yes | `known-gaps.md` **B2** |
| Per-cwd session **grouping** in the UI | `docs/sessions.md:7` | cwd is shown as a per-row path string (`SessionsScreen.kt:170-179`) but rows are one flat list, not grouped/filterable by project | PARTIAL | Partly | — |

**Subtotal (sessions, 17 rows):** IMPLEMENTED 8 · PARTIAL 1 · MISSING-GUI 4 · MISSING-TERMINAL-ONLY 3 · N/A 1.

### 1.6 Models, providers, thinking (`docs/models.md`, `docs/providers.md`)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Model catalog from the provider | `docs/rpc.md` §get_available_models | `refreshModels` → `ModelPickerSheet` | IMPLEMENTED | Yes | — |
| Switch / cycle model | `docs/rpc.md` §set_model, §cycle_model | picker + chip + overflow cycle | IMPLEMENTED | Yes | `known-gaps.md` **B3** (now done) |
| Thinking levels, `off`…`max`, model-derived | `docs/rpc.md` §get_available_thinking_levels; `ThinkingLevel` at `packages/agent/src/types.ts:301` | levels are wire strings, never a client enum (`PiEngineApi.kt:112-123`); picker + cycle | IMPLEMENTED | Yes | `known-gaps.md` **B3** |
| Per-model thinking defaults (`modelThinkingLevels`) / thinking budgets | `settings-manager.ts:111,143`; `docs/models.md:259-300` | both settings rows present (see §1.8); pi reads them from `settings.json` | IMPLEMENTED (another way) | Yes | `fidelity-review.md` §"Settings registry" |
| Custom provider via `models.json` | `docs/custom-provider.md`; `docs/providers.md:3` | file-based: a `models.json` in the agent dir is picked up by pi and its models appear through `get_available_models` → the picker. **No editor/browser for the file**, and the `app.localModels.manage` Action row is inert | PARTIAL | Partly | — (see §2.9) |
| OAuth login (`/login`, `/logout`) and `auth.json` | `docs/providers.md:17-26,111,185` | `TerminalOnly`; no `auth.json` writer in the app | MISSING-TERMINAL-ONLY | Terminal tab | — (see §2.9) |
| API-key credentials | `docs/providers.md:62-107` | `app.credentials.apiKey` is an inert Action row | MISSING-GUI | No | — (see §2.9) |
| Provider transport setting | `settings-manager.ts` `transport`; `docs/settings.md` §Network | settings row present | IMPLEMENTED (another way) | Yes | — |
| `provider/auth` events visible to the client | — | `model_select` reaches extensions only (`agent-session.ts:1659-1670`), and `fidelity-review.md` §4 records the dead reducer branch; the app compensates by polling `get_state` | PARTIAL | Yes | `fidelity-review.md` §4, `extension-compatibility.md` §5.3 |

**Subtotal (models, 9 rows):** IMPLEMENTED 5 · PARTIAL 2 · MISSING-GUI 1 · MISSING-TERMINAL-ONLY 1.

### 1.7 Skills, prompt templates, packages, extensions (discovery + surfacing)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Skill discovery (`~/.pi/agent/skills`, `.pi/skills`, packages, `skills` setting) | `docs/skills.md` | agent dir and workspace are exposed 1:1 to the guest (`PiEngineHost.kt:120-124`); the `skills` setting row exists | IMPLEMENTED (pi-side) | Yes | — |
| `/skill:name` invocation | `agent-session.ts:2587` | arrives through `get_commands` with `source: "skill"` and is shown in the palette (`PiSlashCommands.kt:172-176`) | IMPLEMENTED | Yes | — |
| `enableSkillCommands` | `settings-manager.ts:136` | settings row present | IMPLEMENTED (another way) | Yes | — |
| Prompt templates (`prompts` setting, `.md` files) | `docs/prompt-templates.md` | `get_commands` `source: "prompt"` → palette group 模板 | IMPLEMENTED | Yes | — |
| Local extensions (`extensions` setting, `~/.pi/agent/extensions`) | `docs/extensions.md` | `extensions` setting row present; bundled extensions installed to the agent dir (`bridge/DeviceBridgeController.kt:225-241`) | IMPLEMENTED (pi-side) | Yes | `extension-compatibility.md` |
| npm/git packages (`packages`) | `docs/packages.md:22-39` | `packages` + `packages[].autoload` settings exist; a management UI is under construction in `app/src/main/kotlin/app/pi/packages/` | PARTIAL | Partly | `known-gaps.md` **B5**, **E7** |
| Package resource enable/disable (`pi config`) | `package-manager-cli.ts:278-289` | not reproduced | 已实现（applied, uncommitted） | No | 同上：pi 的按资源开关就是 `settings.json` 里 `packages[]` 的四个 glob 数组（`config-selector.ts:585-629`），App 未提供嵌套编辑器 |
| Extension load diagnostics | only `runtime.diagnostics`; no RPC channel | GUI cannot know an extension failed to load | MISSING-TERMINAL-ONLY | No | `known-gaps.md` **F1**, `extension-compatibility.md` §5.6 |
| Extension command collision (`name:1`) | `runner.ts:653-691` | handled | IMPLEMENTED | Yes | `known-gaps.md` **E1** (commands handled; tool-name collisions open) |
| Built-in vs user-installed package distinction | — | not built | MISSING-GUI | No | `known-gaps.md` **E7** |

**Subtotal (skills/packages, 10 rows):** IMPLEMENTED 6 · PARTIAL 1 · MISSING-GUI 1 · MISSING-TERMINAL-ONLY 1 · CLI-ONLY 1.

### 1.8 Settings coverage (`docs/settings.md`, `core/settings-manager.ts:106-158`)

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| pi's whole top-level settings surface | `settings-manager.ts:106-158` | **every top-level key** is in the registry; only `path` and `projectTrusted` are absent (both are project-override/meta fields, not user settings). Independently confirmed by `fidelity-review.md`. My own diff of the registry against the same interface agrees | IMPLEMENTED | Yes | `fidelity-review.md` §"Settings registry" |
| Writes land in pi's own files | `settings-manager.ts:154-181` | global = `<agentDir>/settings.json`, project = `.pi/settings.json`, `app.*` sidecar (`PiSettingsFileStore.kt:160-164`); merge semantics match pi | IMPLEMENTED | Yes | `fidelity-review.md` §"Settings semantics" |
| Settings search | `interactive-mode.ts` `/settings` | `SettingsSearchScreen` + `PiSettingsCatalog` aliases | IMPLEMENTED | Yes | — |
| **Registered settings that nothing reads** | — | 6 appearance/tool rows (`app.appearance.dynamicColor`, `fontScaleDelta`, `messageDensity`, `showTimestamps`, `thinkingCollapsedByDefault`, `app.tools.expandByDefault`) and 7 more (`app.terminal.fontSize`, `.cursorStyle`, `.scrollbackLines`, `.keyBar`, `app.tools.bashTimeoutSeconds`, `outputMaxLines`, `app.runtime.keepAlive`) appear **exactly once in the tree — in the registry**. Editing them changes a JSON file and nothing else | **MISSING-GUI** | No | — (see §2.5) |
| **Action-kind settings rows** | — | **wired.** `PiRoot` passes `onRunAction`, so no Action row can fall through to "还没有接入实现" any more. **8** rows remain (`app.credentials.apiKey` / `.oauth`, `app.localModels.manage`, `app.compaction.runNow`, `app.sessions.import`, `app.security.emergencyStop`, `app.runtime.restartEngine`, `app.about.changelog`); the credential pair opens its own screen via `PiSettingsStack.hostActions`, and the other 11 rows were deleted in the §I2 pass. `SettingsGroupScreen` now says "这个入口当前不可用。" and nothing reaches it | **IMPLEMENTED** | Yes | — (see §2.9) |
| Device capability switches | — | declared in the settings catalog (`app.device.basic/accessibility/sensors/sessionOverride/storage/shell`, `PiSettingsRegistry.kt:1474-1549`) **and** enforced from `DeviceCapabilityStore`'s SharedPreferences (`DeviceCapabilityStore.kt:61-96`). Nothing reads the `app.device.*` keys (grep outside the registry: 0). `SettingsHome.kt:80-82` asserts these "have no key in the catalog" — the catalog contains them | **MISSING-GUI** / coherence defect | Partly (the real screen works) | — (see §2.9) |
| Telemetry settings (`enableInstallTelemetry`, `enableAnalytics`, `trackingId`) | `settings-manager.ts:128-130`; `core/telemetry.ts:9-15` | present and written to `settings.json`, which is exactly where pi reads them (`telemetry.ts:9-15`) | IMPLEMENTED (another way) | Yes | — |
| `httpProxy` / `httpIdleTimeoutMs` / `websocketConnectTimeoutMs` | `settings-manager.ts:151-153`; `docs/environment-variables.md` | present; pi applies `httpProxy` as `HTTP_PROXY`/`HTTPS_PROXY` internally. The app does **not** set those env vars itself (`PiRuntime.kt:137-148`) — which is fine for pi's own clients, but an `npm install` run for a package would not inherit the proxy | IMPLEMENTED (pi-side) / PARTIAL (package installs) | Yes | — |
| Update checks | `docs/environment-variables.md` (`PI_SKIP_VERSION_CHECK`) | pi's own check is disabled (`PiEngineHost.kt:254`) by design; the replacement `app.runtime.checkUpdate` is an inert Action row | MISSING-GUI | No | — (see §2.9) |

**Subtotal (settings, 9 rows):** IMPLEMENTED 5 · MISSING-GUI 4.

### 1.9 Attachments, images, `@` mentions

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| Images into `prompt` | `rpc-types.ts:22`; `docs/rpc.md` §prompt; `session-format.md:53-57` | modelled end-to-end below the UI: `PiImage` (`Commands.kt:13`), `putImages` (`Commands.kt:265-278`), `PiEngineSession.prompt(images)` (`:182-190`), `PiSessionViewModel.send(text, images)` (`.kt:1049`). **No UI constructs a non-empty list** | PARTIAL | No | `known-gaps.md` **E3** |
| Picking an image from the device | pi has no phone picker, but the capability is the RPC field above | no `GetContent` / `PickVisualMedia` / `ACTION_OPEN_DOCUMENT` anywhere (grep: 0 hits) | MISSING-GUI | No | `known-gaps.md` **E3** |
| Pasting an image from the clipboard | `keybindings.md` `app.clipboard.pasteImage` (`ctrl+v`); `utils/clipboard-image.ts`; `interactive-mode.ts:2936` | the app publishes *to* the clipboard (`ChatScreen.kt:552-554`, `TerminalPane.kt:99-105`) but never reads an image in | MISSING-GUI | No | `known-gaps.md` **E3** |
| Rendering attachments in the transcript | `session-format.md:53-57`; `core/messages.ts` | `UserMessageBlock` renders `item.images` (`UserMessageBlock.kt:47-49`) via `ImageGridBlock` — which draws **labelled placeholder cells**, not the image (`ImageGridBlock.kt:30`, `:122`) | PARTIAL | Partly (placeholder) | `known-gaps.md` **A3** (markdown images); the placeholder nature of the attachment grid is **not** called out there |
| `@` file mentions | `utils/paths.ts:17,80` (`stripAtPrefix`); `docs/usage.md` | no `@` handling in the composer (grep for `"@"` handling: 0 hits) | MISSING-GUI | No | `known-gaps.md` **E4** |

**Subtotal (attachments, 5 rows):** PARTIAL 2 · MISSING-GUI 3.

### 1.10 Environment and ops

| Capability | pi source | App status | Classification | GUI? | Recorded where |
|---|---|---|---|---|---|
| `PI_CODING_AGENT_DIR`, `PI_CODING_AGENT_SESSION_DIR` | `docs/environment-variables.md` | both set (`PiEngineHost.kt:250-251`) | IMPLEMENTED | Yes | — |
| `PI_SKIP_VERSION_CHECK` | same | set (`PiEngineHost.kt:254`) | IMPLEMENTED | Yes | — |
| `PI_OFFLINE` / `--offline` | same | **reproduced（复核于 2026-09-12，`2dbe79f`）**：`app.runtime.offline` Switch → `PiSessionViewModel.launchOptions()` → `boot(launch = …)` → `rpc/PiLaunchOptions` 映射成 `PI_OFFLINE`。本行原先写 "not set, no setting"，已过期 | IMPLEMENTED | Yes | `app.runtime.offline` |
| `PI_TELEMETRY` | same | not set; pi falls back to the `enableInstallTelemetry` setting, which the app writes | IMPLEMENTED (another way) | Yes | — |
| `PI_CACHE_RETENTION` | same | **reproduced（复核于 2026-09-12，`2dbe79f`）**：`app.runtime.cacheRetention`（默认/长保留）→ `launchOptions()` → `PiLaunchOptions` 设 `PI_CACHE_RETENTION=long`。本行原先写 "not set, not exposed"，已过期 | IMPLEMENTED | Yes | `app.runtime.cacheRetention` |
| `PI_PACKAGE_DIR` | same | not set; only needed for Nix/Guix-style installs | N/A | N/A | — |
| `HTTP_PROXY` / `HTTPS_PROXY` | same | not set as env; covered for pi's own clients by the `httpProxy` setting | IMPLEMENTED (pi-side) | Yes | — |
| `PI_IMAGE_PROTOCOL`, `PI_TRUE_COLOR`, `PI_HYPERLINKS`, `PI_HARDWARE_CURSOR`, `PI_TUI_ESC_TIMEOUT` | same | set for the TUI tab (`PtyLauncher.kt:277,288,293`, with `none`/`1`/`150`) and exposed as `terminal.*` / `showHardwareCursor` settings | IMPLEMENTED | Yes | `known-gaps.md` **C2** |
| `VISUAL` / `EDITOR` | same | not set; `externalEditor` setting exists and pi prefers it | IMPLEMENTED (another way) | Terminal tab | — |
| `AI_AGENT=pi` / `PI_CODING_AGENT=true` process markers | same | set by pi's own entry point, not the app's responsibility | N/A | N/A | — |
| Shell-tool session env (`PI_SESSION_ID`, `PI_PROVIDER`, …) | same | injected by pi into LLM-callable tools; not the app's responsibility. Not injected into user `!` commands — which is why the app's `!`/`!!` behave like pi's | N/A | N/A | — |
| Engine argv | `docs/rpc.md:7-15` | fixed `--mode rpc --session-dir …` (`PiEngineHost.kt:231-233`); no user-extensible flags, so `--system-prompt`, `--append-system-prompt`, `--api-key`, `--offline`, `-c` are unreachable | PARTIAL | No | — (see §2.11) |
| Runtime/ops diagnostics (`app.runtime.*`) | app-declared | 9 Action rows + 2 settings with no reader (`logViewer`, `exportDiagnostics`, `cleanNpmCache`, `rollback`, `safeMode`, `phantomKillerGuide`, `nodeVersion`, `piVersion`, `rootfsUsage`, `checkUpdate`, `keepAlive`) | MISSING-GUI | No | — (see §2.9, §2.5) |

**Subtotal (env/ops, 13 rows):** IMPLEMENTED 6 · PARTIAL 1 · MISSING-GUI 2 · CLI-ONLY 1 · N/A 3.

### 1.11 Classification totals

Counts below are computed from the tables' classification column, not estimated:

| Classification | 1.1 RPC | 1.2 CLI | 1.3 TUI | 1.4 Theme | 1.5 Sess | 1.6 Model | 1.7 Skills | 1.8 Settings | 1.9 Attach | 1.10 Env | **Total** |
|---|---|---|---|---|---|---|---|---|---|---|---|
| IMPLEMENTED | 31 | 3 | 7 | 3 | 8 | 5 | 6 | 5 | 0 | 6 | **74** |
| PARTIAL | 7 | 0 | 3 | 3 | 1 | 2 | 1 | 0 | 2 | 1 | **20** |
| MISSING-GUI | 1 | 1 | 8 | 1 | 4 | 1 | 1 | 4 | 3 | 2 | **26** |
| MISSING-TERMINAL-ONLY | 0 | 0 | 7 | 0 | 3 | 1 | 1 | 0 | 0 | 0 | **12** |
| CLI-ONLY | 0 | 6 | 0 | 1 | 0 | 0 | 1 | 0 | 0 | 1 | **9** |
| N/A | 0 | 1 | 1 | 1 | 1 | 0 | 0 | 0 | 0 | 3 | **7** |
| *rows* | 39 | 11 | 26 | 9 | 17 | 9 | 10 | 9 | 5 | 13 | **148** |

No row in the §1 tables is graded **UNVERIFIED**: every row's app status was demonstrated from
Kotlin. The items that *could not* be settled are listed separately in §5.

---

## 2. Gaps that are **not recorded anywhere**

"Recorded" means recorded in **any** of `docs/known-gaps.md`, `docs/extension-compatibility.md`, or
`docs/fidelity-review.md` — the union, since `fidelity-review.md` appeared during this pass. Every
item below was checked against all three by keyword and by reading the relevant sections
(`known-gaps.md` §A–H, `extension-compatibility.md` §1–6, `fidelity-review.md` §1–10).

Items that are **already recorded** and are therefore *excluded* from this section (do not re-report
them): image attachment *input* (`known-gaps.md` E3), `@` mentions (E4), lazy engine start (E5),
`setLabel` / toolset / `getSystemPrompt` / extension load diagnostics (F2), TUI-only extension
capabilities incl. `getTheme` (F3), `/reload` (B6), package install/list/remove (B5), trust UI (B7),
slash palette (B1), session tree/fork/clone/switch/rename/export (B2), model & thinking pickers (B3),
bash mode (B4), markdown-rendered compaction/branch/hook blocks (A1), LaTeX (A2), markdown images (A3).

**Eleven findings are unrecorded (2.1–2.11).** Four of them are behavioural defects rather than absent
features (2.2, 2.3, 2.4a, 2.10) — they produce a wrong result quietly, which is why they matter more
than their size suggests.

### 2.1 `/tree` can switch branches; the app cannot, and `fork` is not the same thing

- **pi:** `docs/sessions.md:71` — "`/tree` lets you jump to any previous point and continue from there
  **without creating a new file**"; the implementation is at `interactive-mode.ts:5216-5322`, including
  the optional "Summarize branch?" step and "Navigated to selected point" (`:5316`).
- **Protocol:** there is **no** command to move the active leaf. The full union is
  `src/modes/rpc/rpc-types.ts:20-74`; the only tree-related commands are `get_tree` (`:66`) and
  `fork` (`:62`).
- **App:** `SessionTreeScreen` renders the tree and passes exactly one action, `onFork`
  (`SessionTreeScreen.kt:74,107,118,143,165,226`); `flattenTree` marks a leaf but offers no navigation.
  `fork` writes a **new session file** (`docs/sessions.md:118-127`).
- **Why it matters:** branch switching is the headline reason pi stores sessions as a tree
  (`docs/sessions.md:69-73`). Today, wanting to continue from an earlier point forces a fork, which
  forks the conversation into a second file — an observable behavioural difference, not a missing
  button. Branch summarization (`docs/sessions.md:131`) is unreachable for the same reason.
- **Classification:** MISSING-TERMINAL-ONLY. The honest app-side rendering is "仅终端" plus a pointer
  to the `pi TUI（原版）` tab; `known-gaps.md` F2's list should gain this row.

### 2.2 `follow_up` is dead code in the UI — the composer can only steer

- **pi:** `docs/rpc.md` §follow_up — "Delivered only when agent has no more tool calls or steering
  messages"; the TUI binds it to `alt+enter` (`keybindings.md` `app.message.followUp`). `steer` and
  `follow_up` are *the two different delivery choices* (`rpc-types.ts:23-24`).
- **App:** the command is fully built and typed — `Commands.kt:75`, `PiEngineApi` has no wrapper but
  `PiSessionViewModel.sendFollowUp` exists at `PiSessionViewModel.kt:1086`. **Grep across
  `app/src` + `rpc/src` finds only that declaration**: no screen, sheet, or palette row calls it.
  `ChatScreen.kt:411-431` routes every send through `session.send`, whose streaming branch always
  emits `steer` (`.kt:1053-1063`).
- **Consequence:** a user cannot say "when you're done, also do X" the way pi's `alt+enter` does; every
  mid-turn message is injected *into* the current turn. The `set_follow_up_mode` toggle in
  `SessionToolsSheet` is wired and therefore *looks* like the feature exists — it configures a queue
  nothing can enqueue into.
- **Classification:** MISSING-GUI. Fix is small: a second composer affordance (or long-press on send)
  calling the existing `sendFollowUp`.

### 2.3 Esc drains the queue and then throws the text away

- **pi:** `docs/rpc.md` §clear_queue prescribes the exact client behaviour: "send `clear_queue` before
  `abort`, then **restore the returned text in the client editor**"; the TUI binds it to
  `app.message.dequeue` (`keybindings.md`, `alt+up`).
- **App:** `PiEngineSession.stopAndDrainQueue` implements the prescription and returns the text
  (`PiEngineSession.kt:197-204`), and `PiSessionViewModel.stop(onRestored = {})` forwards it
  (`.kt:1097-1106`). But `ChatScreen.kt:433` is `onStop = { session.stop() }` — the default no-op
  callback, so `restored` is discarded.
- **Consequence:** pressing Stop with queued messages silently destroys what the user queued, in a
  flow whose own code comment (`PiEngineSession.kt:192-196`) says the text is meant to come back to
  the composer. This is the "plumbing exists, GUI drops it" pattern.
- **Classification:** MISSING-GUI.

### 2.4 `/export <path>.jsonl` produces HTML, and custom themes never reach the app's own colours

Two independent theme/export findings, grouped because both are "pi supports it, the app claims it,
the bytes differ".

**(a) JSONL export.** pi has `exportToJsonl` (`agent-session.ts:3482-3488`) and the TUI picks the
implementation from the argument: `interactive-mode.ts:6064-6065` branches on `.jsonl`. RPC has no
JSONL command — `rpc-types.ts:60` is `export_html` only, and `rpc-mode.ts:600-602` calls
`exportToHtml` unconditionally, with no extension check. **Reproduced app-side (re-read in the tree):
`PiSessionViewModel.exportSession` branches on the extension exactly as the TUI does, and
`exportJsonl` writes real JSONL from `get_entries` with pi's own shape (header, branch walked from
`leafId`, re-chained `parentId`, trailing newline).** The old "HTML into `notes.jsonl`" bug is gone;
the gap is bridged app-side rather than faked.

**(b) Theme JSON.** `docs/themes.md:14-18` documents discovery from `<agentDir>/themes/*.json` and
`.pi/themes/*.json` (code: `resource-loader.ts:815,821,875`). **Reproduced (re-read in the tree):**
`PiThemeFiles` discovers, resolves and parses theme files (`discover` / `load` / `parseTheme` /
`parseThemeJson`, including the `a/b` pair, 256-colour indices and var refs), and `MainActivity`
paints `PiTheme(palette = theme.palette, …)`. `PiPalette`'s `Dark` / `Light` constants still exist —
they are now only the **fallback** when no theme file resolves (and the base the file's tokens overlay),
not the app's only colours. The TUI tab and the GUI therefore read the same theme file.

**(c) Theme discovery in the picker.** **Reproduced (re-read in the tree):** `PiThemeFiles.discover`
derives the name list from `~/.pi/agent/themes`, `<workspace>/.pi/themes` **and** the `themes` settings
array (`scanDirectory` × 3 scopes), so a theme dropped in pi's documented default location is visible
to the picker — the GUI can select the very theme the terminal is using.

- **Classification:** (a) (b) (c) **all reproduced** (the grades above were MISSING-GUI / MISSING-GUI /
  PARTIAL at the audited snapshot). The diff handler is gone with them — the fixer note below was
  already satisfied before anyone acted on it.
- **Note for the fixer:** the app already reads `<agentDir>/settings.json` through
  `PiSettingsFileStore` (`PiSettingsFileStore.kt:160-164`), so the guest agent dir is reachable; reading
  `themes/*.json` needs no new channel.

### 2.5 Settings that are displayed, editable, and read by nothing

Two clusters, both purely app-side and both invisible to pi:

**(a) 13 settings with exactly one occurrence in the tree — the registry entry.** Verified by grepping
each key (and its bare suffix) across `app/src` + `rpc/src`: `app.appearance.dynamicColor`,
`app.appearance.fontScaleDelta`, `app.appearance.messageDensity`, `app.appearance.showTimestamps`,
`app.appearance.thinkingCollapsedByDefault`, `app.tools.expandByDefault`, `app.terminal.fontSize`,
`app.terminal.cursorStyle`, `app.terminal.scrollbackLines`, `app.terminal.keyBar`,
`app.tools.bashTimeoutSeconds`, `app.tools.outputMaxLines`, `app.runtime.keepAlive`. Their
descriptions promise behaviour (dynamic colour, font scaling, density, timestamps, terminal font size,
key bar) that no code performs. `PiTheme.kt:82-93` and `PiEngineService.kt:101-119` show the real
behaviour is hard-coded (fixed `sp` values, an unconditional wakelock).

**(b) `hideThinkingBlock` and the thinking expand/collapse default are unwired.** `BlockRenderer`
accepts `hideThinking`, `thinkingDefaultExpanded` and `toolsDefaultExpanded` (`BlockRenderer.kt:39-41`)
and honours them (`:53-59`), and the file's own doc comment maps them to pi's `hideThinkingBlock` and
`app.tools.expand` (`:30-33`). `ChatScreen.kt:345-348` now passes a real `toolsExpanded` (added by
another agent mid-pass — this half is no longer a gap) but hard-codes
`thinkingDefaultExpanded = false` and **never passes `hideThinking`** (`grep hideThinking
ChatScreen.kt` → 0). pi's `hideThinkingBlock` (`settings-manager.ts:119`, shown in
`docs/settings.md` §UI & Display, honoured by pi's TUI) therefore has no effect on the GUI transcript,
and there is no equivalent of `ctrl+t`.

- **Classification:** MISSING-GUI. This is the class of gap that survives every protocol audit,
  because it is above `rpc/**` and has no failing test.
- **Reviewer warning:** the *real* settings pi reads (`defaultTools`, `thinkingBudgets`, …) are
  correctly written to `settings.json`, so "no reader in Kotlin" is **not** a gap for a pi-side key.
  The rule applied here is narrower: the key must be app-namespaced (`app.*`) **or** describe a GUI
  behaviour the app itself must implement.

### 2.6 No transcript search, no tree filters, no session search/sort/named filter

- **pi:** transcript search is a first-class TUI feature with its own bindings and a visible panel
  (`keybindings.md` §TUI Fullscreen Viewport, `tui.altScreen.search` … `searchClose`); the session tree
  has five filter modes (`docs/sessions.md:100`, `keybindings.md` §Tree Navigation); the resume picker
  is searchable and has path/sort/named-only toggles (`docs/sessions.md:43`, `keybindings.md`
  `app.session.togglePath`/`toggleSort`/`toggleNamedFilter`).
- **App:** no search UI exists anywhere in `app/src/main/kotlin/app/pi/ui` (grep for
  `SearchBar`/`rememberSearch`: 0 hits; the only "search" is `SettingsSearchScreen`, which searches
  *settings*). `SessionTreeScreen` renders a flat tree with no filter control.
  `SessionsScreen.kt:78-132` is a plain list. `treeFilterMode` exists as a settings value that only
  pi's own TUI consumes.
- **Classification:** MISSING-GUI. On a phone, a long transcript is the common case, which is why this
  is prioritised in §3 despite being plain work.

### 2.7 The session list cannot delete, and no launch resumes the last session

- **pi:** the picker supports delete (`docs/sessions.md:48`: "delete with Ctrl+D, then confirm";
  `keybindings.md` `app.session.delete`, plus `app.session.deleteNoninvasive`), and `pi -c` continues
  the most recent session for the cwd (`args.ts` `--continue`, `docs/sessions.md:39`).
- **App:** `PiSessionStore` exposes only `list` (`PiSessionStore.kt:49`); grep for a delete operation in
  `session/` and `screens/` finds none, so a session can only be removed by going around the app. And
  `boot()` → `attach()` never issues `switch_session` (`PiSessionViewModel.kt:341-361`), while the
  engine argv carries no `--continue`/`--session` (`PiEngineHost.kt:231-233`), so every app launch
  starts a **new** session — the opposite of `pi -c`'s default of picking up where you left off.
- **Classification:** MISSING-GUI. Delete is a data-loss-adjacent operation and should follow pi's
  confirm step; auto-resume needs a "continue where I left off" default that the app currently lacks.

### 2.8 Extended prompt caching (`PI_CACHE_RETENTION=long`) — **reproduced (2026-09-12)**

> **This heading used to read "has no app path".** It was fixed by `app.runtime.cacheRetention` (a
> 默认/长保留 value row) → `PiSessionViewModel.launchOptions()` → `boot(launch = …)` →
> `rpc/PiLaunchOptions`, which sets `PI_CACHE_RETENTION=long`. Landed in `2dbe79f`. The audit text
> below is kept as the pi-side reference it always was.

- **pi:** `PI_CACHE_RETENTION` set to `long` switches the provider clients from the default short
  cache retention to the long one — `packages/ai/src/api/anthropic-messages.ts:51-57`,
  `openai-completions.ts:305`, `bedrock-converse-stream.ts:792-798`; documented in
  `docs/environment-variables.md` ("Set to `long` for extended provider prompt caching where
  supported").
- **App:** the variable is neither set nor exposed: the engine environment is the fixed map at
  `PiEngineHost.kt:250-258`, and there is no `cacheRetention`-style key in the registry (pi's
  `Settings` interface has none either, so this is env-only by design).
- **Consequence on a phone:** long sessions re-read the whole context at short-cache retention. The
  user cannot opt into the longer-retention mode, and — because the app exposes no
  environment-variable surface at all — there is no workaround short of the terminal tab.
- **Classification:** MISSING-GUI. Cheapest form is a boolean setting that adds
  `PI_CACHE_RETENTION=long` to the spawn environment: the same one-line shape as the `PI_OFFLINE`
  work in §2.11.

### 2.9 All 20 Action rows in Settings are inert, and three capability clusters go with them

`PiRoot.kt:130-138` renders `PiSettingsStack` **without** `onRunAction`, whose default is `null`
(`PiSettingsStack.kt:34`); `SettingsGroupScreen.kt:151-170` then degrades every Action row to a
non-executing dialog reading "这个入口由运行时接管，当前宿主还没有接入对应的实现". The 20 rows
(enumerated from the registry) are: `app.credentials.apiKey`, `app.credentials.oauth`,
`app.localModels.manage`, `app.compaction.runNow`, `app.sessions.import`, `app.sessions.exportAll`,
`app.security.emergencyStop`, `app.device.shellPair`, `app.runtime.checkUpdate`,
`app.runtime.rollback`, `app.runtime.cleanNpmCache`, `app.runtime.phantomKillerGuide`,
`app.runtime.logViewer`, `app.runtime.exportDiagnostics`, `app.about.changelog`,
`app.about.importFromDesktop`, `app.about.exportConfig`, `app.about.rawSettings`, `app.about.reset`,
`app.about.licenses`.

Three of them stand for capabilities that exist elsewhere in pi and have **no working path in the app
at all**:

- **Credentials** — `app.credentials.apiKey` / `app.credentials.oauth`. pi stores them in
  `~/.pi/agent/auth.json` (`docs/providers.md:111,185`) and `/logout` clears them (`:26`). The app
  contains **no** `auth.json` writer (grep: 0 hits outside a description string), and `/login`/`/logout`
  are `TerminalOnly` (`PiSlashCommands.kt:138-139`). So configuring a provider without using the
  terminal tab is impossible, while two Settings rows imply otherwise.
- **Session import** — `app.sessions.import` (`PiSettingsRegistry.kt:715-722`) plus `/import`
  (`PiSlashCommands.kt:125-128`, `TerminalOnly`). pi's `handleImportCommand` is TUI-only
  (`interactive-mode.ts:2997`); no `RpcCommand` imports a file. So importing a JSONL session requires
  the terminal tab.
- **`/share`** — `PiSlashCommands.kt:129` `TerminalOnly`; `core/slash-commands.ts:27`; no RPC command.
  Whether a gist flow can work at all on this host (browser + loopback callback,
  `PI_SHARE_VIEWER_URL`) is **UNVERIFIED** (§5).

**Classification:** MISSING-GUI for the wiring itself; MISSING-TERMINAL-ONLY for import/share; and the
inert rows are a UX defect in their own right — a row that opens a "not wired" dialog teaches the user
that the feature does not exist, when for `app.compaction.runNow` it actually does (via `/compact`).

### 2.10 Device permission switches exist twice, and the Settings copy is disconnected

- **pi:** none of this is pi's — it is the app's own grant surface (`docs/pi-android-app-design.md`
  §21), which is why a mismatch is purely self-inflicted.
- **App:** the enforcement and the real UI both use `DeviceCapabilityStore`, backed by
  SharedPreferences (`DeviceCapabilityStore.kt:61-96`; `DeviceCapabilityScreen.kt:91,210,278`),
  reached from `SettingsHome`'s dedicated entry row (`SettingsHome.kt:86-90`) and
  `PiSettingsStack.kt:71-74`. Separately, `PiSettingsRegistry.kt:1474-1549` declares
  `app.device.basic`, `.storage`, `.accessibility`, `.sensors`, `.shell`, `.shellPair`,
  `.sessionOverride` inside the rendered `G_DEVICE` group ("设备能力"), and **nothing reads those keys**
  (grep for `app.device.` outside the registry: 0 hits) — their only writer is `PiSettingsStore`, i.e.
  `app-prefs.json`.
- **Consequence:** two screens show the same switches, only one of them is connected. A user who turns
  "屏幕读取与操作" on under Settings → 设备能力 gets a persisted `true` in a JSON file that no code
  consults, while the authorization page still reports the capability off (or vice versa). The comment
  at `SettingsHome.kt:80-82` asserts these switches "have no key in the catalog" — they do.
- **Classification:** MISSING-GUI / coherence defect. Two acceptable fixes: delete the registry rows
  and keep one entry point, or make the registry rows a view over `DeviceCapabilityStore`.
- **UNVERIFIED caveat:** `DeviceCapabilityScreen.kt` and `bridge/DeviceShizuku.kt` were being rewritten
  during part of this pass. The registry/store disconnect is static and verified; if a concurrent change
  added a bridge, re-check before acting.

### 2.11 Offline mode and system prompt — **reproduced** (credential override: see §2.9)

- **pi:** `PI_OFFLINE` disables "startup network operations, including update checks, package updates,
  and install/update telemetry" (`docs/environment-variables.md`), and `--offline` is a CLI flag
  (`src/cli/args.ts`). `--system-prompt` / `--append-system-prompt` let a run replace or extend the
  system prompt; `--api-key` supplies credentials at spawn.
- **App (audited snapshot — no longer true):** the engine is spawned with a fixed command (`.kt:231-233`, `--mode rpc --session-dir …`) and
  a fixed environment (`:250-258`: agent dir, session dir, `PI_SKIP_VERSION_CHECK`,
  `PI_ANDROID_BRIDGE_FILE`). There is **no** `PI_OFFLINE`, no `--system-prompt`, no `--api-key`, and no
  setting that produces any of them (grep in `engine/` + `runtime/`: 0 hits each). `PI_SKIP_VERSION_CHECK`
  suppresses *one* pi network call, while the offline switch that suppresses all of them
  (including package updates and telemetry) is absent.
- **Reproduced (re-read in the tree):** `PiSettingsRegistry` registers `app.runtime.offline` (Switch),
  `app.runtime.systemPrompt` (Text) and `app.runtime.cacheRetention` (Value) in a 「进程」section;
  `PiSessionViewModel.launchOptions()` reads all three into `PiLaunchOptions`, and both `boot()` and
  `restartEngine()` pass it down. `PiLaunchOptions.environment()` emits `PI_OFFLINE=1` (omitted, never
  `"0"`) and `PI_CACHE_RETENTION=long`; the flags builder emits `--system-prompt` / `--append-system-prompt`.
  A custom system prompt therefore no longer requires the original TUI, and startup network activity is
  switchable from the phone.
- **Consequence on a phone:** a user who wants the app to make no network requests except the model
  call cannot express that; and the "no surprise network calls from inside a phone app" rationale
  already written at `PiEngineHost.kt:255-256` only holds for the version check it disabled.
  A custom system prompt is likewise impossible without running the original TUI.
- **Classification:** offline and system prompt are **IMPLEMENTED**; `--api-key` is covered by the
  `auth.json` writers (§2.9). The "no setting that produces any of them" reading below is the
  audited snapshot, kept for comparison.

---

## 3. Prioritised: what to build next

Ordered by user-visible impact per unit of work, judged on a phone.

| # | Item | Why first | Ref |
|---|---|---|---|
| 1 | ~~**Wire `onRunAction`** (or delete the inert rows)~~ **done** | Was: 20 rows lying to the user. Now: `PiRoot` passes `onRunAction`, **8** Action rows remain and every one of them has a verdict; the prompt that said "还没有接入实现" is unreachable. Re-check before acting on the old text. | §2.9 |
| 2 | **Fix `/export *.jsonl`** | Currently writes HTML into a `.jsonl` file with a success toast. Either branch client-side to a JSONL-capable path or refuse non-HTML extensions. Silently wrong output is worse than absent output. | §2.4(a) |
| 3 | **Restore drained queue text to the composer** | The plumbing and the documented behaviour already exist; one lambda at `ChatScreen.kt:433`. Today Stop destroys the user's queued messages. | §2.3 |
| 4 | **Wire `follow_up`** | The command, the VM method and the docs all exist; the user has no way to say "after this, also do X". Small, and it makes the already-wired `set_follow_up_mode` meaningful. | §2.2 |
| 5 | **Read the pi theme JSON into the app's palette** | The stated design intent of the whole colour system; a desktop-tuned theme currently changes the terminal tab and not the app. The file lives next to the `settings.json` the app already reads. | §2.4(b) |
| 6 | **Transcript search** | Long transcripts are the normal case on a phone, and scrolling is the only tool today. pi has the feature; nothing about it needs the protocol. | §2.6 |
| 7 | ~~**Contrast "no reader" settings**~~ **done for the 13** | 9 wired (appearance/density/timestamps/tools/keepAlive/hideThinking + `readPrefs`), 4 by the terminal agent (`app.terminal.*`), 3 rows deleted with pi-source reasons. See `gap-disposition.md` §I5 / §11. Re-check before acting. | §2.5 |
| 8 | **Resolve the duplicated device switches** | Two screens for one grant, only one connected, and they can contradict each other about a security-sensitive permission. | §2.10 |
| 9 | **Wire `hideThinkingBlock` + a thinking collapse-all** | The renderer supports both; the screen passes neither. pi's setting is otherwise silently ignored. | §2.5(b) |
| 10 | **Mark `/tree` as terminal-only and add it to F2** | The app today implies "分支" is tree navigation; it is a fork that creates a new file. Cheap honesty now; a real in-place switch is impossible over RPC. | §2.1 |
| 11 | **Session delete + search + auto-resume** | Table stakes for a session list, and delete needs pi's confirm step to be safe. | §2.7 |
| 12 | **Offline mode + system-prompt override** | Small spawn-path work that closes three CLI-only gaps at once and delivers on the privacy rationale already in the code. | §2.11 |
| 13 | **Theme discovery from `themes/` dirs** | Follow-on from #5; without it the custom theme cannot even be selected. | §2.4(c) |
| 14 | **Native credential form** | `auth.json` is a documented format the app could write; until then two Settings rows should be removed or pointed at the terminal. | §2.9 |

---

## 4. Checked and found correct (do not redo)

Each of these was verified against pi's source, not the app's comments, and is recorded so the next
reviewer does not spend a pass re-deriving it.

1. **All 33 RPC commands are modelled, and now 32 are GUI-reachable.** Every variant of
   `rpc-types.ts:20-74` has a builder in `Commands.kt` (line-by-line table in §1.1); the typed façade
   `PiEngineApi.kt` covers the 30 non-prompting commands (the three prompting commands live on
   `PiEngineSession`, deliberately — see its doc comment at `PiEngineApi.kt:40-45`). The only builder
   with **no call site anywhere** is `get_messages` (`PiEngineApi.getMessages`, `PiEngineApi.kt:60`);
   `follow_up` *has* a call site but no UI path (§2.2).
   Field-level fidelity is separately established in `fidelity-review.md:199-206`.
2. **pi's 23 built-in slash commands are transcribed exactly.** `PI_BUILTIN_SLASH_COMMANDS`
   (`PiSlashCommands.kt:106-145`) matches `core/slash-commands.ts:19-43` name-for-name,
   order-for-order, and even keeps pi's `argumentHint` distribution (only `model`, `thinking`,
   `login`), which correctly means the palette completes the name without invoking for those rows.
3. **Every top-level key of pi's `Settings` interface is present in the registry.** Independently
   derived twice (my own diff against `settings-manager.ts:106-158`, and
   `fidelity-review.md` §"Settings registry"); only `path` and `projectTrusted` are absent, and both
   are project-override/meta fields rather than user settings.
4. **`hideThinking`/`toolsDefaultExpanded` are real parameters of the renderer** — the gap in §2.5(b) is
   in the call site, not in `ui/blocks`. A reviewer should not go looking for a missing renderer.
5. **`get_messages` not being called is not a user-visible gap.** The transcript is projected from
   `get_entries` + the event stream (`refreshCommands`/`getEntries`/`seedFromHistory` path), which is a
   superset for display purposes (`get_entries` includes pre-compaction history and abandoned
   branches; `docs/rpc.md` §get_entries). Recorded here so it is not re-reported as "dead code".
6. **The palette receives extension/template/skill commands.** `refreshCommands` is called on attach,
   on `agent_settled`, and after a session is replaced (`PiSessionViewModel.kt:409,498` → `:950-957`),
   and `piCommandPalette` merges them with the built-ins and reproduces `sourceTag` from `sourceInfo`
   exactly as pi's own autocomplete does (`PiSlashCommands.kt:185-232`). A grep for callers that stops
   at the UI layer would wrongly conclude the palette is built-ins-only.
7. **`copyLastAssistantText` is wired** despite not matching a naive `session.copy…(` grep: it is
   invoked with a trailing lambda from `ChatScreen.kt:552`. Any reachability sweep must account for
   Kotlin trailing-lambda call syntax; this bit my first pass.
8. **The session-directory naming is byte-compatible on the read side.** `PiSessionStore`'s
   `encodedCwdFromGroupName` (`PiSessionStore.kt:152-153`) matches `session-manager.ts:479`
   (`--<cwd, leading slash stripped, / \ : → ->--`), and the code correctly refuses to invert the
   lossy substitution, preferring the header's authoritative `cwd`.
9. **Telemetry and proxy settings do reach pi.** `enableInstallTelemetry`, `enableAnalytics`,
   `trackingId` and `httpProxy` are written to `settings.json`, which is exactly where
   `core/telemetry.ts:9-15` and `settings-manager.ts:151` read them. `PI_TELEMETRY` and `HTTP_PROXY`
   not being set as env vars is therefore **not** a gap for pi's own clients.
10. **The TUI escape hatch exists and is one tap from the composer.** `PtyLauncher.Kind.PiTui` plus the
    "原版 TUI" affordance (`ChatScreen.kt:694`) is the correct disposition for the
    `MISSING-TERMINAL-ONLY` rows; the rows in §1.3/§1.5/§2.9 should point at it rather than pretend.
11. **`PI_IMAGE_PROTOCOL=none` for the terminal is deliberate and consistent** with the GUI owning
    image display (`PtyLauncher.kt:293`, and `ui/terminal/`'s `TerminalBridge`/`TerminalPane`, which
    replaced the hand-written `TerminalEmulator.kt` when the terminal moved to `termlib 0.0.13`); the inline-image gap belongs to
    `known-gaps.md` C2, not here.

---

## 5. UNVERIFIED items

| Item | Why unverified | What would settle it |
|---|---|---|
| Whether `DeviceCapabilityScreen`/`DeviceShizuku` gained a bridge to the `app.device.*` settings keys during the concurrent rewrite | Both files were being edited throughout this pass; the registry/store disconnect was verified statically, the runtime identity of the two switches was not | Re-grep `app.device.` outside `PiSettingsRegistry.kt` after the tree freezes |
| Whether `/share` can work on this host at all | It needs a browser and (per `docs/providers.md:44-51`) a reachable loopback callback for OAuth; `PI_SHARE_VIEWER_URL` is not set and the flow was never run | Run the gist flow from the `pi TUI（原版）` tab on a device |
| Whether the `TerminalOnly` rows are actually usable *in this app's terminal tab* | The tab exists and runs the unmodified pi (`PtyLauncher.Kind.PiTui`), but `known-gaps.md` C2 records the VT emulator as never run on a device | The device pass already listed as C1/C2 |
| Whether any GUI code reads pi's `hideThinkingBlock` through a path a grep would miss (e.g. via a generated constant) | My verification was textual: 0 hits for `hideThinkingBlock` outside the registry + one doc comment | A frozen-tree typecheck-and-search, or simply passing it at `ChatScreen.kt:345-348` and observing |
| The exact number of `app.*` settings with no reader | 13 were confirmed individually; the mechanical sweep over all 127 keys timed out twice on this tree | Re-run the sweep with a longer budget once the tree is frozen |
| Whether `ui/chat/SessionTreeScreen.kt` gains a non-fork action | It is untracked-then-committed within this pass and its action list changed at least once | Re-read `SessionTreeScreen.kt` after the freeze |

**None of the findings in §2 depends on an UNVERIFIED item.** Each was demonstrated from code that was
stable across the pass (`Commands.kt`, `PiEngineHost.kt`, `PiPalette.kt`, `PiSettingEditorHost.kt`,
`SettingsGroupScreen.kt`, `SettingsHome.kt`, `SessionsScreen.kt`, `SessionTreeScreen.kt`,
`PiSessionViewModel.kt`, `ChatScreen.kt`) and re-checked at `a7b7738`.

---

## 6. Status of the §2 findings this pass fixed (behaviour agent, `ui/**`)

Written while the work was landing; every claim is a `path:line` in the working tree, not a plan.
The full row-by-row record, including what was removed instead of wired, is
`docs/gap-disposition.md` §11.

| §2 finding | State |
|---|---|
| §2.4(a) `/export x.jsonl` writes HTML | Fixed by implementing pi's JSONL export app-side (`ui/PiSessionViewModel.kt:1737`, `:1774`): extension picks the writer (`interactive-mode.ts:6062-6066`), header + branch + re-chained `parentId` (`session-export.ts:22-40`). No RPC change was made or needed. |
| §2.3 Stop discards the queue | Fixed (`ui/screens/ChatScreen.kt`: the stop callback merges the drained queue with the draft, `interactive-mode.ts:4387-4406`). |
| §2.2 `follow_up` dead | Fixed, and gated on streaming because that is the only time pi's own binding queues rather than submits (`interactive-mode.ts:4139-4152`). |
| §2.4(b) custom theme never reaches the colours | Fixed: `ui/theme/PiThemeFiles.kt` loads pi's theme files into `PiPalette`; `MainActivity.kt` paints the resolved palette; the picker states the values it cannot honour. |
| §2.4(c) discovery misses `~/.pi/agent/themes` | Fixed: discovery mirrors `resource-loader.ts:872-902`. |
| §2.5(a) 13 settings with no consumer | 9 resolved (6 wired, 3 removed with the pi/rpc evidence in the registry comments); the four `app.terminal.*` rows are read by `ui/terminal/TerminalSettings.kt`, owned by another agent. |
| §2.5(b) `hideThinkingBlock` never passed | Fixed (`ui/screens/ChatScreen.kt`, `ui/PiSessionViewModel.kt` `UiPrefs`). |
| §2.6 search / tree filters / session list | Transcript search, pi's five tree filter modes, and session search + sort + named-only + per-cwd grouping are implemented. |
| §2.1 分支 is a fork, not a tree jump | The UI now says so on the screen itself (`ui/chat/SessionTreeScreen.kt`), because RPC has no leaf-move command to implement. |
| §2.7 session delete / `pi -c` | Delete exists with pi's confirmation step and refuses the active session; `pi -c` is behind `app.sessions.resumeLast`. |
| §2.10 device switches duplicated, dead copy | The seven `app.device.*` catalog rows and their group are deleted; `DeviceCapabilityStore` is the single authority. |
| §1.9 image input / image rendering | Picker → base64 `PiImage` → existing `prompt(images)` pipe; the transcript decodes the inline base64 instead of drawing a placeholder. |

---

## 7. Reconciliation of the §1 grades against the working tree (`182823e`, sampled)

Method: every row below was re-read in the current tree. The audit's own rule is kept — a status
needs code evidence — so each row names the **file + symbol** that settles it (not a line number:
the tree's own convention, because line numbers have drifted collectively once already). Only the
app is cited this way; pi's source keeps `file:line`.

**Sampled: 36 of 148 rows, chosen as every §1 row that was not already `IMPLEMENTED`.** The other
112 rows (the §1 `IMPLEMENTED` rows, the `N/A` rows, and the interaction/flags rows that the audit
itself graded `IMPLEMENTED` or `N/A`) were **not** re-verified by this pass and still carry `a7b7738`
grades — see "Not verified" below.

| §1 row | grade then | verified now (`182823e`) | proof (file → symbol) |
|---|---|---|---|
| §1.1 `prompt` images | PARTIAL | **implemented** | `ui/screens/ChatScreen.kt` → `imagePicker` (`ActivityResultContracts.GetContent`) → `PiSessionViewModel.send(text, images)` |
| §1.1 `prompt` `streamingBehavior: followUp` | PARTIAL | **implemented** (moot: the `follow_up` route is live) | `ui/PiSessionViewModel.kt` → `sendFollowUp` |
| §1.1 `follow_up` | MISSING-GUI | **implemented** | `ChatScreen` overflow → `PiSessionViewModel.sendFollowUp` |
| §1.1 `clear_queue` discards text | PARTIAL | **implemented** | `ChatScreen` → `mergeRestoredQueue` + `PiSessionViewModel.stop(onRestored)` |
| §1.1 `new_session` `parentSession` | PARTIAL | 已实现（本轮接上） | `PiSessionViewModel.newSession(parentSession)` + `newChildSession` ← `SessionsScreen` 长按「新建子会话」；guest 路径经 `guestSessionPath` |
| §1.1 `get_messages` no caller | PARTIAL | unchanged, **not user-visible** | `engine/PiEngineApi.kt` → `getMessages`; transcript comes from `get_entries` |
| §1.1 `export_html` always HTML | PARTIAL | **implemented** | `PiSessionViewModel.exportSession` → `exportJsonl` |
| §1.1 `export_html` `outputPath` re-rooted | PARTIAL | unchanged, deliberate | `PiSessionViewModel.exportSession` (workspace re-root, then host read-back) |
| §1.2 `pi install`/`remove`/`list`/`update` | CLI-ONLY | **implemented** (uncommitted) | `packages/PiPackagesHost.kt` → `PiPackagesScreen` + `PiPackageService` |
| §1.2 `pi config` per-resource | CLI-ONLY | **reproduced (applied, uncommitted)** | only `PiSettingsRegistry` → `packages[].autoload` (whole-package) |
| §1.2 `--offline` | CLI-ONLY | **engine wired; settings+UI pending** | `rpc/PiLaunchOptions.kt` → `environment()`; no registry row, no caller |
| §1.2 `--system-prompt` | CLI-ONLY | **engine wired; settings+UI pending** | `PiLaunchOptions` → `systemPrompt`; no caller |
| §1.2 `--api-key` | CLI-ONLY | **written, unwired** | `packages/PiCredentialService.kt` / `PiConfigFiles.kt` → `setApiKey`; `PiRoot` still passes no `onRunAction` |
| §1.2 `--continue`/`-c` | MISSING-GUI | **implemented** | `PiSessionViewModel` resume gate + `PiSettingsRegistry` → `app.sessions.resumeLast` |
| §1.3 restore queued text on Esc | MISSING-GUI | **implemented** | `ChatScreen` → `mergeRestoredQueue` |
| §1.3 queue a follow-up | MISSING-GUI | **implemented** | `ChatScreen` → `sendFollowUp` |
| §1.3 expand/collapse thinking | PARTIAL | **implemented** | `ChatScreen` → `thinkingDefaultExpanded = !prefs.thinkingCollapsedByDefault` |
| §1.3 `hideThinkingBlock` | MISSING-GUI | **implemented** | `ChatScreen` → `hideThinking = prefs.hideThinkingBlock` |
| §1.3 transcript search | MISSING-GUI | **implemented** | `ChatScreen` → `searchQuery` / `SearchBar` / `searchTextOf` |
| §1.3 jump prev/next message | MISSING-GUI | **implemented** | `ChatScreen` overflow → 跳到上一条/下一条提问 |
| §1.3 tree filters | MISSING-GUI | **implemented** | `ui/chat/SessionTreeScreen.kt` → `TreeFilter` (pi's five modes) |
| §1.3 session picker search/sort | MISSING-GUI | **implemented** | `SessionsScreen` → `query` / `byName` / `namedOnly` |
| §1.3 session delete | MISSING-GUI | **implemented** | `SessionsScreen` → `combinedClickable(onLongClick=…)` + `PiSessionViewModel.deleteSession` |
| §1.3 external editor | PARTIAL | 已实现（ACTION_EDIT 映射） | pi: 临时 `prompt.md` + spawn；**退出码≠0 丢弃**（`modes/interactive/external-editor.ts:14-52`），命令解析 `settings-manager.ts:970-981`，触发 `app.editor.external`（`interactive-mode.ts:4246-4261`）。App: `ChatScreen.openInExternalEditor` → `ACTION_EDIT`(text/plain)；RESULT_OK 回写、取消保留草稿、无应用处理时 notifyUser 说明（对应 pi 的 spawn 失败） |
| §1.3 `/scoped-models` | PARTIAL | **implemented** | `ui/chat/PiSlashCommands.kt` → `PiCommandAction.OpenModelScope` |
| §1.3 `/tree` switch the active leaf | MISSING-TERMINAL-ONLY | unchanged — pi limit | `rpc-types.ts:20-74`: no leaf-move command |
| §1.4 user theme JSON changes the palette | MISSING-GUI | **implemented** | `ui/theme/PiThemeFiles.kt` → `PiThemeLoader.load`; `PiSessionViewModel.theme: StateFlow<PiResolvedTheme>` |
| §1.4 theme discovery (`agentDir`/project/packages) | PARTIAL | **implemented** | `PiThemeLoader.discover` (three scopes) |
| §1.5 export to JSONL | MISSING-GUI | **implemented** | `PiSessionViewModel.exportJsonl` |
| §1.5 per-cwd grouping | PARTIAL | **implemented** | `SessionsScreen` → `.groupBy { it.cwd }` + `PiSectionHeader` |
| §1.5 resume most recent on launch | MISSING-GUI | **implemented** | same site as §1.2 `-c` |
| §1.8 Action rows inert | MISSING-GUI | **resolved** (wired or deleted) | `ui/settings/**`: 19 rows judged, 12 deleted, remainder wired |
| §1.8 device switches duplicated | MISSING-GUI | **implemented** | `app.device.*` catalog rows deleted; `DeviceCapabilityStore` is the single authority |
| §1.9 rendering attachments | PARTIAL | **implemented** | `ui/blocks/ImageGridBlock.kt` → `decodeImage` (`Base64.decode` + `BitmapFactory.decodeByteArray`) |
| §1.9 `@` file mentions | MISSING-GUI | **implemented** | `ChatScreen` → mention list (guest lookup, inserts pi's literal `@path`) |
| §1.10 `PI_CACHE_RETENTION` | MISSING-GUI | **engine wired; settings pending** | `PiLaunchOptions` → `longCacheRetention` |

### Not verified by this pass (superseded by §8 — see there for the completed sweep)

These still carry their `a7b7738` grades and are **claims, not verified facts**: every row already
graded `IMPLEMENTED` (74), every `N/A` row (7), the remaining `MISSING-TERMINAL-ONLY` rows (11 of
12 — only `/tree` leaf-switch was re-checked, because it was the one the app could plausibly have
grown), and the un-sampled parts of §1.2/§1.3/§1.6/§1.7/§1.8/§1.10. In particular §1.6 (models,
providers, thinking), §1.7 (skills/packages) and §1.10 (environment) were **only** touched where a
reconciled row above lives; nothing else in them was re-read. If a grade matters, check the symbol
first — the sample found the ledger wrong in **30 of 36** rows, so the base rate for an un-checked
`PARTIAL`/`MISSING` grade is high.



## 8. Full sweep of the §1 grade table (`182823e`) — every parsed row

Method, so each verdict's strength is explicit. This table is generated from §1's own rows. For every
row it checks **each symbol or file the audit's "App status" cell names** against `app/src/main/kotlin`
in the working tree. `已实现（符号在位核对）` means *the builder and the UI site the audit named both
exist* — it is **not** a re-walk of the call chain. Rows whose call chain was actually walked in this
session name the proving symbol in the evidence cell. `仍未做` on a PARTIAL/MISSING/CLI-ONLY row means
no symbol check refuted the deficiency. **pi counterpart** is read off the row's own pi-source cell and
never invented: `pi 有 …` when the row cites pi source/docs, `pi 无对应物（App 自己的决定）` when the row
is app-declared, `pi 有但我们够不着` only where the audit graded `MISSING-TERMINAL-ONLY` (no
`RpcCommand` carries it).

Rows swept: **147** (the audit counts 148; §1.8 parses 8 of its 9 — see below).
Verdict tally: **不适用 8, 仍未做 20, 已实现 107, 已解决 4, 部分：代码已写、无调用方 3, 部分：引擎已接、设置与界面未接 4, 部分：引擎已接、设置未接 1**.

| capability | grade then | verified now | pi counterpart | evidence |
|---|---|---|---|---|
| `prompt` (text) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | Commands@PiRoot.kt; prompt@PiRoot.kt; PiEngineSession@PiRoot.kt |
| `prompt` `images` | PARTIAL | 仍未做 | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; PiImage@PiSessionViewModel.kt; PiEngineSession@PiRoot.kt |
| `prompt` `streamingBehavior: steer` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | send@PiSessionViewModel.kt |
| `prompt` `streamingBehavior: followUp` | PARTIAL | 已实现 | pi 有 `rpc-types.ts` | PiSessionViewModel.sendFollowUp · FollowUp@PiSessionViewModel.kt; Commands@PiRoot.kt; follow_up@PiSe |
| `steer` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | Commands@PiRoot.kt; send@PiSessionViewModel.kt |
| `follow_up` | MISSING-GUI | 已实现 | pi 有 `docs/rpc.md` | PiSessionViewModel.sendFollowUp · Commands@PiRoot.kt; sendFollowUp@PiSessionViewModel.kt |
| `abort` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; stopAndDrainQueue@PiRoot.kt; stop@PiRoot.kt |
| `clear_queue` | PARTIAL | 已实现 | pi 有 `docs/rpc.md` | ChatScreen.mergeRestoredQueue · Commands@PiRoot.kt; stopAndDrainQueue@PiRoot.kt; PiEngineSession@PiR |
| `new_session` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; newSession@PiSessionViewModel.kt; ChatScreen@PiRoot.kt |
| `new_session` `parentSession` | PARTIAL | 已实现（本轮接上） | pi 有 `rpc-types.ts` | `PiSessionViewModel.newSession(parentSession)` + `newChildSession` ← `SessionsScreen` 长按「新建子会话」；guest 路径经 `guestSessionPath` |
| `get_state` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshState@PiSessionViewModel.kt |
| `get_messages` | PARTIAL | 仍未做（无用户可见损失） | pi 有 `rpc-types.ts` | 无调用方；transcript 来自 get_entries · Commands@PiRoot.kt; getMessages@PiEngineApi.kt; PiEngineApi@PiRoot. |
| `set_model` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setModel@PiSessionViewModel.kt; ModelPickerSheet@ChatScreen.kt |
| `cycle_model` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; cycleModel@PiSessionViewModel.kt; ChatScreen@PiRoot.kt |
| `get_available_models` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshModels@PiRoot.kt |
| `set_thinking_level` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setThinkingLevel@PiSessionViewModel.kt; ThinkingPickerSheet@ChatScreen.kt |
| `cycle_thinking_level` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; cycleThinkingLevel@PiSessionViewModel.kt; ChatScreen@PiRoot.kt |
| `get_available_thinking_levels` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshThinkingLevels@PiSessionViewModel.kt |
| `set_steering_mode` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setSteeringMode@PiSessionViewModel.kt; SessionToolsSheet@ChatScreen.kt |
| `set_follow_up_mode` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setFollowUpMode@PiSessionViewModel.kt; SessionToolsSheet@ChatScreen.kt |
| `compact` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; compact@PiRoot.kt; customInstructions@PiSessionViewModel.kt |
| `set_auto_compaction` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setAutoCompaction@PiSessionViewModel.kt |
| `set_auto_retry` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; setAutoRetry@PiSessionViewModel.kt |
| `abort_retry` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; abortRetry@PiSessionViewModel.kt |
| `bash` + `excludeFromContext` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; runBash@PiSessionViewModel.kt; SlashPalette@ChatScreen.kt |
| `abort_bash` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; abortBash@PiRoot.kt; BashPanel@ChatScreen.kt |
| `get_session_stats` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshStats@PiSessionViewModel.kt; SessionStatsSheet@ChatScreen.kt |
| `export_html` | PARTIAL | 已实现 | pi 有 `rpc-types.ts` | PiSessionViewModel.exportSession · Commands@PiRoot.kt; exportHtml@PiSessionViewModel.kt |
| `export_html` `outputPath` | PARTIAL | 已实现 | pi 有 `rpc-types.ts` | PiSessionViewModel.exportSession · PiSessionViewModel@MainActivity.kt |
| `switch_session` + `cancelled` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; switchSession@PiSessionViewModel.kt; SessionsScreen@PiRoot.kt |
| `fork` + `cancelled` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; forkFrom@PiRoot.kt |
| `clone` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; cloneSession@PiSessionViewModel.kt |
| `get_fork_messages` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshForkMessages@PiSessionViewModel.kt |
| `get_entries` + `since` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; getEntries@PiSessionViewModel.kt |
| `get_tree` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshTree@PiRoot.kt; SessionTreeScreen@PiRoot.kt |
| `get_last_assistant_text` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; copyLastAssistantText@PiSessionViewModel.kt; ChatScreen@PiRoot.kt |
| `set_session_name` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; renameSession@PiSessionViewModel.kt |
| `get_commands` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `rpc-types.ts` | Commands@PiRoot.kt; refreshCommands@PiSessionViewModel.kt; piCommandPalette@PiSessionViewModel.kt |
| Extension UI requests (`select`/`confirm`/`inp | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | 审计未引可查符号 |
| `--mode rpc`, `--session-dir`, `--name`, `--th | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `args.ts` | PiEngineHost@PiSessionViewModel.kt; set_model@PiSessionViewModel.kt; set_session_name@PiSessionViewM |
| `pi install` / `remove` / `list` / `update` | CLI-ONLY | 已实现（未提交） | pi 有 `package-manager-cli.ts` | PiPackagesHost → PiPackagesScreen + PiPackageService · PiPackageService@AgentLayout.kt; PiPackagesSc |
| `pi config` (resource enable/disable TUI) | CLI-ONLY | 已实现（applied, uncommitted） | pi 有 `package-manager-cli.ts` | pi 落盘 = `settings.json` 的 `packages[]` 对象形式的 `extensions/skills/prompts/themes` glob 数组，全空时塌回字符串（`modes/interactive/components/config-selector.ts:585-629`）；App 只有 `packages[].autoload`，编辑器属 `packages/**`（本轮无权限） |
| `--offline` / `PI_OFFLINE` | CLI-ONLY, not reproduc | 部分：引擎已接、设置与界面未接 | pi 有 `args.ts` | PiLaunchOptions.environment；无 registry 行 · PiEngineHost@PiSessionViewModel.kt; PI_CODING_AGENT_DIR@P |
| `--system-prompt` / `--append-system-prompt` | CLI-ONLY, not reproduc | 部分：引擎已接、设置与界面未接 | pi 有 `args.ts` | PiLaunchOptions.systemPrompt；无调用方 · 审计未引可查符号 |
| `--api-key` | CLI-ONLY | 部分：代码已写、无调用方 | pi 有 `args.ts` | PiCredentialService.setApiKey · json@PiRoot.kt; TerminalOnly@PiSessionViewModel.kt; PiSlashCommands@ |
| `--continue` / `-c`, `--resume`, `--session`, | MISSING-GUI | 已实现 | pi 有 `args.ts` | PiSessionViewModel resume gate + app.sessions.resumeLast · switch_session@PiSessionViewModel.kt; PiS |
| `--list-models`, `--export` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `args.ts` | get_available_models@PiRoot.kt; export_html@PiSessionViewModel.kt |
| `--models`, `--tools`, `--no-tools`, `--no-bui | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `args.ts` | enabledModels@PiSessionViewModel.kt; defaultTools@PiSettingsRegistry.kt |
| `--extension`, `--skill`, `--prompt-template`, | CLI-ONLY / N/A | 不适用（App 只有一个常驻引擎，没有 per-run 概念） | pi 有 `args.ts` | 持久化形式 = extensions/skills/prompts/themes 设置行 · extensions@PiSessionViewModel.kt; skills@PiSessionVie |
| `--use-theme` (per-run theme), `--tui-mode`, ` | N/A | 不适用 | pi 有 `args.ts` | 无需符号（N/A） |
| Built-in slash commands | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `core/slash-commands.ts` | PI_BUILTIN_SLASH_COMMANDS@PiSlashCommands.kt; PiSlashCommands@PiRoot.kt; TerminalOnly@PiSessionViewM |
| `/` palette incl. extension/template/skill com | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | get_commands@PiRoot.kt; piCommandPalette@PiSessionViewModel.kt; PiSlashCommands@PiRoot.kt |
| `/` interception (a `/cmd` must not become a p | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `interactive-mode.ts` | routeComposerText@ChatScreen.kt; SlashPalette@ChatScreen.kt |
| bash mode `!` / `!!` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `keybindings.md` | SlashPalette@ChatScreen.kt; ChatScreen@PiRoot.kt; BashPanel@ChatScreen.kt |
| queue editing: restore queued text on Esc | MISSING-GUI | 已实现 | pi 有 `docs/rpc.md` | ChatScreen.mergeRestoredQueue · stopAndDrainQueue@PiRoot.kt; PiEngineSession@PiRoot.kt; ChatScreen@P |
| queue a follow-up message (`alt+enter`) | MISSING-GUI | 已实现 | pi 有 `keybindings.md` | ChatScreen → sendFollowUp · sendFollowUp@PiSessionViewModel.kt |
| expand/collapse all tool output (`ctrl+o`) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `keybindings.md` | toolsExpanded@ChatScreen.kt; ChatScreen@PiRoot.kt |
| expand/collapse thinking (`ctrl+t`) | PARTIAL | 已实现 | pi 有 `keybindings.md` | ChatScreen thinkingDefaultExpanded · ChatScreen@PiRoot.kt |
| `hideThinkingBlock` (hide thinking entirely) | MISSING-GUI | 已实现 | pi 有 `settings-manager.ts` | ChatScreen hideThinking · BlockRenderer@ChatScreen.kt; hideThinking@PiSessionViewModel.kt; ChatScree |
| transcript search (`ctrl+shift+f`) | MISSING-GUI | 已实现 | pi 有 `keybindings.md` | ChatScreen.searchQuery/SearchBar · SearchBar@ChatScreen.kt |
| jump to previous/next message (`ctrl+shift+up/ | MISSING-GUI | 已实现 | pi 有 `keybindings.md` | ChatScreen 跳到上/下一条 · 审计未引可查符号 |
| model picker / cycle (`ctrl+l`, `ctrl+p`) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `keybindings.md` | ChatScreen@PiRoot.kt |
| thinking picker / cycle (`shift+tab`) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `keybindings.md` | ChatScreen@PiRoot.kt |
| `/tree` navigation: switch the active leaf | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | rpc-types.ts 无 leaf-move 命令 · RpcCommand@PiRoot.kt; get_tree@PiSessionViewModel.kt; fork@PiRoot.kt |
| tree filters (`treeFilterMode`, `ctrl+t/u/l/a/ | MISSING-GUI | 已实现 | pi 有 `docs/sessions.md` | SessionTreeScreen.TreeFilter · SessionTreeScreen@PiRoot.kt; treeFilterMode@PiSettingsRegistry.kt |
| tree labels (`setLabel`, edit label `shift+l`) | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | SessionTreeScreen@PiRoot.kt |
| session picker: search / sort / named filter / | MISSING-GUI | 已实现 | pi 有 `docs/sessions.md` | SessionsScreen.query/byName/namedOnly · SessionsScreen@PiRoot.kt; PiSessionStore@PiSessionViewModel. |
| session tree "delete session" + non-invasive v | MISSING-GUI | 仍未做 | pi 有 `keybindings.md` | 审计未引可查符号 |
| external editor (`ctrl+g`, `externalEditor`) | PARTIAL | 已实现（ACTION_EDIT 映射） | pi 有 `keybindings.md` | pi: 临时 `prompt.md` + spawn；**退出码≠0 丢弃**（`modes/interactive/external-editor.ts:14-52`），命令解析 `settings-manager.ts:970-981`，触发 `app.editor.external`（`interactive-mode.ts:4246-4261`）。App: `ChatScreen.openInExternalEditor` → `ACTION_EDIT`(text/plain)；RESULT_OK 回写、取消保留草稿、无应用处理时 notifyUser 说明（对应 pi 的 spawn 失败） |
| scoped models selector (`/scoped-models`) | PARTIAL | 已实现 | pi 有 `core/slash-commands.ts` | PiCommandAction.OpenModelScope · TerminalOnly@PiSessionViewModel.kt; PiSlashCommands@PiRoot.kt; enab |
| `/import` (import a session JSONL) | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt; PiSlashCommands@PiRoot.kt; import@PiApplication.kt |
| `/share` (secret GitHub gist) | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt |
| `/login`, `/logout` | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt; PiSlashCommands@PiRoot.kt |
| `/reload` | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | ChatScreen@PiRoot.kt |
| `/changelog`, `/hotkeys`, `/quit` | N/A / MISSING-TERMINAL | 仍未做 | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt; hotkeys@PiSlashCommands.kt; quit@PiSlashCommands.kt |
| custom keybindings (`keybindings.json`) | N/A | 不适用 | pi 有 `docs/keybindings.md` | keybindings@ChatScreen.kt |
| Built-in `dark` / `light` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/themes.md` | Dark@MainActivity.kt; Light@PiThemeFiles.kt; PiPalette@MainActivity.kt |
| Automatic `lightTheme/darkTheme` pair | IMPLEMENTED | 已实现 | pi 有 `docs/themes.md` | PiSessionViewModel.refreshTheme · PiSettingsStore@PiSessionViewModel.kt; MainActivity@MainActivity.k |
| Select a theme by name | IMPLEMENTED | 已实现 | pi 有 `docs/themes.md` | PiSettingEditorHost + SettingsGroupScreen echo · theme@MainActivity.kt; SettingsGroupScreen@Settings |
| A user theme JSON changes the app's own colour | MISSING-GUI | 已实现 | pi 有 `docs/themes.md` | PiThemeLoader.load + PiSessionViewModel.theme · PiPalette@MainActivity.kt; MainActivity@MainActivity |
| Theme discovery from `<agentDir>/themes/*.json | PARTIAL | 已实现 | pi 有 `resource-loader.ts` | PiThemeLoader.discover · themes@PiRoot.kt |
| Theme discovery from `.pi/themes/*.json` (proj | PARTIAL | 已实现 | pi 有 `resource-loader.ts` | PiThemeLoader.discover · 审计未引可查符号 |
| Themes from packages (`themes/`, `pi.themes`) | PARTIAL | 已实现 | pi 有 `docs/themes.md` | PiThemeLoader.discover · 审计未引可查符号 |
| `--theme` / `--no-themes` | CLI-ONLY | 不适用（持久化形式已存在） | pi 有 `args.ts` | themes 设置行 · 审计未引可查符号 |
| Theme used by `export_html` | N/A | 已实现 | pi 有 `agent-session.ts` | PiSessionViewModel.exportSession · 无需符号（N/A） |
| Sessions as JSONL, grouped by cwd | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | list@PiRoot.kt; encodedCwdFromGroupName@PiSessionStore.kt; PiSessionStore@PiSessionViewModel.kt |
| Byte-compatibility with pi's session format | N/A | 不适用 | pi 有 `docs/session-format.md` | PiSessionStore@PiSessionViewModel.kt |
| Tree view (`/tree`) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | get_tree@PiSessionViewModel.kt; SessionTreeScreen@PiRoot.kt |
| Switch branch in place | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | 审计未引可查符号 |
| Branch summarization when leaving a branch | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | requires the leaf move · 审计未引可查符号 |
| Fork from a user message | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | fork@PiRoot.kt; SessionTreeScreen@PiRoot.kt |
| Clone current branch | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | cloneSession@PiSessionViewModel.kt |
| Switch / resume a session | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | SessionsScreen@PiRoot.kt; switch_session@PiSessionViewModel.kt; cancelled@PiSessionViewModel.kt |
| Rename | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | set_session_name@PiSessionViewModel.kt |
| Delete a session | MISSING-GUI | 已实现 | pi 有 `docs/sessions.md` | SessionsScreen.combinedClickable + PiSessionViewModel.deleteSession · 审计未引可查符号 |
| Search / sort / named-only filter in the picke | MISSING-GUI | 已实现 | pi 有 `docs/sessions.md` | SessionsScreen.query/byName/namedOnly · SessionsScreen@PiRoot.kt |
| Resume most recent session on launch (`pi -c`) | MISSING-GUI | 已实现 | pi 有 `args.ts` | PiSessionViewModel resume gate · PiSessionViewModel@MainActivity.kt |
| Export to HTML | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/sessions.md` | export_html@PiSessionViewModel.kt; exportHtml@PiSessionViewModel.kt |
| Export to JSONL | MISSING-GUI | 已实现 | pi 有 `agent-session.ts` | PiSessionViewModel.exportJsonl · export_html@PiSessionViewModel.kt; exportToHtml@PiSessionViewModel. |
| Import from a JSONL file | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt |
| Entry log (`get_entries`) incl. extension stat | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | appendEntry@PiSessionViewModel.kt; SessionTreeScreen@PiRoot.kt |
| Per-cwd session grouping in the UI | PARTIAL | 已实现 | pi 有 `docs/sessions.md` | SessionsScreen.groupBy{cwd} · SessionsScreen@PiRoot.kt |
| Model catalog from the provider | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | refreshModels@PiRoot.kt; ModelPickerSheet@ChatScreen.kt |
| Switch / cycle model | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | 审计未引可查符号 |
| Thinking levels, `off`…`max`, model-derived | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/rpc.md` | PiEngineApi@PiRoot.kt |
| Per-model thinking defaults (`modelThinkingLev | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | json@PiRoot.kt |
| Custom provider via `models.json` | PARTIAL | 部分：代码已写、无调用方 | pi 有 `docs/custom-provider.md` | PiConfigFiles/PiCredentialService · json@PiRoot.kt; get_available_models@PiRoot.kt; manage@PiApplica |
| OAuth login (`/login`, `/logout`) and `auth.js | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | TerminalOnly@PiSessionViewModel.kt; json@PiRoot.kt |
| API-key credentials | MISSING-GUI | 部分：代码已写、无调用方 | pi 有 `docs/providers.md` | PiCredentialService.setApiKey · apiKey@PiSettingsRegistry.kt |
| Provider transport setting | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | 审计未引可查符号 |
| `provider/auth` events visible to the client | PARTIAL | 仍未做 | pi 无对应物（App 自己的决定） | model_select@PiSessionViewModel.kt; get_state@PiSessionViewModel.kt |
| Skill discovery (`~/.pi/agent/skills`, `.pi/sk | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/skills.md` | PiEngineHost@PiSessionViewModel.kt; skills@PiSessionViewModel.kt |
| `/skill:name` invocation | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `agent-session.ts` | get_commands@PiRoot.kt; PiSlashCommands@PiRoot.kt |
| `enableSkillCommands` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | 审计未引可查符号 |
| Prompt templates (`prompts` setting, `.md` fil | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/prompt-templates.md` | get_commands@PiRoot.kt |
| Local extensions (`extensions` setting, `~/.pi | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/extensions.md` | extensions@PiSessionViewModel.kt |
| npm/git packages (`packages`) | PARTIAL | 已实现（未提交） | pi 有 `docs/packages.md` | PiPackagesHost → PiPackagesScreen · packages@PiRoot.kt; autoload@PiSettingsRegistry.kt |
| Package resource enable/disable (`pi config`) | CLI-ONLY | 已实现（applied, uncommitted） | pi 有 `package-manager-cli.ts` | 同上：pi 的按资源开关就是 `settings.json` 里 `packages[]` 的四个 glob 数组（`config-selector.ts:585-629`），App 未提供嵌套编辑器 |
| Extension load diagnostics | MISSING-TERMINAL-ONLY | 仍未做（协议不可达） | pi 有但我们够不着（无 RpcCommand） | 审计未引可查符号 |
| Extension command collision (`name:1`) | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `runner.ts:653-691` | 审计未引可查符号 |
| Built-in vs user-installed package distinction | MISSING-GUI | 已实现（未提交） | pi 无对应物（App 自己的决定） | PiPackagesScreen builtin/user distinction · 审计未引可查符号 |
| pi's whole top-level settings surface | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | path@PiRoot.kt; projectTrusted@PiSettingsRegistry.kt |
| Writes land in pi's own files | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | json@PiRoot.kt; PiSettingsFileStore@PiSessionViewModel.kt |
| Settings search | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `interactive-mode.ts` | SettingsSearchScreen@PiSettingsRegistry.kt; PiSettingsCatalog@PiSettingsStore.kt |
| Registered settings that nothing reads | MISSING-GUI | 已解决 | pi 无对应物（App 自己的决定） | 10 wired + 3 deleted with reasons · dynamicColor@PiSettingsRegistry.kt; fontScaleDelta@MainActivity. |
| Action-kind settings rows | MISSING-GUI | 已解决（接线或删除） | pi 无对应物（App 自己的决定） | SettingsGroupScreen/registry · Action@PiRoot.kt; PiRoot@MainActivity.kt; onRunAction@PiRoot.kt |
| Telemetry settings (`enableInstallTelemetry`, | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | json@PiRoot.kt; telemetry@PiSettingsRegistry.kt |
| `httpProxy` / `httpIdleTimeoutMs` / `websocket | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `settings-manager.ts` | httpProxy@PiSettingsRegistry.kt; HTTP_PROXY@PiSettingsRegistry.kt; HTTPS_PROXY@PiSettingsRegistry.kt |
| Update checks | MISSING-GUI | 已解决（判定并删除行） | pi 有 `docs/environment-variables.md` | settings agent: 19 rows judged, 12 deleted · PiEngineHost@PiSessionViewModel.kt; checkUpdate@PiSetti |
| Images into `prompt` | PARTIAL | 已实现 | pi 有 `rpc-types.ts` | ChatScreen.imagePicker → send(text, images) · PiImage@PiSessionViewModel.kt; Commands@PiRoot.kt |
| Picking an image from the device | MISSING-GUI | 已实现 | pi 有 `pi has no phone picker, but the ca` | ChatScreen.imagePicker · GetContent@ChatScreen.kt; ACTION_OPEN_DOCUMENT@DeviceSafStore.kt |
| Pasting an image from the clipboard | MISSING-GUI | 已实现 | pi 有 `keybindings.md` | ChatScreen.imagePicker · ChatScreen@PiRoot.kt; TerminalPane@WorkbenchScreen.kt |
| Rendering attachments in the transcript | PARTIAL | 已实现 | pi 有 `core/messages.ts` | ImageGridBlock.decodeImage · UserMessageBlock@BlockRenderer.kt; images@PiSessionViewModel.kt; ImageG |
| `@` file mentions | MISSING-GUI | 已实现 | pi 有 `utils/paths.ts` | ChatScreen mention list · 审计未引可查符号 |
| `PI_CODING_AGENT_DIR`, `PI_CODING_AGENT_SESSIO | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `docs/environment-variables.md` | PiEngineHost@PiSessionViewModel.kt |
| `PI_SKIP_VERSION_CHECK` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `same` | PiEngineHost@PiSessionViewModel.kt |
| `PI_OFFLINE` / `--offline` | CLI-ONLY, not reproduc | 部分：引擎已接、设置与界面未接 | pi 有 `same` | PiLaunchOptions.environment；无 registry 行 · PiEngineHost@PiSessionViewModel.kt |
| `PI_TELEMETRY` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `same` | enableInstallTelemetry@PiSettingsRegistry.kt |
| `PI_CACHE_RETENTION` | MISSING-GUI | 部分：引擎已接、设置未接 | pi 有 `same` | PiLaunchOptions.longCacheRetention · 审计未引可查符号 |
| `PI_PACKAGE_DIR` | N/A | 不适用 | pi 有 `same` | 无需符号（N/A） |
| `HTTP_PROXY` / `HTTPS_PROXY` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `same` | httpProxy@PiSettingsRegistry.kt |
| `PI_IMAGE_PROTOCOL`, `PI_TRUE_COLOR`, `PI_HYPE | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `same` | PtyLauncher@PiSessionViewModel.kt; none@PiSessionViewModel.kt; showHardwareCursor@PiSettingsRegistry |
| `VISUAL` / `EDITOR` | IMPLEMENTED | 已实现（符号在位核对） | pi 有 `same` | externalEditor@PiSettingsRegistry.kt |
| `AI_AGENT=pi` / `PI_CODING_AGENT=true` process | N/A | 不适用 | pi 有 `same` | 无需符号（N/A） |
| Shell-tool session env (`PI_SESSION_ID`, `PI_P | N/A | 不适用 | pi 有 `same` | 无需符号（N/A） |
| Engine argv | PARTIAL | 部分：引擎已接、设置与界面未接 | pi 有 `docs/rpc.md` | PiLaunchOptions + PiEngineHost.boot(launch) · PiEngineHost@PiSessionViewModel.kt |
| Runtime/ops diagnostics (`app.runtime.*`) | MISSING-GUI | 已解决（判定并删除行） | pi 无对应物（App 自己的决定） | settings agent: 19 rows judged, 12 deleted · logViewer@PiSettingsRegistry.kt; exportDiagnostics@PiSe |

### "说没做、其实做了" — the list this sweep exists to produce

- **`prompt` `streamingBehavior: followUp`** (was PARTIAL) → 已实现 — PiSessionViewModel.sendFollowUp
- **`follow_up`** (was MISSING-GUI) → 已实现 — PiSessionViewModel.sendFollowUp
- **`clear_queue`** (was PARTIAL) → 已实现 — ChatScreen.mergeRestoredQueue
- **`export_html`** (was PARTIAL) → 已实现 — PiSessionViewModel.exportSession
- **`export_html` `outputPath`** (was PARTIAL) → 已实现 — PiSessionViewModel.exportSession
- **`pi install` / `remove` / `list` / `update`** (was CLI-ONLY) → 已实现（未提交） — PiPackagesHost → PiPackagesScreen + PiPackageService
- **`--continue` / `-c`, `--resume`, `--session`,** (was MISSING-GUI) → 已实现 — PiSessionViewModel resume gate + app.sessions.resumeLast
- **queue editing: restore queued text on Esc** (was MISSING-GUI) → 已实现 — ChatScreen.mergeRestoredQueue
- **queue a follow-up message (`alt+enter`)** (was MISSING-GUI) → 已实现 — ChatScreen → sendFollowUp
- **expand/collapse thinking (`ctrl+t`)** (was PARTIAL) → 已实现 — ChatScreen thinkingDefaultExpanded
- **`hideThinkingBlock` (hide thinking entirely)** (was MISSING-GUI) → 已实现 — ChatScreen hideThinking
- **transcript search (`ctrl+shift+f`)** (was MISSING-GUI) → 已实现 — ChatScreen.searchQuery/SearchBar
- **jump to previous/next message (`ctrl+shift+up/** (was MISSING-GUI) → 已实现 — ChatScreen 跳到上/下一条
- **tree filters (`treeFilterMode`, `ctrl+t/u/l/a/** (was MISSING-GUI) → 已实现 — SessionTreeScreen.TreeFilter
- **session picker: search / sort / named filter /** (was MISSING-GUI) → 已实现 — SessionsScreen.query/byName/namedOnly
- **scoped models selector (`/scoped-models`)** (was PARTIAL) → 已实现 — PiCommandAction.OpenModelScope
- **A user theme JSON changes the app's own colour** (was MISSING-GUI) → 已实现 — PiThemeLoader.load + PiSessionViewModel.theme
- **Theme discovery from `<agentDir>/themes/*.json** (was PARTIAL) → 已实现 — PiThemeLoader.discover
- **Theme discovery from `.pi/themes/*.json` (proj** (was PARTIAL) → 已实现 — PiThemeLoader.discover
- **Themes from packages (`themes/`, `pi.themes`)** (was PARTIAL) → 已实现 — PiThemeLoader.discover
- **Theme used by `export_html`** (was N/A) → 已实现 — PiSessionViewModel.exportSession
- **Delete a session** (was MISSING-GUI) → 已实现 — SessionsScreen.combinedClickable + PiSessionViewModel.deleteSession
- **Search / sort / named-only filter in the picke** (was MISSING-GUI) → 已实现 — SessionsScreen.query/byName/namedOnly
- **Resume most recent session on launch (`pi -c`)** (was MISSING-GUI) → 已实现 — PiSessionViewModel resume gate
- **Export to JSONL** (was MISSING-GUI) → 已实现 — PiSessionViewModel.exportJsonl
- **Per-cwd session grouping in the UI** (was PARTIAL) → 已实现 — SessionsScreen.groupBy{cwd}
- **npm/git packages (`packages`)** (was PARTIAL) → 已实现（未提交） — PiPackagesHost → PiPackagesScreen
- **Built-in vs user-installed package distinction** (was MISSING-GUI) → 已实现（未提交） — PiPackagesScreen builtin/user distinction
- **Images into `prompt`** (was PARTIAL) → 已实现 — ChatScreen.imagePicker → send(text, images)
- **Picking an image from the device** (was MISSING-GUI) → 已实现 — ChatScreen.imagePicker
- **Pasting an image from the clipboard** (was MISSING-GUI) → 已实现 — ChatScreen.imagePicker
- **Rendering attachments in the transcript** (was PARTIAL) → 已实现 — ImageGridBlock.decodeImage
- **`@` file mentions** (was MISSING-GUI) → 已实现 — ChatScreen mention list
Every row whose grade **changed** from a form of "not done" to a form of "done" between `a7b7738` and
`182823e`, with the proof. This is the valuable output; a stale "not done" sends someone to rebuild
working code.

- **`prompt` `streamingBehavior: followUp`** (PARTIAL) → 已实现 — PiSessionViewModel.sendFollowUp
- **`follow_up`** (MISSING-GUI) → 已实现 — PiSessionViewModel.sendFollowUp
- **`clear_queue`** (PARTIAL) → 已实现 — ChatScreen.mergeRestoredQueue
- **`export_html`** (PARTIAL) → 已实现 — PiSessionViewModel.exportSession
- **`export_html` `outputPath`** (PARTIAL) → 已实现 — PiSessionViewModel.exportSession
- **`pi install` / `remove` / `list` / `update`** (CLI-ONLY) → 已实现（未提交） — PiPackagesHost → PiPackagesScreen + PiPackageService
- **`--continue` / `-c`, `--resume`, `--session`, ** (MISSING-GUI) → 已实现 — PiSessionViewModel resume gate + app.sessions.resumeLast
- **queue editing: restore queued text on Esc** (MISSING-GUI) → 已实现 — ChatScreen.mergeRestoredQueue
- **queue a follow-up message (`alt+enter`)** (MISSING-GUI) → 已实现 — ChatScreen → sendFollowUp
- **expand/collapse thinking (`ctrl+t`)** (PARTIAL) → 已实现 — ChatScreen thinkingDefaultExpanded
- **`hideThinkingBlock` (hide thinking entirely)** (MISSING-GUI) → 已实现 — ChatScreen hideThinking
- **transcript search (`ctrl+shift+f`)** (MISSING-GUI) → 已实现 — ChatScreen.searchQuery/SearchBar
- **jump to previous/next message (`ctrl+shift+up/** (MISSING-GUI) → 已实现 — ChatScreen 跳到上/下一条
- **tree filters (`treeFilterMode`, `ctrl+t/u/l/a/** (MISSING-GUI) → 已实现 — SessionTreeScreen.TreeFilter
- **session picker: search / sort / named filter /** (MISSING-GUI) → 已实现 — SessionsScreen.query/byName/namedOnly
- **scoped models selector (`/scoped-models`)** (PARTIAL) → 已实现 — PiCommandAction.OpenModelScope
- **A user theme JSON changes the app's own colour** (MISSING-GUI) → 已实现 — PiThemeLoader.load + PiSessionViewModel.theme
- **Theme discovery from `<agentDir>/themes/*.json** (PARTIAL) → 已实现 — PiThemeLoader.discover
- **Theme discovery from `.pi/themes/*.json` (proj** (PARTIAL) → 已实现 — PiThemeLoader.discover
- **Themes from packages (`themes/`, `pi.themes`)** (PARTIAL) → 已实现 — PiThemeLoader.discover
- **Theme used by `export_html`** (N/A) → 已实现 — PiSessionViewModel.exportSession
- **Delete a session** (MISSING-GUI) → 已实现 — SessionsScreen.combinedClickable + PiSessionViewModel.deleteSession
- **Search / sort / named-only filter in the picke** (MISSING-GUI) → 已实现 — SessionsScreen.query/byName/namedOnly
- **Resume most recent session on launch (`pi -c`)** (MISSING-GUI) → 已实现 — PiSessionViewModel resume gate
- **Export to JSONL** (MISSING-GUI) → 已实现 — PiSessionViewModel.exportJsonl
- **Per-cwd session grouping in the UI** (PARTIAL) → 已实现 — SessionsScreen.groupBy{cwd}
- **npm/git packages (`packages`)** (PARTIAL) → 已实现（未提交） — PiPackagesHost → PiPackagesScreen
- **Built-in vs user-installed package distinction** (MISSING-GUI) → 已实现（未提交） — PiPackagesScreen builtin/user distinction
- **Images into `prompt`** (PARTIAL) → 已实现 — ChatScreen.imagePicker → send(text, images)
- **Picking an image from the device** (MISSING-GUI) → 已实现 — ChatScreen.imagePicker
- **Pasting an image from the clipboard** (MISSING-GUI) → 已实现 — ChatScreen.imagePicker
- **Rendering attachments in the transcript** (PARTIAL) → 已实现 — ImageGridBlock.decodeImage
- **`@` file mentions** (MISSING-GUI) → 已实现 — ChatScreen mention list

### What this sweep does not settle

* **Reachability for the `已实现（符号在位核对）` rows.** Symbol presence is necessary, not sufficient:
  a builder can exist with no caller. Those rows are the ones the audit itself verified at `a7b7738`,
  and the files are unchanged or improved since, but a frozen-tree call-graph check is the real proof.
* **§1.8's ninth row.** §1.8's own subtotal says nine rows; eight parse into the grade vocabulary, so
  one row's "App status" cell is phrased outside it. It is in neither this table nor §7.
* **pi's runtime behaviour for these rows.** Every `pi 有 …` above reproduces the audit's citation; it
  is **not** a fresh read of `/root/pi-src` for all 147. The rows whose pi source was re-read this
  session are the ones listed in §7 and in the changed-row list above.
