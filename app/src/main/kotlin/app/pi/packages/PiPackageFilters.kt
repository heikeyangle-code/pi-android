package app.pi.packages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One `settings.json` `packages[]` entry in **both** of pi's forms, with the
 * normalisation pi applies when it edits one.
 *
 * ## Why this type exists
 *
 * pi's `packages` array holds either a bare source string or an object with
 * per-resource filters (`core/settings-manager.ts:95-104`):
 *
 * ```
 * "packages": [
 *   "npm:foo",
 *   { "source": "git:github.com/u/r", "autoload": false,
 *     "extensions": ["+src-extra", "-legacy*"], "skills": ["+skills-extra"] }
 * ]
 * ```
 *
 * The app only knew the `(filtered)` suffix `pi list` prints
 * ([PiListOutput]), so **the four glob arrays were unreadable here**: a package
 * whose filters the user set in pi's own TUI looked like a plain package, and
 * anything the app wrote back would have silently discarded them. That is the
 * gap this file closes; the shape below is transcribed from pi, not invented.
 *
 * ## The patterns, and what they mean *in pi*
 *
 * The four arrays are glob patterns, and their leading character decides the
 * meaning — pi classifies them at `core/package-manager.ts:709-716` and applies
 * them with `minimatch` at `:722-725`:
 *
 *  - a leading `+` — force-**include** (`:715` → `:722`)
 *  - a leading `-` — force-**exclude** (`:716` → `:725`)
 *  - a leading `!` — exclude (`:714`)
 *  - no prefix — an ordinary pattern, matched against the path, the file name and
 *    the package-relative path (`:668-676`)
 *
 * pi's config selector generates the `+`/`-` from a checkbox
 * (`modes/interactive/components/config-selector.ts:608-618`: a leading `+` when
 * the resource is enabled, a leading `-` when it is not, after removing any
 * existing pattern with the same body). This app edits the **strings themselves**
 * rather than a checkbox, so every pattern pi wrote — including a bare one —
 * stays visible and removable here instead of being re-derived and rewritten.
 *
 * ## The one deliberate deviation, and why
 *
 * pi cleans up an entry whose filters all went away by replacing it with the bare
 * source string (`config-selector.ts:623-628`):
 *
 * ```ts
 * const hasFilters = ["extensions", "skills", "prompts", "themes"].some(
 *   (k) => pkg[k] !== undefined,
 * );
 * if (!hasFilters) packages[pkgIndex] = pkg.source;
 * ```
 *
 * That check looks at the four arrays **only**, so an entry carrying `autoload`
 * would lose it. Dropping a user's "install but do not autoload" as a side effect
 * of editing a glob is silent data loss, so [normalize] keeps the object form
 * whenever `autoload` is set. Everything else follows pi: an array that becomes
 * empty is removed from the object (`:619`), and a pattern already present is
 * replaced rather than duplicated (`:611-614`).
 */
object PiPackageFilters {

    /**
     * The four resource keys pi's config selector edits
     * (`modes/interactive/components/config-selector.ts:26-38`).
     */
    val RESOURCE_TYPES: List<String> = listOf("extensions", "skills", "prompts", "themes")

    /** `autoload` is pi's own key on the object form (`settings-manager.ts:99`). */
    private const val SOURCE = "source"
    private const val AUTOLOAD = "autoload"

    /**
     * One parsed entry. [filters] keeps only non-empty arrays and preserves the
     * order pi stored; [autoload] is null when the key is absent (which is not the
     * same as `false`).
     */
    data class Entry(
        val source: String,
        val autoload: Boolean? = null,
        val filters: Map<String, List<String>> = emptyMap(),
    ) {
        fun patterns(type: String): List<String> = filters[type].orEmpty()

        val hasFilters: Boolean get() = RESOURCE_TYPES.any { type -> !filters[type].isNullOrEmpty() }
    }

    /**
     * Parse one array element. Returns null for anything that is not a usable
     * entry (a missing or blank `source`, or a JSON shape pi could not have
     * written), so the caller can report it instead of inventing a package.
     */
    fun parse(element: JsonElement?): Entry? = when (element) {
        is JsonPrimitive -> element.content.takeIf { element.isString && it.isNotBlank() }?.let {
            Entry(source = it)
        }

        is JsonObject -> {
            val source = (element[SOURCE] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (source.isNullOrBlank()) {
                null
            } else {
                Entry(
                    source = source,
                    autoload = (element[AUTOLOAD] as? JsonPrimitive)?.booleanOrNull,
                    filters = RESOURCE_TYPES.mapNotNull { type ->
                        val patterns = (element[type] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                            ?.filter { it.isNotBlank() }
                        // An empty array is the same as an absent key: pi removes the
                        // key rather than storing an empty array (`:619`).
                        patterns?.takeIf { it.isNotEmpty() }?.let { type to it }
                    }.toMap(),
                )
            }
        }

        else -> null
    }

    /** The JSON pi would write for [entry]: the object form, or a bare source. */
    fun toJson(entry: Entry): JsonElement {
        val normalized = normalize(entry)
        if (!normalized.keepsObjectForm) return JsonPrimitive(normalized.source)
        return JsonObject(
            buildMap {
                put(SOURCE, JsonPrimitive(normalized.source))
                normalized.autoload?.let { put(AUTOLOAD, JsonPrimitive(it)) }
                for (type in RESOURCE_TYPES) {
                    val patterns = normalized.patterns(type)
                    if (patterns.isNotEmpty()) put(type, JsonArray(patterns.map { JsonPrimitive(it) }))
                }
            },
        )
    }

    /**
     * pi's cleanup, applied here rather than at the call site: drop empty arrays,
     * and use the bare source string only when there is nothing left to carry —
     * except when `autoload` is set (see the file note above; that is the one place
     * where this app does not follow `config-selector.ts:623-628`).
     */
    fun normalize(entry: Entry): Entry = entry.copy(
        filters = RESOURCE_TYPES.mapNotNull { type ->
            entry.patterns(type).takeIf { it.isNotEmpty() }?.let { type to it }
        }.toMap(),
    )

    /** True when the entry must stay in object form; see [normalize]. */
    private val Entry.keepsObjectForm: Boolean get() = hasFilters || autoload != null

    /**
     * Add [pattern] to [type], replacing any existing pattern with the same **body**
     * — pi strips a leading `!` or `+` or `-` before comparing
     * (`config-selector.ts:611-614`), so a `+` and a `-` form of one pattern cannot
     * both exist. The pattern is stored exactly as typed: this app does not
     * synthesise the sign pi's checkbox does.
     */
    fun withPattern(entry: Entry, type: String, pattern: String): Entry {
        val clean = pattern.trim()
        if (type !in RESOURCE_TYPES || clean.isEmpty()) return entry
        val body = clean.removePrefix("!").removePrefix("+").removePrefix("-")
        val kept = entry.patterns(type).filter { existing ->
            existing.removePrefix("!").removePrefix("+").removePrefix("-") != body
        }
        return entry.copy(filters = entry.filters + (type to (kept + clean)))
    }

    /** Remove [pattern] from [type] verbatim; the key disappears when it empties. */
    fun withoutPattern(entry: Entry, type: String, pattern: String): Entry =
        entry.copy(
            filters = entry.filters + (type to entry.patterns(type).filterNot { it == pattern }),
        )
}
