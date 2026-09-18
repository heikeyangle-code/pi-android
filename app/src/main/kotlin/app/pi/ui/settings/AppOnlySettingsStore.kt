package app.pi.ui.settings

import app.pi.runtime.RuntimeSelection
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The settings store decorator that gives the two **app-only** runtime rows a home
 * in the registry without putting a single byte of them into pi's `settings.json`.
 *
 * ## Why this exists
 *
 * The registry (`PiSettingsRegistry`) is a transcription of documents pi reads. A row
 * added there is otherwise indistinguishable from a pi setting: it is addressed by a
 * dotted key, rendered by the group screen, and written through [PiSettingsStore] —
 * which is `PiSettingsFileStore`, i.e. `~/.pi/agent/settings.json`. The runtime
 * choice must not be written there. It selects *which binary launches the guest*,
 * before pi exists, and pi has no reader for it; leaving it in pi's file would be the
 * same "two truths, one of which silently wins" shape the device-capability rows were
 * removed for (`PiSettingsRegistry.kt:1128-1137`).
 *
 * So the rows stay in the registry (they are runtime rows, and that is where a user
 * looks for them) and this decorator intercepts exactly those two keys on the way to
 * the real store:
 *
 *  - `app.runtime.proroot` — the user's switch. Read from and written through
 *    [RuntimeSelection], which stores it in app-only `SharedPreferences` (the pattern
 *    `DeviceCapabilityStore` established for app state that is not a pi setting) and, on
 *    the way in, clears both the failure counter and the cached gate verdict — the two
 *    things that otherwise outlive a retry.
 *  - `app.runtime.prorootStatus` — a **derived, read-only** sentence: the runtime
 *    that is actually in effect and, when it is not proroot, why. Derived from the
 *    probe cache and the failure counter by `RuntimeSelection.status()`, which the
 *    host computes once per composition and passes in — reading it per frame would
 *    hash five `.so` files on the composition thread.
 *
 * Every other key is delegated unchanged, so this is invisible to the rest of the
 * settings stack: search, the group screen, `EffectiveKind` badges and the audit
 * harness all keep working through the ordinary interface.
 *
 * One consequence worth stating: a write to the switch reports a change to
 * `onSettingWritten` like any other row, and it does **not** invalidate the settings
 * cache (there is no pi document involved). The host's `RuntimeFacts` refresh is what
 * makes the status row catch up.
 */
class AppOnlySettingsStore(
    private val base: PiSettingsStore,
    /**
     * The decision point, not the preference store: the switch write has to clear the
     * gate's cached verdict as well as the failure counter, and that pairing lives in
     * `RuntimeSelection.setProrootEnabled` (a cached *failure* would otherwise survive
     * because its key cannot change — see that method).
     */
    private val runtime: RuntimeSelection,
    /**
     * The effective-runtime sentence for `app.runtime.prorootStatus`. Passed in
     * rather than computed here: see the class KDoc.
     */
    private val runtimeStatus: String,
) : PiSettingsStore {

    override fun read(key: String): JsonElement? = when (key) {
        KEY_PROROOT -> JsonPrimitive(runtime.prorootEnabled)
        KEY_STATUS -> JsonPrimitive(runtimeStatus)
        else -> base.read(key)
    }

    override fun write(key: String, value: JsonElement) {
        when (key) {
            KEY_PROROOT -> runtime.setProrootEnabled(value.boolValue(false))
            // A derived value has no writer. The row is `readOnly`, so nothing should
            // reach here; swallowing it keeps a future editor from writing a sentence
            // into pi's settings.json under a key the app would then ignore.
            KEY_STATUS -> Unit
            else -> base.write(key, value)
        }
    }

    /**
     * "Restore the default" for the switch means **off** (`RuntimePreferences.prorootEnabled`
     * is false unless the user turned it on), and it is a write, not a deletion: the switch's
     * home is `SharedPreferences`, where "absent" and "false" are the same thing anyway.
     *
     * The derived status row has no value to drop, so it stays swallowed for the same reason
     * [write] swallows it.
     */
    override fun remove(key: String) {
        when (key) {
            KEY_PROROOT -> runtime.setProrootEnabled(false)
            KEY_STATUS -> Unit
            else -> base.remove(key)
        }
    }

    companion object {
        /** The switch. App-only; see the class KDoc. */
        const val KEY_PROROOT = "app.runtime.proroot"

        /** The effective runtime and the fallback reason. Derived, read-only. */
        const val KEY_STATUS = "app.runtime.prorootStatus"
    }
}
