package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 *  - **Objects merge recursively, everything else replaces** — with the one
 *    exception pi carves out for `defaultTools`: a project list made **only** of
 *    `+name`/`-name` modifiers appends to the global selection instead of
 *    replacing it (`core/settings-manager.ts:223-228`, applied by
 *    `:249-251`; see [mergeDefaultTools]). Every other array still replaces
 *    wholesale, because pi's `isMergeableObject` returns false for arrays. That
 *    special case was added in 0.99.0; before it, a project
 *    `defaultTools: ["+codemode"]` replaced the global list, so the app's
 *    "effective value" showed the opposite of what pi ran.
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
     * pi's `deepMergeSettings` (`core/settings-manager.ts:247-252`): the generic
     * `deepMergeObjects` (`:193-210`) plus the `defaultTools` special case it
     * applies afterwards (`:250-251`). [overrides] wins, but only objects
     * recurse; arrays and scalars are taken wholesale, except for
     * `defaultTools` — see [mergeDefaultTools].
     *
     * The two steps are kept separate on purpose: pi's special case is applied
     * once, at the top level, so a nested key that happens to be named
     * `defaultTools` must still merge generically.
     */
    fun merge(base: JsonObject, overrides: JsonObject): JsonObject {
        val merged = mergeObjects(base, overrides)
        if (!overrides.containsKey("defaultTools")) return merged
        val tools = mergeDefaultTools(base["defaultTools"], overrides["defaultTools"]) ?: return merged
        val out = LinkedHashMap(merged)
        out["defaultTools"] = tools
        return JsonObject(out)
    }

    /**
     * pi's `mergeDefaultTools` (`core/settings-manager.ts:223-228`): the rule
     * with which a project `defaultTools` list combines with the global one.
     *
     * A list made **only** of `+name`/`-name` entries (`isToolModifier`,
     * `:215-217`) is appended to the inherited selection, so it modifies it;
     * anything else — a plain tool name, a mix of plain and modifier, a
     * non-array, or an entry that is not a string — replaces it outright. A
     * **mixed** list is the trap: pi takes its plain names as the whole
     * whitelist (`:236`), so `["+codemode","grep"]` unexpectedly disables
     * read/bash/edit/write.
     *
     * Pure and public so the rule can be pinned by a JVM test without a
     * filesystem, which is why this object lives in `:rpc` at all.
     *
     * Null mirrors pi's `undefined`: an absent override keeps the base. An
     * empty override list is *not* special-cased, faithfully to pi — in
     * JavaScript `[].every(isToolModifier)` is vacuously true, so a project
     * `defaultTools: []` returns the global selection unchanged (`:226`).
     */
    fun mergeDefaultTools(base: JsonElement?, overrides: JsonElement?): JsonElement? {
        // pi: `if (overrides === undefined) return base`.
        if (overrides == null) return base
        val baseArray = base as? JsonArray
        val overrideArray = overrides as? JsonArray
        // pi: settings files are not validated, so a malformed value replaces instead of throwing.
        if (baseArray == null || overrideArray == null || !overrideArray.all { isToolModifier(it) }) {
            return overrides
        }
        return JsonArray(baseArray + overrideArray)
    }

    /**
     * pi's `isToolModifier` (`core/settings-manager.ts:215-217`) is
     * `typeof entry === "string" && (startsWith("+") || startsWith("-"))`, so a
     * JSON number, object or null in the list makes the whole override replace.
     */
    private fun isToolModifier(entry: JsonElement): Boolean {
        val name = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        return name.startsWith("+") || name.startsWith("-")
    }

    /**
     * pi's `deepMergeObjects` (`core/settings-manager.ts:193-210`): [overrides]
     * wins, but only objects recurse.
     */
    private fun mergeObjects(base: JsonObject, overrides: JsonObject): JsonObject {
        val out = LinkedHashMap(base)
        for ((key, overrideValue) in overrides) {
            val baseValue = base[key]
            out[key] = if (baseValue is JsonObject && overrideValue is JsonObject) {
                mergeObjects(baseValue, overrideValue)
            } else {
                overrideValue
            }
        }
        return JsonObject(out)
    }
}
