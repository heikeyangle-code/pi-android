package app.pi.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Editing a pi settings document as a JSON tree.
 *
 * This lives in the protocol module for one reason: `:rpc` has no Android
 * dependency, so this logic — the part that can silently corrupt a user's
 * configuration — is covered by unit tests that run on any JDK. The same
 * functions on the Android side could only be tested through a full Gradle
 * build, which on this project's build machine is not always possible.
 *
 * Semantics are pi's (`core/settings-manager.ts`), verified against the source:
 *
 *  - **Objects merge recursively, everything else replaces.** pi's
 *    `isMergeableObject` returns false for arrays, so a project `defaultTools`
 *    list replaces the global one rather than appending to it.
 *  - **Documents are sparse.** An absent key means "use the built-in default",
 *    which is why [lookup] answers null rather than a default.
 *  - **Writes preserve siblings.** Setting one key must not reorder or drop the
 *    hundreds of keys a user may have, including ones pi does not know about.
 */
object SettingsDocument {

    /**
     * Look up a dotted path (`compaction.reserveTokens`). Null when any segment
     * is missing or when an intermediate node is not an object.
     */
    fun lookup(document: JsonObject, key: String): JsonElement? {
        var current: JsonElement = document
        for (segment in key.split('.')) {
            val obj = current as? JsonObject ?: return null
            current = obj[segment] ?: return null
        }
        return current
    }

    /**
     * Return a copy of [document] with [key] set to [value], leaving every other
     * key — including unknown ones — exactly as it was.
     */
    fun setPath(document: JsonObject, key: String, value: JsonElement): JsonObject {
        val segments = key.split('.')
        fun recurse(node: JsonObject, depth: Int): JsonObject {
            val segment = segments[depth]
            val next = LinkedHashMap(node)
            next[segment] = if (depth == segments.lastIndex) {
                value
            } else {
                recurse(node[segment] as? JsonObject ?: JsonObject(emptyMap()), depth + 1)
            }
            return JsonObject(next)
        }
        return recurse(document, 0)
    }

    /**
     * Return a copy of [document] with [key] removed, pruning any parent object
     * that becomes empty. Pruning matters: pi treats an empty object differently
     * from an absent key in a few places, and a settings file littered with `{}`
     * is harder for a human to read.
     */
    fun removePath(document: JsonObject, key: String): JsonObject {
        val segments = key.split('.')
        fun recurse(node: JsonObject, depth: Int): JsonObject {
            val segment = segments[depth]
            val next = LinkedHashMap(node)
            if (depth == segments.lastIndex) {
                next.remove(segment)
            } else {
                val child = node[segment] as? JsonObject ?: return node
                val pruned = recurse(child, depth + 1)
                if (pruned.isEmpty()) next.remove(segment) else next[segment] = pruned
            }
            return JsonObject(next)
        }
        return recurse(document, 0)
    }

    /**
     * pi's `deepMergeObjects`: [overrides] wins, but only objects recurse.
     * Arrays and scalars are taken wholesale from [overrides].
     */
    fun merge(base: JsonObject, overrides: JsonObject): JsonObject {
        val out = LinkedHashMap(base)
        for ((key, overrideValue) in overrides) {
            val baseValue = base[key]
            out[key] = if (baseValue is JsonObject && overrideValue is JsonObject) {
                merge(baseValue, overrideValue)
            } else {
                overrideValue
            }
        }
        return JsonObject(out)
    }
}
