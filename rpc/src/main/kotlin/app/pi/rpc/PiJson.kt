package app.pi.rpc

import kotlinx.serialization.json.JsonObject

/**
 * The one strict JSON entry point, exposed for callers outside this module.
 *
 * The app has to read pi's on-disk artifacts too — session files, `settings.json`
 * — and those are the same strict JSON as the wire. Rather than let a second
 * parser appear in `:app` with slightly different leniency, this delegates to the
 * same internal parser the event decoder uses.
 *
 * Deliberately NOT lenient: pi emits strict JSON, and a lenient parse would
 * silently accept a truncated record (the classic symptom of a reader that split
 * on the wrong character) instead of surfacing it.
 */
object PiJson {

    /** Parse one JSON object, or null if the text is not one. Never throws. */
    fun parseObjectOrNull(text: String): JsonObject? = runCatching {
        // Fully qualified on purpose: `internal` is a soft keyword in Kotlin and
        // is not accepted as a bare package qualifier in an expression.
        app.pi.rpc.internal.Json.parseObject(text)
    }.getOrNull()
}
