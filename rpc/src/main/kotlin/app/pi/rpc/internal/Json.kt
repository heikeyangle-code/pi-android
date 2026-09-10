package app.pi.rpc.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Strict JSON entry point for wire records.
 *
 * Deliberately NOT lenient: pi emits strict JSON, and lenient parsing would
 * silently accept a truncated frame (the classic symptom of a reader that split
 * on the wrong character) instead of surfacing it. Unparsable input is handled
 * one level up by degrading to an `Unknown` event.
 */
internal object Json {
    private val parser = Json {
        isLenient = false
        allowTrailingComma = false
        allowSpecialFloatingPointValues = true
    }

    fun parseObject(text: String): JsonObject? =
        parser.parseToJsonElement(text) as? JsonObject
}
