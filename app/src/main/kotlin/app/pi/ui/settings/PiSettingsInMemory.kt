package app.pi.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement

/**
 * In-memory store used for previews, tests and the first UI pass, and as the
 * fallback when the settings stack is rendered without a wired runtime.
 *
 * Backed by a snapshot state map, so a value read during composition is also a
 * subscription: writing a setting recomposes exactly the rows that display it.
 *
 * Split out of `PiSettingsStore.kt` — which now holds only the interface and is
 * Android-free so the bare-JVM `settings-store` harness can compile it — because
 * `mutableStateMapOf` and `rememberInMemoryPiSettingsStore` need Compose, and
 * [inMemoryDefaultStore] needs the Compose-importing registry.
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
    override fun remove(key: String) {
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
