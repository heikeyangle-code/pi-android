package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pi's persisted model catalog — `<agentDir>/models-store.json` — read as a
 * **capabilities** source.
 *
 * ## Where the file is, and why it is the right source
 *
 * `FileModelsStore` defaults to `join(dirname(modelsPath), "models-store.json")`
 * (`core/model-runtime.ts:180`), and `modelsPath` is `join(agentDir, "models.json")`
 * (`core/agent-session-services.ts:142`) — so the catalog lives beside the file this
 * app already edits. Its shape is `Record<providerId, { models: Model[] }>`
 * (`packages/ai/src/models-store.ts:3-14`), where each `Model` carries the vendor
 * metadata: `id`, `name`, `reasoning`, `input`, `contextWindow`, `maxTokens`, `cost`.
 *
 * Why the app needs a second source at all: `get_available_models` answers from
 * `modelRuntime.getAvailableSnapshot()` → `snapshot.available`
 * (`modes/rpc/rpc-mode.ts:490-493`, `core/model-runtime.ts:422-424`), and pi **hides a
 * provider whose credential is missing** (`docs/models.md:34-36`). So at the exact
 * moment a user adds a provider for the first time — the credential is being entered
 * right now — the engine's list cannot contain its models, and the app had nothing to
 * resolve capabilities from. It then wrote a declaration carrying only an `id`, and
 * pi *replaces* the catalog entry with it (`docs/known-gaps.md` §M11/§M13): a 1M
 * context window became 128 000, `maxTokens` 384 000 became 16 384, `reasoning` went
 * false, cost went 0, and images stopped being sent.
 *
 * The catalog file is the one source that exists **before** the credential does.
 *
 * ## Not a second copy of pi's data
 *
 * Nothing here is written back, and nothing is invented: a key that is missing stays
 * `null` so the caller can tell "pi says text-only" from "pi says nothing". This is a
 * reader for a file pi owns, in the same spirit as the app's other pi-file readers.
 */
object PiModelCatalog {

    /** One catalog entry, as far as this app has a use for it. */
    data class Entry(
        val id: String,
        val name: String?,
        val reasoning: Boolean?,
        val acceptsImages: Boolean?,
        val contextWindow: Long?,
        val maxTokens: Long?,
    )

    /**
     * The entries [providerId] has in [text], or an empty list when the file is
     * absent, unparseable, or simply has nothing for that provider.
     *
     * Every failure is a silence rather than a guess, deliberately: the caller's
     * fallback is the engine's own answer, and a wrong capability is worse than a
     * missing one (it is what produced the bug above).
     */
    fun parse(text: String, providerId: String): List<Entry> {
        val root = runCatching { PiJsonLite.parseObject(text) }.getOrNull() ?: return emptyList()
        val block = root[providerId] as? JsonObject ?: return emptyList()
        val models = block["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrBlank()) return@mapNotNull null
            Entry(
                id = id,
                name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                reasoning = (obj["reasoning"] as? JsonPrimitive)?.let { primitive ->
                    primitive.content.toBooleanStrictOrNull()
                },
                acceptsImages = (obj["input"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?.let { inputs -> "image" in inputs },
                contextWindow = (obj["contextWindow"] as? JsonPrimitive)?.content?.toLongOrNull(),
                maxTokens = (obj["maxTokens"] as? JsonPrimitive)?.content?.toLongOrNull(),
            )
        }
    }
}

/**
 * The one JSON parse this reader needs, kept here rather than pulled from the
 * settings layer: [PiConfigFiles] strips comments and goes through the app's settings
 * code, while this file is pi's own cache and is plain JSON
 * (`JSON.stringify(current, null, 2)`, `core/models-store.ts:132`).
 */
private object PiJsonLite {
    fun parseObject(text: String): JsonObject? =
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
