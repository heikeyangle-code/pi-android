package app.pi.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The pure half of pi's **in-session tree navigation** (`navigateTree`) — the one pi
 * behaviour the app had no path to until the `pi-android-bridge` extension exposed it as
 * a command.
 *
 * ## Why this file exists separate from the ViewModel
 *
 * Three decisions here are arithmetic over pi's own rules, not Android work:
 *
 *  1. **Where the leaf lands** for a chosen entry ([landingFor]);
 *  2. **Whether to ask about a summary**, and what the answer means
 *    ([wantsSummary] / [needsCustomInstructions] / [summaryPromptShown]);
 *  3. **the argument text** the command receives ([navigateCommandArgs]).
 *
 * All three are pinned by `app/src/test/kotlin/app/pi/ui/chat/PiTreeNavigationCheck.kt`,
 * which compiles this file without Android or Compose.
 *
 * ## pi's real semantics, transcribed
 *
 * `AgentSession.navigateTree(targetId, { summarize, customInstructions, replaceInstructions,
 * label })` (`packages/coding-agent/src/core/agent-session.ts:3136-3332`):
 *
 *  - it throws while streaming (`:3140-3142`) or while compacting (`:3143-3147`) — so the
 *    caller must be idle before it is asked;
 *  - a target that is a **user `message`** — or a `custom_message` — puts the leaf at that
 *    entry's **`parentId`**, and hands the entry's text back as `editorText`
 *    (`:3265-3274`). Every other entry type puts the leaf **at the entry itself**
 *    (`:3275-3277`). That is the rule [landingFor] encodes, and it is why a row's action is
 *    "继续之前" rather than "继续于此" whenever the row is a user message;
 *  - `summarize` is only *acted on* when there is something to summarize — pi runs the
 *    summarizer under `options.summarize && entriesToSummarize.length > 0` (`:3229`);
 *    the app cannot know that length, so it does not try to;
 *  - the summary entry is appended **at the navigation target** and becomes the active
 *    leaf itself (`session-manager.ts:1395-1416` → `_appendEntry` sets
 *    `leafId = entry.id`, `:1061`), so the abandoned path is not in the new context;
 *  - `{ cancelled: true }` means an extension cancelled it in `session_before_tree`
 *    (`:3204-3206`); `{ aborted: true }` means the summarization was aborted
 *    (`:3247-3249`); `{ cancelled: false }` with no work means "already there"
 *    (`:3151-3154`).
 *
 * `packages/coding-agent/src/modes/interactive/interactive-mode.ts:5216-5325` is the
 * reference for the *user-facing* flow, and the app follows it: ask "Summarize branch?"
 * unless `branchSummary.skipPrompt` is set, abort a running response first, and re-render
 * the whole conversation afterwards. It is **not** a source of new behaviour.
 */

/** Where pi puts the session's leaf for a chosen entry. */
internal enum class NavigateLanding {
    /**
     * `newLeafId = entry.parentId`, and pi returns the entry's own text as `editorText`
     * (`agent-session.ts:3265-3274`).
     *
     * This is the "rewind to just before this message and let me re-say it" case, and it
     * is also what `fork`'s default `position: "before"` does
     * (`agent-session-runtime.ts:266`、`:282-285`) — the difference is that `fork` writes a
     * new session file and `navigateTree` does not.
     */
    BeforeEntry,

    /** `newLeafId = entry.id` (`agent-session.ts:3275-3277`). */
    AtEntry,
}

/** `entry.type` as a string, or null when it is missing/not a string. */
private fun typeOf(entry: JsonObject): String? =
    (entry["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `entry.message.role` as a string, or null. */
private fun roleOf(entry: JsonObject): String? {
    val message = entry["message"] as? JsonObject ?: return null
    return (message["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * pi's leaf rule for [entry], verbatim from `agent-session.ts:3265-3277`.
 *
 * A `branch_summary` entry (what a previous navigation wrote) is **not** a user message
 * and not a `custom_message`, so it lands [NavigateLanding.AtEntry] — the leaf goes to the
 * summary itself, which is exactly where pi left it
 * (`session-manager.ts:1395-1416` → `:1061`).
 */
internal fun landingFor(entry: JsonObject): NavigateLanding = when {
    typeOf(entry) == "message" && roleOf(entry) == "user" -> NavigateLanding.BeforeEntry
    typeOf(entry) == "custom_message" -> NavigateLanding.BeforeEntry
    else -> NavigateLanding.AtEntry
}

/** The three answers pi's own `/tree` offers before navigating (`interactive-mode.ts:5238-5242`). */
enum class BranchSummaryChoice {
    /** `"No summary"` — leaf moves, abandoned path is dropped. */
    NoSummary,

    /** `"Summarize"` — the default summarizer prompt. */
    Summarize,

    /** `"Summarize with custom prompt"` — opens the instructions editor first. */
    SummarizeWithPrompt,
}

/**
 * Whether pi would ask at all.
 *
 * `branchSummary.skipPrompt` is a **pi setting** (`settings-manager.ts:910`,
 * `getBranchSummarySkipPrompt`) and the TUI reads it in exactly this place
 * (`interactive-mode.ts:5236`): when true the question is skipped and the answer is
 * "no summary". The app has no settings row for it (it is read by the TUI alone, which is
 * why it is not in `PiSettingsCatalog`), so it is read from the settings document here —
 * the same way `ProjectScreen` reads `defaultProjectTrust` — and honoured, because this
 * flow *is* the TUI flow that key describes.
 */
internal fun summaryPromptShown(skipPromptSetting: Boolean): Boolean = !skipPromptSetting

/** The effective `summarize` argument, after the skip-prompt ruling. */
internal fun wantsSummary(choice: BranchSummaryChoice, skipPromptSetting: Boolean): Boolean =
    summaryPromptShown(skipPromptSetting) && choice != BranchSummaryChoice.NoSummary

/** Whether the answer needs the instructions editor before it can be dispatched. */
internal fun needsCustomInstructions(choice: BranchSummaryChoice): Boolean =
    choice == BranchSummaryChoice.SummarizeWithPrompt

/**
 * The `args` half of the extension command line, as JSON.
 *
 * ## Why JSON rather than `key=value`
 *
 * pi splits the command line on the **first space only**
 * (`agent-session.ts:1333-1335`: `text.indexOf(" ")`, then `text.slice(spaceIndex + 1)`),
 * so everything after the command name survives intact — including further spaces, quotes
 * and newlines. That makes a JSON object the one encoding with no escaping bugs on either
 * side: `customInstructions` is free text a user typed, and a positional grammar
 * (`id summarize text`) would be ambiguous the moment that text contains a space.
 *
 * Absent options are **omitted**, not sent as null: pi's own options object is
 * `{ summarize?, customInstructions?, replaceInstructions?, label? }`
 * (`agent-session.ts:3138`) and `undefined` is not the same as `false` for
 * `replaceInstructions` (`:3217-3219`).
 *
 * ## What the field set deliberately does *not* include
 *
 * `editorText` — the target message's text that pi's TUI puts back in the editor
 * (`interactive-mode.ts:5313`) — is **unreachable from RPC mode**, so there is no field for
 * it and the app does not pretend otherwise: the extension-facing wiring returns exactly
 * `{ cancelled: result.cancelled }` and drops `editorText`, `aborted` and `summaryEntry`
 * (`modes/rpc/rpc-mode.ts:329-335`), which is also all its declared type promises
 * (`core/extensions/types.ts:375-379`). The same omission is why an aborted summarization
 * cannot be told apart from a completed navigation out here. Both are pi's own gaps in RPC
 * mode; "跳转" itself — the leaf move and the summary — is unaffected.
 */
internal fun navigateCommandArgs(
    targetId: String,
    summarize: Boolean,
    customInstructions: String? = null,
    replaceInstructions: Boolean? = null,
    label: String? = null,
): String = buildString {
    append("{\"targetId\":")
    append(quote(targetId))
    append(",\"summarize\":")
    append(if (summarize) "true" else "false")
    customInstructions?.takeIf { it.isNotBlank() }?.let {
        append(",\"customInstructions\":")
        append(quote(it))
    }
    replaceInstructions?.let {
        append(",\"replaceInstructions\":")
        append(if (it) "true" else "false")
    }
    label?.takeIf { it.isNotBlank() }?.let {
        append(",\"label\":")
        append(quote(it))
    }
    append('}')
}

/**
 * JSON string quoting, restricted to what this builder needs.
 *
 * Only the two characters that can appear in a JSON string and break it are escaped
 * (`"` and `\`), plus the C0 controls, which JSON forbids raw. The input here is a pi
 * entry id (8 hex characters — `session-manager.ts:221-228` slices a UUID) or text a user
 * typed into a composer, so this is deliberately not a general-purpose encoder.
 */
private fun quote(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

/**
 * The two facts the app can observe after dispatching the command, and the three outcomes
 * they can add up to.
 *
 * The **extension** knows more than this (`aborted` / `cancelled` / "already there"), and
 * says so through pi's own channels — `ctx.ui.notify` for the sentence, `setEditorText` for
 * the rewind text. What the app must decide on its own is narrower and has to be exact:
 * **should the conversation be rebuilt?** pi sends no event for a navigation (it emits the
 * *extension* event `session_tree`, `agent-session.ts:3315-3321`, which RPC does not
 * forward — `rpc-mode.ts:356` subscribes to `AgentSessionEvent` only), so the leaf moving is
 * the only authoritative signal, and it comes from `get_tree`/`get_entries`, both of which
 * report the **in-memory** leaf (`rpc-mode.ts:648`、`:653`).
 */
internal enum class NavigateOutcome {
    /** The leaf moved: rebuild the conversation from the new branch. */
    Moved,

    /**
     * The leaf is where it was. Covers every no-op pi can return — "already there"
     * (`agent-session.ts:3151-3154`), a cancelled `session_before_tree` (`:3204-3206`), an
     * aborted summarization (`:3247-3249`), and a navigation whose target was already the
     * leaf's parent. Nothing to rebuild, and saying so is the app's job because pi's
     * sentence went to the extension, not to the client.
     */
    NoMove,

    /**
     * The command never ran. Only two causes, and both are the app's: the bridge extension
     * is not loaded, or the engine is not ready.
     *
     * This is the **dangerous** one to get wrong. `_tryExecuteExtensionCommand` returns
     * `false` for an unregistered name (`agent-session.ts:1338`), and `prompt` then falls
     * through to `_runInputHandlers` and sends the text to the model as an ordinary user
     * turn (`:1198-1218`). So the app must never dispatch a command it has not seen in
     * `get_commands` (`rpc-mode.ts:685-690`).
     */
    Refused,
}

/**
 * The outcome from the two leaf readings, or [NavigateOutcome.Refused] when the dispatch
 * was refused before the wire.
 *
 * A null [leafBefore] is a legal reading: pi's leaf is null before the first entry and
 * after `resetLeaf()` (`session-manager.ts:1386-1388`), which is exactly what navigating to
 * the **first** user message produces (`agent-session.ts:3278` → `:3297-3298`). Comparing
 * null to a string is therefore a real move, not missing data — hence the [refused] flag
 * being a separate input rather than a null check.
 */
internal fun navigateOutcome(leafBefore: String?, leafAfter: String?, refused: Boolean): NavigateOutcome = when {
    refused -> NavigateOutcome.Refused
    leafBefore == leafAfter -> NavigateOutcome.NoMove
    else -> NavigateOutcome.Moved
}

/**
 * `SessionManager.getBranch(leafId)` — root-first, walked through `parentId`.
 *
 * This is the app's rebuilt conversation after a navigation, and it is the reason a
 * navigation cannot reuse the ordinary replay: `SessionFileReader.readTail` returns the
 * file's **physical** tail and `TranscriptReducer.seedFromHistory` folds whatever list it
 * is given in order (`rpc/Transcript.kt:2051-2068`). After `navigateTree` the file still
 * contains the abandoned path — `branch()`/`branchWithSummary()` never delete entries
 * (`session-manager.ts:1374-1384`、`:1395-1416`) — so a physical read would show messages
 * that are **not** in pi's active context. Filtering to the branch is what makes the rebuilt
 * screen agree with what the model will be sent
 * (`SessionManager.buildSessionContext`, `:853-855`).
 *
 * Generic over the row type so the harness can pin it without JSON; [activeBranch] is the
 * `JsonObject` form the ViewModel uses.
 */
internal fun <T> branchFromRootToLeaf(
    items: List<T>,
    leafId: String?,
    idOf: (T) -> String?,
    parentOf: (T) -> String?,
): List<T> {
    if (leafId == null) return emptyList()
    val byId = HashMap<String, T>(items.size)
    for (item in items) idOf(item)?.let { byId[it] = item }
    val path = ArrayDeque<T>()
    var current = byId[leafId] ?: return emptyList()
    val seen = HashSet<String>()
    while (true) {
        val id = idOf(current) ?: break
        // A cycle is a malformed session file; pi's own walk would spin on it too
        // (`session-manager.ts:1285-1300`), so the visited set is the one addition and it
        // turns a hang into a short branch.
        if (!seen.add(id)) break
        path.addFirst(current)
        val parent = parentOf(current) ?: break
        current = byId[parent] ?: break
    }
    return path.toList()
}

/** [branchFromRootToLeaf] over raw session entries. */
internal fun activeBranch(entries: List<JsonObject>, leafId: String?): List<JsonObject> =
    branchFromRootToLeaf(
        items = entries,
        leafId = leafId,
        idOf = { (it["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
        parentOf = { (it["parentId"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
    )
