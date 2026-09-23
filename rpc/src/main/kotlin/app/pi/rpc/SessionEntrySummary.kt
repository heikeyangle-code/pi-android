package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The session tree's text for an entry type **this build does not model**.
 *
 * ## Why this exists
 *
 * `SessionEntries.kt`'s parser is total on purpose: an entry type a newer pi introduced
 * becomes `SessionEntry.Unknown` and keeps its `raw` object, so a session written by a
 * future engine loses no data. What the tree screen then did with it was
 * `entry.raw.toString()` — a wall of JSON. The parser's choice was right; the *rendering*
 * of it was lazy, and it is the treatment every future entry type inherits, not a
 * one-off for the type that exposed it (`context_edit`, new in pi 0.87.1).
 *
 * ## What pi itself does
 *
 * pi's own tree selector renders a **settings/bookkeeping** line for the types it knows
 * and hides those rows under its default filter (`modes/interactive/components/
 * tree-selector.js`):
 *
 *  - `:486`/`:694` — `context_edit` becomes `[context omit: <id>]` or
 *    `[context replace: <id>]`, decided by whether `replacement` is null;
 *  - `:259-262` — `label`, `context_edit`, `custom`, `model_change`,
 *    `thinking_level_change` and `session_info` are the settings/bookkeeping set, hidden
 *    unless the filter is `all`.
 *
 * The six named types are the ones *this app models* except `context_edit`, which arrives
 * as `Unknown` — so [SETTINGS_ENTRY_TYPES] is the type-name complement the filter needs,
 * and [unknownDetail] gives `context_edit` pi's sentence instead of a field dump.
 *
 * ## What it deliberately is not
 *
 * No new data class, no parse branch, no schema knowledge: everything here reads the
 * `Unknown` entry's raw JSON. A `context_edit` the app later decides to model can delete
 * the special case and keep the generic body; a *different* new type needs no code at all.
 * Android-free (stdlib + kotlinx.serialization through `PiJson`), which is what lets
 * `app/src/test/kotlin/app/pi/rpc/SessionEntryDetailCheck.kt` compile it on a bare JVM.
 */
object SessionEntrySummary {

    /**
     * The four keys every entry carries (`EntryMeta` in `SessionEntries.kt`, pi's
     * `SessionEntryBase`). A field dump that repeats the id/timestamp on every row buries
     * the one field the reader has not seen before.
     */
    private val METADATA_KEYS = setOf("id", "parentId", "timestamp", "type")

    /**
     * pi's settings/bookkeeping types (`tree-selector.js:259-262`) **that this build does
     * not model as a data class**. The modelled ones (`label`, `custom`, `model_change`,
     * `thinking_level_change`, `session_info`) are matched by type in the screen's filter,
     * so only the unmodelled remainder belongs here — today just `context_edit`. A type
     * pi adds later shows up in the default filter until someone adds it here, which is
     * the visible-and-wrong direction rather than the silent one.
     */
    val SETTINGS_ENTRY_TYPES: Set<String> = setOf("context_edit")

    /** Longest single value rendered on one line; a longer string is cut, not wrapped. */
    private const val MAX_VALUE_CHARS = 80

    /** Whether an unmodelled `type` is one of pi's settings/bookkeeping entries. */
    fun isSettingsEntry(type: String?): Boolean = type != null && type in SETTINGS_ENTRY_TYPES

    /**
     * pi's sentence for a `context_edit` row (`tree-selector.js:486`): an entry either
     * **omits** an earlier entry from model context (`replacement: null`) or **replaces**
     * its content. Both name the entry they act on, because the target is the only thing
     * that makes the row meaningful — the row itself carries no text.
     *
     * `replacement` is absent *or* `null` for the omit case: pi writes `null` explicitly,
     * and an older/hand-written record may not carry the key at all. Both are "no
     * replacement", so both read as omit.
     */
    fun contextEditLine(raw: JsonObject): String {
        val target = raw.str("targetId").orEmpty().ifEmpty { "(未给出 targetId)" }
        val replacement = raw["replacement"]
        val omitted = replacement == null || replacement is JsonNull
        return if (omitted) {
            "context omit: $target（收回了该条目对模型上下文的贡献）"
        } else {
            "context replace: $target（改写了该条目的上下文内容）"
        }
    }

    /**
     * The generic body for **any** entry type this build does not model: pi's own fields,
     * minus the metadata, one `key=value` per line.
     *
     * Scalars are shown; a nested object or array is named by shape only (`<object>`,
     * `<array(3)>`) because the point is "here is what this entry carries", not a JSON
     * pretty-printer for a phone screen. Field order is the order pi wrote them in, so two
     * reads of the same session render identically.
     */
    fun unknownBody(raw: JsonObject): String {
        val fields = raw.entries.filterNot { it.key in METADATA_KEYS }
        if (fields.isEmpty()) {
            val type = raw.str("type").orEmpty()
            return "（$type 条目没有其它字段）"
        }
        return fields.joinToString("\n") { (key, value) -> "$key=${scalar(value)}" }
    }

    /** [unknownBody] for everything except `context_edit`, which gets pi's own sentence. */
    fun unknownDetail(type: String, raw: JsonObject): String =
        if (type == "context_edit") contextEditLine(raw) else unknownBody(raw)

    private fun scalar(value: JsonElement): String = when (value) {
        is JsonNull -> "null"
        is JsonObject -> "<object>"
        is JsonArray -> "<array(${value.size})>"
        is JsonPrimitive -> value.content.replace('\n', ' ').take(MAX_VALUE_CHARS)
    }
}
