# Rendering-pipeline review: `pi-android` vs `pi`

Source-verified review of the transcript/rendering pipeline against `pi` at
`/root/pi-src`. Findings only; each carries an app citation and a pi (or spec)
citation.

**Counts by rating: 34 findings — DEFECT 8, GAP 10, PERF 8, DEAD CODE 6,
INCONSISTENCY 2.** Events parsed but never rendered: **1** (`turn_start`), plus
**8 of 11** `message_update` delta kinds.

## As-of, and the moving tree

* `pi-android` commit **`98678da`** (`docs: add the gap disposition table, …`).
* `pi-src` commit **`bbb61e3`**.
* `git status --short` grew from 31 to 33 entries during the review (it was still
  growing at the final pass). Twelve concern the rendering pipeline this report
  covers: `ui/PiSessionViewModel.kt`, `ui/PiRoot.kt`, `ui/screens/ChatScreen.kt`,
  `ui/render/PiMarkdown.kt`, `ui/blocks/BranchSummaryBlock.kt`,
  `ui/blocks/CompactionBlock.kt`, `ui/theme/PiTheme.kt`,
  `ui/terminal/{TerminalPane,TerminalSurface,TerminalKeyBar}.kt`, and the new
  untracked `ui/theme/PiThemeFiles.kt` + `ui/terminal/TerminalSettings.kt`, plus
  the new `rpc/PiLaunchOptions.kt`.
* Line numbers were verified against the working tree twice (last pass 19:46).
  Method: every cited file was checked with `git diff --shortstat` — **all cited
  files are clean vs HEAD except `ui/PiSessionViewModel.kt`,
  `ui/screens/ChatScreen.kt`, `ui/render/PiMarkdown.kt`, `ui/theme/PiTheme.kt`
  and `ui/PiRoot.kt`**, whose citations were re-read line by line at the end. In
  that final pass `ChatScreen.kt` had moved by ~50 lines in four minutes while
  `rpc/**`, `engine/PiEngineSession.kt` and all `ui/blocks/**` I cite were
  untouched, so **treat a line mismatch as a move, not a stale finding — the
  quoted snippet is authoritative.**
* Nothing here was written to any file except this report. The parent declared
  `app/src/main/kotlin/app/pi/terminal/**` and `ui/terminal/**` unowned; the
  mtimes say otherwise (`terminal/TerminalController.kt` written 19:15:27,
  `ui/terminal/TerminalSettings.kt` created 19:12:50, `ui/terminal/*` written
  19:40+), so those files are also occupied in practice and no edit was made
  there. _(**Later note:** the hand-written emulator those files belonged to was
  replaced by `termlib 0.0.13` — `app/src/main/kotlin/app/pi/terminal/` no longer
  exists, and `ui/terminal/` now holds `TerminalBridge.kt`, `TerminalKeyBar.kt`,
  `TerminalPalette.kt`, `TerminalPane.kt`, `TerminalSettings.kt`. The paragraph
  above is kept as the review-time snapshot.)_
  Every defect found lives in a file that is occupied by an agent, so all
  fixes below are patches, not edits.

### Cannot be verified without a device (no emulator, no Gradle, no APK)

* **Anything about actual appearance**: text overflow/clipping, whether a long
  path ellipsises, whether the 400-line tool card is scrollable in practice,
  whether `horizontalScroll` on a code fence fights the transcript's vertical
  scroll. Code-level only: the contrast numbers below are computed from
  `PiPalette.kt` hex values, not sampled from pixels.
* **Real frame timings.** All PERF findings are static cost arguments
  (allocation per event, O(n) work per token), not measurements. Whether they are
  visible on a given phone is a measurement question.
* **Whether the markdown renderer (`multiplatform-markdown-renderer-m3` 0.45.0)
  internally memoises** `colors`/`typography`/`components`. The artifact is not
  in any local cache, so F32's "memoisation defeated" half is unverified; the
  per-composition allocation is certain.
* **Compose skippability** of `UiState`/`PiSessionViewModel` (no Compose compiler
  plugin in `tools/typecheck.sh`), so F7 states which composables *read* the
  invalidated state rather than claiming exact recomposition counts.
* **`/app/*` device endpoints and the real Terminal pane** — out of scope, and
  no device here.

`bash tools/typecheck.sh` on the working tree: last line
`typecheck: OK (:rpc + :app, cross-module boundary reproduced)`, zero `error:`
diagnostics. No source file was changed by this review, so that is the tree's
state, not a verdict on a diff.

---

# Findings, by severity

## F1 — DEFECT — a message sent mid-turn never reaches the screen

* App: `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:1245-1262` — the
  streaming branch sends `PiCommands.steer(...)` and returns; only the
  non-streaming branch calls `engine.prompt(trimmed, images)` (which is the only
  path that echoes locally, via `PiEngineSession.prompt` →
  `transcript.onUserPrompt`). `sendFollowUp` (`:1293-1305`) sends
  `PiCommands.followUp` with no echo either.
* App, the receiving side: `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:587-593`
  — `is PiEvent.MessageStart -> { if (event.role == "assistant") { …maps.clear() }
  }` appends nothing for `role == "user"`; `:596-608` (`MessageEnd`) also appends
  nothing for a user message. So the row is never created, live.
* pi: `packages/coding-agent/src/modes/interactive/interactive-mode.ts:3224-3226`
  — `} else if (event.message.role === "user") { this.addMessageToChat(event.message); … }`.
* Consequence: type while the agent is working (the normal case for steering);
  the composer clears, a transient "穿插 N" chip appears, and the message itself
  is **invisible** — the user cannot see what they queued or what was delivered.
  It reappears only after reopening the session, when `get_entries` replay
  projects it.
* Minimal fix (one line, mirrors `PiEngineSession.prompt`'s own echo): in
  `PiSessionViewModel.send`, call `engine.transcript.onUserPrompt(trimmed, images)`
  before `engine.send(PiCommands.steer(...))`; same in `sendFollowUp`. The failed-
  steer case is already surfaced through `PiEvent.Response` → `lastError`.

## F2 — DEFECT — aborted/errored turns leave tool cards spinning "运行中" forever

* App: `rpc/.../Transcript.kt:596-608` — the `MessageEnd` branch calls
  `finishStreaming()` and nothing else; no `ToolCall` is closed. The card renders
  `ToolStatus.Pending` as `…` + "运行中"
  (`app/.../ui/blocks/ToolCallBlock.kt:41-60`).
* pi: `interactive-mode.ts:3294-3302` — on `stopReason === "aborted" || "error"`
  every pending tool component is handed
  `updateResult({content:[{type:"text",text:errorMessage}], isError:true})` and
  `pendingTools.clear()`; replay does the same at `:3735-3746`.
* Consequence: press Stop (or hit a provider error/rate limit) while a tool is
  running and the card shows a live "运行中" indefinitely — indistinguishable
  from a hang. The status glyph, container colour and footer all stay pending.
* Minimal fix: in the same `MessageEnd` branch, when `event.stopReason` is
  `"aborted"`/`"error"`, walk `items`, set every `ToolCall` still `Pending` to
  `ToolStatus.Error` with `output` (or `isError`) set from the stop reason, and
  return `Updated(lastTouchedIndex)`.

## F3 — DEFECT — a truncated (`length`) or aborted answer reports nothing

* App: `rpc/.../Transcript.kt:596-608` ignores `stopReason`; `startReason` is
  parsed at `rpc/.../Events.kt:371` (`stopReason = msg?.str("stopReason") ?: …`)
  and read by nobody. `errorMessage` is not parsed at all.
* pi: `components/assistant-message.ts:182-200` — `length` → `"Response was
  truncated before completion."`, `aborted` → the abort message (using
  `message.errorMessage`), `error` → `"Error: <errorMessage>"`, each as a red
  line under the partial content.
* Consequence: an answer cut off by the output-token limit looks like a complete
  answer. Nothing on screen distinguishes it from a finished turn — this is silent
  information loss, not a cosmetic gap.
* Minimal fix: in `MessageEnd`, map `stopReason` to an `ErrorText` append
  (`length` → “回复被令牌上限截断”; `error` → “模型调用失败” + reason) and mark the
  pending tools as in F2. Add `errorMessage` to `PiEvent.MessageEnd` (Events.kt:110-128)
  so the detail is pi's own text.

## F4 — DEFECT — the follow-the-tail scroll is re-animated on every token and steals the scroll

* App: `app/.../ui/screens/ChatScreen.kt:213-218` — the comment says

  ```
  // Follow the tail while streaming, but never steal the scroll: only scroll
  // when the count grows, and let the user's own scrolling win afterwards.
  LaunchedEffect(state.revision) {
      val last = listState.layoutInfo.totalItemsCount - 1
      if (last >= 0) listState.animateScrollToItem(last)
  }
  ```

  Neither claim in that comment is implemented: there is no comparison against
  the previous item count, and no check of whether the list is already at the
  bottom.
* `state.revision` moves on **every** event (`PiEngineSession.kt:139-140`), so the
  effect fires once per streamed token.
* Spec: `docs/pi-android-ui-spec.md:433` §4.5 “**流式时若已解锁跟随，绝不抢滚动**（最常被
  做错的地方）”, and `:436` “默认跟随最新。用户上滑 > 1 屏 → 解锁跟随 → 右下浮出
  「↓ 回到最新（N）」FAB”.
* Consequence: scroll up to read something earlier while the model is still
  streaming and the next token yanks the view back to the bottom — the exact
  failure the spec calls out as the most commonly botched one. Each token also
  cancels and restarts an `animateScrollToItem` animation, which is a large part
  of the stutter while streaming.
* Minimal fix: keep the previous item count in `remember { mutableStateOf(0) }`
  and only act when it grew; gate on the list already being at the bottom
  (`listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == last - 1`); use
  the non-suspending `scrollToItem` while `state.streaming` and reserve
  `animateScrollToItem` for a user-triggered “回到最新”.

## F5 — DEFECT — every `entry_appended` is projected twice

* App: `app/.../engine/PiEngineSession.kt:138-140` — `transcript.onEvent(event)`
  already routes `PiEvent.EntryAppended` into the reducer (`Transcript.kt:675`
  `is PiEvent.EntryAppended -> event.entry?.let(::onEntry) ?: …`). Then
  `app/.../ui/PiSessionViewModel.kt:666-675` calls
  `engine.transcript.onEntry(entry)` a second time, plus a manual
  `syncTranscript(engine)`.
* The comment justifying the second call (“the engine's revision counter only
  moves for events it handled itself”) is contradicted by the code it points at:
  `PiEngineSession.kt:139` bumps `_changes` for **every** non-`response` event
  (including one whose `TranscriptChange` is `None`), so the revision already
  moved and the `engine.revision.collect` at `PiSessionViewModel.kt:588-590`
  already republishes.
* Why it has not bitten yet: pi emits `entry_appended` only from the extension
  `appendEntry` path (`packages/coding-agent/src/core/agent-session.ts:2614-2621`)
  and that always carries a `type: "custom"` entry (`session-manager.ts:1136-1147`),
  which `onEntry` has no case for (`Transcript.kt:1112`), so both calls are no-ops
  today.
* Consequence: latent. The moment `custom` is handled (F6) or a newer pi appends a
  projectable entry type, every extension entry renders **twice**, with two
  different `LazyColumn` keys — a duplicated card plus a duplicated day separator
  risk.
* Minimal fix: delete lines `667-674` in `PiSessionViewModel.kt` (the
  `onEntry` call and the `syncTranscript` call); the revision flow already covers
  it.

## F6 — GAP — extension `custom` entries (`entry_appended`) render nothing

* App: `rpc/.../Transcript.kt:1068-1112` — `onEntry` handles `message`,
  `model_change`, `thinking_level_change`, `compaction`, `branch_summary`,
  `custom_message`/`hook_message`, `skill`/`skill_invocation`; `custom` falls to
  `else -> TranscriptChange.None` (`:1112`). `ENTRY_EVENT_TYPES`
  (`Transcript.kt:1561-1570`) omits `custom` too.
* pi: `interactive-mode.ts:3202-3205` → `addCustomEntryToChat(event.entry)`
  (`:3557-3562`) renders it through the extension's registered entry renderer
  (`components/custom-entry.ts:40-61`). Fairness: pi is *silent* when the
  extension registered no renderer for that `customType` (`custom-entry.ts:54-56`
  returns without adding a child), so the app's silence is only wrong for
  extensions that do register one.
* Consequence: an extension that keeps visible state with `pi.appendEntry()`
  shows nothing in the app even where the desktop TUI draws it. There is no
  fallback row either, so the entry is invisible in the transcript (the session
  tree screen shows it, which is a different surface).
* Minimal fix: add a `"custom"` case to `onEntry` that appends a **distinct,
  muted** row carrying `customType` and a compact rendering of `data` (not a
  `HookMessage` — a `custom` entry is extension state deliberately kept out of
  the model's context, while `custom_message` *is* context; reusing the
  `HookMessage` block would assert something false). Renderer-defined output
  itself is unreachable over RPC and must not be promised.

## F7 — PERF — the whole screen and the whole app root are invalidated on every token, and the change index the reducer computes is thrown away

* App: `app/.../ui/PiSessionViewModel.kt:611-617` —
  `transcript = engine.transcript.transcript.toList(), revision = engine.revision.value, streaming = …`
  runs once per event, and `PiEngineSession.kt:139-140` bumps the revision for
  **every** non-`response` event regardless of whether anything changed.
* App, read sites: `ChatScreen.kt:123` (`val state by session.state.collectAsState()`
  for the whole screen) and `PiRoot.kt:78` (the same StateFlow collected by the
  app root, which re-evaluates `Scaffold`, the bottom bar and the destination
  switch).
* App, the discarded signal: `rpc/.../Transcript.kt:490-503` documents
  `TranscriptChange.Updated(index)` as “so the UI can update one row instead of
  recomposing the list”; `PiEngineSession.kt:138` computes it into a local `val
  change` that is only compared against `None` — the index is never published.
* Spec: `docs/pi-android-ui-spec.md:354` §4.2 “**流式优化**：正在流式的那个 item 的文本
  放在一个独立的 `MutableState<String>` 里，只有那个 composable 读它 → 列表其余部分不重组
  （这是打字机流畅的关键）”, and §4.3's backpressure rule (drop intermediate deltas past 32 pending).
* Consequence: cost per token is O(session length) — a fresh N-element list, a
  fresh `UiState`, a re-run of the `LazyColumn` content lambda
  (`ChatScreen.kt:402-409`), plus invalidated screen-level state. A long session
  gets progressively laggier while streaming; nothing bounds it.
* Minimal fix (cheap 80 %, no new architecture): publish the change instead of the
  list — `PiEngineSession` keeps the reducer's `TranscriptChange` and exposes
  `changedIndex`; `syncTranscript` then updates `UiState.transcript` **without**
  `.toList()` when `change is None` (skip the whole publish), and the streaming
  row's text lives in a per-row `MutableState<String>` as §4.2 asks. Full
  conformance is the §4.2 design and is a refactor — report-only.

## F8 — PERF — `tool_execution_update` is applied unthrottled (spec asks 200 ms)

* App: `rpc/.../Transcript.kt:886-900` — every update replaces the row
  (`items[index] = current.copy(output = merged)`) and returns
  `TranscriptChange.Updated(index)`; `PiEngineSession.kt:139-140` bumps the
  revision for each, so each chunk drives the F7 publish path.
* Spec: `docs/pi-android-ui-spec.md:369` §4.3, row `tool_execution_update` → “**节流
  200ms** 追加输出（避免抖动）”.
* pi: not applicable (pi re-renders on its own tick).
* Consequence: a `bash`/`grep` tool emitting hundreds of chunks per second
  publishes hundreds of full `UiState`s per second; the visible symptom is a
  jittering output area and dropped frames during exactly the long operations
  where the user is watching the screen.
* Minimal fix: in `onToolUpdate`, keep updating the stored row but return
  `TranscriptChange.None` unless ≥200 ms have passed since the last published
  update for that `toolCallId` (the reducer already has `now()`); the final
  `tool_execution_end` always publishes.

## F9 — PERF — session replay and entry projection run on the main thread

* App: `PiSessionViewModel.kt:598-606` — `viewModelScope.launch { replayHistory(engine); … }`;
  `viewModelScope` is `Dispatchers.Main.immediate`. `replayHistory` (`:1074-1086`)
  then does `PiResponses.entries(response)` (parsing the entire `get_entries`
  payload) and `engine.transcript.seedFromHistory(...)` (projecting every entry)
  inline. The same function is called on every session switch/`fork`/`clone`
  (`:1725`).
* pi cannot help: `get_entries` has no limit, only a forward `since` cursor
  (`packages/coding-agent/docs/rpc.md:717-728`), so the client cannot ask for
  less.
* Consequence: opening or switching to a large session blocks the frame thread
  for the whole parse + projection — the visible symptom is a frozen UI with no
  progress indication (there is no `Boot.Working` for a replay). The author
  already knows the pattern: `refreshTuiOnlyExtensions` wraps its scan in
  `withContext(Dispatchers.IO)` (`:1198`), and this call does not.
* Minimal fix: `withContext(Dispatchers.Default) { seedFromHistory(PiResponses.entries(response)) }`,
  then `syncTranscript` on Main. Note that `seedFromHistory` mutates the reducer,
  so the reducer must not be read from another coroutine meanwhile — the
  `revision` collector does read `transcript.streaming`, so a mutex or doing the
  parse off-thread and the seed on Main is the safe split (parse is the expensive
  half).

## F10 — GAP — no live token/context indicator; all three `usage` payloads are parsed and dropped

* App: `rpc/.../Events.kt:522-530` defines `TokenUsage`; it is parsed for
  `message_update` (`:362`), `message_end` (`:372`) and
  `compaction_end.result` (`Responses.kt:177`). A repository-wide grep for
  `TokenUsage` in `app/src/main` returns **nothing** — no reader exists, so no
  usage figure is ever painted.
* Spec: §4.1 page structure — the 32 dp status row is
  `~/projects/api · main · ↑24.1k ↓3.2k · ◐ 52% · sonnet-4.5`; §4.9 wants the row
  in `warning` above 70 % context and `error` above 90 %. The app's AppBar second
  line shows only `session.engineLabel(state)` (busy/工作中/就绪).
* pi: the TUI footer carries the same accounting (`modes/interactive/components/footer.ts`),
  and streaming usage is used for the cache-miss/diagnostics notices
  (`interactive-mode.ts:3306`, `:3682-3696`).
* Consequence: while streaming the user has no idea how full the context is, so
  the “about to auto-compact” signal the spec asks for cannot exist. Totals are
  visible only by opening the stats sheet, which polls `get_session_stats`
  (`ChatSheets.kt:513`) — i.e. after the fact.
* Minimal fix: accumulate the last `TokenUsage` into `UiState.meta` and render a
  status line from `totalTokens`/cost plus a context percentage when
  `get_state`/`get_session_stats` supplies the window size.

## F11 — DEFECT — block margins and the block rhythm are ~2× the spec

* App: `ChatScreen.kt:393-398` —
  `contentPadding = PaddingValues(vertical = PiSpacing.unit, horizontal = PiSpacing.screen)`;
  then every block adds its own margin: `ui/blocks/BlockChrome.kt:57-64`
  (`BlockColumn`) is `.padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit / 2)`.
  `PiSpacing.screen = 16.dp`, `PiSpacing.unit = 18.dp` (`ui/theme/PiTheme.kt:32-41`).
* Net: **32 dp** side margins; gap between two blocks = 18 (list spacing) + 9 + 9
  (block padding) = **36 dp**.
* Spec: `docs/pi-android-ui-spec.md:779` §7.4 通用规则 “宽度撑满；块间距 16dp”; the
  `assistant-text` row (`:784`) says “内边距左右 0（靠页面边距 16dp）”.
* Consequence: on a 360 dp phone the prose column is 296 dp instead of 328 dp —
  10 % of the width lost, so code fences/tables wrap ~10 % sooner — and the stream
  reads as loosely spaced (36 dp of air between rows where the spec asks 16).
* Minimal fix: remove `.padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit / 2)`
  from `BlockColumn` and set the `LazyColumn`'s `vertical` spacing to `16.dp`.
  The two blocks that do not use `BlockColumn` (`DateSeparatorBlock.kt:30`,
  `ModelChangeBlock.kt:38`) already pad themselves, so nothing loses its margin.

## F12 — DEFECT — dark-theme `dim` text is at 2.1–2.9:1, under the spec's 3:1 floor

Computed from `PiPalette.kt` (WCAG 2.x relative luminance):

| pair | ratio | where it is text |
|---|---|---|
| `dim` #666666 on `userMessageBg` #343541 | **2.11:1** | `ui/blocks/UserMessageBlock.kt:54-59` — the user message's own timestamp |
| `dim` #666666 on `cardBg` #1E1E24 | **2.89:1** | `ui/blocks/BranchSummaryBlock.kt:64-70` — the branch id |
| `dim` #666666 on `pageBg` #18181E | 3.08:1 | `ModelChangeBlock.kt:46`, `NoticeBlock.kt:48`, `DateSeparatorBlock.kt:42` (passes the 3:1 meta floor, barely) |

* Spec: `docs/pi-android-ui-spec.md:831` §9 无障碍 “**对比度**：正文 ≥4.5:1，元信息 ≥3:1”.
* pi: these are pi's own token values (`dark.json` `vars.dimGray = "#666666"`), so
  the palette is faithful; the defect is in which text the app paints with `dim` —
  a 11.5 sp timestamp on a mid-grey bubble is the least readable text in the
  transcript by a factor of two.
* Consequence: the user-message timestamp and the branch id are effectively
  unreadable on a phone in daylight; TalkBack reads them but sighted users cannot.
* Minimal fix: use `palette.muted` for these two (4.47:1 on the canvas; on
  `userMessageBg` it is 3.07:1, still under 4.5 — so for the timestamp on the
  bubble use `palette.userMessageText` at 60 % alpha or `#A0A0A0`), or move the
  timestamp out of the bubble. Do **not** change `PiPalette` — theme fidelity is a
  stated product rule (`PiPalette.kt:6-29`).

## F13 — DEFECT — content tokens below 4.5:1 (tool output, diff context, thinking headline)

| pair | ratio | where it is content |
|---|---|---|
| `toolOutput` #808080 on `toolPendingBg` #282832 | **3.69:1** | `ToolCallBlock.kt:91-95` (tool output, mono 13/20), `DiffBlock.kt:103-106` (raw diff body) |
| `toolDiffContext` #808080 on `toolPendingBg` #282832 | **3.69:1** | `DiffBlock.kt:175` (context lines, mono 13/20) |
| `toolOutput` #808080 on `cardBg` #1E1E24 | **4.20:1** | `ErrorBlock.kt:80-84` (error detail) — the `SystemPromptBlock` this row also cited **no longer exists** (see F22) |
| `muted` #808080 on `pageBg` #18181E | **4.47:1** | `ThinkingBlockBlock.kt:51-55` — the “思考 12s” headline in `bodyMedium` |
| `customMessageLabel` #9575CD on `customMessageBg` #2D2838 | 3.87:1 | `CompactionBlock.kt:71-76`, `HookMessageBlock.kt:49-56` (labels — clears the 3:1 meta floor) |

* Spec: `docs/pi-android-ui-spec.md:831` §9 (same line as F12): body ≥4.5:1.
* pi: `toolOutput = gray = #808080` (`dark.json` `vars.gray`), so again the value
  is pi's; pi's terminal is not a phone in sunlight and pi prints one glyph per
  cell, not 13 sp body text.
* Consequence: tool output — the thing the user opens the card to read — is the
  lowest-contrast text in the app, and the thinking headline is under the floor
  even though it is set in `bodyMedium`.
* Minimal fix: keep the tokens for chrome, but render code/output bodies with a
  lifted variant (e.g. `toolOutput` composited 15 % toward `text` → ~4.9:1) via a
  small derived colour in the block, not in `PiPalette`.

## F14 — GAP — the light theme's `muted`/`dim` correction the spec demands does not exist

* App: `ui/theme/PiTheme.kt:249-267` — `palette: PiPalette = if (dark) PiPalette.Dark else PiPalette.Light`,
  passed straight into `MaterialTheme`; `PiThemeFiles` (theme-file loading) also
  returns `base` (`PiPalette.Light`) merged with the file's tokens
  (`PiThemeFiles.kt:274-276`, `:356`). No correction step anywhere.
* Spec: `docs/pi-android-ui-spec.md:98` §2.1 “对比度：正文 ≥4.5:1；`muted` 在浅色主题下需
  微调（pi 的 light 主题这项偏弱，App 要给一个修正档）” and `:831` the same sentence.
* Computed: light `muted` #6C6C6C on `userMessageBg` #E8E8E8 = **4.29:1**, on
  `toolPendingBg` #E8E8F0 = **4.31:1**; light `dim` #767676 on `pageBg` #F8F8F8 =
  4.28:1 and on `userMessageBg` = 3.71:1.
* Consequence: in light mode the metadata and tool output of a conversation sit
  just under the floor; the spec names this exact case as one the app must fix.
* Minimal fix: add one derived correction when the active palette is light
  (e.g. `muted`/`dim` darkened ~8 %) inside `PiTheme`'s palette resolution, and
  record it as a note in `PiResolvedTheme.notes` so an imported theme's fidelity
  claim stays honest.

## F15 — GAP — user messages render raw Markdown source

* App: `ui/blocks/UserMessageBlock.kt:40-46` — `Text(text = item.text, style = bodyLarge, color = palette.userMessageText)`.
  No markdown pass (`PiMarkdownText` is not called from this file).
* pi: `components/user-message.ts:40-56` — the user's text goes through
  `Markdown` with `userMessageText` as the colour, `preserveOrderedListMarkers`
  and `preserveBackslashEscapes`.
* Spec: §7.4 `user-message` “**支持 Markdown** 与图片”.
* Consequence: the same syntax renders two ways in one transcript — `**bold**`,
  `` `code` `` and list markers are typeset in an assistant message and shown
  literally in the user's own; a user who pastes a code block sees the fence.
* Minimal fix: route the body through `PiMarkdownText` (optionally adding a
  `textColor` parameter, since `piMarkdownColors()` hardcodes `text = palette.text`).
  pi's `preserveOrderedListMarkers`/`preserveBackslashEscapes` options may not be
  exposed by the renderer — check before promising exact parity.

## F16 — GAP — images returned by a tool are flattened to the literal text `[image]`

* App: `rpc/.../Events.kt:566-580` — `contentText` maps an `image` content block
  to `"[image]"`; `PiEvent.ToolExecutionEnd` (`Events.kt:146-154`) carries only
  `resultText`, and `TranscriptItem.ToolCall` (`Transcript.kt:59-81`) has no image
  list. So a tool's image bytes are discarded at the parser.
* pi: `components/tool-execution.ts:374-398` — every `content` block of type
  `image` is rendered as an `Image` (with kitty conversion at `:211-232`).
* Consequence: a screenshot tool, `read` of a PNG, or any chart-returning tool
  shows the user the four characters `[image]` where pi shows the picture — on a
  phone, the one payload the platform renders best.
* Minimal fix: parse `result.content[type=image]` into `List<PiImage>` on
  `ToolExecutionEnd`/`ToolCall` and render it with the existing
  `ImageGridBlock`. Note `ImageGridBlock` itself only draws labelled placeholders
  (`ImageGridBlock.kt:95-140` — the bytes are never decoded), so this buys the
  metadata now and needs a decoder to become a picture; the plugin is the
  recorded blocker in `docs/known-gaps.md` A3.

## F17 — GAP — tool output is hard-capped at 400 lines with no way to see the rest, and the >200 KB rule is absent

* App: `ui/blocks/ToolCallBlock.kt:90-102` renders `headLines(item.output, MAX_OUTPUT_LINES)`
  and, past the cap, a static line “仅显示前 400 行，完整输出请前往工作区”;
  `MAX_OUTPUT_LINES = 400` at `:134`. `expanded` only reveals those same 400 lines
  — there is no path to the remainder.
* pi: `components/tool-execution.ts:40` (`FALLBACK_PREVIEW_LINES = 10`) and
  `:156-170` — collapsed shows 10 lines with “… (N more lines, <key> to expand)”,
  **expanded shows all lines**.
* Spec: `docs/pi-android-ui-spec.md:357` §4.2 “超长输出：默认渲染前 200 行 + 「展开全部」
  （展开后启用虚拟滚动）；单块 >200 KB 直接给「前往工作区查看完整日志」”.
* Consequence: `bash` output over 400 lines is silently incomplete — the app both
  exceeds the spec's collapsed budget (400 vs 200) and denies the spec's “expand
  all”. A user reads a truncated log and believes it is the whole log.
* Minimal fix: while collapsed use the spec's 200 lines, and when `expanded`
  render the full text (the spec's virtual-scroll caveat applies); add the
  `>200 KB → 前往工作区` branch.

## F18 — GAP — compaction/branch-summary cost is parsed then dropped

* App: `rpc/.../Responses.kt:177` (`val usage: TokenUsage?` on `CompactionResult`);
  `CompactionMarker` (`Transcript.kt:108-119`) has no usage field, and
  `onCompactionEnd` (`Transcript.kt:1011-1055`) reads only
  `summary`/`tokensBefore`/`firstKeptEntryId`.
* pi: `interactive-mode.ts:3430-3436` appends a `compaction_cost` notice after a
  compacted session, and `:3803-3809` formats it (“Compaction … (~$0.03)”), also
  on replay (`:3791-3793`).
* Consequence: the user pays for a summarization call and the transcript never
  says so; the tokens-freed chip is shown but not the cost of freeing them.
* Minimal fix: carry `usage` onto `CompactionMarker` and append a `Notice` row (or
  a footer line) using pi's wording, gated the same way pi gates it
  (`settings.showCacheMissNotices`).

## F19 — GAP — five tap affordances are unreachable: their callbacks are never supplied

`BlockRenderer` has exactly one call site (`ChatScreen.kt:402-409`) and it passes
none of the five optional callbacks declared at `BlockRenderer.kt:42-46`.

| affordance | app where it is gated off | pi / spec |
|---|---|---|
| full-screen diff | `ui/blocks/DiffBlock.kt:87-97` — the “全屏” label exists only `if (onOpenFull != null)` | spec §4.8 “工具卡（edit/write） 点击 → 全屏 diff”; §7.4 “点击开全屏 diff（统一/并排可切）” |
| branch jump | `BranchSummaryBlock.kt:49-51` — with `onClick == null` the row only expands | spec §7.4 branch-summary “点击跳转” |
| model panel | `ModelChangeBlock.kt:40` — `Modifier.clickable` only `if (onClick != null)` | spec §4.8 “模型变更行 点击 → 打开模型面板” |
| image viewer | `ImageGridBlock.kt:100-106` — `clickable` only `if (onClick != null)`; `ChatScreen` passes nothing | spec §4.7/§7.4 “点击全屏（缩放/保存/分享）” |
| error retry | `ErrorBlock.kt:73-77` — the 重试 button exists only `if (onRetry != null)` | spec §7.4 error-text “一句人话 + 「详情」+ 「重试」”; §4.9 “流内 ErrorText + 「重试 / 换模型 / 查看详情」” |

* Consequence: the app looks like it has these features (the parameters, the
  gated UI, the KDocs) and has none of them. `ErrorBlock`'s retry is the worst:
  the spec's recovery path from a provider error does not exist on screen.
* Minimal fix per row: `onModelClick` → `sheet = ChatSheet.Model` (already exists,
  `ChatScreen.kt:186-190` region); `onBranchClick` → open the session tree
  (`session.refreshTree(); requestNav(NavRequest.SessionTree)`); the other three
  have no target in the app today (no diff route, no image viewer, no retry
  action) and need new UI — for those, either implement or **delete the dead
  parameter and the gated label** so the code stops claiming a feature.

## F20 — GAP — spec §4.8's per-block actions are absent

* App: `grep combinedClickable|detectTapGestures|longClick` over
  `app/src/main/kotlin/app/pi/ui/blocks/**` returns **nothing** — no block has a
  long-press or a copy action. The only copy path is the AppBar's
  “复制最后一条回复” (`ChatScreen.kt:345`, via
  `session.copyLastAssistantText`).
* Spec: §4.8 table — long-press any text → 复制/引用/搜索; user message →
  复制 · 编辑并从此分叉 · 删除后续; assistant message → 复制全部 · 保存为文件 · 重新生成;
  bash card → 复制命令 · 复制输出 · 在工作区终端重跑 · 查看完整日志; edit/write card →
  回滚此文件; read/grep/find/ls → 打开文件页并定位到行.
* Consequence: on a phone there is no way to get one tool's command or output out
  of the transcript, which is the single most common thing a user wants from a
  tool card. pi's TUI at least has scrollback copy.
* Minimal fix: new UI, not a fix — report only (parent's “不方便修的不修”).

## F21 — GAP — sending mid-turn silently steers instead of asking

* App: `PiSessionViewModel.kt:1245-1262` — `if (engine.transcript.streaming) { engine.send(steer(...)) } else { engine.prompt(...) }`;
  the UI shows no choice. The chip only reports counts
  (`ChatScreen.kt:666-679`).
* Spec: §4.4 发送行为 table — “STREAMING 点发送 | 弹三选 Sheet：**排队穿插**（`steer`）/
  **等它做完**（`followUp`）/ **打断，重说**（`clear_queue`+`abort`+重发）”; also
  “长按发送 → 直接选送达方式”.
* Consequence: a tap on Send during a turn makes an irreversible queueing decision
  on the user's behalf; `followUp` exists in the ViewModel (`sendFollowUp`,
  `:1293`) but is not reachable from any UI.
* Minimal fix: the three-way sheet on streaming send (needs new UI → report only);
  the `followUp` half is already implemented.

## F22 — DEAD CODE — the `system-prompt` block kind cannot be produced — **CLOSED: the item was deleted**

> **Closed after this review (verified in the tree).** The coordinated deletion this
> section asked for has happened: `ui/blocks/SystemPromptBlock.kt` no longer exists,
> `BlockRenderer`'s `when` has no `system_prompt` branch, `onSystemPrompt` has no
> caller anywhere in `app/**` or `rpc/**`, and `rpc/Transcript.kt`'s class note records
> that the item, the block and the hook went together. The snapshot below is kept as
> the reason the deletion was safe.

* App (snapshot — all three of these were later **deleted**, see the note above the section): `SystemPrompt` item (`Transcript.kt:157-162`), `SystemPromptBlock`
  (`ui/blocks/SystemPromptBlock.kt:31`) and the renderer branch
  (`BlockRenderer.kt:71`) exist; the only producer is
  `TranscriptReducer.onSystemPrompt` (`Transcript.kt:1444`), which **no main-source
  code calls** (`grep onSystemPrompt app/src/main rpc/src/main` → the declaration
  and a KDoc reference only; the single call site is
  `rpc/src/test/.../TranscriptBlocksTest.kt:217`).
* pi: there is no `system_prompt` session entry — the persisted union is
  `message | thinking_level_change | model_change | compaction | branch_summary |
  custom | custom_message | label | session_info`
  (`core/session-manager.ts:145-155`), the system prompt is only reachable through
  `getSystemPrompt()`, and pi's renderable item set is
  `AgentMessage | custom entry | compaction_cost`
  (`interactive-mode.ts:233`) — no system-prompt row.
* Consequence: none user-visible. It is a renderer and a spec row (§7.4
  system-prompt) that no wire data can ever fill.
* Minimal fix: either wire `onSystemPrompt` to a real source — the app has none,
  so this becomes an app-only feature that must be labelled as such — or delete
  the item, the block and the spec row's claim. Deleting is the honest option.

## F23–F27 — DEAD CODE and unreachable branches (grouped)

| id | app cite | why it can never run | fix |
|---|---|---|---|
| F23 | `rpc/.../Transcript.kt:71` (`val outputTruncated: Boolean = false`) + `ui/blocks/ToolCallBlock.kt:129` (`if (item.outputTruncated) parts += "已截断"`) | nothing anywhere assigns it (grep over the whole repo finds the declaration and this one read) | set it from pi's tool-result details (`details.truncation` / `details.truncated`) or drop the field and the label |
| F24 | `rpc/.../Events.kt:23-43` — `Start`, `TextStart`, `TextEnd`, `ThinkingStart`, `ThinkingEnd`, `ToolCallDelta`, `ToolCallEnd`, `Done` — all parsed, all fall into `else -> TranscriptChange.None` at `Transcript.kt:855` | pi emits every one of them (`packages/ai/src/types.ts` `AssistantMessageEvent`; providers push them, e.g. `api/anthropic-messages.ts:628-803`; `modes/json-event.ts:32-36` strips only `partial`) | drop the ones with no use, or wire `TextEnd.content` / `ToolCallEnd.toolCall` as the authoritative block text (which is also the fix for a lost-delta edge case) |
| F25 | `ui/blocks/BlockRenderer.kt:80-88` (`else -> NoticeBlock(... "暂不支持的内容块…")`) | `TranscriptItem` is a sealed interface in the same compilation unit and all 14 subclasses are matched above, so the branch is unreachable — while the *event* path it was written to compensate for (`PiEvent.Unknown`, `Transcript.kt:755-759`) renders nothing at all | move the “unknown, upgrade the app” row to `PiEvent.Unknown` handling in the reducer, where a newer pi's event can actually appear; delete this branch |
| F26 | `rpc/.../Transcript.kt:1450` (`onSkill`), `:1458` (`onModelChange`), `:1483` (`onError`), `:1103` (`"skill", "skill_invocation"` entry aliases), `:1568-1569` (`ENTRY_EVENT_TYPES` entries) | no main-source caller and no pi producer: pi has no `skill`/`skill_invocation` entry type or event (`_expandSkillCommand` rewrites the user message — `core/agent-session.ts`), so `onSkillEntry` is reachable only from a hand-fed `JsonObject`; the public helpers are called only by `TranscriptBlocksTest` | delete the unreachable aliases/helpers or document them as app-only APIs with a caller |
| F27 | `rpc/.../Transcript.kt:761` (`PiEvent.TurnStart` → `None`) | parsed but has no renderer; `TranscriptChange` is also discarded | no fix needed — pi uses `turn_start` only for a progress indicator (`interactive-mode.ts:3183-3193`) which the app expresses through `streaming`; recorded so nobody re-raises it |

## F28 — INCONSISTENCY — the same "expand this block" gesture differs per block

| block | gesture | cite |
|---|---|---|
| tool card | whole card (`BlockCard(modifier = clickable)`), plus an `ExpandLabel` that is *not* itself clickable | `ToolCallBlock.kt:63-68`, `:114-116` |
| diff | whole card | `DiffBlock.kt:51-56` |
| thinking | header row only (`ToggleRow`) | `ThinkingBlockBlock.kt:48`, `BlockChrome.kt:173-186` |
| hook message | header row only, and only when the body is long (`collapsible`) | `HookMessageBlock.kt:36`, `:43-46` |
| skill | header row only, always | `SkillInvocationBlock.kt:39` |
| ~~system prompt~~ | ~~header row only~~ | **block deleted — see F22** |
| compaction | the centre chip only | `CompactionBlock.kt:62-68` |
| branch summary | the header row, but what it *does* is callback-dependent (jump vs expand) | `BranchSummaryBlock.kt:49-51` |

* Consequence: a user who learns that tapping a tool card expands it learns
  nothing about the next card; the chip-only target on the compaction block is
  under a 48 dp touch target (spec §9 “触控目标：≥48dp” — the chip is
  `labelSmall` + `vertical = 4.dp`).
* Minimal fix: standardise on `ToggleRow` for every block with a body, and give
  the compaction chip `PiSpacing.touchTarget`-height padding.

## F29 — INCONSISTENCY — off-scale dp literals bypass `PiSpacing`/`PiShapes`

`PiSpacing` is `{18, 16, 12, 48, 56, 32, 64, 72}` and `PiShapes` is
`{16, 12, 24, 16, 16, 28, 28, 50 %, 50 %}` (`ui/theme/PiTheme.kt:31-54`). These
literals are not any of those values:

* `ui/blocks/BlockChrome.kt:61` and `:119` — `Arrangement.spacedBy(6.dp)` inside
  every block (the file's own KDoc at `:44-47` claims “nothing in this package
  hardcodes a value”).
* `ui/blocks/BlockChrome.kt:137-139`, `:161`, `:165` — `3.dp`, `2.dp`, `16.dp`.
* `ui/blocks/AssistantTextBlock.kt:50-52` — `4.dp`, `8.dp/16.dp`, radius `2.dp`.
* `ui/blocks/DiffBlock.kt:65-76`, `:92`, `:96`, `:120`, `:162`, `:167`, `:173`, `:179`
  — `1/2/6/8/16/30.dp`.
* `ui/blocks/ToolCallBlock.kt:77`, `:86`, `:94` — `10.dp`, `8.dp`, `2.dp`.
* `ui/blocks/SkillInvocationBlock.kt:41`, `:47`; `HookMessageBlock.kt:48`;
  `BranchSummaryBlock.kt:55`;
  `CompactionBlock.kt:64`, `:73`, `:108`; `NoticeBlock.kt:37`, `:44`;
  `ModelChangeBlock.kt:48`, `:58`; `ErrorBlock.kt:45`, `:68`, `:83`.

* Spec: §2.3 defines the radius scale, §7.4 the 16 dp block spacing; a literal that
  is in neither is drift rather than a decision.
* Consequence: two blocks that should read as the same component differ by 2 dp
  gaps and 2 dp radii; the divergence is invisible per block and obvious across
  the stream.
* Minimal fix: add the missing steps to `PiSpacing`/`PiShapes` (`space2 = 2.dp`,
  `space4`, `space6`, `space8`, `space10`, `stripe = 3.dp`, `innerRadius = 12.dp`
  is already `cardInner`) and replace the literals. Mechanical, mechanical-diff
  safe, but touches many files that are currently occupied.

## F30–F33 — PERF, per-composition allocation and O(n) work on hot rows

| id | app cite | cost | minimal fix |
|---|---|---|---|
| F30 | `ui/blocks/BlockChrome.kt:189-190` — `formatClock` builds `SimpleDateFormat("HH:mm", …)` on every call; called from `UserMessageBlock.kt:55`, `NoticeBlock.kt:46`, `DateSeparatorBlock.kt:39` | a locale/pattern parse per visible timestamped row, on every recomposition of that row (every token, via F7) | hoist one formatter (or use `java.time.format.DateTimeFormatter`) and `remember` it |
| F31 | `ui/blocks/ToolCallBlock.kt:107` + `:123-132` — `toolFooter` builds a `mutableListOf` + `joinToString`, and calls `lineCount(item.output)` (`BlockChrome.kt:217-218` = `text.count { it == '\n' }`) on every composition; when expanded, `headLines` (`BlockChrome.kt:221-225`) `split('\n')`s the whole output | O(output) scan per recomposition plus an array of every line of a possibly-megabyte tool result, on the row that recomposes on every output chunk | `remember(item.output) { lineCount(...) }` and `remember(item.output, MAX_OUTPUT_LINES) { headLines(...) }`; drop the list allocation |
| F32 | `ui/render/PiMarkdown.kt:70-79` — `Markdown(content = content, colors = piMarkdownColors(), typography = piMarkdownTypography(), padding = piMarkdownPadding(), dimens = piMarkdownDimens(), components = piMarkdownComponents())` — all five are plain functions called during composition (`PiMarkdownTheme.kt:54`, `:78`, `:119`, `:132`; `PiMarkdownComponents.kt:88`), returning fresh objects | five fresh objects per recomposition of every markdown block; if the library memoises on them, that memoisation is defeated — **the library's internals are not verifiable here** (artifact absent) | `remember { piMarkdownComponents() }` etc. — one line each, no behaviour change |
| F33 | `ChatScreen.kt:402-409` — `items(state.transcript, key = { it.key })` with no `contentType` | `LazyColumn` cannot reuse a slot across differing block kinds while scrolling a long session, so every kind change re-inflates | add `contentType = { it::class }` (the item types are already distinct) |

## F34 — PERF — nothing bounds the transcript or the entry list

* App: the reducer's `items` grows without limit (`Transcript.kt:524`), the
  `UiState` republishes all of it (F7), and `ChatScreen` renders all of it
  (`ChatScreen.kt:402`). `replayHistory` asks for the whole log (F9).
* Spec: `docs/pi-android-ui-spec.md:436` §4.5 “长会话加载：打开时先渲染最后 50 条，向上滚动
  时分批加载更早的 entry…顶部显示加载指示”.
* pi cannot serve that: `get_entries` documents only a forward `since` cursor
  (`docs/rpc.md:719`), so backward paging is impossible without a second
  mechanism. Recorded so nobody builds a fix against an API that cannot answer.
* Consequence: memory and per-token cost grow monotonically for the life of a
  session; there is no cap and no “load earlier” affordance.
* Minimal fix: cap what is *rendered* client-side (e.g. keep the reducer whole but
  slice the list for the `LazyColumn`, with an “加载更早” header that extends the
  slice) — this is the spec's visible behaviour and needs no pi change.

---

## Event-coverage matrix

pi's stdout = `session.subscribe(...)` → `output(toJsonEvent(event))`
(`packages/coding-agent/src/modes/rpc/rpc-mode.ts:355-357`), so **every**
`AgentSessionEvent` (`core/agent-session.ts:143-186`) reaches the wire, plus
`extension_error`/`extension_ui_request` emitted directly by rpc-mode
(`:129-269`, `:349`). `toJsonEvent` strips only `partial` from `message_update`
(`modes/json-event.ts:32-36`).

| # | event (rpc.md §Event Types, `docs/rpc.md:859-884`) | pi emit site | parsed | folded | rendered | verdict |
|---|---|---|---|---|---|---|
| 1 | `agent_start` | `agent/src/agent-loop.ts:110,139` | `Events.kt:347` | `Transcript.kt:609-612` (`streaming = true`) | composer Stop icon `ChatScreen.kt:458`, `:720` | OK |
| 2 | `agent_end` | `agent-session.ts:666` (adds `willRetry`) | `:348` | `:613` | `finishStreaming()` | OK |
| 3 | `agent_settled` | `agent-session.ts:628-629` | `:349` | `:614` + `PiSessionViewModel` refresh | state refresh | OK |
| 4 | `turn_start` | `agent-loop.ts:111,140` | `:352` | none (`:761`) | — | **parsed, never rendered** (pi uses it only for progress/status, `interactive-mode.ts:3183-3193`; parity OK, recorded) |
| 5 | `turn_end` | `agent-loop.ts:216,243` | `:353-355` | `:630-634` day separator | `DateSeparatorBlock` | OK (`toolResultCount` is parsed and unused — harmless) |
| 6 | `message_start` | `agent-loop.ts:113`; `agent-session.ts:787-791` | `:357` | `:587-593` | assistant rows appear on first delta | **partial — `role: "user"` and `role: "custom"` ignored → F1**; on replay, user rows come from `onEntry` instead |
| 7 | `message_update` | `agent-loop.ts:337-338`; shaped at `json-event.ts:48-66` | `:358-364` | `:766-857` | `AssistantTextBlock` / `ThinkingBlockBlock` / `ToolCallBlock` / `ErrorBlock` | OK for the four; **8 of 11 delta kinds dropped (F24)**; `usage` dropped (F10) |
| 8 | `message_end` | `agent-loop.ts` (`message_end`); `agent-session.ts:800-806` | `:366-376` | `:596-608` | `finishStreaming()` | **`stopReason` parsed (`:371`) and ignored; `errorMessage` not parsed → F2, F3** |
| 9 | `bash_execution_update` | `agent-session.ts:3027` | `:398-401` | VM `:679-687` | `BashPanel` | OK |
| 10 | `tool_execution_start` | `agent-loop.ts:386,444,499` | `:378-382` | `:859-884` | `ToolCallBlock` | OK |
| 11 | `tool_execution_update` | `agent-loop.ts:695` | `:384-388` | `:886-900` | `ToolCallBlock` | rendered, **unthrottled → F8** |
| 12 | `tool_execution_end` | `agent-loop.ts:776` | `:390-396` | `:902-955` + `appendToolDiff` | `ToolCallBlock` + `DiffBlock` | OK; `details.truncation`/images dropped → F16, F23 |
| 13 | `queue_update` | `agent-session.ts:594` | `:403-406` | VM `:621-625` | `QueueChip` counts | OK |
| 14 | `compaction_start` | `agent-session.ts:1970,2290` | `:408` | `:998-1009` | `CompactionBlock` (Running) | OK |
| 15 | `compaction_end` | `agent-session.ts:2085,2098,2199,2309,2364,2408,2434` | `:409-417` | `:1011-1055` | `CompactionBlock` | OK; `result.usage` dropped → F18 |
| 16 | `auto_retry_start` | `agent-session.ts:2934` | `:419-424` | `:636-646` | `NoticeBlock` (warning) | OK |
| 17 | `auto_retry_end` | `agent-session.ts:702,1129,2956` | `:426-430` | `:648-659` | `ErrorBlock` only when `success == false` | OK |
| 18 | `summarization_retry_scheduled` | `agent-session.ts:2894` | `:432-437` | `:681-705` | `NoticeBlock` (one row, updated) | OK |
| 19 | `summarization_retry_attempt_start` | `agent-session.ts:2903` | `:439-442` | `:707-736` | same row, relabelled | OK |
| 20 | `summarization_retry_finished` | `agent-session.ts:2908` | `:444` | `:738-751` | same row, “重试结束” | OK |
| 21 | `extension_error` | `rpc-mode.ts:349` | `:466-470` | `:661-668` + VM snackbar `:625-641` | `ErrorBlock` + snackbar | OK |
| 22 | `extension_ui_request` | `rpc-mode.ts:129-269` | `:446-464` | VM `:522-592` | `ExtensionUiHost` | OK |
| 23 | `response` | rpc-mode reply path | `:339-345` | — | VM `lastError` | OK |
| 24 | `entry_appended` (not in the rpc.md table; `agent-session.ts:158`) | `agent-session.ts:2614-2621` (extension `appendEntry` only) | `:472-479` | `:675` → `onEntry` → **`custom` unhandled (`:1112`)** | **nothing** | **folded twice (F5) and unrenderable (F6)** |
| 25 | `session_info_changed` (not in the table) | `agent-session.ts:3116` | `:481` | VM `:656-660` | AppBar title | OK |
| 26 | `thinking_level_changed` (not in the table) | `agent-session.ts:1830` | `:482` | `:620-623` + VM | composer border + level chip | OK |

### `message_update` sub-kinds (rpc.md `:961-973`)

| delta | emitted by pi | parsed | rendered |
|---|---|---|---|
| `text_start` | `anthropic-messages.ts:628`, `google-generative-ai.ts:137`, `bedrock-converse-stream.ts:603` | `Events.kt:24` | no (implicit — the row is created on the first `text_delta`) |
| `text_delta` | `…:669`, `…:159`, `…:607` | `:25` | yes (`Transcript.kt:770-790`) |
| `text_end` | `…:715`, `…:249`, `…:713` | `:28` | **no** (carries the authoritative `content`) |
| `thinking_start` | `…:637,647`, `…:133`, `…:622` | `:29` | no (row created on first delta) |
| `thinking_delta` | `…:681`, `…:147`, `…:629` | `:30` | yes (`:792-810`) |
| `thinking_end` | `…:722`, `…:256`, `…:717` | `:33` | **no** |
| `toolcall_start` | `…:660`, `…:204`, `…:581` | `:34` | yes (`:814-830`, placeholder card) |
| `toolcall_delta` | `…:694`, `…:206`, `…:612` | `:37` | **no** (`tool_execution_start` supplies the args later, so the card's summary stays empty while arguments stream) |
| `toolcall_end` | `…:733`, `…:211`, `…:724` | `:40` | **no** (carries the full `toolCall`) |
| `done` | `…:803`, `…:278`, `…:326` | `:43` | no (`stopReason` arrives on `message_end`) |
| `error` | `…:813`, `…:289`, `…:337` | `:44` | yes → `ErrorBlock` “模型调用失败” (`:832-839`); detail uses `reason`, not the delta's `error.errorMessage` |
| unknown | a newer pi | `:47` | yes, deduplicated `Notice` (`:846-853`) |

**Counts.** Events parsed but never rendered: **1** (`turn_start`) — plus **8 of
11** `message_update` delta kinds. Event shapes that render but can never be
emitted: **1** item kind (`SystemPrompt`, F22). Parsed fields silently dropped:
`message_end.stopReason`, `message_end.errorMessage` (not parsed),
`message_update.usage`, `message_end.message.usage`, `compaction_end.result.usage`,
`text_end.content`, `toolcall_end.toolCall`, tool-result `image` blocks,
tool-result `details.truncation`. The **worst case the brief asks about** —
emitted by pi, not parsed at all — is **empty**: every event type in rpc.md
§Event Types and every `AgentSessionEvent` variant has a parse arm; the only
`extension_error` field mismatch is that pi sends `error` where the parser also
accepts `error` (`Events.kt:467` `o.str("message") ?: o.str("error")`), which is
correct.

---

## Component fidelity: kinds on each side

**pi's renderable item set** is `AgentMessage | custom entry | compaction_cost`
(`interactive-mode.ts:233`), where `AgentMessage = Message |
CustomAgentMessages[…]` (`agent/src/types.ts:326`) and `Message = UserMessage |
AssistantMessage | ToolResultMessage` (`ai/src/types.ts:470`), plus the
`bashExecution` role (`core/messages.ts:30`). **pi's persisted entry union is 9
kinds** (`session-manager.ts:145-155`), and `sessionEntryToContextMessages`
(`:383-408`) proves that only `message`, `custom_message`, `branch_summary` and
`compaction` become rendered items at all — `thinking_level_change`,
`model_change`, `label`, `session_info` and `custom` produce nothing on replay.

**The app renders 14 `TranscriptItem` kinds** (`rpc/.../Transcript.kt:29-187`):
`UserMessage`, `AssistantText`, `ThinkingBlock`, `ToolCall`, `ToolDiff`,
`CompactionMarker`, `BranchSummary`, `HookMessage`, `ModelChange`,
`SkillInvocation`, `SystemPrompt`, `ErrorText`, `DateSeparator`, `Notice`.

| app block | pi counterpart | information the app drops | information the app invents |
|---|---|---|---|
| `UserMessage` (with `images`) | `user-message.ts` + `tool-execution.ts` images | Markdown rendering (F15); images are placeholders, not pictures (`ImageGridBlock.kt:95-140`) | the timestamp (`UserMessageBlock.kt:54-59`) — pi shows none |
| `AssistantText` | `assistant-message.ts` text branch | — | the streaming cursor block (`AssistantTextBlock.kt:47-55`); pi has none |
| `ThinkingBlock` | `assistant-message.ts:120-166` | when hidden, pi prints an italic label (`:147-149`, default "Thinking...") and the app prints **nothing** (`BlockRenderer.kt:53-55`) — spec §7.4 sanctions this, so it is a decision, not a bug; the app also emits one block per `contentIndex` where pi joins a run of thinking blocks (`:131`) | level label + elapsed time (`ThinkingBlockBlock.kt:41-45,56-63`) |
| `ToolCall` | `tool-execution.ts` | **the tool's own `renderCall`/`renderResult`** (`:306-358`): pi draws `edit` as a diff, `bash` as a command block, etc. The app has one generic card for every tool — the largest architectural fidelity gap, and the one the spec defers to a per-card WebView (`pi-android-ui-spec.md` §4.10) | the status glyph/label, exit code, duration, line count footer (`ToolCallBlock.kt:105-132`) — the spec asks for it, pi has no such footer |
| `ToolDiff` | `diff.ts` (via the edit/write renderers) | — | the 16 dp symbol column + 8 % wash + line numbers (`DiffBlock.kt:158-186`) — spec-mandated |
| `CompactionMarker` | `compaction-summary-message.ts` + `compaction_cost` notice | **the cost notice** (F18); pi re-renders the whole context after compaction (`interactive-mode.ts:3418-3437`) where the app only updates the marker | the hairline+chip treatment, the reason caption |
| `BranchSummary` | `branch-summary-message.ts:41-56` | the branch jump (F19) | — |
| `HookMessage` | `custom-message.ts:107-111` | pi has no collapsed branch (the app's 4-line preview is its own) | the collapsed preview |
| `ModelChange` | **none** — no pi component renders a `model_change` entry | — | the whole row; spec §7.4 requires it, so this is sanctioned divergence |
| `SkillInvocation` | `skill-invocation-message.ts` | — | — |
| `SystemPrompt` | **none** (F22) | — | the whole block; unreachable in production |
| `ErrorText` | `interactive-mode.ts` `showError` + `assistant-message.ts:182-200` | the `length`/`aborted`/`error` variants (F3), the retry action (F19) | the card treatment |
| `DateSeparator` | **none** — pi has no day separator | — | the whole row; spec §4.6 requires it |
| `Notice` | status indicators (`status-indicator.ts`) | pi shows retry as a live status indicator with the countdown, the app as a permanent transcript row | the transcript placement |

---

## Ready-to-apply patches (files owned by other agents)

Each is the smallest change that addresses the finding; apply one at a time.

**P1 — F1, mid-turn echo** (`ui/PiSessionViewModel.kt`, at the current `:1245-1262`)

```diff
         if (engine.transcript.streaming) {
+            // F1: echo the queued message locally, as `prompt` already does —
+            // otherwise a steer/follow-up is invisible until the session is reopened.
+            engine.transcript.onUserPrompt(trimmed, images)
             engine.send(
                 PiCommands.steer(
```

**P2 — F5, remove the double projection** (`ui/PiSessionViewModel.kt`, current `:666-675`; delete `:667-674`)

```diff
             is PiEvent.EntryAppended -> {
-                val entry = event.entry
-                val engine = session
-                if (entry != null && engine != null) {
-                    engine.transcript.onEntry(entry)
-                    // The engine's revision counter only moves for events it
-                    // handled itself, so the projection has to be republished here.
-                    syncTranscript(engine)
-                }
             }
```

**P3 — F8, throttle tool updates** (`rpc/.../Transcript.kt`, `onToolUpdate` at `:886`)

```diff
+    private var lastToolPublishAt = 0L
+
     private fun onToolUpdate(event: PiEvent.ToolExecutionUpdate): TranscriptChange {
         val index = toolIndexByCallId[event.toolCallId] ?: return TranscriptChange.None
         val current = items.getOrNull(index) as? ToolCall ?: return TranscriptChange.None
         val chunk = event.partialText ?: return TranscriptChange.None
@@
         items[index] = current.copy(output = merged)
-        return TranscriptChange.Updated(index)
+        // pi-android-ui-spec.md §4.3: throttle the transcript publish to 200ms —
+        // the stored row is always current, only the repaint is coalesced.
+        val now = now()
+        if (now - lastToolPublishAt < TOOL_UPDATE_THROTTLE_MS) return TranscriptChange.None
+        lastToolPublishAt = now
+        return TranscriptChange.Updated(index)
```

**P4 — F6, render `custom` entries** (`rpc/.../Transcript.kt`, `onEntry` `when` at `:1073`)

```diff
+            // An extension's own state entry. pi renders it through the
+            // extension's registered entry renderer (interactive-mode.ts:3557);
+            // that renderer is a TUI component and cannot cross the RPC boundary,
+            // so the honest fallback is the type plus its payload.
+            "custom" -> onCustomEntry(entry, entryId, ts)
```

(plus a `onCustomEntry` that appends a muted `HookMessage`-shaped row — keep it a
*distinct* kind if you can, because `custom` state is deliberately not context.)

**P5 — F3 + F2, honour `stopReason`** (`rpc/.../Transcript.kt`, `MessageEnd` at `:596`)

```diff
         is PiEvent.MessageEnd -> {
             val finished = finishStreaming()
+            // pi reports the outcome of a turn here (assistant-message.ts:182-200);
+            // without this a `length` stop reads as a complete answer and an
+            // aborted turn leaves tool cards pending forever.
+            if (event.role == "assistant" &&
+                (event.stopReason == "aborted" || event.stopReason == "error" || event.stopReason == "length")
+            ) {
+                return failTurn(event.stopReason, event.text) ?: finished
+            }
             if (event.role == "custom" && event.display != false && !event.text.isNullOrEmpty()) {
```

**P6 — F11, one page margin and a 16 dp rhythm** (`ui/blocks/BlockChrome.kt:57-64` and `ui/screens/ChatScreen.kt:393-398`)

```diff
-        modifier = modifier
-            .fillMaxWidth()
-            .padding(horizontal = PiSpacing.screen, vertical = PiSpacing.unit / 2),
+        // The LazyColumn owns the page margin and the block rhythm (spec §7.4:
+        // 块间距 16dp, assistant-text 左右内边距 0) — padding here doubled both.
+        modifier = modifier.fillMaxWidth(),
```

```diff
                 contentPadding = PaddingValues(
-                    vertical = PiSpacing.unit,
+                    vertical = 16.dp,
                     horizontal = PiSpacing.screen,
                 ),
```

**P7 — F12, lift the two unreadable `dim` labels** (`ui/blocks/UserMessageBlock.kt:58`,
`ui/blocks/BranchSummaryBlock.kt:67`)

```diff
-                    color = palette.dim,
+                    // 2.11:1 on userMessageBg (spec §9 wants ≥3:1 for metadata).
+                    color = palette.userMessageText.copy(alpha = 0.62f),
```

**P8 — F30, one clock formatter** (`ui/blocks/BlockChrome.kt:188-190`)

```diff
-internal fun formatClock(ts: Long): String =
-    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
+private val CLOCK_FIELD = ThreadLocal.withInitial {
+    SimpleDateFormat("HH:mm", Locale.getDefault())
+}
+
+internal fun formatClock(ts: Long): String = CLOCK_FIELD.get()!!.format(Date(ts))
```

**P9 — F32, `remember` the markdown config objects** (`ui/render/PiMarkdown.kt:70-79`)

> **CORRECTION (2026-09-11) — the diff below does not compile; do not apply it.**
> Four of the five builders are `@Composable` (`markdownColor` and `markdownTypography` in
> the m3 artifact, `markdownPadding` and `markdownDimens` in core; only
> `markdownComponents` is not), and `remember`'s calculation lambda is declared
> `@DisallowComposableCalls`. Wrapping them produced, on the Gradle release build:
> `@Composable invocations can only happen from the context of a @Composable function`
> (`PiMarkdown.kt:80-84`, `PiMarkdownTheme.kt:98-99` in the tree at `19c8a25`).
> The fix that does compile is the one now in the tree: read the theme inputs at the
> composition site (`PiTheme.palette`, `isSystemInDarkTheme()`,
> `MaterialTheme.typography.bodyLarge`, `PiTheme.text.mono`), then remember **pure
> constructors** against them — `piMarkdownColors(palette, darkTheme)` and
> `piMarkdownTypography(palette, base, mono)` build the library's public
> `DefaultMarkdownColors` / `DefaultMarkdownTypography` directly, and `padding`/`dimens`
> are top-level constants implementing the library's public interfaces (its
> `DefaultMarkdownPadding`/`DefaultMarkdownDimens` are private). See
> `ui/render/PiMarkdown.kt:76-88` and `ui/render/PiMarkdownTheme.kt`. This is a class of
> error `tools/typecheck.sh` cannot see: it does not run the Compose compiler plugin.
>
> **Addendum from the `ui/render/**` owner, after this note was written** (the tree moved when
> the skill card was corrected at the parent's ruling). The four reads and three `remember`s are
> now `ui/render/PiMarkdown.kt:87-101`, because an optional `textColor: Color?` was added to
> `PiMarkdownText` — pi's `defaultTextStyle.color` option (`components/skill-invocation-message.ts:43`,
> applied by `applyDefaultStyle` at `components/markdown.ts:385`) for the one block that draws its
> markdown in `customMessageText`. The pure constructors it calls are unchanged in place:
> `ui/render/PiMarkdownTheme.kt:136` (`piMarkdownColors`, now with `textColor`),
> `:178` (`piAlertColors`), `:212` (`piMarkdownTypography`, now with `textColor`), `:250-290` (padding constants),
> `:292-324` (dimens constants), and `piMarkdownComponents()` is non-composable at
> `ui/render/PiMarkdownComponents.kt:98-103`. Nothing about the correction above changes: still no
> `@Composable` call inside any `remember`.

```diff
-            colors = piMarkdownColors(),
-            typography = piMarkdownTypography(),
-            padding = piMarkdownPadding(),
-            dimens = piMarkdownDimens(),
-            components = piMarkdownComponents(),
+            colors = remember(paletteIsDark) { piMarkdownColors() },
+            typography = remember { piMarkdownTypography() },
+            padding = remember { piMarkdownPadding() },
+            dimens = remember { piMarkdownDimens() },
+            components = remember { piMarkdownComponents() },
```

**P10 — F19, wire the two callbacks that have a target today**
(`ui/screens/ChatScreen.kt:402-409`)

```diff
                     BlockRenderer(
                         item = item,
                         toolsDefaultExpanded = toolsExpanded,
                         thinkingDefaultExpanded = false,
+                        // F19: the model row already has a target (ChatSheet.Model).
+                        onModelClick = {
+                            session.refreshModels()
+                            sheet = ChatSheet.Model
+                        },
                     )
```

## Checked and found correct (do not redo)

1. **`LazyColumn` key stability.** Synthetic keys are `"$prefix-${now()}-${seq++}"`
   (`Transcript.kt:564`) with a monotonic `seq` that `reset()` deliberately does
   not reset (`:1539-1549`); replayed rows use pi's entry id with a `-1`/`-2`
   suffix for multi-block messages (`:1150-1159`); tool cards use pi's
   `toolCallId` and diffs use `"$callId-diff"`. No collision found, and a session
   replacement changes every key, which is the correct behaviour (full replace).
2. **The reducer is incremental.** No full rebuild per event; `seedFromHistory`
   (`:1128-1139`) is the only whole-list pass and runs only on attach /
   session-switch. The per-token cost is in the *publication* (F7), not the fold.
3. **The highlight bridge's debounce and LRU really do apply on the streaming
   path.** `produceState` restarts per `code` and only waits when the block has
   already changed once (`PiMarkdownComponents.kt:287-302`, `delay(STREAM_SETTLE_MS)`
   = 200 ms at `:314`), the request runs on `Dispatchers.Default` (`:300`), the
   cache is a bounded access-ordered `LinkedHashMap` (`PiNodeCodeHighlighter.kt:92-95`,
   `CACHE_ENTRIES = 128`) and only definitive answers are cached (`:145-147`).
   The whole path is total (`:124-130`).
4. **Interleaved content blocks are numbered correctly.** One row per
   `contentIndex` with a per-index map (`Transcript.kt:548-549`, `:774-800`) —
   verified against pi's `anthropic-messages.ts`, which emits a second
   `text_start` at index 2 after a tool call, so a "last text row" heuristic would
   have mis-ordered the transcript.
5. **`turn_start`/`turn_end` carrying no `turnIndex` on the RPC wire is right.**
   `agent/src/types.ts:433-440` vs the extension-facing
   `core/extensions/types.ts:765-777` plus `agent-session.ts:771-782` and
   `json-event.ts:48-53`: the extension objects never reach stdout. The reducer's
   count-free model (`Events.kt:76-100`) is correct.
6. **`entry_appended` is not a general replay channel.** pi emits it only from the
   extension `appendEntry` path (`agent-session.ts:2614-2621`), so the app's
   `seedFromHistory`-based rebuild is the right primary path.
7. **`queue_update` and `session_info_changed` are rendered.** Counts → the chip
   row (`PiSessionViewModel.kt:621-625`, `ChatScreen.kt:666-679`); the name
   → the AppBar title (`:656-660`).
8. **The palette is pi's theme verbatim.** All 56 tokens plus the 3 export tokens
   match `dark.json`/`light.json`, *including* the shared greys that look like a
   mistake: dark `muted`/`toolOutput`/`thinkingText`/`mdQuote`/`mdQuoteBorder`/`mdHr`/`mdCodeBlockBorder`
   are all `#808080` because pi's own `dark.json` points them at `vars.gray`, and
   light's are all `#6C6C6C`. Identical values are **pi's** collapse, not app
   drift (`PiPalette.kt:119-173`; `packages/coding-agent/src/modes/interactive/theme/dark.json`).
9. **The typography and spacing tokens match spec §2.2/§2.3.** body 15/23, meta
   11.5/16, mono 13/20, monoSmall 11.5/17 (`PiTheme.kt:93-112`); card 16 dp, inner
   12 dp, input 24/16 dp, sheet/dialog 28 dp (`:43-54`); `unit = 18.dp` is pi's
   line-height unit as documented.
10. **The user-message container matches spec §4.6/§7.4 exactly** —
    `userMessageBg`, 16 dp radius (via `PiShapes.card`), 14 dp padding
    (`UserMessageBlock.kt:31-38`).
11. **The diff block matches spec §7.4** — 16 dp symbol column, 8 % wash,
    `+N −N`, context runs folded over 4, whole block folded past 200 rows
    (`DiffBlock.kt:161`, `:75-77`, `:230-233`, `:247-248`).
12. **`contentText`/`summarizeToolArgs`/`parseUnifiedDiff` are total** — never
    throw on hostile input (`Events.kt:563-581`, `Transcript.kt:412-421`,
    `:236-291`), which is what keeps a newer pi's payload from blanking the
    screen.
13. **`thinking_level_changed` is the single writer of the displayed level**
    (`Transcript.kt:620-623`, `PiSessionViewModel.kt:645-652` region), matching
    pi's silent clamping in `setThinkingLevel`.
14. **`bash_execution_update` is accumulated from the event stream and completed
    from the `bash` response** — the only two places pi carries the data
    (`agent-session.ts:3027` + `rpc-mode.ts:563-584`), and `BashRun`'s
    `truncated`/`fullOutputPath` really are populated (`PiSessionViewModel.kt:1268-1276`
    region, `BashPanel.kt:122-137`).

## Optimisations, ranked by expected benefit

1. **Publish the reducer's `TranscriptChange` instead of a copied list, and stop
   bumping the revision for no-op events** (F7). Removes an O(n) list copy, a
   whole `UiState` and a screen-level invalidation per token — the largest single
   win and the one that scales with session length.
2. **Put the streaming item's text in its own `MutableState<String>`** as §4.2
   specifies, so a token recomposes one row and nothing else (F7).
3. **Throttle `tool_execution_update` to 200 ms** (F8); one conditional in the
   reducer, which is where the spec puts the rule.
4. **Move the `get_entries` parse and the seed projection off the main thread**
   (F9); a `withContext` around the parse.
5. **Two-phase markdown**: light inline parse while `streaming`, full render at
   `message_end` (F7/F32's spec clause). Big for long answers; needs the streaming
   renderer work the spec already anticipated.
6. **Cap what is rendered, not what is stored** (F34), with a “加载更早” header.
7. **`remember` the five markdown theme/component objects** (F32) — five lines,
   no behaviour change.
8. **`remember(lineCount)`/`remember(headLines)` per tool row** (F31).
9. **Hoist the clock formatter** (F30).
10. **Add `contentType` to `items`** (F33).
