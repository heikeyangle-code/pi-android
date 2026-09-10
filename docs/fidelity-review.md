# Fidelity review: pi-android vs pi (adversarial pass)

State reviewed: `/root/pi-android` at `e8bffc0` (working tree dirty: 13 modified paths plus
`app/src/main/kotlin/app/pi/packages/` and `app/src/main/kotlin/app/pi/ui/chat/` untracked; other
agents were editing `ui/**`, `render/**`, `terminal/**`, `highlight/**`, `rpc/src/test/**` while this
pass ran), against `/root/pi-src` at `bbb61e3`. Date: 2026-09-10.

Every finding below was checked against pi's source, not against this repo's comments. Where a claim
could not be demonstrated from source it is marked UNVERIFIED with the evidence that would settle it.
Line numbers refer to the state above; `rpc/src/main/kotlin/app/pi/rpc/{Events,Transcript,Jsonl}.kt`
and `Commands.kt`/`Responses.kt`/`SettingsDocument.kt` were **not** among the concurrently-modified
files, so those citations are stable. `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt` **was**
modified mid-pass; its citations were re-read after the change.

---

## 1. DEFECT — a skill invocation can never be projected, so `/skill:name` renders as raw XML in the user bubble

App: `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:939` (`"skill", "skill_invocation" -> onSkillEntry`),
`Transcript.kt:1206-1225`, `Transcript.kt:1249-1255` (`userText`; returns text blocks verbatim).
pi: `packages/coding-agent/src/core/agent-session.ts:132-141` (`parseSkillBlock`), `agent-session.ts:1363-1376`
(`_expandSkillCommand` inlines the skill body into the user message as `<skill name="…" location="…">…</skill>`),
`packages/coding-agent/src/modes/interactive/interactive-mode.ts:3629-3642` (pi splits that text back out and
renders a dedicated skill block plus the trailing user message).

pi does not persist a `skill` entry: the skill arrives as an ordinary `message` entry whose user text is the
`<skill …>` wrapper. The app has the block type (`Transcript.kt:150-155`), a reducer path (`onSkillEntry`,
`onSkill`), and a renderer (`app/src/main/kotlin/app/pi/ui/blocks/BlockRenderer.kt:69`,
`ui/blocks/SkillInvocationBlock.kt:23`), but nothing in `app/src/main` or `rpc/src/main` ever parses `<skill`
(grep for `"<skill"` / `parseSkillBlock` returns no hits outside the type definitions above).

Observable consequence: after `/skill:foo`, the user's own bubble shows the literal
`<skill name="foo" location="/…">References are relative to …` plus the whole skill body, where pi shows a
`foo` skill card with the body and a separate trailing user message. The `SkillInvocation` renderer is
unreachable in both the live path and history replay.

Minimal fix: in the user-message projection (`onHistoryMessage`'s `"user"` branch and whatever feeds
`onUserPrompt` for a sent `/skill:`), apply pi's regex from `agent-session.ts:133`; emit `SkillInvocation`
and then the captured `userMessage` as its own `UserMessage`.

## 2. DEFECT — `compaction_end.result` is dropped, so the compaction block renders with no summary live

App: `rpc/src/main/kotlin/app/pi/rpc/Events.kt:144-151` (models only `reason`/`aborted`/`willRetry`/`errorMessage`),
`rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:868-892` (`onCompactionEnd` sets status only).
pi: `packages/coding-agent/src/core/agent-session.ts:161-168` (event type includes `result: CompactionResult`),
`agent-session.ts:2082-2090` (emit with `result: compactionResult`, whose fields are `summary`, `firstKeptEntryId`,
`tokensBefore`, `estimatedTokensAfter`, `usage`, `details` — `core/compaction/compaction.ts:88-97`).

The `compaction` session entry that carries the summary is appended by the session manager, and `entry_appended`
is emitted only by the extension `appendEntry` path (`agent-session.ts:2616-2621`), so the app cannot recover it
live. Result: `CompactionMarker.summary` / `.tokensFreed` (`Transcript.kt:108-119`) stay empty until the session is
reopened and `onCompactionEntry` runs from history — the live block is an unlabelled "compressed" row while pi's
shows the summary and token count.

Minimal fix: model `result` on `PiEvent.CompactionEnd` and copy `summary` / `tokensBefore` /
`estimatedTokensAfter` into the running marker in `onCompactionEnd`.

## 3. MISSING — all three summarization-retry events are parsed and then discarded

App: `rpc/src/main/kotlin/app/pi/rpc/Events.kt:176-196` and `Events.kt:364-376` build
`SummarizationRetryScheduled` / `SummarizationRetryAttemptStart` / `SummarizationRetryFinished`.
No consumer exists: `TranscriptReducer.onEvent` has no branch for any of the three and falls through
`Transcript.kt:637` (`else -> TranscriptChange.None`); a grep for `Summarization` across `app/src/main` and
`rpc/src/main` outside `Events.kt` returns nothing.
pi: `packages/coding-agent/src/core/agent-session.ts:171-184` (the three event shapes), emitted at
`agent-session.ts:2894` and neighbours.

The app's own comment on these events says the opposite of what the code does — `Events.kt:170-175`:
"Ignoring them would silently hide a stalling context — they are rare enough that a user seeing nothing would
assume the agent had hung." That is exactly the current behaviour.

Observable consequence: when compaction or branch summarisation fails and pi retries with backoff, the app shows
nothing at all; the user cannot distinguish a retry loop from a hang. (Contrast the agent-level retry path, which is
surfaced — `Transcript.kt:591-601`.)

Minimal fix: fold `SummarizationRetryScheduled` into a `Notice` the way `AutoRetryStart` is, and close it on
`SummarizationRetryFinished`.

## 4. DEAD CODE — the reducer treats `model_select` as a session entry type, but pi emits it to extensions only

App: `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:914` (`"model_change", "model_select" -> onModelEntry`) and
`Transcript.kt:1366-1378` (`ENTRY_EVENT_TYPES` contains `"model_select"`).
pi: `packages/coding-agent/src/core/session-manager.ts:144-153` — the persisted `SessionEntry` union contains
`model_change` and no `model_select`; `packages/coding-agent/src/core/agent-session.ts:1659-1670` — `_emitModelSelect`
calls `this._extensionRunner.emit(...)`, never `_emit`/`subscribe` listeners, so the event is not on RPC stdout either.

The app already documents this correctly elsewhere: `app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt:467-470`
and `PiSessionViewModel.kt:870-873` state that `model_select` never reaches session listeners. The reducer branch
contradicts it.

Observable consequence: the branch can never fire (a replayed JSONL entry or a live stdout record with
`type: "model_select"` does not exist), and it hides the real gap: an extension-driven model change produces no
live signal at all, so the model indicator is only corrected by the `agent_settled` poll
(`PiSessionViewModel.kt:471-476`). Minimal fix: delete `"model_select"` from both places, or route it explicitly as
an app-synthesised signal rather than an entry type.

## 5. COMMENT LIE — "The payload of an appended entry is not on the wire"

App: `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:625-627`: "The payload of an appended entry is not on the wire;
the App requests `get_entries { since }` and feeds the result to [onEntry]" — and then returns
`TranscriptChange.None`, discarding the entry.
pi: `packages/coding-agent/src/core/agent-session.ts:158` (`{ type: "entry_appended"; entry: SessionEntry }`) and
`agent-session.ts:2620` (`this._emit({ type: "entry_appended", entry })`); `src/modes/json-event.ts:49-51` passes
non-`message_update` events through unchanged, so the entry really is on RPC stdout. The app's own parser proves it
independently: `Events.kt:400-406` reads `o.obj("entry")?.str("id")` / `.str("type")`.

Observable consequence: the two fields the app does extract (`EntryAppended.entryId`, `.entryType`,
`Events.kt:241-243`) are read by nobody (grep: only the definitions in `Events.kt`), and the full payload is
thrown away — so an extension `custom` entry, and any future entry kind that arrives only through this event, can
never appear without a refetch.

Minimal fix: keep the `entry` object on `PiEvent.EntryAppended` and feed it to `onEntry`; if it is deliberately
not wanted, correct the comment to say so.

## 6. DEAD CODE — `AssistantDelta.Done.stopReason` reads a field pi never sends

App: `rpc/src/main/kotlin/app/pi/rpc/Events.kt:43` (`data class Done(val stopReason: String?)`) and
`Events.kt:440` (`"done" -> AssistantDelta.Done(e.str("stopReason"))`).
pi: `packages/ai/src/types.ts:557-561` — `{ type: "done"; reason: Extract<StopReason, …>; message: AssistantMessage }`;
`src/modes/json-event.ts:32-37` strips only `partial`, so the wire record is `{type:"done", reason, message}`.
`reason` is never `stopReason`.

Observable consequence: `Done.stopReason` is permanently `null`. It is currently unread (the only `.stopReason`
in the repo is on a `PiMessage` in `rpc/src/test/.../RpcResponsesTypedTest.kt:135`), so the damage is latent: the
first consumer that trusts this field silently loses the stop reason.

Minimal fix: rename the property to `reason` and parse `e.str("reason")`.

## 7. COMMENT LIE — `Jsonl.kt` claims blank records are skipped "matching pi's reader"

App: `rpc/src/main/kotlin/app/pi/rpc/Jsonl.kt:38-41` (blank records dropped) with the comment
"Records that are blank after CR trimming are skipped, matching pi's reader" (`Jsonl.kt:40`).
pi: `src/modes/rpc/jsonl.ts:25-41` — the reader emits every LF-delimited span, including `""`
(`emitLine` is called unconditionally when a `\n` is found at index 0), and `rpc-mode.ts:752-766` turns a blank
line into a `{"command":"parse","success":false}` error response rather than skipping it.

Observable consequence: none at runtime today (pi never writes a blank stdout record), which is why this is a
comment defect and not a behaviour defect — but the stated equivalence with pi's reader is false, and a future
reader who relies on it will draw a wrong conclusion about handshake/framing tolerance.

Minimal fix: reword to "skipped on purpose; pi's own reader emits blank lines and answers them with a parse error".

## 8. COMMENT LIE — `AssistantDelta.Unknown` is documented as "rendered as a generic notice" but is swallowed

App: `rpc/src/main/kotlin/app/pi/rpc/Events.kt:46-47`: "An event kind a newer pi introduced. Rendered as a generic
notice." The value is constructed at `Events.kt:442` and consumed nowhere: `Transcript.kt:712`
(`else -> TranscriptChange.None`) drops it, and no other file references `AssistantDelta.Unknown`.

Observable consequence: a delta kind added by a newer pi disappears silently — the opposite of the file's own
stated policy at `Events.kt:50-57` ("an engine upgrade must degrade the UI, never blank it"). Minimal fix: render
`Unknown` as a `Notice` in `onMessageUpdate`, or delete the claim.

## 9. DEAD CODE — two reducer branches keyed on entry types pi cannot emit (the third, `skill`, is finding 1)

App: `rpc/src/main/kotlin/app/pi/rpc/Transcript.kt:938` (`"system_prompt"`), `Transcript.kt:940` (`"error"`),
and their membership in `ENTRY_EVENT_TYPES` (`Transcript.kt:1366-1378`).
pi: `packages/coding-agent/src/core/session-manager.ts:144-153` — the persisted union is exactly
`message, thinking_level_change, model_change, compaction, branch_summary, custom, custom_message, label,
session_info`, and `FileEntry` adds only the `session` header (`session-manager.ts:30-41, 156`). No `system_prompt`
or `error` entry type exists anywhere in `packages/coding-agent/src`, and neither is in `AgentSessionEvent`
(`agent-session.ts:144-185`), so neither can reach RPC stdout either.

The app's comment at `Transcript.kt:929-937` concedes these are app-level kinds rather than pi entry types, so this
is not a hidden error — but the branches remain unreachable and the `SystemPrompt` / `ErrorText` rows can only be
produced by the explicit `onSystemPrompt` / `onError` APIs (`Transcript.kt:1259-1301`), never by `onEntry`.
(Correctness of the comment itself was checked: the union list matches `session-manager.ts:144-153` exactly, and
`Transcript.kt:934-936`'s claim that a skill command "is expanded into an ordinary user message" is right —
`agent-session.ts:1363-1376` — which is precisely why finding 1 is a real rendering gap and not a dead branch.)

Observable consequence: a reviewer reading `onEntry` believes pi can deliver these records through
`get_entries`/`entry_appended`; it cannot. Minimal fix: move the two branches out of `onEntry` (keep the public
`onSystemPrompt`/`onError` helpers) so the entry-type switch mirrors pi's union.

## 10. UNVERIFIED — `contentIndex` is parsed and then ignored, so interleaved content blocks cannot be ordered

App: `rpc/src/main/kotlin/app/pi/rpc/Events.kt:25,30` keep `contentIndex`, and no consumer reads it
(grep `contentIndex` across `app/src/main` + `rpc/src/main`: only the definitions and the parser assignments).
`Transcript.kt:646-661` finds the target row with `items.indexOfLast { it is AssistantText && it.streaming }` and
`Transcript.kt:664-680` does the same for thinking; `streaming` is cleared only by `finishStreaming`
(`Transcript.kt:1305-1327`).
pi: `packages/ai/src/types.ts:546-556` — every `text_*`/`thinking_*`/`toolcall_*` event carries its own
`contentIndex`, i.e. pi addresses content blocks positionally.

If, inside one assistant message, pi emits a `text_delta` whose `contentIndex` is *not* the one the app is
currently accumulating into (for example text → tool call → text, or an interleaved thinking block), the app
appends the second block's text onto the first `AssistantText` row, which may sit before the tool card, instead
of creating a block at the pi-correct position. I could not prove from source that any shipped provider emits two
text blocks with a non-text block between them in a single message, so this stays UNVERIFIED.

What would settle it: one captured RPC stdout record sequence in which a `text_delta` with a new `contentIndex`
follows a `toolcall_start` or `thinking_start` in the same message (pi's `test/suite` with the faux provider can
produce this on demand). If confirmed, the fix is to key the streaming row by `contentIndex` rather than by
"last streaming row".

---

## Checked and found correct (do not redo)

**RPC commands** — all 33 `RpcCommand` variants of `src/modes/rpc/rpc-types.ts:20-74` have a builder in
`Commands.kt`, with the same field names, types and optionality, and no builder sends a field pi does not declare:
`streamingBehavior` on `prompt` only (`rpc-types.ts:22`), no such field on `steer`/`follow_up`
(`rpc-types.ts:23-24`, and `Commands.kt:70-74`'s comment about this is correct), `parentSession`, `provider`+`modelId`,
`level`, `mode`, `customInstructions`, `enabled`, `command`+`excludeFromContext`, `outputPath`, `sessionPath`,
`entryId`, `since`, `name`. The three `extension_ui_response` shapes match
`rpc-types.ts:288-291` and `pending.resolve(response)` in `rpc-mode.ts:776-780`.

**RPC responses** — every reader in `Responses.kt` was compared field-by-field with `handleCommand`
(`rpc-mode.ts:390-713`) and the types it returns: `RpcSessionState` (`rpc-types.ts:96-109`), `set_model` returning the
bare `Model` (`rpc-mode.ts:479`), `cycle_model`'s `| null` (`rpc-mode.ts:483-488`), `clear_queue`, `new_session`/
`switch_session`/`clone` `{cancelled}`, `fork` `{text, cancelled}` (`rpc-mode.ts:618`),
`get_fork_messages` `{entryId, text}` (`agent-session.ts:3337-3352`), `get_entries`/`get_tree` incl. `leafId`,
`BashResult` (`core/bash-executor.ts:29-40`), `SessionStats` (`agent-session.ts:270-287`), `ContextUsage`
(`core/extensions/types.ts:290-296`), `CompactionResult` incl. `estimatedTokensAfter`
(`core/compaction/compaction.ts:88-97`), and `get_commands` emitting `sourceInfo` rather than the flat
`path`/`location` that `docs/rpc.md` shows (`rpc-mode.ts:682-712`) — `Responses.kt:64-70` describes this split
correctly and reads both.

**Event union coverage** — every member of `AgentSessionEvent` (`agent-session.ts:144-185`) plus
`extension_ui_request`, `extension_error` and `response` has a `PiEvent` variant and a dispatch arm in `Events.kt`.
Field names verified: `agent_end.willRetry`, `queue_update.steering/followUp`, `bash_execution_update.id/delta`
(`agent-session.ts:3027`), `auto_retry_*`, `session_info_changed.name`, `thinking_level_changed.level`,
`tool_execution_*`, and the `extension_ui_request` per-method fields (`prefill` on `editor`, `notifyType`, `statusKey`,
`widgetKey/widgetLines/widgetPlacement`, `timeout`) against `rpc-types.ts:246-281` and `rpc-mode.ts:129-269`.

**turn_start / turn_end** — `Events.kt:76-83`'s comment is correct: the RPC-visible `AgentEvent` union is
`{ type: "turn_start" }` and `{ type: "turn_end"; message; toolResults }` (`packages/agent/src/types.ts:436-437`).
The `turnIndex`-bearing shapes at `agent-session.ts:771-786` and `extensions/types.ts:765-772` are extension events
and never reach `session.subscribe`, which is what RPC prints (`rpc-mode.ts:355-359` via `toJsonEvent`,
`json-event.ts:49-51`).

**toolcall_start** — `Events.kt:34-36` and `Events.kt:431-434` are correct, and this one is easy to get wrong:
`toJsonAssistantMessageEvent` adds `id` and `toolName` to `toolcall_start` on the wire
(`json-event.ts:23-30`), so the app's use of them to open the card early is sound. Only `pi-messages` supplies them
upstream (`packages/ai/src/api/pi-messages.ts:66`), and `json-event.ts` re-derives them from `partial` for every
provider.

**Message / content-block model** — `Messages.kt` field names match `packages/ai/src/types.ts:351-381`
(`textSignature`, `thinkingSignature`, `redacted`, `namespace`, `mimeType`) and `core/messages.ts:29-66`
(`bashExecution`, `custom`, `branchSummary`, `compactionSummary`). `parseContentBlocks` handles pi's
`string | (TextContent|ImageContent)[]` correctly, including the `JsonNull`-is-a-`JsonPrimitive` trap
(`Messages.kt:273-285`). The `PiMessage.text` doc claim about `getLastAssistantText` is accurate
(`agent-session.ts:3501-3521`).

**Session entries** — `SessionEntries.kt` transcribes all nine `SessionEntry` variants and their fields from
`session-manager.ts:42-142`, and correctly excludes the `session` header (`session-manager.ts:30-41, 156`) and the
optional `details`/`usage`/`fromHook` fields. `SessionTreeNode` (`label`, `labelTimestamp`) matches
`session-manager.ts:159-166`.

**Settings registry** — every key in pi's `Settings` interface (`core/settings-manager.ts:101-151`) is present in
`PiSettingsRegistry.kt`, and no registry key collides with a pi key except the deliberately namespaced `app.*` and
`packages[].autoload` (routed to a sidecar by `PiSettingsFileStore.kt:140`). Defaults, option vocabularies and
ranges were checked against `docs/settings.md` and source: `thinkingLevelOptions` == `ThinkingLevel`
(`packages/agent/src/types.ts:301`); `transportOptions` == `Transport` (`packages/ai/src/types.ts:110`);
`doubleEscapeAction` == `"fork"|"tree"|"none"`; `treeFilterMode` == `"default"|"no-tools"|"user-only"|"labeled-only"|"all"`
(`settings-manager.ts:141`); `fullscreenScrollbar` == `"hidden"|"auto"|"always"` (`packages/tui/.../scroll-view.ts:4`);
`fullscreenExitOutput` (`settings-manager.ts:50`); `TerminalSettings` incl. `showTerminalProgress`, which the registry
correctly labels as source-only (`settings-manager.ts:52-60`); `builtinTools` == the eight documented names and the
default `read,bash,edit,write` (`agent-session.ts:2833`). `terminal.hyperlinks`/`trueColor`/`images`/`outputPad`
scalar-vs-string handling is right for pi's schema, as is the `theme` `"light/dark"` literal
(`modes/interactive/theme/theme.ts:580-608`).

**Settings semantics** — `SettingsDocument.merge` matches `deepMergeObjects`/`isMergeableObject` exactly
(`settings-manager.ts:154-181`, arrays replace, objects recurse), `lookup` is sparse-by-design, `setPath`/`removePath`
preserve unknown siblings, and `PiSetting.elementFromEntries`/`editableEntries` are container-aware, so array-valued
and object-valued settings (`defaultTools`, `packages`, `compaction.modelOverrides`) round-trip without the flattening
bug that a previous pass found.

**JSONL framing** — splitting on `\n` only and stripping one trailing `\r` is right (`jsonl.ts:10-41`), and the
`U+2028`/`U+2029` rationale is pi's own (`jsonl.ts:4-9`, `docs/rpc.md:28-37`). The 8 MiB record cap has no pi
counterpart but is far above pi's own truncation threshold, so it cannot drop a legitimate record.
