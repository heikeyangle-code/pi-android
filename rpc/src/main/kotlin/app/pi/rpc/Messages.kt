package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where an extension command, prompt template or skill was loaded from.
 *
 * Shape from `src/core/source-info.ts` (`SourceInfo`). `rpc-types.ts` attaches
 * one to every `RpcSlashCommand`.
 *
 * Note the documented/implemented split: `docs/rpc.md`'s `get_commands` example
 * shows top-level `path` and `location` fields, but `rpc-mode.ts` never emits
 * them — it emits this object instead (`rpc-mode.ts` "get_commands" case). The
 * reader in [PiResponses.slashCommands] accepts either, because the doc is what
 * a future minor version would follow if it ever added the flat form back.
 */
data class PiSourceInfo(
    val path: String?,
    val source: String?,
    val scope: String?,
    val origin: String?,
    val baseDir: String?,
)

/**
 * One block of a message `content` array.
 *
 * Transcribed from `docs/session-format.md` §"Content Blocks" and verified
 * against `packages/ai/src/types.ts`. [Unknown] keeps the raw object so an
 * unrecognised block type degrades to "not rendered" instead of losing the
 * message around it.
 */
sealed interface PiContentBlock {

    data class Text(
        val text: String,
        /** Provider replay signature; meaningless to the UI, kept for round-tripping. */
        val signature: String?,
    ) : PiContentBlock

    data class Image(
        val data: String,
        val mimeType: String?,
    ) : PiContentBlock

    data class Thinking(
        val thinking: String,
        val signature: String?,
        val redacted: Boolean,
    ) : PiContentBlock

    data class ToolCall(
        val id: String?,
        val name: String?,
        /**
         * Null when the provider sent arguments as a JSON *string* rather than an
         * object. Stored messages from pi always use an object, so this only
         * occurs for a provider-specific variant; the raw block is still in
         * [Unknown.raw] when the whole block was unrecognised.
         */
        val arguments: JsonObject?,
        val namespace: String?,
    ) : PiContentBlock

    data class Unknown(
        val type: String?,
        val raw: JsonObject,
    ) : PiContentBlock
}

/**
 * One `AgentMessage`, as returned by `get_messages` and embedded in
 * `message` session entries.
 *
 * Union from `docs/session-format.md` §"AgentMessage Union":
 * core shapes live in `packages/ai/src/types.ts`, the extended ones in
 * `src/core/messages.ts`. Field names are the contract, so they are pi's.
 */
sealed interface PiMessage {

    /** pi's `role` discriminator, verbatim. */
    val role: String

    /**
     * Flattened display text, mirroring pi's own extraction: `text` blocks are
     * concatenated, an image becomes `[image]`, thinking and tool calls
     * contribute nothing. For an assistant message this is exactly what
     * `get_last_assistant_text` returns (`agent-session.ts#getLastAssistantText`
     * loops over `content` and only appends `type === "text"`).
     */
    val text: String

    data class User(
        val content: List<PiContentBlock>,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "user"
        override val text: String get() = flattenBlocks(content)
    }

    data class Assistant(
        val content: List<PiContentBlock>,
        val api: String?,
        val provider: String?,
        val model: String?,
        val usage: TokenUsage?,
        val stopReason: String?,
        val errorMessage: String?,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "assistant"
        override val text: String get() = flattenBlocks(content)
    }

    data class ToolResult(
        val toolCallId: String?,
        val toolName: String?,
        val content: List<PiContentBlock>,
        /** Nested LLM work the tool performed; already counted in session totals. */
        val usage: TokenUsage?,
        val isError: Boolean,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "toolResult"
        override val text: String get() = flattenBlocks(content)
    }

    /**
     * Created by the `bash` RPC command, not by an LLM tool call
     * (`src/core/messages.ts#BashExecutionMessage`).
     */
    data class BashExecution(
        val command: String?,
        val output: String?,
        /** Absent when the process was killed or cancelled (`BashResult.exitCode`). */
        val exitCode: Int?,
        val cancelled: Boolean,
        val truncated: Boolean,
        val fullOutputPath: String?,
        /** True for `!!` commands, which never enter LLM context. */
        val excludeFromContext: Boolean?,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "bashExecution"
        override val text: String get() = output.orEmpty()
    }

    data class Custom(
        val customType: String?,
        val content: List<PiContentBlock>,
        val display: Boolean,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "custom"
        override val text: String get() = flattenBlocks(content)
    }

    data class BranchSummary(
        val summary: String?,
        val fromId: String?,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "branchSummary"
        override val text: String get() = summary.orEmpty()
    }

    data class CompactionSummary(
        val summary: String?,
        val tokensBefore: Long?,
        val timestamp: Long?,
    ) : PiMessage {
        override val role = "compactionSummary"
        override val text: String get() = summary.orEmpty()
    }

    /** A role a newer pi introduced. Never dropped — the raw object is kept. */
    data class Unknown(
        override val role: String,
        val raw: JsonObject,
    ) : PiMessage {
        override val text: String get() = ""
    }
}

// --------------------------------------------------------------------- parsing

/**
 * Parse one `AgentMessage`.
 *
 * Total by construction: an unrecognised `role` yields [PiMessage.Unknown], and
 * a missing field yields a null/empty value rather than an exception. See the
 * unknown-field policy on [PiResponses].
 */
fun parsePiMessage(raw: JsonObject): PiMessage {
    val role = raw.str("role").orEmpty()
    val timestamp = raw.long("timestamp")
    return when (role) {
        "user" -> PiMessage.User(
            content = parseContentBlocks(raw["content"]),
            timestamp = timestamp,
        )

        "assistant" -> PiMessage.Assistant(
            content = parseContentBlocks(raw["content"]),
            api = raw.str("api"),
            provider = raw.str("provider"),
            model = raw.str("model"),
            usage = raw.obj("usage")?.let(PiEvents::parseUsage),
            stopReason = raw.str("stopReason"),
            errorMessage = raw.str("errorMessage"),
            timestamp = timestamp,
        )

        "toolResult" -> PiMessage.ToolResult(
            toolCallId = raw.str("toolCallId"),
            toolName = raw.str("toolName"),
            content = parseContentBlocks(raw["content"]),
            usage = raw.obj("usage")?.let(PiEvents::parseUsage),
            isError = raw.bool("isError") ?: false,
            timestamp = timestamp,
        )

        "bashExecution" -> PiMessage.BashExecution(
            command = raw.str("command"),
            output = raw.str("output"),
            exitCode = raw.int("exitCode"),
            cancelled = raw.bool("cancelled") ?: false,
            truncated = raw.bool("truncated") ?: false,
            fullOutputPath = raw.str("fullOutputPath"),
            excludeFromContext = raw.bool("excludeFromContext"),
            timestamp = timestamp,
        )

        // Version 3 renamed the old `hookMessage` role to `custom`
        // (docs/session-format.md §"Session Version"). Old sessions are migrated
        // on load, so `custom` is the only spelling pi emits; the reader below
        // still recognises the legacy role so a pre-migration file read straight
        // off disk does not become an Unknown message.
        "custom", "hookMessage" -> PiMessage.Custom(
            customType = raw.str("customType"),
            content = parseContentBlocks(raw["content"]),
            display = raw.bool("display") ?: false,
            timestamp = timestamp,
        )

        "branchSummary" -> PiMessage.BranchSummary(
            summary = raw.str("summary"),
            fromId = raw.str("fromId"),
            timestamp = timestamp,
        )

        "compactionSummary" -> PiMessage.CompactionSummary(
            summary = raw.str("summary"),
            tokensBefore = raw.long("tokensBefore"),
            timestamp = timestamp,
        )

        else -> PiMessage.Unknown(role, raw)
    }
}

/**
 * Parse a message `content` field, which pi types as
 * `string | (TextContent | ImageContent)[]`.
 *
 * A bare string becomes a single [PiContentBlock.Text]; anything that is neither
 * a string nor an array yields an empty list.
 */
internal fun parseContentBlocks(element: JsonElement?): List<PiContentBlock> = when (element) {
    null -> emptyList()
    // JsonNull is a JsonPrimitive subclass, so it must be matched first.
    is JsonNull -> emptyList()
    is JsonPrimitive -> if (element.isString) {
        listOf(PiContentBlock.Text(element.content, null))
    } else {
        emptyList()
    }

    is JsonArray -> element.mapNotNull { parseContentBlock(it) }
    is JsonObject -> listOfNotNull(parseContentBlock(element))
}

/** Parse one content block; null for a non-object (e.g. a stray number). */
internal fun parseContentBlock(element: JsonElement?): PiContentBlock? {
    val obj = element as? JsonObject ?: return null
    return when (obj.str("type")) {
        "text" -> PiContentBlock.Text(
            text = obj.str("text").orEmpty(),
            signature = obj.str("textSignature"),
        )

        "image" -> PiContentBlock.Image(
            data = obj.str("data").orEmpty(),
            mimeType = obj.str("mimeType"),
        )

        "thinking" -> PiContentBlock.Thinking(
            thinking = obj.str("thinking").orEmpty(),
            signature = obj.str("thinkingSignature"),
            redacted = obj.bool("redacted") ?: false,
        )

        "toolCall" -> PiContentBlock.ToolCall(
            id = obj.str("id"),
            name = obj.str("name"),
            arguments = obj.obj("arguments"),
            namespace = obj.str("namespace"),
        )

        else -> PiContentBlock.Unknown(obj.str("type"), obj)
    }
}

/** Text blocks concatenated, images as `[image]`, everything else ignored. */
internal fun flattenBlocks(blocks: List<PiContentBlock>): String = buildString {
    for (block in blocks) {
        when (block) {
            is PiContentBlock.Text -> append(block.text)
            is PiContentBlock.Image -> append("[image]")
            is PiContentBlock.Thinking, is PiContentBlock.ToolCall, is PiContentBlock.Unknown -> Unit
        }
    }
}
