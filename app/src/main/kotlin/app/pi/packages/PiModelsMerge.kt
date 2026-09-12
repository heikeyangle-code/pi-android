package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The merge rules `PiModelsFile` writes `<agentDir>/models.json` with.
 *
 * ## Why this is its own object
 *
 * It is the half of that writer that can be wrong **silently**: pi's schema allows
 * keys this app does not model, and a reader-modifier-writer that rebuilds a block
 * from its own data class deletes every one of them. That is exactly what happened
 * — `PiModelsFile.upsert` used to assign the freshly built block over the old one,
 * so saving a provider discarded its `headers`, `compat`, `oauth`, `apiKey` and
 * `modelOverrides`, and saving a model discarded its `thinkingLevelMap`,
 * `samplingParams`, `headers`, `compat`, `baseUrl` and `cost.tiers`
 * (`docs/known-gaps.md` §M12).
 *
 * Keeping the rules here — no files, no Compose, no Android — is what lets
 * `PackagesPureLogicCheck` run them on a bare JVM, which is the only kind of test
 * this repository can run for the app module.
 *
 * ## The rule, in one line
 *
 * **The app owns some keys; pi owns the rest.** Every key in the written block wins;
 * every other key already in the file is carried over untouched.
 *
 * @see PiConfigFiles for the file-level read/write and the schema citations.
 */
internal object PiModelsMerge {

    /**
     * One provider block: [written] laid over [existing].
     *
     * `models` is special-cased, and only `models`: its **absence** in [written] is
     * itself a statement — "pi's own catalog is the only declaration for this
     * provider" (`PiCredentialService.save`) — so the key is removed rather than
     * left as the merge would. Leaving it would re-declare exactly the models the
     * caller decided not to declare, which is the failure `docs/known-gaps.md` §M11
     * is about.
     */
    fun provider(existing: JsonObject?, written: JsonObject): JsonObject {
        if (existing == null) return written
        val merged = LinkedHashMap<String, JsonElement>(existing)
        for ((key, value) in written) merged[key] = value
        val declaredModels = written["models"]
        if (declaredModels != null) {
            merged["models"] = models(existing["models"], declaredModels)!!
        } else {
            merged.remove("models")
        }
        return JsonObject(merged)
    }

    /**
     * The same rule one level down: each declared entry is laid over the entry that
     * already carries its `id`, so the per-model keys this app does not model
     * survive. An entry the file did not already have is written as-is.
     */
    fun models(existing: JsonElement?, declared: JsonElement?): JsonElement? {
        val list = declared as? JsonArray ?: return declared
        val prior = (existing as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { obj -> idOf(obj)?.let { id -> id to obj } }
            ?.toMap()
            .orEmpty()
        if (prior.isEmpty()) return list
        return JsonArray(
            list.map { element ->
                val obj = element as? JsonObject ?: return@map element
                val before = idOf(obj)?.let { prior[it] } ?: return@map element
                JsonObject(LinkedHashMap<String, JsonElement>(before).apply { putAll(obj) })
            },
        )
    }

    private fun idOf(obj: JsonObject): String? = (obj["id"] as? JsonPrimitive)?.content
}
