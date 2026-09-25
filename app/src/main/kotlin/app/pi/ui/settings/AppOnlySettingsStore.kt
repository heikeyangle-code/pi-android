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
 *
 *    **This write is also the trigger** for the switch taking effect in this session:
 *    `PiSessionViewModel.onSettingWritten` reacts to the key by running the probe now and
 *    restarting the engine onto the chosen runtime (`RuntimeSwitchAction`). Before that,
 *    the row's own description told the user to exit the app and come back.
 *  - `app.runtime.prorootStatus` — a **derived, read-only** sentence: the runtime
 *    that is actually in effect and, when it is not proroot, why — including *which*
 *    probe stage refused and the recorded line that says so, because the gate's verdict
 *    is cached as evidence lines and a row that stopped at "探针未通过" was showing less
 *    than the app knew (`runtime/ProrootProbeNarrative`). Derived from the probe cache
 *    and the failure counter by `RuntimeSelection.status()`, which the host computes
 *    once per epoch on `Dispatchers.IO` and passes in — reading it per frame would hash
 *    five `.so` files on the composition thread.
 *
 *    The same `status()` read also feeds the row's **detail block**: the recorded
 *    per-phase lines, bounded with a sentence saying how many were left out, handed to
 *    the group screen as a precomputed string (`runtimeDetailOverrides` in
 *    `RuntimeFacts.kt`). It stays a string rather than a key on this class on purpose:
 *    `read(key)` here must remain a lookup over values the host already produced, and a
 *    key that computed the block would reintroduce the per-frame probe-cache read (and
 *    `.so` hash) this split exists to prevent.
 *
 * Every other key is delegated unchanged, so this is invisible to the rest of the
 * settings stack: search, the group screen, `EffectiveKind` badges and the audit
 * harness all keep working through the ordinary interface.
 *
 * One consequence worth stating: a write to the switch reports a change to
 * `onSettingWritten` like any other row, and it does **not** invalidate the settings
 * cache (there is no pi document involved). Two other things then make the status row
 * catch up: the host's own epoch bump on that write, and — because the write starts the
 * probe-then-restart flow — the progress the ViewModel publishes until the flow settles
 * (`runtimeSwitch`), which is what re-reads `RuntimeSelection.status()` one last time
 * with the new verdict in place.
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
        KEY_BXROOT -> JsonPrimitive(runtime.bxrootEnabled)
        KEY_STATUS -> JsonPrimitive(runtimeStatus)
        else -> base.read(key)
    }

    override fun write(key: String, value: JsonElement) {
        when (key) {
            KEY_PROROOT -> runtime.setProrootEnabled(value.boolValue(false))
            KEY_BXROOT -> runtime.setBxrootEnabled(value.boolValue(false))
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
            KEY_BXROOT -> runtime.setBxrootEnabled(false)
            KEY_STATUS -> Unit
            else -> base.remove(key)
        }
    }

    companion object {
        /** The switch. App-only; see the class KDoc. */
        const val KEY_PROROOT = "app.runtime.proroot"

        /**
         * The **second** runtime switch (`docs/bxroot-runtime.md`). App-only for the same
         * reason as [KEY_PROROOT]: it selects which binary launches the guest, before pi
         * exists, so pi has no reader for it.
         */
        const val KEY_BXROOT = "app.runtime.bxroot"

        /** The effective runtime and the fallback reason. Derived, read-only. */
        const val KEY_STATUS = "app.runtime.prorootStatus"
    }
}
