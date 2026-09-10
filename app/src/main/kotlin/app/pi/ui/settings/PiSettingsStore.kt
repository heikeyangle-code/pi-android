package app.pi.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement

/**
 * The read/write surface for pi's settings documents.
 *
 * pi keeps two JSON files (`~/.pi/agent/settings.json` for the global scope and
 * `.pi/settings.json` for the project scope) and merges the project document
 * recursively over the global one before a session reads a value. That merge,
 * the file IO and the credential store are the runtime's business; this
 * interface is deliberately only what the settings UI needs.
 *
 * A value is addressed by its dotted path exactly as it appears in JSON
 * (`compaction.reserveTokens`) and the whole value tree crosses the boundary as
 * a [JsonElement] rather than a string. That matters for two settings the spec
 * calls out: array-valued settings keep their glob syntax (`!pattern` exclude,
 * `+path` force-include, `-path` force-exclude) and are never normalised into a
 * plain path list, and `theme` round-trips the literal `"lightTheme/darkTheme"`
 * automatic form unchanged instead of being split into two fields.
 */
interface PiSettingsStore {
    /** Returns the raw value at [key], or null when the key is not set. */
    fun read(key: String): JsonElement?

    /** Stores [value] at [key] verbatim. The store must not re-serialise it. */
    fun write(key: String, value: JsonElement)
}

/**
 * In-memory store used for previews, tests and the first UI pass, and as the
 * fallback when the settings stack is rendered without a wired runtime.
 *
 * Backed by a snapshot state map, so a value read during composition is also a
 * subscription: writing a setting recomposes exactly the rows that display it.
 */
class InMemoryPiSettingsStore(initial: Map<String, JsonElement> = emptyMap()) : PiSettingsStore {
    private val values = mutableStateMapOf<String, JsonElement>()

    init {
        values.putAll(initial)
    }

    override fun read(key: String): JsonElement? = values[key]

    override fun write(key: String, value: JsonElement) {
        values[key] = value
    }

    /** Drops an explicit value so the key falls back to pi's built-in default. */
    fun remove(key: String) {
        values.remove(key)
    }

    /** Snapshot of every key that currently carries an explicit value. */
    fun snapshot(): Map<String, JsonElement> = values.toMap()
}

/**
 * A store seeded with every registry default.
 *
 * A real `settings.json` is sparse — omitting a key is how a user asks for pi's
 * built-in default — so seeding is only a convenience for the UI: it makes a
 * group screen or a picker readable before the runtime store is wired.
 */
fun inMemoryDefaultStore(): InMemoryPiSettingsStore =
    InMemoryPiSettingsStore(
        PiSettingsCatalog.settings
            .mapNotNull { setting ->
                setting.defaultValue?.let { value -> setting.key to value }
            }
            .toMap(),
    )

@Composable
fun rememberInMemoryPiSettingsStore(): PiSettingsStore = remember { inMemoryDefaultStore() }
