package app.pi.ui.settings

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Picks the level-2 editor for a row (spec §6.1).
 *
 * Switch rows are edited in place and action rows are confirmed by the caller, so
 * only the four value-bearing kinds reach this point. Every write goes through
 * [PiSettingsStore.write] with the value the user actually chose — the store, not
 * this layer, is responsible for landing it in `settings.json`.
 */
@Composable
fun PiSettingEditorSheet(
    setting: PiSetting,
    store: PiSettingsStore,
    onDismiss: () -> Unit,
    onWritten: (PiSetting) -> Unit = {},
) {
    when (setting.kind) {
        PiRowKind.Switch, PiRowKind.Action -> Unit

        PiRowKind.Value -> {
            if (setting.key == "theme") {
                PiThemeEditorSheet(
                    currentRaw = setting.textIn(store, "dark"),
                    knownThemes = localThemeNames(store),
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

/**
 * Theme file names known to the App, derived from the `themes` path list.
 *
 * Theme names cannot contain "/" because pi reserves it for the automatic
 * light/dark pair, so a theme file's basename is its name.
 */
private fun localThemeNames(store: PiSettingsStore): List<String> {
    val themesRow = PiSettingsCatalog.byKey["themes"] ?: return emptyList()
    return themesRow
        .editableEntries(store.read("themes"))
        .map { it.trim() }
        .filter { it.endsWith(".json") }
        .map { it.substringAfterLast('/').removeSuffix(".json") }
        .filter { name -> name.isNotEmpty() && !name.contains('*') && !name.contains('!') }
        .distinct()
}
