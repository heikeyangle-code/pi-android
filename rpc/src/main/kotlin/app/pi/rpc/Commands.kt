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

    /**
     * pi's command surface also has a bare `steer`, and a mid-turn message normally
     * goes through [prompt] with `streamingBehavior = Steer` — what pi's own TUI
     * submits with (`interactive-mode.ts:3137-3143`). The app still sends this
     * command in exactly one window: **while pi is compacting**, where `prompt`
     * throws (`Cannot submit a prompt while compaction is in progress`,
     * `core/agent-session.ts:1192-1196`) and `steer` queues instead
     * (`_queueUserInput`, `:1388-1413`).
     *
     * The difference between the two routes is narrower than "prompt runs pi's
     * input processing and this does not": `_queueUserInput` runs the extension
     * `input` handlers and the skill/prompt-template expansion for the bare
     * commands too. What `prompt` adds is its **pre-flight** — the compaction
     * check, model/credential validation — and the fact that the TUI exercises it.
     *
     * Kept as the queueing route (and because this object transcribes pi's whole
     * command surface, with `CommandsTest` pinning the wire shape).
     */
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
     * pi's bare `follow_up`. Like [steer], the app no longer sends it: a follow-up
     * is [prompt] with `streamingBehavior = FollowUp`
     * (`interactive-mode.ts:4143-4150`), and it carries no `streamingBehavior` of
     * its own — `steer` and `follow_up` *are* the two delivery choices, and pi's own
     * union has no such field on either, so sending one would be inventing wire pi
     * does not accept. Kept for the same transcription reason as [steer].
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

    /**
     * pi's `cycle_model` — `app.model.cycleForward` / `cycleBackward` on the
     * keyboard, and **the app no longer sends it**: the overflow-menu entry that
     * called it was removed by the user's decision (cycling is a keybinding
     * affordance, and the model picker already lists every model). The command stays
     * because this object transcribes pi's whole command surface and the tests pin
     * its wire shape — the same treatment `steer` / `follow_up` get after the
     * `prompt` + `streamingBehavior` change.
     */
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
