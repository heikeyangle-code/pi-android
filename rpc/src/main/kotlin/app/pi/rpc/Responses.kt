package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed readers for the `data` payload of each RPC response.
 *
 * Every shape here is transcribed from pi's own definitions, and the field names
 * and nullability are the compatibility contract:
 *
 *  - `RpcSlashCommand`, `RpcSessionState`, `RpcResponse` — `src/modes/rpc/rpc-types.ts`
 *  - `SessionStats`  — `src/core/agent-session.ts`
 *  - `ContextUsage`  — `src/core/extensions/types.ts`
 *  - `BashResult`    — `src/core/bash-executor.ts`
 *  - `CompactionResult` — `src/core/compaction/compaction.ts`
 *  - `SessionEntry`, `SessionTreeNode` — `src/core/session-manager.ts`
 *  - `Model`, `ModelCost`, `Usage` — `packages/ai/src/types.ts`
 *  - message union — `docs/session-format.md`
 *  - response payloads and their `null` cases — `docs/rpc.md`, cross-checked
 *    against the `handleCommand` switch in `src/modes/rpc/rpc-mode.ts`
 *
 * ## Policy for unknown and absent fields
 *
 * pi adds fields in minor versions, so the readers are forward-compatible by
 * construction:
 *
 *  1. **Unknown fields are ignored.** A reader pulls only the keys it names and
 *     never validates the key set, so a new field can never break a parse. This
 *     is a hard requirement, not a nicety: a strict parser would make an engine
 *     upgrade a client crash.
 *  2. **Absent/`null` optional fields degrade** to `null`, an empty collection,
 *     `false` or `0`, matching the optionality pi documents.
 *  3. **An absent required field never throws and is never fabricated.** Where a
 *     wrong value would be a lie (a compaction `summary`, a fork's `text`),
 *     the property is nullable and reads as `null`. Where the payload is a
 *     collection, an element missing the field that identifies it (`name` for a
 *     slash command, `id` for a model) is dropped — the element is unusable and
 *     presenting it under an invented key would be worse. A whole unrecognised
 *     element kind is preserved as `Unknown` carrying the raw object rather than
 *     dropped, because dropping it would lose the message around it.
 *  4. **A missing or non-object `data` makes the reader return `null`** (or an
 *     empty collection). Callers that need a value — the `:app` façade —
 *     translate that into an explicit error, which is what keeps "pi sent a
 *     legitimate `data: null`" (documented for `cycle_model` and
 *     `cycle_thinking_level`) distinguishable from "pi sent something we could
 *     not read"; see [dataIsNull].
 *  5. **The readers never throw.** Every cast is a safe cast, so a shape change
 *     degrades the UI instead of crashing it.
 *
 * The snapshot readers ([sessionState], [sessionStats]) predate this policy and
 * keep their original behaviour: their scalars fall back to documented defaults
 * (`0`, `false`) because a partial snapshot is still a usable snapshot. Their
 * behaviour is pinned by existing tests and is deliberately not tightened.
 */
object PiResponses {

    /** What kind of thing backs a slash command; drives grouping in the palette. */
    enum class CommandSource { Extension, Prompt, Skill, Unknown }

    /**
     * `RpcSlashCommand`.
     *
     * `sourceInfo` is what the RPC handler actually emits (`rpc-mode.ts`
     * "get_commands"); `location` and `path` are the flat fields `docs/rpc.md`
     * documents but the implementation never sends. Both are read, so a pi that
     * follows the doc one day still parses.
     */
    data class SlashCommand(
        val name: String,
        val description: String?,
        val source: CommandSource,
        val sourceInfo: PiSourceInfo?,
        val location: String?,
        val path: String?,
    )

    /** `RpcSessionState`. */
    data class SessionState(
        /**
         * The full current `Model`, or `null` when none is selected. pi's
         * `rpc-types.ts` marks this optional and `rpc-mode.ts` always assigns it
         * (`model: session.model`), so `null` means "no model selected".
         */
        val model: ModelInfo?,
        val thinkingLevel: String?,
        val isStreaming: Boolean,
        val isCompacting: Boolean,
        val steeringMode: String?,
        val followUpMode: String?,
        val sessionFile: String?,
        val sessionId: String?,
        val sessionName: String?,
        val autoCompactionEnabled: Boolean,
        val messageCount: Int,
        val pendingMessageCount: Int,
    )

    /** `SessionStats.tokens` — cumulative, unlike per-message [TokenUsage]. */
    data class TokenTotals(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val total: Long,
    )

    /** `ContextUsage`. */
    data class ContextUsage(
        val tokens: Long?,
        val contextWindow: Long?,
        val percent: Double?,
    )

    /** `SessionStats`. */
    data class SessionStats(
        val sessionFile: String?,
        val sessionId: String?,
        val userMessages: Int,
        val assistantMessages: Int,
        val toolCalls: Int,
        val toolResults: Int,
        val totalMessages: Int,
        val tokens: TokenTotals?,
        val cost: Double?,
        val contextUsage: ContextUsage?,
    )

    /** `Model`, trimmed to what a picker and a status line need. */
    data class ModelInfo(
        val id: String,
        val name: String,
        val provider: String?,
        val api: String?,
        val baseUrl: String?,
        val reasoning: Boolean,
        val acceptsImages: Boolean,
        val contextWindow: Long?,
        val maxTokens: Long?,
        /** USD per million tokens. */
        val inputCost: Double?,
        val outputCost: Double?,
        val cacheReadCost: Double?,
        val cacheWriteCost: Double?,
    )

    /**
     * `cycle_model` data: `{ model, thinkingLevel, isScoped } | null`.
     *
     * `model` is documented as always present when the payload is non-null, but
     * is modelled nullable because a reader must not invent one.
     */
    data class CycleModelResult(
        val model: ModelInfo?,
        val thinkingLevel: String?,
        val isScoped: Boolean,
    )

    /** `cycle_thinking_level` data: `{ level } | null`. */
    data class CycleThinkingResult(val level: String?)

    /**
     * `compact` data — `CompactionResult` from `core/compaction/compaction.ts`.
     *
     * `estimatedTokensAfter` is a heuristic over the rebuilt context, not a
     * provider-exact count, and `usage` "may be omitted by custom compaction
     * handlers" (docs/rpc.md).
     */
    data class CompactionResult(
        val summary: String?,
        val firstKeptEntryId: String?,
        val tokensBefore: Long?,
        val estimatedTokensAfter: Long?,
        val usage: TokenUsage?,
        /** True when an extension attached an opaque `details` payload. */
        val hasDetails: Boolean,
    )

    /** `bash` data — `BashResult` from `core/bash-executor.ts`. */
    data class BashResult(
        val output: String,
        /** Null when the process was killed or cancelled. */
        val exitCode: Int?,
        val cancelled: Boolean,
        val truncated: Boolean,
        /** Present only when `truncated` is true. */
        val fullOutputPath: String?,
    )

    /** `clear_queue` data. */
    data class ClearQueueResult(
        val steering: List<String>,
        val followUp: List<String>,
    )

    /** `new_session` / `switch_session` / `clone` data. */
    data class CancelledResult(val cancelled: Boolean)

    /** `fork` data. `text` is absent when pi could not extract the message. */
    data class ForkResult(
        val text: String?,
        val cancelled: Boolean,
    )

    /** One element of `get_fork_messages`. */
    data class ForkMessage(
        val entryId: String,
        val text: String,
    )

    /** `get_entries` data. `leafId` is null for an empty session. */
    data class EntriesPage(
        val entries: List<SessionEntry>,
        val leafId: String?,
    )

    /** `get_tree` data. */
    data class TreePage(
        val tree: List<SessionTreeNode>,
        val leafId: String?,
    )

    /** `export_html` data. */
    data class ExportResult(val path: String?)

    // ------------------------------------------------------------------ helpers

    /**
     * True when pi sent no `data` at all, or an explicit `data: null`.
     *
     * `rpc-mode.ts`'s `success()` omits `data` only when the argument was
     * `undefined`; an explicit `null` is serialised (`success(id, "cycle_model",
     * null)`), which is the documented "only one model available" case. Both
     * spellings mean "no payload", and both must be distinguished from a payload
     * a reader failed to understand.
     */
    fun dataIsNull(response: PiEvent.Response): Boolean =
        response.data == null || response.data is JsonNull

    // ------------------------------------------------------------------ readers

    fun slashCommands(response: PiEvent.Response): List<SlashCommand> {
        val commands = dataArray(response, "commands")
        return commands.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val sourceInfo = obj.obj("sourceInfo")
            SlashCommand(
                name = name,
                description = obj.str("description"),
                source = when (obj.str("source")) {
                    "extension" -> CommandSource.Extension
                    "prompt" -> CommandSource.Prompt
                    "skill" -> CommandSource.Skill
                    else -> CommandSource.Unknown
                },
                sourceInfo = sourceInfo?.let(::parseSourceInfo),
                // docs/rpc.md documents these flat; rpc-mode.ts does not send
                // them. Reading both costs nothing and keeps the doc and the
                // implementation in agreement.
                location = obj.str("location"),
                path = obj.str("path") ?: sourceInfo?.str("path"),
            )
        }
    }

    fun sessionState(response: PiEvent.Response): SessionState? {
        val obj = dataObject(response) ?: return null
        return SessionState(
            model = model(obj["model"]),
            thinkingLevel = obj.str("thinkingLevel"),
            isStreaming = obj.bool("isStreaming") ?: false,
            isCompacting = obj.bool("isCompacting") ?: false,
            steeringMode = obj.str("steeringMode"),
            followUpMode = obj.str("followUpMode"),
            sessionFile = obj.str("sessionFile"),
            sessionId = obj.str("sessionId"),
            sessionName = obj.str("sessionName"),
            autoCompactionEnabled = obj.bool("autoCompactionEnabled") ?: false,
            messageCount = obj.int("messageCount") ?: 0,
            pendingMessageCount = obj.int("pendingMessageCount") ?: 0,
        )
    }

    fun sessionStats(response: PiEvent.Response): SessionStats? {
        val obj = dataObject(response) ?: return null
        val tokens = obj.obj("tokens")?.let {
            TokenTotals(
                input = it.long("input") ?: 0,
                output = it.long("output") ?: 0,
                cacheRead = it.long("cacheRead") ?: 0,
                cacheWrite = it.long("cacheWrite") ?: 0,
                total = it.long("total") ?: 0,
            )
        }
        val usage = obj.obj("contextUsage")?.let {
            ContextUsage(
                tokens = it.long("tokens"),
                contextWindow = it.long("contextWindow"),
                percent = it.dbl("percent"),
            )
        }
        return SessionStats(
            sessionFile = obj.str("sessionFile"),
            sessionId = obj.str("sessionId"),
            userMessages = obj.int("userMessages") ?: 0,
            assistantMessages = obj.int("assistantMessages") ?: 0,
            toolCalls = obj.int("toolCalls") ?: 0,
            toolResults = obj.int("toolResults") ?: 0,
            totalMessages = obj.int("totalMessages") ?: 0,
            tokens = tokens,
            cost = obj.dbl("cost"),
            contextUsage = usage,
        )
    }

    /** `get_available_models` → `{ models: Model[] }`. */
    fun availableModels(response: PiEvent.Response): List<ModelInfo> =
        dataArray(response, "models").mapNotNull(::model)

    /**
     * `set_model` → the full `Model` object itself, not a wrapper
     * (`rpc-mode.ts` `success(id, "set_model", model)`).
     */
    fun setModel(response: PiEvent.Response): ModelInfo? = model(response.data)

    /** `cycle_model` → `{ model, thinkingLevel, isScoped }`, or null data. */
    fun cycleModel(response: PiEvent.Response): CycleModelResult? {
        val obj = dataObject(response) ?: return null
        return CycleModelResult(
            model = model(obj["model"]),
            thinkingLevel = obj.str("thinkingLevel"),
            isScoped = obj.bool("isScoped") ?: false,
        )
    }

    /** `get_available_thinking_levels` → `{ levels: ThinkingLevel[] }`. */
    fun thinkingLevels(response: PiEvent.Response): List<String> =
        (dataObject(response)?.get("levels") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?: emptyList()

    /** `cycle_thinking_level` → `{ level }`, or null data when unsupported. */
    fun cycleThinkingLevel(response: PiEvent.Response): CycleThinkingResult? {
        val obj = dataObject(response) ?: return null
        return CycleThinkingResult(level = obj.str("level"))
    }

    /** `compact` → `CompactionResult`. */
    fun compactionResult(response: PiEvent.Response): CompactionResult? =
        compactionResult(response.data)

    /**
     * Parse a bare `CompactionResult` object.
     *
     * Shared by the `compact` response and the `result` field of a
     * `compaction_end` event — pi builds both from the same
     * `core/compaction/compaction.ts` value, so they must not drift.
     */
    fun compactionResult(element: JsonElement?): CompactionResult? {
        val obj = element as? JsonObject ?: return null
        return CompactionResult(
            summary = obj.str("summary"),
            firstKeptEntryId = obj.str("firstKeptEntryId"),
            tokensBefore = obj.long("tokensBefore"),
            estimatedTokensAfter = obj.long("estimatedTokensAfter"),
            usage = obj.obj("usage")?.let(PiEvents::parseUsage),
            hasDetails = obj.containsKey("details") && obj["details"] !is JsonNull,
        )
    }

    /** `bash` → `BashResult`. */
    fun bashResult(response: PiEvent.Response): BashResult? {
        val obj = dataObject(response) ?: return null
        return BashResult(
            output = obj.str("output").orEmpty(),
            exitCode = obj.int("exitCode"),
            cancelled = obj.bool("cancelled") ?: false,
            truncated = obj.bool("truncated") ?: false,
            fullOutputPath = obj.str("fullOutputPath"),
        )
    }

    /** `clear_queue` → `{ steering, followUp }`. */
    fun clearQueueResult(response: PiEvent.Response): ClearQueueResult? {
        val obj = dataObject(response) ?: return null
        return ClearQueueResult(
            steering = obj.strList("steering"),
            followUp = obj.strList("followUp"),
        )
    }

    /** `new_session` / `switch_session` / `clone` → `{ cancelled }`. */
    fun cancelledResult(response: PiEvent.Response): CancelledResult? {
        val obj = dataObject(response) ?: return null
        return CancelledResult(cancelled = obj.bool("cancelled") ?: false)
    }

    /** `fork` → `{ text, cancelled }`. `text` is `selectedText`, which is optional. */
    fun forkResult(response: PiEvent.Response): ForkResult? {
        val obj = dataObject(response) ?: return null
        return ForkResult(
            text = obj.str("text"),
            cancelled = obj.bool("cancelled") ?: false,
        )
    }

    /** `get_fork_messages` → `{ messages: [{ entryId, text }] }`. */
    fun forkMessages(response: PiEvent.Response): List<ForkMessage> =
        dataArray(response, "messages").mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            // entryId is what `fork` takes; without it the row cannot be used.
            val entryId = obj.str("entryId") ?: return@mapNotNull null
            ForkMessage(entryId = entryId, text = obj.str("text").orEmpty())
        }

    /**
     * `get_entries` → `{ entries, leafId }`.
     *
     * The wire is `SessionEntry[]`; every element parses into a typed
     * [SessionEntry], an unrecognised `type` becoming [SessionEntry.Unknown].
     *
     * Note pi answers `success: false` (not an empty page) when `since` does not
     * match any entry id — a caller passing a stale cursor gets an error, not a
     * silently empty result.
     */
    fun sessionEntries(response: PiEvent.Response): EntriesPage? {
        val obj = dataObject(response) ?: return null
        val entries = (obj["entries"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map(::parseSessionEntry)
            ?: emptyList()
        return EntriesPage(entries = entries, leafId = obj.str("leafId"))
    }

    /** `get_tree` → `{ tree, leafId }`. */
    fun tree(response: PiEvent.Response): TreePage? {
        val obj = dataObject(response) ?: return null
        val nodes = (obj["tree"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.map(::parseSessionTreeNode)
            ?: emptyList()
        return TreePage(tree = nodes, leafId = obj.str("leafId"))
    }

    /** `get_last_assistant_text` → `{ text: string | null }`. */
    fun lastAssistantText(response: PiEvent.Response): String? =
        dataObject(response)?.str("text")

    /** `get_messages` → `{ messages: AgentMessage[] }`. */
    fun messages(response: PiEvent.Response): List<PiMessage> =
        dataArray(response, "messages").mapNotNull { (it as? JsonObject)?.let(::parsePiMessage) }

    /** `export_html` → `{ path: string }`. */
    fun exportPath(response: PiEvent.Response): String? =
        dataObject(response)?.str("path")

    /** `export_html` → typed wrapper. */
    fun exportResult(response: PiEvent.Response): ExportResult? {
        val obj = dataObject(response) ?: return null
        return ExportResult(path = obj.str("path"))
    }

    /** `new_session` / `switch_session` / `clone` → `{ cancelled: boolean }`. */
    fun cancelled(response: PiEvent.Response): Boolean =
        dataObject(response)?.bool("cancelled") ?: false

    /**
     * `get_entries` → the raw entry objects.
     *
     * Kept as an escape hatch for callers that want the bytes; prefer
     * [sessionEntries], which returns typed entries.
     */
    fun entries(response: PiEvent.Response): List<JsonObject> {
        val entries = dataObject(response)?.get("entries") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { it as? JsonObject }
    }

    // ----------------------------------------------------------------- internals

    /**
     * Parse a bare `Model` object.
     *
     * `id` is what identifies a model to every later command and what the picker
     * shows, so a model without one is dropped rather than keyed by an invention.
     * `name` falls back to the id, as pi's own UI does.
     */
    private fun model(element: JsonElement?): ModelInfo? {
        val obj = element as? JsonObject ?: return null
        val id = obj.str("id") ?: return null
        val cost = obj.obj("cost")
        return ModelInfo(
            id = id,
            name = obj.str("name") ?: id,
            provider = obj.str("provider"),
            api = obj.str("api"),
            baseUrl = obj.str("baseUrl"),
            reasoning = obj.bool("reasoning") ?: false,
            acceptsImages = (obj["input"] as? JsonArray)
                ?.any { (it as? JsonPrimitive)?.content == "image" } ?: false,
            contextWindow = obj.long("contextWindow"),
            maxTokens = obj.long("maxTokens"),
            inputCost = cost?.dbl("input"),
            outputCost = cost?.dbl("output"),
            cacheReadCost = cost?.dbl("cacheRead"),
            cacheWriteCost = cost?.dbl("cacheWrite"),
        )
    }

    private fun parseSourceInfo(obj: JsonObject): PiSourceInfo = PiSourceInfo(
        path = obj.str("path"),
        source = obj.str("source"),
        scope = obj.str("scope"),
        origin = obj.str("origin"),
        baseDir = obj.str("baseDir"),
    )

    private fun dataObject(response: PiEvent.Response): JsonObject? = response.data as? JsonObject

    private fun dataArray(response: PiEvent.Response, key: String): JsonArray =
        dataObject(response)?.get(key) as? JsonArray ?: JsonArray(emptyList())
}
