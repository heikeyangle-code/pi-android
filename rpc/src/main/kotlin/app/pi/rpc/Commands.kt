package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** An image attached to a prompt, in pi's `ImageContent` shape. */
data class PiImage(val base64: String, val mimeType: String)

/** How a message submitted mid-stream is delivered (pi requires this). */
enum class StreamingBehavior(val wire: String) {
    /** Injected after the current turn finishes executing its tool calls. */
    Steer("steer"),

    /** Injected only once the agent has finished all work. */
    FollowUp("followUp"),
}

/** pi's queue draining policy, settable per queue. */
enum class QueueMode(val wire: String) {
    OneAtATime("one-at-a-time"),
    All("all"),
}

/**
 * Builders for all 33 commands of pi's RPC surface, plus the out-of-band
 * `extension_ui_response`.
 *
 * These are thin and dumb on purpose: the wire *is* the compatibility contract
 * (see docs/pi-android-app-design.md §4.2), so nothing here may rename, nest or
 * "improve" a field. Every builder takes an explicit [id] used for request /
 * response correlation; pi echoes it back on the matching `response`.
 *
 * Encoding is `JsonElement.toString()`, which kotlinx-serialization emits as
 * compact, valid JSON — no reflection, no serializer lookup on the hot path.
 */
object PiCommands {

    // ---------------------------------------------------------------- prompting

    fun prompt(
        id: String,
        message: String,
        images: List<PiImage> = emptyList(),
        streamingBehavior: StreamingBehavior? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "prompt")
        put("message", message)
        putImages(images)
        streamingBehavior?.let { put("streamingBehavior", it.wire) }
    }

    fun steer(
        id: String,
        message: String,
        images: List<PiImage> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "steer")
        put("message", message)
        putImages(images)
    }

    /**
     * pi's `follow_up` carries no `streamingBehavior` either: `steer` and
     * `follow_up` *are* the two delivery choices, and pi's own union has no such
     * field on either. Sending one would be inventing wire pi does not accept.
     */
    fun followUp(
        id: String,
        message: String,
        images: List<PiImage> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "follow_up")
        put("message", message)
        putImages(images)
    }

    fun abort(id: String): JsonObject = simple(id, "abort")

    fun clearQueue(id: String): JsonObject = simple(id, "clear_queue")

    /** @param parentSession pi accepts a parent session path when branching. */
    fun newSession(id: String, parentSession: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "new_session")
        parentSession?.let { put("parentSession", it) }
    }

    // -------------------------------------------------------------------- state

    fun getState(id: String): JsonObject = simple(id, "get_state")

    fun getMessages(id: String): JsonObject = simple(id, "get_messages")

    // -------------------------------------------------------------------- model

    fun setModel(id: String, provider: String, modelId: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_model")
        put("provider", provider)
        put("modelId", modelId)
    }

    fun cycleModel(id: String): JsonObject = simple(id, "cycle_model")

    fun getAvailableModels(id: String): JsonObject = simple(id, "get_available_models")

    // ----------------------------------------------------------------- thinking

    fun setThinkingLevel(id: String, level: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_thinking_level")
        put("level", level)
    }

    fun cycleThinkingLevel(id: String): JsonObject = simple(id, "cycle_thinking_level")

    fun getAvailableThinkingLevels(id: String): JsonObject =
        simple(id, "get_available_thinking_levels")

    // -------------------------------------------------------------------- queue

    fun setSteeringMode(id: String, mode: QueueMode): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_steering_mode")
        put("mode", mode.wire)
    }

    fun setFollowUpMode(id: String, mode: QueueMode): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_follow_up_mode")
        put("mode", mode.wire)
    }

    // --------------------------------------------------------------- compaction

    fun compact(id: String, customInstructions: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "compact")
        customInstructions?.let { put("customInstructions", it) }
    }

    fun setAutoCompaction(id: String, enabled: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_auto_compaction")
        put("enabled", enabled)
    }

    // -------------------------------------------------------------------- retry

    fun setAutoRetry(id: String, enabled: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_auto_retry")
        put("enabled", enabled)
    }

    fun abortRetry(id: String): JsonObject = simple(id, "abort_retry")

    // --------------------------------------------------------------------- bash

    fun bash(id: String, command: String, excludeFromContext: Boolean? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", "bash")
            put("command", command)
            excludeFromContext?.let { put("excludeFromContext", it) }
        }

    fun abortBash(id: String): JsonObject = simple(id, "abort_bash")

    // ------------------------------------------------------------------ session

    fun getSessionStats(id: String): JsonObject = simple(id, "get_session_stats")

    /** @param outputPath pi accepts an explicit destination for the export. */
    fun exportHtml(id: String, outputPath: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "export_html")
        outputPath?.let { put("outputPath", it) }
    }

    fun switchSession(id: String, sessionPath: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "switch_session")
        put("sessionPath", sessionPath)
    }

    fun fork(id: String, entryId: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "fork")
        put("entryId", entryId)
    }

    fun clone(id: String): JsonObject = simple(id, "clone")

    fun getForkMessages(id: String): JsonObject = simple(id, "get_fork_messages")

    /**
     * Durable incremental transcript read. Pass the last seen entry id as
     * [since] to receive only what is new — this is how the UI re-attaches
     * after a reconnect without re-projecting the whole session.
     */
    fun getEntries(id: String, since: String? = null): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "get_entries")
        since?.let { put("since", it) }
    }

    fun getTree(id: String): JsonObject = simple(id, "get_tree")

    fun getLastAssistantText(id: String): JsonObject = simple(id, "get_last_assistant_text")

    fun setSessionName(id: String, name: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "set_session_name")
        put("name", name)
    }

    // ----------------------------------------------------------------- commands

    fun getCommands(id: String): JsonObject = simple(id, "get_commands")

    // ------------------------------------------------------- extension UI answer

    /**
     * Answer a blocking `extension_ui_request`. Exactly one of [value],
     * [confirmed] or [cancelled] applies, depending on the request's `method`.
     */
    fun extensionUiValue(id: String, value: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "extension_ui_response")
        put("value", value)
    }

    fun extensionUiConfirmed(id: String, confirmed: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "extension_ui_response")
        put("confirmed", confirmed)
    }

    fun extensionUiCancelled(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", "extension_ui_response")
        put("cancelled", true)
    }

    // ------------------------------------------------------------------- helper

    /** Serialise for the wire: one record, one LF. */
    fun encode(command: JsonObject): String = command.toString() + "\n"

    private fun simple(id: String, type: String): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putImages(images: List<PiImage>) {
        if (images.isEmpty()) return
        putJsonArray("images") {
            for (image in images) {
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("data", image.base64)
                        put("mimeType", image.mimeType)
                    },
                )
            }
        }
    }
}
