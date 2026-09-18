package app.pi.ui.settings

import androidx.compose.runtime.Composable
import app.pi.ui.theme.PiThemeEntry
import kotlinx.serialization.json.JsonPrimitive

/**
 * Picks the level-2 editor for a row (spec §6.1).
 *
 * Switch rows are edited in place and action rows are confirmed by the caller, so
 * only the four value-bearing kinds reach this point. Every write goes through
 * [PiSettingsStore.write] with the value the user actually chose — the store, not
 * this layer, is responsible for landing it in `settings.json`.
 *
 * [knownThemes] is passed in rather than derived here: the names pi can actually
 * load come from the file system (the agent dir's `themes` directory and the
 * project's `.pi/themes`), not from the `themes` setting alone, so the caller —
 * which owns the resolved theme — is the one authority for the list.
 */
@Composable
fun PiSettingEditorSheet(
    setting: PiSetting,
    store: PiSettingsStore,
    onDismiss: () -> Unit,
    knownThemes: List<PiThemeEntry> = emptyList(),
    themeNotes: List<String> = emptyList(),
    themeError: String? = null,
    /**
     * Values that do not live in the store, by key: the read-only 运行时 rows, which the
     * host reads from the runtime tree and the foreground service (see `RuntimeFacts`).
     *
     * The row already renders these instead of the store value, so an editor that read the
     * store would show a **different** value from the row the user tapped — the empty sheet
     * over a row reading `0.85.1` that `docs/settings-audit-impl.md` §B4 records. Text rows
     * therefore start from the override, exactly like the row does.
     */
    valueOverrides: Map<String, String> = emptyMap(),
    onWritten: (PiSetting) -> Unit = {},
) {
    when (setting.kind) {
        PiRowKind.Switch, PiRowKind.Action -> Unit

        PiRowKind.Value -> {
            if (setting.key == "theme") {
                PiThemeEditorSheet(
                    currentRaw = setting.textIn(store, "dark"),
                    knownThemes = knownThemes,
                    notes = themeNotes,
                    error = themeError,
                    onSet = { value ->
                        store.write(setting.key, JsonPrimitive(value))
                        onWritten(setting)
                    },
                    onDismiss = onDismiss,
                )
            } else {
                PiOptionPickerSheet(
                    title = setting.title,
                    description = setting.description,
                    options = setting.options,
                    currentWire = setting.textIn(store, ""),
                    allowCustom = setting.allowCustom,
                    onPick = { value ->
                        store.write(setting.key, setting.wireElement(value))
                        onWritten(setting)
                    },
                    onDismiss = onDismiss,
                )
            }
        }

        PiRowKind.Number -> PiNumberEditorSheet(
            setting = setting,
            initial = store.read(setting.key)?.intValueOrNull(),
            // "Restore the default" **removes the key**; it must never write `JsonNull`.
            // A null in the file is not "unset" to pi — `parseTimeoutSetting` treats it as
            // present-and-unparseable and throws, which stops `pi --mode rpc` from starting
            // when the key is `httpIdleTimeoutMs` (`docs/settings-audit-impl.md` §B1).
            onSet = { value ->
                if (value == null) store.remove(setting.key) else store.write(setting.key, JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.Text -> PiTextEditorSheet(
            setting = setting,
            // The row shows this value, so the editor must show the same one.
            initial = valueOverrides[setting.key] ?: setting.textIn(store, ""),
            // "Clear" is also a removal: an empty string is a *value* in the file (`""` is not
            // `undefined`), and every text row here means "unset" when it is empty — for
            // `shellPath` pi's `if (customShellPath)` makes `""` behave as unset, and for the
            // app's own launch knobs `takeIf { it.isNotBlank() }` does the same. Removing it
            // also keeps `isExplicit()` from reporting a value that is not really set.
            onSet = { value ->
                if (value.isEmpty()) store.remove(setting.key) else store.write(setting.key, JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.List -> PiListEditorSheet(
            setting = setting,
            initialEntries = setting.editableEntries(store.read(setting.key) ?: setting.defaultValue),
            // pi's own rules for the object-valued rows, applied **before** the write:
            // `compaction.modelOverrides` with a non-integer value makes pi throw on every
            // turn's compaction check (`core/settings-manager.ts:854-880` →
            // `core/agent-session.ts:540`), so saving it silently is the bug
            // (`docs/settings-audit-impl.md` §B5). `validateEntries` returns the sentence to
            // show instead of saving, or null when the lines are acceptable.
            validate = { entries -> setting.validateEntries(entries) },
            onSet = { entries ->
                store.write(setting.key, setting.elementFromEntries(entries))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )
    }
}
