package app.pi.ui

import android.content.Context
import app.pi.ui.settings.PiSettingsStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * `app.extensions.args` — the **extension CLI flags** the user wants every engine
 * launch to carry, and the one row in the settings screen whose value must never
 * reach pi's `settings.json`.
 *
 * ## Why app-only storage, and not a pi key
 *
 * Every other row in `PiSettingsRegistry` is a transcription of a document pi
 * reads. This one is not: pi has no `extensions.args` (or any equivalent) settings
 * key — the only channel for an extension-registered flag is pi's own command line
 * (`main.ts:737` → `core/agent-session-services.ts:107-111`), and a value left in
 * `settings.json` would be a key pi never reads. Worse, it would be *reported* as a
 * live setting by every screen that lists them. So the value lives where this app
 * already keeps the other piece of state that exists before pi does
 * (`DeviceCapabilityStore`): app-only
 * storage, in this case `SharedPreferences` — no file pi can see, and nothing to
 * migrate.
 *
 * ## Why a decorator rather than a special case in the UI
 *
 * The settings stack addresses every row through one [PiSettingsStore]; a row whose
 * value must not go to pi's file is therefore a store concern, not a screen
 * concern. [ExtensionArgsStoreDecorator] intercepts exactly this key and delegates
 * everything else untouched, which is the shape `DeviceCapabilityStore`'s rows
 * already use for state pi must not see. This decorator is the thinner of the two:
 * it keeps one string, with no switch and no failure counter beside it.
 *
 * `write` stores the text verbatim (including an empty string, which is how the row
 * is cleared) and `remove` deletes it — never `JsonNull`, because a null in a
 * settings document is exactly the shape the settings audit found fatal for pi's
 * own parser (`PiSettingsStore`'s KDoc, `docs/settings-audit-impl.md` §B1).
 */
/** The app-only home of one string. One instance per process is enough, but this is cheap to construct. */
class ExtensionArgsStore(private val context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The user's text, or null when the row was never set (or was cleared). */
    fun read(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    /** The stored text exactly as typed, including blank — for the editor's own prefill. */
    fun readRaw(): String = prefs.getString(KEY, "").orEmpty()

    fun write(text: String) {
        prefs.edit().putString(KEY, text).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val KEY = "app.extensions.args"

        /** Private to the app, named after the value it holds. */
        const val PREFS_NAME = "pi-extension-args"
    }
}

/**
 * [PiSettingsStore] over [base], with `app.extensions.args` redirected to
 * [ExtensionArgsStore].
 *
 * Every other key — reads, writes and removals — goes to [base] unchanged, so the
 * settings stack, its search, the `EffectiveKind` badges and the audit harness keep
 * working through the ordinary interface.
 */
class ExtensionArgsStoreDecorator(
    private val base: PiSettingsStore,
    private val sidecar: ExtensionArgsStore,
) : PiSettingsStore {

    override fun read(key: String): JsonElement? = when (key) {
        ExtensionArgsStore.KEY -> sidecar.read()?.let { JsonPrimitive(it) }
        else -> base.read(key)
    }

    override fun write(key: String, value: JsonElement) {
        when (key) {
            // The row's editor hands over a string; anything else (a null, an array)
            // would be a bug in the editor rather than user input, and is ignored
            // rather than stringified into the sidecar.
            ExtensionArgsStore.KEY -> (value as? JsonPrimitive)?.takeIf { it.isString }
                ?.let { sidecar.write(it.content) }
                ?: sidecar.clear()

            else -> base.write(key, value)
        }
    }

    override fun remove(key: String) {
        when (key) {
            ExtensionArgsStore.KEY -> sidecar.clear()
            else -> base.remove(key)
        }
    }
}
