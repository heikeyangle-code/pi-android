package app.pi.ui.settings

import androidx.compose.runtime.Composable
import app.pi.ui.theme.PiThemeEntry
import kotlinx.serialization.json.JsonNull
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
            onSet = { value ->
                store.write(setting.key, if (value == null) JsonNull else JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.Text -> PiTextEditorSheet(
            setting = setting,
            initial = setting.textIn(store, ""),
            onSet = { value ->
                store.write(setting.key, JsonPrimitive(value))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )

        PiRowKind.List -> PiListEditorSheet(
            setting = setting,
            initialEntries = setting.editableEntries(store.read(setting.key) ?: setting.defaultValue),
            onSet = { entries ->
                store.write(setting.key, setting.elementFromEntries(entries))
                onWritten(setting)
            },
            onDismiss = onDismiss,
        )
    }
}
