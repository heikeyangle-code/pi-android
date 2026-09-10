package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed readers for the `data` payload of each RPC response.
 *
 * Every shape here is transcribed from pi's own definitions, and the field names
 * and nullability are the compatibility contract:
 *
 *  - `RpcSlashCommand`  — `src/modes/rpc/rpc-types.ts`
 *  - `RpcSessionState`  — same file
 *  - `SessionStats`     — `src/core/agent-session.ts`
 *  - `ContextUsage`     — `src/core/extensions/types.ts`
 *  - `Model`, `ModelCost` — `packages/ai/src/types.ts`
 *
 * All readers are total: an absent or misshapen field yields null rather than an
 * exception, because a newer pi must degrade the UI, not break it.
 */
object PiResponses {

    /** What kind of thing backs a slash command; drives grouping in the palette. */
    enum class CommandSource { Extension, Prompt, Skill, Unknown }

    /** `RpcSlashCommand` */
    data class SlashCommand(
        val name: String,
        val description: String?,
        val source: CommandSource,
    )

    /** `RpcSessionState` */
    data class SessionState(
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

    /** `ContextUsage` */
    data class ContextUsage(
        val tokens: Long?,
        val contextWindow: Long?,
        val percent: Double?,
    )

    /** `SessionStats` */
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
        val reasoning: Boolean,
        val acceptsImages: Boolean,
        val contextWindow: Long?,
        val maxTokens: Long?,
        /** USD per million tokens. */
        val inputCost: Double?,
        val outputCost: Double?,
    )

    // ------------------------------------------------------------------ readers

    fun slashCommands(response: PiEvent.Response): List<SlashCommand> {
        val commands = dataObject(response)?.get("commands") as? JsonArray ?: return emptyList()
        return commands.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            SlashCommand(
                name = name,
                description = obj.str("description"),
                source = when (obj.str("source")) {
                    "extension" -> CommandSource.Extension
                    "prompt" -> CommandSource.Prompt
                    "skill" -> CommandSource.Skill
                    else -> CommandSource.Unknown
                },
            )
        }
    }

    fun sessionState(response: PiEvent.Response): SessionState? {
        val obj = dataObject(response) ?: return null
        return SessionState(
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

    /** `get_available_models` → `{ models: Model[] }` */
    fun availableModels(response: PiEvent.Response): List<ModelInfo> {
        val models = dataObject(response)?.get("models") as? JsonArray ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            val cost = obj.obj("cost")
            ModelInfo(
                id = id,
                name = obj.str("name") ?: id,
                provider = obj.str("provider"),
                reasoning = obj.bool("reasoning") ?: false,
                acceptsImages = (obj["input"] as? JsonArray)
                    ?.any { (it as? JsonPrimitive)?.content == "image" } ?: false,
                contextWindow = obj.long("contextWindow"),
                maxTokens = obj.long("maxTokens"),
                inputCost = cost?.dbl("input"),
                outputCost = cost?.dbl("output"),
            )
        }
    }

    /** `get_available_thinking_levels` → `{ levels: ThinkingLevel[] }` */
    fun thinkingLevels(response: PiEvent.Response): List<String> {
        val levels = dataObject(response)?.get("levels") as? JsonArray ?: return emptyList()
        return levels.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    /** `get_entries` → `{ entries: SessionEntry[] }` (shape verified by the caller). */
    fun entries(response: PiEvent.Response): List<JsonObject> {
        val entries = dataObject(response)?.get("entries") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { it as? JsonObject }
    }

    /** `get_last_assistant_text` → `{ text: string }` */
    fun lastAssistantText(response: PiEvent.Response): String? =
        dataObject(response)?.str("text")

    /** `export_html` → `{ path: string }` */
    fun exportPath(response: PiEvent.Response): String? =
        dataObject(response)?.str("path")

    /** `new_session` / `switch_session` / `clone` → `{ cancelled: boolean }` */
    fun cancelled(response: PiEvent.Response): Boolean =
        dataObject(response)?.bool("cancelled") ?: false

    private fun dataObject(response: PiEvent.Response): JsonObject? = response.data as? JsonObject
}
