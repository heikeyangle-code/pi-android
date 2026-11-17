package app.pi.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 会话树「跳转」的纯逻辑：摘要选项与「是否真的移动了」的判定。
 *
 * 跳转本身是 SDK 的 `navigateTree`（宿主命令 `session.navigate`），不再经任何桥接扩展。
 * 这里只保留两件与 Android 无关、可在没有手机的机器上跑的判定，由
 * `PiTreeNavigationCheck` 钉住：摘要问题怎么问/怎么答，以及两次读到的叶子说明了什么。
 */

/** The three answers pi's own `/tree` offers before navigating (`interactive-mode.ts:5526-5530`). */
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
 * `branchSummary.skipPrompt` is a **pi setting** (`settings-manager.ts:983`,
 * `getBranchSummarySkipPrompt`) and the TUI reads it in exactly this place
 * (`interactive-mode.ts:5524`): when true the question is skipped and the answer is
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
 * The two facts the app can observe after dispatching the command, and the three outcomes
 * they can add up to.
 *
 * The **extension** knows more than this (`aborted` / `cancelled` / "already there"), and
 * says so through pi's own channels — `ctx.ui.notify` for the sentence, `setEditorText` for
 * the rewind text. What the app must decide on its own is narrower and has to be exact:
 * **should the conversation be rebuilt?** pi sends no event for a navigation (it emits the
 * *extension* event `session_tree`, `agent-session.ts:4091-4095`, which RPC does not
 * forward — `rpc-mode.ts:356` subscribes to `AgentSessionEvent` only), so the leaf moving is
 * the only authoritative signal, and it comes from `get_tree`/`get_entries`, both of which
 * report the **in-memory** leaf (`rpc-mode.ts:646`、`:651`).
 */
internal enum class NavigateOutcome {
    /** The leaf moved: rebuild the conversation from the new branch. */
    Moved,

    /**
     * The leaf is where it was. Covers every no-op pi can return — "already there"
     * (`agent-session.ts:3930-3933`), a cancelled `session_before_tree` (`:3975-3985`), an
     * aborted summarization (`:4021-4022`), and a navigation whose target was already the
     * leaf's parent. Nothing to rebuild, and saying so is the app's job because pi's
     * sentence went to the extension, not to the client.
     */
    NoMove,

    /**
     * The command never ran. Only two causes, and both are the app's: the bridge extension
     * is not loaded, or the engine is not ready.
     *
     * This is the **dangerous** one to get wrong. `_tryExecuteExtensionCommand` returns
     * `false` for an unregistered name (`agent-session.ts:2069`), and `prompt` then falls
     * through to `_runInputHandlers` and sends the text to the model as an ordinary user
     * turn (`:1930-1937` 之后继续走完 `prompt` 本身). So the app must never dispatch a command it has not seen in
     * `get_commands` (`rpc-mode.ts:685-690`).
     */
    Refused,
}

/**
 * The outcome from the two leaf readings, or [NavigateOutcome.Refused] when the dispatch
 * was refused before the wire.
 *
 * A null [leafBefore] is a legal reading: pi's leaf is null before the first entry and
 * after `resetLeaf()` (`session-manager.ts:998`、`:1591-1593`), which is exactly what navigating to
 * the **first** user message produces (`agent-session.ts:4044-4046` → `:4074-4076`). Comparing
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
 * (`session-manager.ts:1579-1584`、`:1600-1625`) — so a physical read would show messages
 * that are **not** in pi's active context. Filtering to the branch is what makes the rebuilt
 * screen agree with what the model will be sent
 * (`SessionManager.buildSessionContext`，`session-manager.ts:576-581` → `buildSessionProjection`
 * `:543` → `buildSessionPath(entries, leafId, byId)` `:550`；方法形态在 `:1497-1500`，它传的是
 * `this.leafId`。**注意**：本行原先引 `:853-855`，那是同一个文件里的 `buildSessionInfo`（会话
 * **列表**元数据），不是这条链上的东西 —— 与 pi 的模型上下文无关)。
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
        // (`session-manager.ts:1469-1479`), so the visited set is the one addition and it
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
