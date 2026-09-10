package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One stored decision per canonical directory. `null` is a legal stored value —
 * pi keeps it and skips it during lookup (`trust-manager.ts:48`, `:129-131`) — hence
 * the nullable value type rather than an absent key.
 *
 * Top-level rather than nested: Kotlin's nested type aliases need
 * `-Xnested-type-aliases`, and adding a compiler flag to `tools/typecheck.sh` and
 * `app/build.gradle.kts` for one alias is not a trade this file gets to make.
 */
typealias TrustStore = Map<String, Boolean?>

/**
 * pi's project-trust store, as a pure value layer.
 *
 * This object is the whole format, with no Android and no filesystem in it, so the
 * rules below can be checked without a device. The file-level half (which file,
 * when to mirror it) lives in [TrustRepository].
 *
 * ## The format, read off pi's own source rather than guessed
 *
 * `core/trust-manager.ts`:
 *
 *  - The store is `<agentDir>/trust.json` (`:212-214`, `ProjectTrustStore`'s
 *    constructor). `agentDir` is `getAgentDir()` (`config.ts:531-537`), which is
 *    `$PI_CODING_AGENT_DIR` — set by the engine host to `/root/.pi/agent`.
 *  - It is a JSON **object mapping canonical absolute directory → `true | false |
 *    null`**. Anything else makes the whole store invalid and pi *throws*
 *    (`:111-121`). A `null` is a legal stored value; `setMany` deletes the key
 *    instead of storing null (`:236-237`), so a `null` can only arrive from a file
 *    a user edited. `writeTrustFile` preserves whatever it read (`:129-131`).
 *  - Serialisation is `JSON.stringify(sorted, null, 2) + "\n"` with keys sorted
 *    (`:125-135`) — 2-space indent, sorted keys, trailing newline. Getting this
 *    wrong is not cosmetic: pi rewrites this file from its own in-memory copy on
 *    the next trust change, so a foreign shape is destroyed either way, but a
 *    reader that cannot parse what pi wrote breaks *trust*.
 *  - Lookup is **nearest ancestor wins**, and only `true`/`false` count — a `null`
 *    value is skipped and the walk continues upward (`:44-58`). Trusting
 *    `/workspace` therefore trusts everything below it.
 *  - A key is `canonicalizePath(resolvePath(cwd))` (`:40-42`), and
 *    `canonicalizePath` is `realpathSync` with a fallback to the input when the
 *    path does not exist (`utils/paths.ts:28-34`). Symlinks must therefore be
 *    resolved before writing — see [TrustRepository.canonicalizeGuestPath].
 *
 * ## Why a BOM is stripped
 *
 * pi reads through `stripBom` (`:105`). A file written by a Windows editor with a
 * BOM would otherwise fail `JSON.parse` and make pi throw on *every* startup,
 * which is a bricked agent. Stripping it costs nothing.
 */
object TrustFile {

    /** A resolved entry, i.e. what pi's `getEntry` returns (`trust-manager.ts:220-225`). */
    data class Entry(val path: String, val decision: Boolean)

    sealed interface Parse {
        data class Ok(val store: TrustStore) : Parse

        /**
         * The file exists but is not a valid store. [message] is pi's own wording
         * for the same condition, because the app must not invent a second
         * vocabulary for a failure the user will also see from pi.
         */
        data class Invalid(val message: String) : Parse
    }

    /** pi's three invalid cases, with pi's exact messages (`trust-manager.ts:108-119`). */
    fun parse(text: String, path: String): Parse {
        val body = stripBom(text)
        val element: JsonElement = runCatching { Json.parseToJsonElement(body) }.getOrElse { error ->
            val detail = error.message ?: error::class.java.simpleName
            return Parse.Invalid("Failed to read trust store $path: $detail")
        }
        // `parsed === null || typeof parsed !== "object" || Array.isArray(parsed)`
        val obj: JsonObject = element as? JsonObject
            ?: return Parse.Invalid("Invalid trust store $path: expected an object")

        val store = LinkedHashMap<String, Boolean?>()
        for ((key, value) in obj) {
            // JsonNull is the only accepted non-boolean: pi stores a literal null
            // and treats it as "no decision here, keep walking up".
            val decision: Boolean? = when {
                value is JsonNull -> null
                else -> (value as? JsonPrimitive)?.booleanOrNull
                    ?: return Parse.Invalid(
                        "Invalid trust store $path: value for ${jsonQuote(key)} must be true, false, or null",
                    )
            }
            store[key] = decision
        }
        return Parse.Ok(store)
    }

    /**
     * Serialise exactly as `writeTrustFile` does (`trust-manager.ts:125-135`).
     *
     * Sorted by UTF-16 code unit: JavaScript's `Array.prototype.sort()` compares
     * UTF-16 code units, and Kotlin's `String.compareTo` compares `Char`s, so
     * `sorted()` is the same order. Non-`true`/`false`/`null` entries are dropped
     * by the caller's type; nothing else can be in a [TrustStore].
     */
    fun serialize(store: TrustStore): String {
        val keys = store.keys.sorted()
        val body = keys.joinToString(separator = ",\n", prefix = "{\n", postfix = "\n}") { key ->
            "  ${jsonQuote(key)}: ${render(store[key])}"
        }
        // An empty store serialises to "{}", not "{\n\n}" — `JSON.stringify({}, null, 2)`
        // is "{}", and matching that matters because pi rewrites the file.
        val text = if (keys.isEmpty()) "{}" else body
        return text + "\n"
    }

    private fun render(decision: Boolean?): String = when (decision) {
        true -> "true"
        false -> "false"
        null -> "null"
    }

    /**
     * Nearest ancestor wins, `true`/`false` only (`trust-manager.ts:44-58`).
     *
     * @param canonicalCwd already canonicalised by the caller — this function does
     *        no filesystem work, so it cannot resolve symlinks itself.
     */
    fun nearest(store: TrustStore, canonicalCwd: String): Entry? {
        var current = canonicalCwd
        while (true) {
            val value = store[current]
            if (value != null) return Entry(current, value)

            val parent = posixDirname(current)
            if (parent == current) return null
            current = parent
        }
    }

    /**
     * pi's `getProjectTrustParentPath` (`trust-manager.ts:60-64`): the parent, or
     * `undefined` at the filesystem root.
     */
    fun parentOf(canonicalPath: String): String? {
        val parent = posixDirname(canonicalPath)
        return if (parent == canonicalPath) null else parent
    }

    /**
     * Apply one `setMany` update list (`trust-manager.ts:231-244`): `null` deletes,
     * anything else stores. Keys are canonicalised by the caller.
     */
    fun applyUpdates(store: TrustStore, updates: List<Pair<String, Boolean?>>): TrustStore {
        val next = LinkedHashMap(store)
        for ((path, decision) in updates) {
            if (decision == null) next.remove(path) else next[path] = decision
        }
        return next
    }

    /**
     * POSIX `dirname` with Node's semantics, because trust keys are guest paths on
     * a Linux filesystem and `java.io.File.parent` would apply host rules.
     *
     * Node: `dirname("/a/b") === "/a"`, `dirname("/a") === "/"`, `dirname("/") ===
     * "/"` (which is what terminates the ancestor walk), `dirname("a") === "."`.
     */
    fun posixDirname(path: String): String {
        if (path.isEmpty()) return "."
        // Strip trailing slashes but never the leading one.
        var end = path.length
        while (end > 1 && path[end - 1] == '/') end--
        val trimmed = path.substring(0, end)
        if (trimmed == "/") return "/"
        val slash = trimmed.lastIndexOf('/')
        if (slash < 0) return "."
        if (slash == 0) return "/"
        return trimmed.substring(0, slash)
    }

    /** `JSON.stringify(key)` for the error message: quotes and escapes. */
    private fun jsonQuote(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
        return out.toString()
    }

    /** `utils/text.ts`'s `stripBom`; only a leading U+FEFF is removed. */
    fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text
}
